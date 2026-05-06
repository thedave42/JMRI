package jmri.jmrit.usb;


import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.swing.JMenuItem;
import javax.swing.JToolBar;

import jmri.*;
import jmri.jmrit.jython.Jynstrument;
import jmri.jmrit.roster.RosterEntry;
import jmri.jmrit.roster.swing.RosterEntryComboBox;
import jmri.jmrit.roster.swing.RosterEntrySelectorPanel;
import jmri.jmrit.throttle.AddressListener;
import jmri.jmrit.throttle.AddressPanel;
import jmri.jmrit.throttle.LoadXmlThrottlesLayoutAction;
import jmri.jmrit.throttle.ThrottleFrame;
import jmri.jmrit.throttle.ThrottleFrameManager;
import jmri.jmrit.throttle.ThrottleWindow;
import jmri.jmrit.usb.swing.RailDriverAirStatusPanel;
import jmri.util.FileUtil;
import jmri.util.MathUtil;
import jmri.util.ThreadingUtil;

import org.hid4java.*;
import org.hid4java.event.HidServicesEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RailDriver support
 *
 * @author George Warner Copyright (c) 2017-2018
 */
public class RailDriverMenuItem extends JMenuItem implements HidServicesListener, PropertyChangeListener {

    private static final short VENDOR_ID = 0x05F3;
    private static final short PRODUCT_ID = 0x00D2;
    public static final String SERIAL_NUMBER = null; // For later use, if not null, uncomment line 454

    private HidServices hidServices = null;
    private HidDevice hidDevice = null;

    /** Tracks physical RailDriver presence independently of whether the HID
     *  handle has been opened. Set true on
     *  {@link #hidDeviceAttached HidServicesEvent} matching VID/PID and on
     *  successful device acquire in {@link #ensureDeviceAndPolling}; set
     *  false on detach. {@link #isRailDriverConnected} reads this. */
    private volatile boolean railDriverPresent = false;


    //TODO: Remove this if/when the RailDriver script is removed
    //private final boolean invokeOnMenuOnly = true;

    private Thread thread = null;
    private boolean shutdownRegistered = false;
    private ThrottleWindow throttleWindow = null;
    private ThrottleFrame activeThrottleFrame = null;
    private AddressPanel attachedAddressPanel = null;

    /**
     * Per-throttle semi-realistic physics engine. Lazily created in
     * {@link #attachThrottleWindow()} after the {@code activeThrottleFrame}
     * binding succeeds; disposed in {@link #propertyChange}'s
     * {@code "ancestor"} case alongside the {@code throttleDispatcher}
     * deregistration. {@code null} means no throttle window is open and
     * the engine is unreachable.
     */
    private SemiRealisticThrottleEngine engine = null;

    /**
     * True between {@link #requestAttachToThrottle} and the matching
     * {@code "activeThrottleFrame"} PCS event. Drives the Jynstrument's
     * "binding…" State 2.5 visual.
     */
    private volatile boolean attachInProgress = false;

    /**
     * AddressListener that binds the engine's throttle/roster lifecycle to
     * the address panel. {@code notifyAddressThrottleFound} attaches the
     * engine; {@code notifyAddressReleased} detaches it. Per the rubber-duck
     * critique on stage 2: the engine MUST track loco changes via this
     * lifecycle channel, not via window attach/detach, because the throttle
     * is acquired asynchronously after the window opens.
     */
    private final AddressListener addressListener = new AddressListener() {
        @Override
        public void notifyAddressChosen(LocoAddress address) {
            // No-op: a new address has been requested but no throttle yet.
        }

        @Override
        public void notifyAddressReleased(LocoAddress address) {
            if (engine != null) {
                engine.detachThrottle();
            }
        }

        @Override
        public void notifyAddressThrottleFound(DccThrottle t) {
            if (engine == null) return;
            SemiRealisticSettings s = getCalibration().semiRealistic();
            engine.updateSettings(s);
            engine.attachThrottle(t);
        }

        @Override
        public void notifyConsistAddressChosen(LocoAddress address) {}

        @Override
        public void notifyConsistAddressReleased(LocoAddress address) {}

        @Override
        public void notifyConsistAddressThrottleFound(DccThrottle t) {
            // Treat consist throttle the same as a single-throttle for the
            // engine's purposes — the engine drives whichever throttle the
            // address panel is exposing.
            notifyAddressThrottleFound(t);
        }
    };

    /**
     * Per-profile calibration data for the analog controls. Populated lazily
     * in {@link #setupRailDriver()} (or via {@link #reloadCalibration()} after
     * the settings window writes a new file). Always non-null after first
     * call to {@link #getCalibration()}.
     */
    private RailDriverCalibration calibration = null;

    /**
     * Static accessor for the most recently constructed RailDriverMenuItem,
     * used by {@link RailDriverSettingsFrame} to subscribe to live byte
     * events and to trigger a calibration reload after Save. Returns null
     * if the user has not yet constructed an instance (i.e. the Debug menu
     * with the RailDriver entry has not been opened).
     */
    private static RailDriverMenuItem instance = null;

    /**
     * Per-byte change threshold applied to analog axes (bytes 0..6) by
     * the polling thread. The new byte must differ from the
     * last-emitted byte by at least this many counts before an event
     * fires; smaller-magnitude changes are silently absorbed. Absorbs
     * the ~1-byte potentiometer jitter observed when a lever is held
     * at rest. Digital bytes (7..13) bypass this filter.
     */
    private static final int ANALOG_NOISE_THRESHOLD = 2;

    @CheckForNull
    public static RailDriverMenuItem getInstance() {
        return instance;
    }

    public RailDriverMenuItem(String name) {
        super();
        initGUI(name);
        setupListeners();
        instance = this;
    }

    public RailDriverMenuItem() {
        // TODO: remove "(built in)" if/when this replaces Raildriver script
        this(Bundle.getMessage("RdBuiltIn"));
    }

    private void initGUI(String name) {
        setText(name);
    }

    private void setupListeners() {
        // Note: mi does NOT addPropertyChangeListener(this). The polling
        // thread's "Value" events are routed through the separate
        // throttleDispatcher listener, registered only while a throttle
        // frame is attached. That keeps the "activeThrottleFrame == null"
        // invariant strict: any null in dispatchValueEvent is a real bug
        // and will NPE the polling thread (which is what we want — it
        // surfaces the bug instead of silently absorbing it).
        addActionListener((ActionEvent e) -> {
            log.info("RailDriverMenuItem Action!");
            if (ensureDeviceAndPolling()) {
                attachThrottleWindow();
            }
        });
    }

    protected void setupHidServices() {
        try {
            HidServicesSpecification hidServicesSpecification = new HidServicesSpecification();
            hidServicesSpecification.setAutoShutdown(true);
            hidServicesSpecification.setScanInterval(500);
            hidServicesSpecification.setPauseInterval(5000);
            hidServicesSpecification.setScanMode(ScanMode.SCAN_AT_FIXED_INTERVAL_WITH_PAUSE_AFTER_WRITE);

            // Get HID services using custom specification
            hidServices = HidManager.getHidServices(hidServicesSpecification);
            hidServices.addHidServicesListener(RailDriverMenuItem.this);

            // do the services have to be started here?
            // They currently wait for the action to be triggered
            // so that they're not starting at ctor time, e.g. in tests
            // Provide a list of attached devices
            //log.info("Enumerating attached devices...");
            //for (HidDevice hidDevice : hidServices.getAttachedHidDevices()) {
            //    log.info(hidDevice.toString());
            //}
            //
  /*          if (!invokeOnMenuOnly) {
                // start the HID services
                InstanceManager.getDefault(ShutDownManager.class).register(hidServices::stop);
                log.debug("Starting HID services.");
                hidServices.start();

                // Open the device device by Vendor ID, Product ID and serial number
                hidDevice = hidServices.getHidDevice(VENDOR_ID, PRODUCT_ID, SERIAL_NUMBER);
                if (hidDevice != null) {
                    log.info("Got RailDriver hidDevice: {}", hidDevice);
                    // Consider overriding dropReportIdZero on Windows
                    // if you see "The parameter is incorrect"
                    // HidApi.dropReportIdZero = true;
                    setupRailDriver();
                }
            }*/
        } catch (HidException ex) {
            log.error("HidException", ex);
        }
    }

    /**
     * Listener that handles polling-thread {@code "Value"} events and
     * dispatches them to the active throttle frame. Registered on
     * {@code mi} from {@link #attachThrottleWindow()} (only after
     * {@link #activeThrottleFrame} is non-null) and removed from
     * {@link #propertyChange}'s {@code "ancestor"} case when the
     * throttle window legitimately closes. Outside of that registered
     * window, the polling thread's {@code "Value"} events have no
     * listener and are silently ignored.
     */
    private final PropertyChangeListener throttleDispatcher = this::dispatchValueEvent;

    /**
     * Idempotent. Opens hid4java if not already initialised, opens the
     * device by VID/PID if not already open, loads the per-profile
     * calibration if not already loaded, and starts the polling thread
     * if not already running. Does not open or attach a throttle window.
     * <p>
     * Both the throttle menu's action listener and
     * {@link RailDriverSettingsAction} call this to bring the device
     * live without needing a throttle window. Calibration can therefore
     * run with or without an active throttle.
     *
     * @return true if the device is open and polling at return; false if
     *         hid4java init failed or no matching device is connected.
     */
    public boolean ensureDeviceAndPolling() {
        if (hidServices == null) {
            setupHidServices();
            if (hidServices == null) {
                return false;
            }
        }
        if (hidDevice == null) {
            hidDevice = hidServices.getHidDevice(VENDOR_ID, PRODUCT_ID, SERIAL_NUMBER);
            if (hidDevice == null) {
                return false;
            }
            log.info("Got RailDriver hidDevice: {}", hidDevice);
            // Successful acquire: ensure connected state matches reality
            // for callers that didn't see a hot-plug event (cold-plug).
            boolean was = railDriverPresent;
            railDriverPresent = true;
            if (!was) {
                firePropertyChange("railDriverConnected", false, true);
            }
        }
        if (calibration == null) {
            calibration = RailDriverCalibration.loadOrDefault(RailDriverCalibration.getDefaultFile());
        }
        setLEDs("Pro");
        speakerOn();
        if (thread == null || !thread.isAlive()) {
            startPollingThread();
        }
        if (!shutdownRegistered) {
            shutdownRegistered = true;
            InstanceManager.getDefault(ShutDownManager.class).register(
                    this::stopPollingAndReleaseDevice);
        }
        return true;
    }

    /**
     * Cleanly stops the polling thread and releases the HID device.
     * Registered with {@link ShutDownManager} so JMRI can shut down
     * without the non-exiting polling thread blocking the JVM, and so
     * the USB port is released for the next JMRI session.
     */
    private void stopPollingAndReleaseDevice() {
        Thread t = thread;
        if (t != null && t.isAlive()) {
            t.interrupt();
            try {
                t.join(2000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            thread = null;
        }
        HidDevice dev = hidDevice;
        if (dev != null) {
            try {
                dev.close();
            } catch (Exception ex) {
                log.debug("Error closing HID device during shutdown", ex);
            }
            hidDevice = null;
        }
        if (hidServices != null) {
            try {
                hidServices.shutdown();
            } catch (Exception ex) {
                log.debug("Error shutting down HID services", ex);
            }
            hidServices = null;
        }
        log.info("RailDriver polling stopped and HID device released.");
    }

    /** Returns true while the device polling thread is alive. */
    public boolean isPollingActive() {
        return thread != null && thread.isAlive();
    }

    /**
     * Acquires (or creates) a throttle window, wires this menu item as a
     * listener for {@code ancestor} / {@code ThrottleFrame} events on
     * it, and registers {@link #throttleDispatcher} so that polling-thread
     * {@code "Value"} events are dispatched to the active throttle. Must
     * only be called once the device + polling are up (see
     * {@link #ensureDeviceAndPolling()}).
     */
    private void attachThrottleWindow() {
        testRailDriver(false);  // set true to test RailDriver functions

        ThrottleFrameManager tfManager = InstanceManager.getDefault(ThrottleFrameManager.class);

        if (activeThrottleFrame == null) {
            try {
                LoadXmlThrottlesLayoutAction lxta = new LoadXmlThrottlesLayoutAction();
                if (!lxta.loadThrottlesLayout(new File(ThrottleFrame.getDefaultThrottleFilename()))) {
                    throw new IOException();
                }
            } catch (IOException ex) {
                throttleWindow = tfManager.createThrottleWindow();
                activeThrottleFrame = (ThrottleFrame) throttleWindow.newThrottleController();
            }
        }

        // LoadXmlThrottlesLayoutAction uses an invokeLater to open the
        // default throttles layout, so listener wiring has to wait until
        // that has completed.
        ThreadingUtil.runOnGUIEventually(() -> {
            if (activeThrottleFrame == null) {
                throttleWindow = tfManager.getCurrentThrottleFrame();
                if (throttleWindow != null) {
                    activeThrottleFrame = throttleWindow.getCurrentThrottleFrame();
                }
            }
            if (activeThrottleFrame != null) {
                ThrottleFrame oldActive = null;
                activeThrottleFrame.toFront();
                throttleWindow.addPropertyChangeListener(this);
                activeThrottleFrame.addPropertyChangeListener(this);
                // Idempotent: removing first guards against double-registration
                // if the user opens the throttle menu more than once in a session.
                removePropertyChangeListener("Value", throttleDispatcher);
                addPropertyChangeListener("Value", throttleDispatcher);

                // Stage 2: wire the semi-realistic engine to the throttle's
                // AddressPanel. The engine itself starts in DETACHED state;
                // it transitions to ATTACHED via notifyAddressThrottleFound
                // (or, if a throttle is already acquired before we got here,
                // the synthetic call below).
                if (engine == null) {
                    engine = new SemiRealisticThrottleEngine();
                }
                engine.updateSettings(getCalibration().semiRealistic());

                AddressPanel addressPanel = activeThrottleFrame.getAddressPanel();
                if (attachedAddressPanel != null && attachedAddressPanel != addressPanel) {
                    attachedAddressPanel.removeAddressListener(addressListener);
                }
                addressPanel.removeAddressListener(addressListener); // idempotent
                addressPanel.addAddressListener(addressListener);
                attachedAddressPanel = addressPanel;
                DccThrottle existing = addressPanel.getThrottle();
                if (existing != null) {
                    addressListener.notifyAddressThrottleFound(existing);
                }

                // Stage 2: auto-install the toolbar Jynstrument once the
                // throttle window is fully bound. Idempotent — the helper
                // walks the toolbar to detect a previously-installed copy
                // (see plan §3.2 step 5).
                autoInstallJynstrument(throttleWindow);

                // Phase 9: install the air status panel when semi-realistic
                // mode is enabled. Idempotent — skips if already installed
                // on the same window.
                installAirStatusPanel();

                // Stage 2: notify the Jynstrument and any other listeners
                // that the active throttle frame is now bound.
                firePropertyChange("activeThrottleFrame", oldActive, activeThrottleFrame);
                if (attachInProgress) {
                    attachInProgress = false;
                    firePropertyChange("attachInProgress", true, false);
                }
            } else if (attachInProgress) {
                // Bind failed; release the in-progress flag so the
                // Jynstrument's State 2.5 doesn't latch.
                attachInProgress = false;
                firePropertyChange("attachInProgress", true, false);
            }
        });
    }

    /**
     * Recursively walks {@code container}'s descendants and returns true if
     * any {@link Jynstrument} child has a class name ending in
     * {@code classNameSuffix}. Used to keep the toolbar mode-toggle install
     * idempotent across repeat {@link #attachThrottleWindow()} calls within
     * a session, and to detect saved-layout XML restoration of the toggle.
     * <p>
     * The walk is necessary because {@code ThrottleWindow.throttleToolBar}
     * is private with no public getter (verified at
     * {@code ThrottleWindow.java:57}); the precedent for iterating
     * {@code throttleToolBar.getComponents()} for {@code Jynstrument}
     * instances is established at
     * {@code ThrottleWindow.java:160-167} (close handler) and
     * {@code ThrottleWindow.java:801-810} (save).
     */
    private static boolean hasJynstrumentInstalled(Container container, String classNameSuffix) {
        Component[] comps = container.getComponents();
        if (comps == null) return false;
        for (Component c : comps) {
            if (c instanceof Jynstrument) {
                String n = c.getClass().getName();
                if (n.endsWith(classNameSuffix) || n.endsWith("." + classNameSuffix)) {
                    return true;
                }
            }
            if (c instanceof JToolBar) {
                if (hasJynstrumentInstalled((JToolBar) c, classNameSuffix)) return true;
            } else if (c instanceof Container) {
                if (hasJynstrumentInstalled((Container) c, classNameSuffix)) return true;
            }
        }
        return false;
    }

    /** Auto-install the RailDriver Jynstruments onto the throttle toolbar
     *  if not already present. Each install is independent so one failure
     *  does not prevent the other. Errors are tolerated — this is a UX
     *  nicety, not a correctness requirement. */
    private void autoInstallJynstrument(ThrottleWindow tw) {
        if (tw == null) return;

        if (!hasJynstrumentInstalled(tw.getContentPane(), "RailDriverModeToggle")) {
            try {
                String modePath = FileUtil.getProgramPath()
                        + "jython/Jynstruments/ThrottleWindowToolBar/RailDriverModeToggle.jyn";
                tw.ynstrument(modePath);
            } catch (RuntimeException ex) {
                log.warn("Auto-install of RailDriverModeToggle Jynstrument failed", ex);
            }
        }

        if (!hasJynstrumentInstalled(tw.getContentPane(), "RailDriverConnectivityIndicator")) {
            try {
                String indicatorPath = FileUtil.getProgramPath()
                        + "jython/Jynstruments/ThrottleWindowToolBar/RailDriverConnectivityIndicator.jyn";
                tw.ynstrument(indicatorPath);
            } catch (RuntimeException ex) {
                log.warn("Auto-install of RailDriverConnectivityIndicator Jynstrument failed", ex);
            }
        }
    }

    /**
     * Subscribes the {@link RailDriverAirStatusPanel} (which lives inside
     * the {@code ThrottleFrame}) to the semi-realistic engine's air state
     * events, and makes it visible. Idempotent — skips if already wired
     * to the same engine.
     * <p>
     * Must be called on the EDT after engine and throttle frame are set.
     */
    private void installAirStatusPanel() {
        if (activeThrottleFrame == null || engine == null) return;
        if (!isSemiRealisticLiveEnabled()) return;

        RailDriverAirStatusPanel panel = activeThrottleFrame.getAirStatusPanel();
        if (panel == null) return;

        SemiRealisticSettings s = getCalibration().semiRealistic();
        panel.subscribeToEngine(engine, s, (newPos) -> {
            SemiRealisticSettings snapshot = new SemiRealisticSettings(getCalibration().semiRealistic());
            snapshot.loadSliderPosition = newPos;
            getCalibration().semiRealistic().loadSliderPosition = newPos;
            ThreadingUtil.runOnLayout(() -> {
                if (engine != null) {
                    engine.updateSettings(snapshot);
                }
            });
        });
        panel.setVisible(true);
        log.debug("Air status panel subscribed to engine and made visible");
    }

    /**
     * Unsubscribes the air status panel from the engine and hides it.
     * Safe to call when the panel is not connected.
     */
    private void removeAirStatusPanel() {
        if (activeThrottleFrame != null) {
            RailDriverAirStatusPanel panel = activeThrottleFrame.getAirStatusPanel();
            if (panel != null) {
                panel.unsubscribeFromEngine();
                panel.setVisible(false);
                log.debug("Air status panel unsubscribed from engine and hidden");
            }
        }
    }

    private void startPollingThread() {
        thread = new Thread(ThreadingUtil.getJmriThreadGroup(), () -> {
            byte[] buff_old = new byte[14]; // read buffer
            Arrays.fill(buff_old, (byte) 0);
            // Use Thread.currentThread() so the loop condition does not
            // depend on the outer `thread` field reference being stable
            // across thread restarts.
            while (!Thread.currentThread().isInterrupted()) {
                // Hot-plug defence (per stage 2 rubber-duck critique): the
                // hidDeviceDetached listener nulls the shared hidDevice
                // reference asynchronously. Snapshot it locally and bail
                // out cleanly if the device went away — otherwise the
                // next isOpen() call would NPE.
                HidDevice dev = hidDevice;
                if (dev == null) {
                    try {
                        TimeUnit.MILLISECONDS.sleep(500);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                    continue;
                }
                try {
                    if (!dev.isOpen()) {
                        dev.open();
                    }
                } catch (IllegalStateException ex) {
                    log.warn("RailDriver HID device open failed; pausing polling", ex);
                    try { TimeUnit.MILLISECONDS.sleep(500); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                byte[] buff_new = new byte[14]; // read buffer
                int ret;
                try {
                    // Use a 50ms timeout so the loop's isInterrupted() check
                    // runs regularly and Thread.interrupt() can cleanly stop
                    // the thread during JMRI shutdown. The no-timeout overload
                    // (read(byte[])) blocks indefinitely in native code and
                    // does not respond to Java's interrupt mechanism.
                    ret = dev.read(buff_new, 50);
                } catch (IllegalStateException ex) {
                    log.warn("RailDriver HID device read failed; pausing polling", ex);
                    try { TimeUnit.MILLISECONDS.sleep(500); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                    continue;
                }
                if (ret > 0) {
                    for (int i = 0; i < buff_new.length; i++) {
                        // Per-axis change detection. Analog bytes (0..6) get
                        // hysteresis to absorb the ~1-byte potentiometer
                        // jitter the user observes when a lever is at rest;
                        // an event only fires when the new byte differs
                        // from the last-emitted byte by at least
                        // ANALOG_NOISE_THRESHOLD. Digital bytes (7..13) are
                        // unfiltered — buttons need single-bit response.
                        boolean changed;
                        if (i < 7) {
                            int diff = Math.abs((0xFF & buff_new[i]) - (0xFF & buff_old[i]));
                            changed = diff >= ANALOG_NOISE_THRESHOLD;
                        } else {
                            changed = buff_old[i] != buff_new[i];
                        }
                        if (changed) {
                            if (i < 7) {
                                int vInt = 0xFF & buff_new[i];
                                String byteName = String.format("Byte %d", i);
                                firePropertyChange("RawByte", byteName, Integer.toString(vInt));

                                double vDouble = (256 - vInt) / 256.D;
                                if (i == 1) {
                                    // Lever DOWN (toward THROTTLE label, byte ~0xdd) -> positive value -> loco moves.
                                    // Lever UP (toward DYN BRAKE label, byte ~0x3a) -> negative value -> dynamic-brake side.
                                    vDouble = 1.D - (2.D * vDouble);
                                }
                                String name1 = String.format("Axis %d", i);
                                log.info("firePropertyChange(\"Value\", {}, {})", name1, vDouble);
                                firePropertyChange("Value", name1, Double.toString(vDouble));
                            } else {
                                byte xor = (byte) (buff_old[i] ^ buff_new[i]);
                                for (int bit = 0; bit < 8; bit++) {
                                    byte mask = (byte) (1 << bit);
                                    if (mask == (mask & xor)) {
                                        int n = (8 * (i - 7)) + bit;
                                        String name2 = String.format("%d", n);
                                        boolean down = (mask == (buff_new[i] & mask));
                                        log.info("firePropertyChange(\"Value\", {}, {})", name2, down ? "1" : "0");
                                        firePropertyChange("Value", name2, down ? "1" : "0");
                                    }
                                }
                            }
                            buff_old[i] = buff_new[i];
                        }
                    }
                } else {
                    String error = hidDevice.getLastErrorMessage();
                    if (error != null) {
                        log.error("hidDevice.read error: {}", error);
                    }
                }
            }
        });
        thread.setName("RailDriver");
        thread.setDaemon(true);
        thread.start();
    }

    private void testRailDriver(boolean testFlag) {
        if (testFlag) {
            new Thread(() -> {
                //
                // this is here for testing the SevenSegmentAlpha (LED display)
                //
                for (int pass = 0; pass < 3; pass++) {
                    for (char c = 'A'; c < 'Z'; c++) {
                        StringBuilder s = new StringBuilder();
                        for (int i = 0; i < 3; i++) {
                            char ci = (char) (c + i);
                            ci = (char) (((ci - 'A') % 26) + 'A');
                            s.append(ci);
                            if (0 == ci % 3) {
                                s.append('.');
                            }
                        }
                        setLEDs(s.toString());
                        sleep(0.25);
                    }
                }

                sendString("The quick brown fox jumps over the lazy dog.", 0.250);
                sleep(2.0);

                setLEDs("8.8.8.");
                sleep(2.0);

                setLEDs("???");
                sleep(3.0);

                setLEDs("Pro");
            }).start();
        }
    }

    /**
     * send a string to the LED display (asynchronously)
     *
     * @param string what to send
     * @param delay  how much to delay before shifting in next character
     */
    public void sendStringAsync(@Nonnull String string, double delay) {
        new Thread(() -> {
            sendString(string, delay);
        }).start();
    }

    /**
     * send a string to the LED display
     *
     * @param string what to send
     * @param delay  how much to delay before shifting in next character
     */
    public void sendString(@Nonnull String string, double delay) {
        for (int i = 0; i < string.length(); i++) {
            StringBuilder ledstring = new StringBuilder();
            int maxJ = 3;
            for (int j = 0; j < maxJ; j++) {
                if (i + j < string.length()) {
                    char c = string.charAt(i + j);
                    ledstring.append(c);
                    if (c == '.') {
                        maxJ++;
                    }
                } else {
                    break;
                }
            }
            setLEDs(ledstring.toString());
            sleep(delay);
        }
    }

    private void sleep(double delay) {
        try {
            TimeUnit.MILLISECONDS.sleep((long) (delay * 1000.0));
        } catch (InterruptedException ex) {
            log.debug("TimeUnit.sleep InterruptedException", ex);
        }
    }

    //
    // constants used to talk to RailDriver
    //
    // these are the report ID's
    private final byte LEDCommand = (byte) 134; // Command code to set the LEDs.
    private final byte SpeakerCommand = (byte) 133; // Command code to set the speaker state.

    // Seven segment lookup table for digits ('0' thru '9')
    private final byte SevenSegment[] = {
        //'0'   '1'   '2'   '3'   '4'   '5'   '6'   '7'   '8'   '9'
        0x3f, 0x06, 0x5b, 0x4f, 0x66, 0x6d, 0x7d, 0x07, 0x7f, 0x6f};

    // Seven segment lookup table for alphas ('A' thru 'Z')
    private final byte SevenSegmentAlpha[] = {
        //'A'   'b'   'C'   'd'   'E'   'F'   'g'   'H'   'i'   'J'
        0x77, 0x7C, 0x39, 0x5E, 0x79, 0x71, 0x6F, 0x76, 0x04, 0x1E,
        //'K'   'L'   'm'   'n'   'o'   'P'   'q'   'r'   's'   't'
        0x70, 0x38, 0x54, 0x23, 0x5C, 0x73, 0x67, 0x50, 0x6D, 0x44,
        //'u'   'v'   'W'   'X'   'y'   'z'
        0x1C, 0x62, 0x14, 0x36, 0x72, 0x49
    };

    // other seven segment display patterns
    private final byte BLANKSEGMENT = 0x00;
    private final byte QUESTIONMARK = 0x53;
    private final byte DASHSEGMENT = 0x40;
    private final byte DPSEGMENT = (byte) 0x80;

    // Set the LEDS.
    public void setLEDs(@Nonnull String ledstring) {
        byte[] buff = new byte[7]; // Segment buffer.
        Arrays.fill(buff, (byte) 0);

        int outIdx = 2;
        for (int i = 0; i < ledstring.length(); i++) {
            char c = ledstring.charAt(i);
            if (Character.isDigit(c)) {
                //log.debug("buff[{}] = {}", outIdx, "" + c);
                // Get seven segment code for digit.
                buff[outIdx] = SevenSegment[c - '0'];
            } else if (Character.isWhitespace(c)) {
                buff[outIdx] = BLANKSEGMENT;
            } else if (c == '_') {
                buff[outIdx] = BLANKSEGMENT;
            } else if (c == '?') {
                buff[outIdx] = QUESTIONMARK;
            } else if ((c >= 'A') && (c <= 'Z')) {
                // Get seven segment code for alpha.
                buff[outIdx] = SevenSegmentAlpha[c - 'A'];
            } else if ((c >= 'a') && (c <= 'z')) {
                // Get seven segment code for alpha.
                buff[outIdx] = SevenSegmentAlpha[c - 'a'];
            } else if (c == '-') {
                buff[outIdx] = DASHSEGMENT;
            } else // Is it a decimal point?
            if (c == '.') {
                // If so, OR in the decimal point segment.
                buff[outIdx + 1] |= DPSEGMENT;
                outIdx++;
            } else {    // everything else is ignored
                outIdx++;
            }
            outIdx--;
            if (outIdx < 0) {
                if (++i < ledstring.length()) {
                    if (ledstring.charAt(i) == '.') {
                        buff[0] |= DPSEGMENT;
                    }
                }
                break;
            }
        }
        sendMessage(buff, LEDCommand);
    }   // setLEDs

    public void setSpeakerOn(boolean onFlag) {
        byte[] buff = new byte[7]; // data buffer
        Arrays.fill(buff, (byte) 0);

        buff[5] = (byte) (onFlag ? 1 : 0);      // On / off

        sendMessage(buff, SpeakerCommand);
    }   // setSpeakerOn

    // Turn speaker on.
    public void speakerOn() {
        setSpeakerOn(true);
    }

    // Turn speaker off.
    public void speakerOff() {
        setSpeakerOn(false);
    }

    /**
     * send message to hid device {p}
     * <p>
     * @param message   the message to send
     * @param reportID  the report ID
     */
    private void sendMessage(byte[] message, byte reportID) {
        HidDevice dev = hidDevice;
        if (dev == null) {
            log.debug("sendMessage: no HID device; ignoring");
            return;
        }
        // Ensure device is open after an attach/detach event
        try {
            if (!dev.isOpen()) {
                dev.open();
            }
        } catch (IllegalStateException ex) {
            log.error("hidDevice open Exception", ex);
            return;
        }

        try {
            int ret = dev.write(message, message.length, reportID);
            if (ret >= 0) {
                log.debug("hidDevice.write returned: {}", ret);
            } else {
                log.error("hidDevice.write error: {}", dev.getLastErrorMessage());
            }
        } catch (IllegalStateException ex) {
            log.error("hidDevice.write Exception", ex);
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public void hidDeviceAttached(HidServicesEvent event) {
        log.info("hidDeviceAttached({})", event);
        // Stage 2: VID/PID-matched hot-plug fires a PCS event so the
        // toolbar Jynstrument can update its icon. The Debug-menu
        // workflow keeps owning the polling lifecycle — we deliberately
        // do NOT auto-call setupRailDriver() here so cold-plug behaviour
        // is unchanged for users without the Jynstrument.
        HidDevice tHidDevice = event.getHidDevice();
        if (tHidDevice != null
                && tHidDevice.getVendorId() == VENDOR_ID
                && tHidDevice.getProductId() == PRODUCT_ID) {
            boolean was = railDriverPresent;
            railDriverPresent = true;
            if (!was) {
                firePropertyChange("railDriverConnected", false, true);
            }
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public void hidDeviceDetached(HidServicesEvent event) {
        log.info("hidDeviceDetached({})", event);
        if (hidDevice == event.getHidDevice()) {
            hidDevice = null;
        }
        // VID/PID match (or our active device went away) → mark absent.
        HidDevice tHidDevice = event.getHidDevice();
        if (tHidDevice != null
                && tHidDevice.getVendorId() == VENDOR_ID
                && tHidDevice.getProductId() == PRODUCT_ID) {
            boolean was = railDriverPresent;
            railDriverPresent = false;
            if (was) {
                firePropertyChange("railDriverConnected", true, false);
            }
        }
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public void hidFailure(HidServicesEvent event) {
        log.warn("hidFailure({})", event);
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public void hidDataReceived(HidServicesEvent event) {
        // No-op. This class reads the device synchronously from its polling
        // thread (see setupRailDriver) and does not consume listener-pushed
        // data. Method required by hid4java 0.8.0's HidServicesListener.
    }

    /*
     * {@inheritDoc}
     */
    @Override
    public void propertyChange(PropertyChangeEvent event) {
        // log.debug("{}", event);
        switch (event.getPropertyName()) {
            case "ancestor":
                // Throttle window or active throttle frame is closing.
                // Stop dispatching polling-thread "Value" events to it
                // and clear our references. The polling thread itself
                // continues so the settings window's calibration tab (if
                // open) keeps receiving "RawByte" events.
                removePropertyChangeListener("Value", throttleDispatcher);
                if (attachedAddressPanel != null) {
                    attachedAddressPanel.removeAddressListener(addressListener);
                    attachedAddressPanel = null;
                }
                if (engine != null) {
                    engine.dispose();
                    engine = null;
                }
                removeAirStatusPanel();
                ThrottleFrame oldFrame = activeThrottleFrame;
                if (throttleWindow != null) {
                    throttleWindow.removePropertyChangeListener(this);
                    throttleWindow = null;
                }
                if (activeThrottleFrame != null) {
                    activeThrottleFrame.removePropertyChangeListener(this);
                    activeThrottleFrame = null;
                }
                if (oldFrame != null) {
                    firePropertyChange("activeThrottleFrame", oldFrame, null);
                }
                break;
            case "ThrottleFrame":
                //Current throttle frame changed
                Object object = event.getNewValue();
                //log.debug("event.newValue(): " + object);
                if (object == null) {
                    if (activeThrottleFrame != null) {
                        activeThrottleFrame.removePropertyChangeListener(this);
                        activeThrottleFrame = null;
                    }
                } else if (object instanceof ThrottleFrame) {

                    if (throttleWindow != null) {
                        throttleWindow.removePropertyChangeListener(this);
                        throttleWindow = null;
                    }
                    if (activeThrottleFrame != null) {
                        activeThrottleFrame.removePropertyChangeListener(this);
                        activeThrottleFrame = null;
                    }

                    activeThrottleFrame = (ThrottleFrame) object;
                    throttleWindow = activeThrottleFrame.getThrottleControllersContainer();

                    throttleWindow.addPropertyChangeListener(this);
                    activeThrottleFrame.addPropertyChangeListener(this);

                }
                break;
            default:
                break;
        }
    }   // propertyChange

    /**
     * Listener body for polling-thread {@code "Value"} events. Registered
     * as {@link #throttleDispatcher} only while a throttle frame is
     * attached; outside that window, polling-thread {@code "Value"}
     * events have no listener and are silently ignored.
     * <p>
     * Intentionally has no null-guard on {@link #activeThrottleFrame}.
     * If this method is reached with a null active frame, the
     * registration lifecycle is wrong and the resulting NPE surfaces
     * the bug rather than silently absorbing it (see
     * {@code plan-impl-phase3.md} follow-up: don't-mask refactor).
     */
    private void dispatchValueEvent(PropertyChangeEvent event) {
        String oldValue = event.getOldValue().toString();
        String newValue = event.getNewValue().toString();
        DccThrottle throttle = activeThrottleFrame.getAddressPanel().getThrottle();
        AddressPanel addressPanel = activeThrottleFrame.getAddressPanel();
        //log.info("propertyChange \"Value\" old: {}, new: {}", oldValue, newValue);

        double value;
        try {
            value = Double.parseDouble(newValue);
        } catch (NumberFormatException ex) {
            log.error("RailDriver parse property new value ('{}')", newValue, ex);
            return;
        }
        switch (oldValue) {
            case "Axis 0":
                // REVERSER. Direction switches when the live value crosses
                // calibrated thresholds (midpoints between Neutral and the
                // two extremes per RailDriverCalibration).
                log.info("REVERSER value: {}", value);
                if (throttle != null) {
                    final DccThrottle t = throttle;
                    RailDriverCalibration cal = getCalibration();
                    SemiRealisticThrottleEngine.Direction dir;
                    if (value < cal.reverserReverseThreshold()) {
                        dir = SemiRealisticThrottleEngine.Direction.REVERSE;
                        ThreadingUtil.runOnGUIEventually(() -> t.setIsForward(false));
                    } else if (value > cal.reverserForwardThreshold()) {
                        dir = SemiRealisticThrottleEngine.Direction.FORWARD;
                        ThreadingUtil.runOnGUIEventually(() -> t.setIsForward(true));
                    } else {
                        dir = SemiRealisticThrottleEngine.Direction.NEUTRAL;
                    }
                    if (engine != null) {
                        final SemiRealisticThrottleEngine.Direction fDir = dir;
                        ThreadingUtil.runOnLayoutEventually(() -> engine.setDirection(fDir));
                    }
                }
                break;
            case "Axis 1":
                // THROTTLE / Dynamic Brake. Lever DOWN (toward THROTTLE label)
                // produces positive values that drive the loco; lever UP
                // (toward DYN BRAKE label) produces negative values that
                // currently only set the "DBr" LED (dyn-brake wiring is a
                // future stage). The min / max pin values come from the
                // calibrated Idle (+ user-configurable deadband) and full
                // Throttle byte values.
                log.info("THROTTLE value: {}", value);
                if (throttle != null) {
                    final DccThrottle t = throttle;
                    RailDriverCalibration cal = getCalibration();
                    double throttle_min = cal.throttleMin();
                    double throttle_max = cal.throttleMax();
                    double v = MathUtil.pin(value, throttle_min, throttle_max);
                    double fraction = (v - throttle_min) / (throttle_max - throttle_min);
                    final float fractionF = (float) fraction;
                    // Stage 2: when the engine is driving, route the lever
                    // through it instead of writing setSpeedSetting directly.
                    if (engine != null && engine.isDriving()) {
                        ThreadingUtil.runOnLayoutEventually(() -> {
                            engine.setThrottleFraction(fractionF);
                            engine.setDynBrakeFraction(0f);
                        });
                    } else {
                        ThreadingUtil.runOnGUIEventually(() -> t.setSpeedSetting(fractionF));
                    }
                    if (value < 0) {
                        //TODO: dynamic braking
                        setLEDs("DBr");
                    } else {
                        String speed = String.format("%03d", (int) fraction*100);
                        //log.info("speed: " + speed);
                        setLEDs(speed);
                    }
                }
                break;
            case "Axis 2":
                // AUTOBRAKE is the state of the Automatic (trainline) brake.
                // Large values for no braking, small values for more braking.
                // Converts the calibrated byte range to a 0..100 demanded
                // air line value (100 = released, 0 = emergency) for the
                // Westinghouse air model.
                log.info("AUTOBRAKE value: {}", value);
                if (engine != null) {
                    RailDriverCalibration cal = getCalibration();
                    int released = cal.autoBrake().released != null
                            ? cal.autoBrake().released
                            : RailDriverCalibration.DEF_AUTOBRAKE_RELEASED;
                    int emg = cal.autoBrake().emg != null
                            ? cal.autoBrake().emg
                            : RailDriverCalibration.DEF_AUTOBRAKE_EMG;
                    int range = released - emg;
                    if (range != 0) {
                        // Recover byte from the polling thread's (256-byte)/256 transform.
                        int byteValue = (int) Math.round((1.0 - value) * 256.0);
                        // Map to 0..100 where released=100, emg=0.
                        int demand = (int) Math.round(((double)(byteValue - emg) / (double) range) * 100.0);
                        if (demand < 0) demand = 0;
                        if (demand > 100) demand = 100;
                        final int fDemand = demand;
                        ThreadingUtil.runOnLayoutEventually(() -> engine.setAirBrakeDemand(fDemand));
                    }
                }
                break;
            case "Axis 3":
                // INDEPENDBRK is the state of the Independent (engine only)
                // brake. Large value = no braking; small value = more braking.
                // Stage 2 wires this into the semi-realistic engine's
                // F_brake_mech force term. The fraction calculation works
                // in byte-space per plan §3.2 step 2.
                log.info("INDEPENDBRK value: {}", value);
                if (engine != null) {
                    RailDriverCalibration cal = getCalibration();
                    int fullRelease = cal.indepBrake().fullRelease != null
                            ? cal.indepBrake().fullRelease
                            : RailDriverCalibration.DEF_INDEPBRAKE_FULLRELEASE;
                    int fullApp = cal.indepBrake().fullApplication != null
                            ? cal.indepBrake().fullApplication
                            : RailDriverCalibration.DEF_INDEPBRAKE_FULLAPP;
                    int range = fullRelease - fullApp;
                    if (range != 0) {
                        // Recover the byte value from the polling thread's
                        // (256 - byte) / 256 transform. Math.round avoids
                        // the off-by-one rounding artifacts of plain (int).
                        int byteValue = (int) Math.round((1.0 - value) * 256.0);
                        float fraction = (float) (fullRelease - byteValue) / (float) range;
                        if (fraction < 0f) fraction = 0f;
                        if (fraction > 1f) fraction = 1f;
                        final float brFrac = fraction;
                        ThreadingUtil.runOnLayoutEventually(() -> engine.setIndepBrakeFraction(brFrac));
                    }
                }
                break;
            case "Axis 4":
                // BAILOFF is the Independent brake 'bailoff', this is the spring
                // loaded right movement of the Independent brake lever.  Larger
                // values mean the lever has been shifted right.
                log.info("BAILOFF value: {}", value);
                break;
            case "Axis 5":
                // HEADLIGHT is the state of the headlight switch.  A value below 0.5
                // is off, a value near 0.5 is dim, and a number much larger than 0.5
                // is full. This is an analog input w/detents, not a switch!
                log.info("HEADLIGHT value: {}", value);
                break;
            case "Axis 6":
                // LIGHTS is the locomotive headlight rotary (#13 per
                // control-inventory.md): three physical positions Off / Dim / Full.
                // Per Java's `(256 - vInt)/256` transform, OFF (~0x52) yields the
                // highest value (~0.68) and Full (~0x9c) the lowest (~0.39); Dim sits
                // between them. Drive F0 (the conventional DCC headlight function)
                // off in OFF position, on in any other position. Threshold comes
                // from RailDriverCalibration (calibrated midpoint between OFF and
                // Dim, or OFF and Full if Dim isn't captured; defaults to ~0.6).
                log.info("LIGHTS value: {}", value);
                if (throttle != null) {
                    final DccThrottle t = throttle;
                    final boolean lightsOn = value < getCalibration().lightsThreshold();
                    ThreadingUtil.runOnGUIEventually(() -> t.setFunction(0, lightsOn));
                }
                break;
            default:
                log.info("FUNCTION {} value: {}", oldValue, value);
                boolean isDown = (value > 0.5D);
                int fNum ;
                try {
                    fNum = Integer.parseInt(oldValue);
                } catch (NumberFormatException ex) {
                    //log.error("RailDriver parse property new value ('{}') exception: {}", newValue, ex);
                    return;
                }
                String ledString = String.format("F%d", fNum + 1);
                switch (fNum) {
                    case 28: {  // zoom/rocker button up
                        if ((addressPanel != null) && isDown) {
                            final AddressPanel ap = addressPanel;
                            ThreadingUtil.runOnGUIEventually(() -> ap.selectRosterEntry());
                            DccLocoAddress a = addressPanel.getCurrentAddress();
                            ledString = "sel " + ((a != null) ? a.toString() : "null");
                        }
                        fNum = -1;  // case 28 handles its own action; suppress trailing setFunction
                        break;
                    }
                    case 29: {  // zoom/rocker button down
                        if ((addressPanel != null) && isDown) {
                            final AddressPanel ap = addressPanel;
                            ThreadingUtil.runOnGUIEventually(() -> ap.dispatchAddress());
                            DccLocoAddress a = addressPanel.getCurrentAddress();
                            ledString = "dis " + ((a != null) ? a.toString() : "null");
                        }
                        fNum = -1;
                        break;
                    }
                    case 30: {  // four way panning up
                        if ((addressPanel != null) && isDown) {
                            int selectedIndex = addressPanel.getRosterSelectedIndex();
                            if (selectedIndex > 1) {
                                final AddressPanel ap = addressPanel;
                                final int newIndex = selectedIndex - 1;
                                ThreadingUtil.runOnGUIEventually(() -> ap.setRosterSelectedIndex(newIndex));
                                ledString = String.format("Prev %d", selectedIndex - 1);
                            }
                        }
                        fNum = -1;
                        break;
                    }
                    case 31: {  // four way panning right
                        if (isDown) {
                            ThrottleWindow tw = throttleWindow;
                            if (tw != null) {
                                ThreadingUtil.runOnGUIEventually(() -> tw.nextThrottleFrame());
                            }
                            ledString = "NXT";
                        }
                        fNum = -1;
                        break;
                    }
                    case 32: {  // four way panning down
                        if ((addressPanel != null) && isDown) {
                            RosterEntrySelectorPanel resp = addressPanel.getRosterEntrySelector();
                            if (resp != null) {
                                RosterEntryComboBox recb = resp.getRosterEntryComboBox();
                                if (recb != null) {
                                    int cnt = recb.getItemCount();
                                    int selectedIndex = addressPanel.getRosterSelectedIndex();
                                    if (selectedIndex + 1 < cnt) {
                                        try {
                                            final AddressPanel ap = addressPanel;
                                            final int newIndex = selectedIndex + 1;
                                            ThreadingUtil.runOnGUIEventually(() -> ap.setRosterSelectedIndex(newIndex));
                                            ledString = String.format("Next %d", selectedIndex + 1);
                                        } catch (ArrayIndexOutOfBoundsException ex) {
                                            // ignore this
                                        }
                                    }
                                }
                            }
                        }
                        fNum = -1;
                        break;
                    }
                    case 33: {  // four way panning left
                        if (isDown) {
                            ThrottleWindow tw = throttleWindow;
                            if (tw != null) {
                                ThreadingUtil.runOnGUIEventually(() -> tw.previousThrottleFrame());
                            }
                            ledString = "PRE";
                        }
                        fNum = -1;
                        break;
                    }
                    case 34: {  // Gear Shift Up
                        if ((throttle != null) && isDown) {
                            // shuntFn
                            final DccThrottle t = throttle;
                            ThreadingUtil.runOnGUIEventually(() -> t.setFunction(3, false));
                        }
                        break;
                    }
                    case 35: {  // Gear Shift Down
                        if ((throttle != null) && isDown) {
                            // shuntFn
                            final DccThrottle t = throttle;
                            ThreadingUtil.runOnGUIEventually(() -> t.setFunction(3, true));
                        }
                        break;
                    }
                    case 36:
                    case 37: {  // Emergency Brake up/down
                        if ((throttle != null) && isDown) {
                            if (engine != null && engine.isDriving()) {
                                ThreadingUtil.runOnLayoutEventually(() -> engine.emergencyHalt());
                            } else {
                                final DccThrottle t = throttle;
                                ThreadingUtil.runOnGUIEventually(() -> t.setSpeedSetting(-1));
                            }
                        }
                        break;
                    }

                    case 38: {  // Alerter
                        if (isDown) {
                            fNum = 6;   // alertFn
                        }
                        break;
                    }
                    case 39: {  // Sander
                        if (isDown) {
                            fNum = 7;   // sandFn
                        }
                        break;
                    }
                    case 40: {  // Pantograph
                        if (isDown) {
                            fNum = 8;   // pantoFn
                        }
                        break;
                    }
                    case 41: {  // Bell
                        if (isDown) {
                            fNum = 1;   // bellFn
                        }
                        break;
                    }
                    case 42:
                    case 43: {  // Horn/Whistle
                        fNum = 2;   // hornFn
                        break;
                    }
                    default: {
                        // Front-edge user-assignable buttons (parser slots 0..27)
                        // map to F1..F28 (slot N -> F(N+1)). F0 is driven by the
                        // Lights rotary (Axis 6), freeing all 28 front-edge buttons
                        // to cover F1..F28 cleanly on a 29-function loco.
                        if (fNum >= 0 && fNum <= 27) {
                            fNum = fNum + 1;
                        }
                        break;
                    }
                }
                if (throttle != null && fNum >= 0) {
                    final DccThrottle t = throttle;
                    final int finalFNum = fNum;
                    final boolean finalIsDown = isDown;
                    int functionsLength = ThreadingUtil.runOnGUIwithReturn(() -> t.getFunctions().length);
                    if (finalFNum < functionsLength) {
                        boolean isMomentary = ThreadingUtil.runOnGUIwithReturn(() -> t.getFunctionMomentary(finalFNum));
                        if (!isMomentary) {
                            if (finalIsDown) {
                                boolean current = ThreadingUtil.runOnGUIwithReturn(() -> t.getFunction(finalFNum));
                                ThreadingUtil.runOnGUIEventually(() -> t.setFunction(finalFNum, !current));
                            }
                        } else {
                            ThreadingUtil.runOnGUIEventually(() -> t.setFunction(finalFNum, finalIsDown));
                        }
                    }
                }
                if (isDown) {
                    if (ledString.length() <= 3) {
                        setLEDs(ledString);
                    } else {
                        sendStringAsync(ledString, 0.333);
                    }
                }
                break; // if (oldValue.equals(...) {} else...
        }
    }

    /**
     * Returns the active per-profile calibration, lazily creating a default
     * (non-null) instance the first time this is called before
     * {@link #setupRailDriver()} has run. Callers can rely on a non-null
     * return.
     */
    @Nonnull
    public RailDriverCalibration getCalibration() {
        if (calibration == null) {
            calibration = RailDriverCalibration.loadOrDefault(RailDriverCalibration.getDefaultFile());
        }
        return calibration;
    }

    /**
     * Re-reads the calibration file from disk. Called by the unified
     * settings frame after Save so subsequent control movements use the
     * new values without restarting JMRI. Stage 2: also pushes the new
     * settings to the engine and fires {@code "liveEnabledChanged"} /
     * {@code "persistedEnabledChanged"} when those values changed across
     * the reload.
     */
    public void reloadCalibration() {
        boolean oldPersisted = false;
        boolean oldLive = false;
        if (calibration != null) {
            oldPersisted = calibration.semiRealistic().persistedEnabled;
            oldLive = calibration.semiRealistic().liveEnabled;
        }
        calibration = RailDriverCalibration.loadOrDefault(RailDriverCalibration.getDefaultFile());
        SemiRealisticSettings s = calibration.semiRealistic();
        log.info("RailDriver calibration reloaded.");
        if (engine != null) {
            engine.updateSettings(s);
        }
        // Update the air status panel's slider labels/range if settings changed.
        if (activeThrottleFrame != null) {
            RailDriverAirStatusPanel panel = activeThrottleFrame.getAirStatusPanel();
            if (panel != null) {
                ThreadingUtil.runOnGUIEventually(() -> panel.updateSettings(s));
            }
        }
        if (oldPersisted != s.persistedEnabled) {
            firePropertyChange("persistedEnabledChanged", oldPersisted, s.persistedEnabled);
        }
        if (oldLive != s.liveEnabled) {
            firePropertyChange("liveEnabledChanged", oldLive, s.liveEnabled);
        }
    }

    // ==================== Stage 2 public API (settings + Jynstrument) ====================

    /** @return current live enable state (engine + Jynstrument source of truth). */
    public boolean isSemiRealisticLiveEnabled() {
        return getCalibration().semiRealistic().liveEnabled;
    }

    /** @return current persisted enable state (Settings tab source of truth). */
    public boolean isSemiRealisticPersistedEnabled() {
        return getCalibration().semiRealistic().persistedEnabled;
    }

    /**
     * Jynstrument toolbar click: mutates only {@code liveEnabled}. Does NOT
     * write XML; persisted state is unchanged. The session-only flag resets
     * to {@code persistedEnabled} on next launch.
     * <p>
     * The Settings-tab Save/Apply path is implemented inside
     * {@link RailDriverSettingsFrame#doSaveOrApply(boolean)} as a direct
     * {@code working.save(file)} + {@link #reloadCalibration()} sequence;
     * there is no equivalent {@code applyPersistedEnabled} entry point on
     * this class, because the Settings tab needs to persist the entire
     * calibration (including the calibration tab's per-axis bytes), not
     * just the {@code persistedEnabled} flag. {@code reloadCalibration}
     * fires {@code persistedEnabledChanged} and {@code liveEnabledChanged}
     * when the values change across the reload.
     */
    public void setSemiRealisticEnabledSessionOnly(boolean enabled) {
        SemiRealisticSettings s = getCalibration().semiRealistic();
        boolean wasLive = s.liveEnabled;
        if (wasLive == enabled) return;
        s.liveEnabled = enabled;
        firePropertyChange("liveEnabledChanged", wasLive, enabled);
    }

    /** @return whether a RailDriver HID device is currently physically
     *  present (set on hot-plug attach event with matching VID/PID, or
     *  on successful cold-plug device acquire; cleared on detach). */
    public boolean isRailDriverConnected() {
        return railDriverPresent;
    }

    /** @return the throttle frame currently bound to RailDriver, or null. */
    @CheckForNull
    public ThrottleFrame getActiveThrottleFrame() {
        return activeThrottleFrame;
    }

    /** @return true between {@link #requestAttachToThrottle} and the
     *  matching {@code "activeThrottleFrame"} PCS event. */
    public boolean isAttachInProgress() {
        return attachInProgress;
    }

    /**
     * Asynchronously bind the RailDriver to {@code tf}. No-op when
     * {@code tf} is already the active frame. Otherwise sets
     * {@code attachInProgress = true}, fires {@code "attachInProgress"}
     * (true), schedules {@link #ensureDeviceAndPolling()} +
     * {@link #attachThrottleWindow()} on the EDT. The post-attach lambda
     * (inside {@code attachThrottleWindow}) fires
     * {@code "activeThrottleFrame"} and clears
     * {@code "attachInProgress"} (false) on success.
     */
    public void requestAttachToThrottle(@CheckForNull ThrottleFrame tf) {
        if (tf != null && tf == activeThrottleFrame) {
            return; // already bound
        }
        if (attachInProgress) {
            return; // request already in flight
        }
        attachInProgress = true;
        firePropertyChange("attachInProgress", false, true);
        if (tf != null) {
            // Adopt the requested frame as the target before
            // attachThrottleWindow runs; the inner lambda will fire
            // activeThrottleFrame against whatever tfManager surfaces.
            activeThrottleFrame = tf;
            throttleWindow = tf.getThrottleControllersContainer();
        }
        ThreadingUtil.runOnGUIEventually(() -> {
            if (!ensureDeviceAndPolling()) {
                attachInProgress = false;
                firePropertyChange("attachInProgress", true, false);
                return;
            }
            attachThrottleWindow();
        });
    }

    /** Add a listener for the new stage-2 PCS events
     *  ({@code "liveEnabledChanged"}, {@code "persistedEnabledChanged"},
     *  {@code "railDriverConnected"}, {@code "activeThrottleFrame"},
     *  {@code "attachInProgress"}). Listeners are invoked on whatever
     *  thread fires the event (typically the EDT). */
    public void addSettingsListener(@Nonnull PropertyChangeListener l) {
        addPropertyChangeListener(l);
    }

    public void removeSettingsListener(@Nonnull PropertyChangeListener l) {
        removePropertyChangeListener(l);
    }

    //initialize logging
    private transient final static Logger log = LoggerFactory.getLogger(RailDriverMenuItem.class);

}
