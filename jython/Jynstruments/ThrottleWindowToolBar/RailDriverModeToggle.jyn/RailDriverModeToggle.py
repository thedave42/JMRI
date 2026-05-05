# RailDriverModeToggle.jyn — toolbar Jynstrument for the RailDriver
# semi-realistic-throttle mode toggle.
#
# This Jynstrument exposes a single button on the throttle window's toolbar
# that flips the engine's *liveEnabled* state (session-only — no XML write).
# Right-click opens the unified RailDriver Settings window. Behaviour follows
# the five-state table in plan §2.6:
#
#   1. No device         → grey, "RailDriver not detected", click no-op
#   2. Device, no bind   → click bootstraps attach + sets pending toggle
#   2.5 Attach in flight → "binding…", click absorbed
#   3. Bound elsewhere   → grey, "bound to another window", click no-op
#   4. Fully operational → click flips liveEnabled
#
# State transitions are driven by PCS events fired from RailDriverMenuItem:
# liveEnabledChanged, railDriverConnected, activeThrottleFrame,
# attachInProgress.
#
# See jython/Jynstruments/README for the framework, and
# docs/rpi-raildriver/semi-realistic-throttle-plan.md §2.6 for the spec.

import java
import jmri.jmrit.jython.Jynstrument as Jynstrument
import jmri.jmrit.usb.RailDriverMenuItem as RailDriverMenuItem
from java.awt.event import MouseAdapter
from java.beans import PropertyChangeListener
from javax.swing import JButton, JPopupMenu, JMenuItem, ImageIcon


class RailDriverModeToggle(Jynstrument):

    def getExpectedContextClassName(self):
        return "jmri.jmrit.throttle.ThrottleWindow"

    def init(self):
        self.menuItem = RailDriverMenuItem.getInstance()
        self.iconOn = ImageIcon(self.getFolder() + "/icons/raildriver-on.png")
        self.iconOff = ImageIcon(self.getFolder() + "/icons/raildriver-off.png")
        self.iconWait = ImageIcon(self.getFolder() + "/icons/raildriver-binding.png")
        self.button = JButton(self.iconOff)
        self.button.actionPerformed = self.onClick
        self.add(self.button)
        self.pendingSessionToggle = False

        # Build right-click popup with single "Settings..." item that opens
        # the unified RailDriver Settings frame.
        popup = JPopupMenu()
        item = JMenuItem("Settings...")
        item.actionPerformed = self.openSettings
        popup.add(item)
        self.setPopUpMenu(popup)

        # Forward right-clicks on the inner button to the Jynstrument's own
        # popup-menu trigger so the operator can right-click anywhere on the
        # toolbar item to access Settings.
        self.button.addMouseListener(_PopupForwarder(self))

        if self.menuItem is not None:
            self.listener = _PCSListener(self)
            self.menuItem.addSettingsListener(self.listener)
        else:
            # Debug menu has not been opened yet — Jynstrument is dormant.
            self.listener = None

        self._refreshState()

    def quit(self):
        # Listener-deregistration only. NEVER persists "removed" state —
        # that is solely a function of whether the throttle layout XML
        # restored the toggle on next launch.
        if self.menuItem is not None and self.listener is not None:
            self.menuItem.removeSettingsListener(self.listener)
        self.listener = None

    # ----- click handler (State table §2.6) -----

    def onClick(self, evt):
        if self.menuItem is None:
            return
        if not self.menuItem.isRailDriverConnected():
            return  # State 1
        if self.menuItem.isAttachInProgress():
            return  # State 2.5
        active = self.menuItem.getActiveThrottleFrame()
        ctx = self.getContext()
        if active is None:
            # State 2 → State 2.5: bootstrap attach + defer toggle to the
            # post-bind activeThrottleFrame event handler.
            self.pendingSessionToggle = True
            current = self._currentThrottleFrame(ctx)
            self.menuItem.requestAttachToThrottle(current)
        elif active is self._currentThrottleFrame(ctx):
            # State 4: flip liveEnabled for this session only.
            new = not self.menuItem.isSemiRealisticLiveEnabled()
            self.menuItem.setSemiRealisticEnabledSessionOnly(new)
        # State 3 (bound elsewhere) → no-op

    def openSettings(self, evt):
        # Reflectively load the action class so that Jython does not need to
        # import it at module-import time (which would fail when the .jyn
        # is exercised in test contexts where the Java class is not yet
        # on the classpath).
        try:
            cls = java.lang.Class.forName("jmri.jmrit.usb.swing.RailDriverSettingsAction")
            action = cls.getDeclaredConstructor().newInstance()
            action.actionPerformed(evt)
        except Exception:
            pass

    # ----- internal helpers -----

    def _currentThrottleFrame(self, ctx):
        # ctx is the ThrottleWindow that owns this Jynstrument.
        if ctx is None:
            return None
        return ctx.getCurrentThrottleFrame()

    def _onPCS(self, evt):
        name = evt.getPropertyName()
        ctx = self.getContext()
        myFrame = self._currentThrottleFrame(ctx)
        if name == "activeThrottleFrame":
            new = evt.getNewValue()
            if new is myFrame and self.pendingSessionToggle:
                # Deferred toggle: now that THIS Jynstrument's throttle is
                # bound, apply the toggle the user clicked in State 2.
                self.menuItem.setSemiRealisticEnabledSessionOnly(
                    not self.menuItem.isSemiRealisticLiveEnabled())
                self.pendingSessionToggle = False
            elif new is not myFrame:
                # Bind landed on a different frame; cancel the deferred toggle.
                self.pendingSessionToggle = False
        self._refreshState()

    def _refreshState(self):
        if self.menuItem is None:
            self.button.setIcon(self.iconOff)
            self.button.setEnabled(False)
            self.button.setToolTipText("RailDriver throttle menu not yet opened")
            return
        if not self.menuItem.isRailDriverConnected():
            self.button.setIcon(self.iconOff)
            self.button.setEnabled(False)
            self.button.setToolTipText("RailDriver not detected")
            return
        if self.menuItem.isAttachInProgress():
            self.button.setIcon(self.iconWait)
            self.button.setEnabled(False)
            self.button.setToolTipText("RailDriver attaching to this throttle...")
            return
        active = self.menuItem.getActiveThrottleFrame()
        ctx = self.getContext()
        myFrame = self._currentThrottleFrame(ctx)
        if active is None:
            self.button.setIcon(self.iconOff)
            self.button.setEnabled(True)
            self.button.setToolTipText(
                "Click to attach RailDriver to this throttle and toggle semi-realistic mode")
            return
        if active is not myFrame:
            self.button.setIcon(self.iconOff)
            self.button.setEnabled(False)
            self.button.setToolTipText("RailDriver already bound to another throttle window")
            return
        # State 4: fully operational
        self.button.setEnabled(True)
        if self.menuItem.isSemiRealisticLiveEnabled():
            self.button.setIcon(self.iconOn)
            self.button.setToolTipText(
                "RailDriver semi-realistic throttle: ON (session)")
        else:
            self.button.setIcon(self.iconOff)
            self.button.setToolTipText(
                "RailDriver semi-realistic throttle: OFF (session)")


class _PCSListener(PropertyChangeListener):
    """Bridge from Java PCS events back into the Jynstrument. Marshals onto
    the EDT because hidDeviceAttached/Detached fire from hid4java's worker
    thread and the listener mutates Swing state."""

    def __init__(self, owner):
        self.owner = owner

    def propertyChange(self, evt):
        from javax.swing import SwingUtilities
        owner = self.owner
        def run():
            try:
                owner._onPCS(evt)
            except Exception:
                pass
        if SwingUtilities.isEventDispatchThread():
            run()
        else:
            SwingUtilities.invokeLater(run)


class _PopupForwarder(MouseAdapter):
    """Forwards right-click presses on the inner JButton to the
    Jynstrument's popup-menu trigger so the operator can right-click anywhere
    on the toolbar item to access the Settings... menu."""

    def __init__(self, owner):
        self.owner = owner

    def mousePressed(self, e):
        self._maybeShow(e)

    def mouseReleased(self, e):
        self._maybeShow(e)

    def _maybeShow(self, e):
        if e.isPopupTrigger():
            popup = self.owner.getPopUpMenu()
            if popup is not None:
                popup.show(e.getComponent(), e.getX(), e.getY())
