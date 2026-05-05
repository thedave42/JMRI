package jmri.jmrit.usb;

import java.beans.PropertyChangeSupport;
import java.io.File;
import java.io.IOException;
import java.util.Set;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import jmri.InstanceManager;
import jmri.profile.AuxiliaryConfiguration;
import jmri.profile.Profile;
import jmri.profile.ProfileManager;
import jmri.profile.ProfileUtils;
import jmri.spi.PreferencesManager;
import jmri.util.jdom.JDOMUtil;
import jmri.util.prefs.AbstractPreferencesManager;
import jmri.util.prefs.InitializationException;

import org.jdom2.Element;
import org.jdom2.JDOMException;
import org.jdom2.Namespace;
import org.openide.util.lookup.ServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * PreferencesManager for RailDriver calibration and semi-realistic
 * throttle settings. Persists two AuxiliaryConfiguration fragments:
 * <ul>
 *   <li>{@code <rd:hardwareCalibration>} in <b>private</b> space —
 *       per-machine HID byte detents.</li>
 *   <li>{@code <rd:semiRealistic>} in <b>shared</b> space —
 *       EngineDriver-aligned operator preferences.</li>
 * </ul>
 * <p>
 * On first load, if the new fragments are absent but a legacy
 * {@code raildriver-calibration.xml} file exists, detents are migrated
 * automatically and the legacy file is renamed to {@code .bak}.
 *
 * @author the Dave (phase 8)
 */
@ServiceProvider(service = PreferencesManager.class)
public class RailDriverPreferencesManager extends AbstractPreferencesManager {

    /** Namespace for both AuxiliaryConfiguration fragments. */
    public static final String NAMESPACE = "http://jmri.org/xml/schema/raildriver/3";
    static final Namespace NS = Namespace.getNamespace("rd", NAMESPACE);

    /** Fragment element name for hardware calibration (private space). */
    public static final String HW_CAL_ELEMENT = "hardwareCalibration";
    /** Fragment element name for semi-realistic settings (shared space). */
    public static final String SETTINGS_ELEMENT = "semiRealistic";

    /** PCS event fired after settings are explicitly applied (not on every save). */
    public static final String SETTINGS_CHANGED = "settingsChanged";
    /** PCS event fired after calibration is explicitly applied. */
    public static final String CALIBRATION_CHANGED = "calibrationChanged";

    private static final String LEGACY_FILE_NAME = "raildriver-calibration.xml";

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private RailDriverCalibration calibration;

    // ======================== PreferencesManager lifecycle ========================

    @Override
    public void initialize(Profile profile) throws InitializationException {
        if (isInitialized(profile)) return;

        calibration = new RailDriverCalibration();

        try {
            AuxiliaryConfiguration auxConfig =
                    ProfileUtils.getAuxiliaryConfiguration(profile);

            boolean hwCalLoaded = loadHardwareCalibration(auxConfig, calibration);
            boolean settingsLoaded = loadSettings(auxConfig, calibration);

            // Per-fragment migration: only migrate what's missing.
            if (!hwCalLoaded || !settingsLoaded) {
                migrateLegacy(profile, auxConfig, calibration,
                        hwCalLoaded, settingsLoaded);
            }
        } catch (Exception ex) {
            log.warn("RailDriver preferences init failed; using defaults", ex);
        }

        setInitialized(profile, true);
    }

    @Override
    public void savePreferences(Profile profile) {
        if (calibration == null) return;
        try {
            AuxiliaryConfiguration auxConfig =
                    ProfileUtils.getAuxiliaryConfiguration(profile);
            saveHardwareCalibration(auxConfig, calibration);
            saveSettings(auxConfig, calibration);
        } catch (Exception ex) {
            log.error("Failed to save RailDriver preferences", ex);
        }
    }

    // ======================== Public access ========================

    /**
     * Returns the in-memory calibration. Callers should not cache this
     * reference across save/reload boundaries.
     */
    @Nonnull
    public RailDriverCalibration getCalibration() {
        if (calibration == null) {
            calibration = new RailDriverCalibration();
        }
        return calibration;
    }

    /**
     * Replaces the in-memory calibration with a copy of {@code cal},
     * saves both fragments, and fires {@link #CALIBRATION_CHANGED} and
     * {@link #SETTINGS_CHANGED} PCS events.
     */
    public void applyAndSave(@Nonnull RailDriverCalibration cal) {
        if (calibration == null) {
            calibration = new RailDriverCalibration();
        }
        calibration.copyFrom(cal);
        Profile profile = ProfileManager.getDefault().getActiveProfile();
        if (profile != null) {
            savePreferences(profile);
        }
        pcs.firePropertyChange(CALIBRATION_CHANGED, null, calibration);
        pcs.firePropertyChange(SETTINGS_CHANGED, null,
                calibration.semiRealistic());
    }

    /**
     * Replaces only the semi-realistic settings, saves the shared
     * fragment, and fires {@link #SETTINGS_CHANGED}.
     */
    public void applySettings(@Nonnull SemiRealisticSettings s) {
        if (calibration == null) {
            calibration = new RailDriverCalibration();
        }
        calibration.semiRealistic().copyFrom(s);
        Profile profile = ProfileManager.getDefault().getActiveProfile();
        if (profile != null) {
            try {
                AuxiliaryConfiguration auxConfig =
                        ProfileUtils.getAuxiliaryConfiguration(profile);
                saveSettings(auxConfig, calibration);
            } catch (Exception ex) {
                log.error("Failed to save semi-realistic settings", ex);
            }
        }
        pcs.firePropertyChange(SETTINGS_CHANGED, null,
                calibration.semiRealistic());
    }

    /**
     * Replaces only the hardware calibration detents, saves the private
     * fragment, and fires {@link #CALIBRATION_CHANGED}.
     */
    public void applyCalibration(@Nonnull RailDriverCalibration cal) {
        if (calibration == null) {
            calibration = new RailDriverCalibration();
        }
        calibration.copyCalibrationFieldsFrom(cal);
        Profile profile = ProfileManager.getDefault().getActiveProfile();
        if (profile != null) {
            try {
                AuxiliaryConfiguration auxConfig =
                        ProfileUtils.getAuxiliaryConfiguration(profile);
                saveHardwareCalibration(auxConfig, calibration);
            } catch (Exception ex) {
                log.error("Failed to save hardware calibration", ex);
            }
        }
        pcs.firePropertyChange(CALIBRATION_CHANGED, null, calibration);
    }

    public void addPropertyChangeListener(
            java.beans.PropertyChangeListener l) {
        pcs.addPropertyChangeListener(l);
    }

    public void removePropertyChangeListener(
            java.beans.PropertyChangeListener l) {
        pcs.removePropertyChangeListener(l);
    }

    // ======================== Fragment I/O ========================

    /**
     * Loads the {@code <rd:hardwareCalibration>} fragment from private
     * space into the calibration POJO.
     *
     * @return true if the fragment existed and was loaded
     */
    private boolean loadHardwareCalibration(
            @Nonnull AuxiliaryConfiguration auxConfig,
            @Nonnull RailDriverCalibration cal) {
        org.w3c.dom.Element w3c =
                auxConfig.getConfigurationFragment(HW_CAL_ELEMENT, NAMESPACE, false);
        if (w3c == null) return false;
        try {
            Element e = JDOMUtil.toJDOMElement(w3c);
            populateCalibrationFromElement(cal, e);
            log.debug("Loaded hardware calibration from AuxiliaryConfiguration (private)");
            return true;
        } catch (Exception ex) {
            log.warn("Failed to parse hardware calibration fragment; using defaults", ex);
            return false;
        }
    }

    /**
     * Loads the {@code <rd:semiRealistic>} fragment from shared space
     * into the calibration's settings.
     *
     * @return true if the fragment existed and was loaded
     */
    private boolean loadSettings(
            @Nonnull AuxiliaryConfiguration auxConfig,
            @Nonnull RailDriverCalibration cal) {
        org.w3c.dom.Element w3c =
                auxConfig.getConfigurationFragment(SETTINGS_ELEMENT, NAMESPACE, true);
        if (w3c == null) return false;
        try {
            Element e = JDOMUtil.toJDOMElement(w3c);
            cal.semiRealistic().loadFrom(e);
            log.debug("Loaded semi-realistic settings from AuxiliaryConfiguration (shared)");
            return true;
        } catch (Exception ex) {
            log.warn("Failed to parse semi-realistic settings fragment; using defaults", ex);
            return false;
        }
    }

    private void saveHardwareCalibration(
            @Nonnull AuxiliaryConfiguration auxConfig,
            @Nonnull RailDriverCalibration cal) {
        Element e = buildCalibrationElement(cal);
        try {
            auxConfig.putConfigurationFragment(
                    JDOMUtil.toW3CElement(e), false);
        } catch (JDOMException ex) {
            log.error("Failed to write hardware calibration fragment", ex);
        }
    }

    private void saveSettings(
            @Nonnull AuxiliaryConfiguration auxConfig,
            @Nonnull RailDriverCalibration cal) {
        Element e = cal.semiRealistic().writeTo();
        e.setNamespace(NS);
        try {
            auxConfig.putConfigurationFragment(
                    JDOMUtil.toW3CElement(e), true);
        } catch (JDOMException ex) {
            log.error("Failed to write semi-realistic settings fragment", ex);
        }
    }

    // ======================== Fragment ↔ POJO ========================

    private Element buildCalibrationElement(@Nonnull RailDriverCalibration cal) {
        Element e = new Element(HW_CAL_ELEMENT, NS);
        e.addContent(buildSubElement("reverser",
                "forward", cal.reverser().forward,
                "neutral", cal.reverser().neutral,
                "reverse", cal.reverser().reverse));
        e.addContent(buildSubElement("throttle",
                "fullThrottle", cal.throttle().fullThrottle,
                "idleLow", cal.throttle().idleLow,
                "idleHigh", cal.throttle().idleHigh,
                "fullDynBrake", cal.throttle().fullDynBrake));
        e.addContent(buildSubElement("autoBrake",
                "released", cal.autoBrake().released,
                "sup", cal.autoBrake().sup,
                "cs", cal.autoBrake().cs,
                "emg", cal.autoBrake().emg));
        e.addContent(buildSubElement("indepBrake",
                "fullRelease", cal.indepBrake().fullRelease,
                "fullApplication", cal.indepBrake().fullApplication,
                "bailoffRest", cal.indepBrake().bailoffRest,
                "bailoffPressed", cal.indepBrake().bailoffPressed));
        e.addContent(buildSubElement("wiper",
                "off", cal.wiper().off,
                "slow", cal.wiper().slow,
                "full", cal.wiper().full));
        e.addContent(buildSubElement("lights",
                "off", cal.lights().off,
                "dim", cal.lights().dim,
                "full", cal.lights().full));
        return e;
    }

    private void populateCalibrationFromElement(
            @Nonnull RailDriverCalibration cal, @Nonnull Element e) {
        readInts(e.getChild("reverser"), cal.reverser(), "forward", "neutral", "reverse");
        readInts(e.getChild("throttle"), cal.throttle(), "fullThrottle", "idleLow", "idleHigh", "fullDynBrake");
        readInts(e.getChild("autoBrake"), cal.autoBrake(), "released", "sup", "cs", "emg");
        readInts(e.getChild("indepBrake"), cal.indepBrake(), "fullRelease", "fullApplication", "bailoffRest", "bailoffPressed");
        readInts(e.getChild("wiper"), cal.wiper(), "off", "slow", "full");
        readInts(e.getChild("lights"), cal.lights(), "off", "dim", "full");
    }

    /** Generic helper: reads named int children into the matching fields
     *  of a calibration sub-POJO via reflection-free positional matching
     *  with the existing populate* methods in RailDriverCalibration. */
    private void readInts(@CheckForNull Element parent, Object pojo, String... fields) {
        if (parent == null) return;
        // Delegate to the existing static populate methods by re-using
        // the POJO type dispatch already in RailDriverCalibration.
        // This avoids duplicating field-level assignment code.
        if (pojo instanceof RailDriverCalibration.ReverserCal) {
            RailDriverCalibration.ReverserCal r = (RailDriverCalibration.ReverserCal) pojo;
            r.forward = readIntChild(parent, "forward");
            r.neutral = readIntChild(parent, "neutral");
            r.reverse = readIntChild(parent, "reverse");
        } else if (pojo instanceof RailDriverCalibration.ThrottleCal) {
            RailDriverCalibration.ThrottleCal t = (RailDriverCalibration.ThrottleCal) pojo;
            t.fullThrottle = readIntChild(parent, "fullThrottle");
            t.idleLow = readIntChild(parent, "idleLow");
            t.idleHigh = readIntChild(parent, "idleHigh");
            t.fullDynBrake = readIntChild(parent, "fullDynBrake");
        } else if (pojo instanceof RailDriverCalibration.AutoBrakeCal) {
            RailDriverCalibration.AutoBrakeCal a = (RailDriverCalibration.AutoBrakeCal) pojo;
            a.released = readIntChild(parent, "released");
            a.sup = readIntChild(parent, "sup");
            a.cs = readIntChild(parent, "cs");
            a.emg = readIntChild(parent, "emg");
        } else if (pojo instanceof RailDriverCalibration.IndepBrakeCal) {
            RailDriverCalibration.IndepBrakeCal i = (RailDriverCalibration.IndepBrakeCal) pojo;
            i.fullRelease = readIntChild(parent, "fullRelease");
            i.fullApplication = readIntChild(parent, "fullApplication");
            i.bailoffRest = readIntChild(parent, "bailoffRest");
            i.bailoffPressed = readIntChild(parent, "bailoffPressed");
        } else if (pojo instanceof RailDriverCalibration.WiperCal) {
            RailDriverCalibration.WiperCal w = (RailDriverCalibration.WiperCal) pojo;
            w.off = readIntChild(parent, "off");
            w.slow = readIntChild(parent, "slow");
            w.full = readIntChild(parent, "full");
        } else if (pojo instanceof RailDriverCalibration.LightsCal) {
            RailDriverCalibration.LightsCal l = (RailDriverCalibration.LightsCal) pojo;
            l.off = readIntChild(parent, "off");
            l.dim = readIntChild(parent, "dim");
            l.full = readIntChild(parent, "full");
        }
    }

    @CheckForNull
    private static Integer readIntChild(@Nonnull Element parent, String name) {
        Element child = parent.getChild(name);
        if (child == null) return null;
        String text = child.getTextTrim();
        if (text == null || text.isEmpty()) return null;
        try {
            int v = Integer.parseInt(text);
            return (v >= 0 && v <= 255) ? v : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Builds a sub-element with name/value pairs as children. */
    private static Element buildSubElement(String name, Object... nameValuePairs) {
        Element e = new Element(name);
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            String childName = (String) nameValuePairs[i];
            Object value = nameValuePairs[i + 1];
            Element c = new Element(childName);
            c.setText(value != null ? value.toString() : "");
            e.addContent(c);
        }
        return e;
    }

    // ======================== Legacy migration ========================

    /**
     * Migrates from the freestanding {@code raildriver-calibration.xml}
     * file to AuxiliaryConfiguration fragments. Per-fragment: only
     * migrates what's missing.
     */
    private void migrateLegacy(
            @Nonnull Profile profile,
            @Nonnull AuxiliaryConfiguration auxConfig,
            @Nonnull RailDriverCalibration cal,
            boolean hwCalAlreadyLoaded,
            boolean settingsAlreadyLoaded) {

        File legacyFile = getLegacyFile(profile);
        if (legacyFile == null || !legacyFile.exists()) {
            log.debug("No legacy raildriver-calibration.xml found; using defaults");
            return;
        }

        // Check for .bak — migration already ran.
        File bakFile = new File(legacyFile.getPath() + ".bak");
        if (bakFile.exists()) {
            log.debug("Legacy .bak exists — migration already completed");
            return;
        }

        log.info("Migrating RailDriver settings from legacy file: {}", legacyFile);

        // Use existing loader to parse the legacy file.
        RailDriverCalibration legacy =
                RailDriverCalibration.loadOrDefault(legacyFile);

        // Migrate calibration detents (private) if not already loaded.
        if (!hwCalAlreadyLoaded) {
            cal.copyCalibrationFieldsFrom(legacy);
            saveHardwareCalibration(auxConfig, cal);
            log.info("Migrated hardware calibration detents to private fragment");
        }

        // Migrate settings (shared) if not already loaded.
        if (!settingsAlreadyLoaded) {
            // v2 semiRealistic fields are discarded — field meanings
            // changed incompatibly from physics-based to step-rate.
            // Write fresh defaults instead.
            boolean hadV2Settings = legacy.semiRealistic().persistedEnabled
                    || legacy.semiRealistic().baseAccelDelayMs
                            != SemiRealisticSettings.DEFAULT_BASE_ACCEL_DELAY_MS;
            if (hadV2Settings) {
                log.warn("Legacy v2 <semiRealistic> fields discarded — "
                        + "field meanings changed from physics-based to "
                        + "step-rate. Fresh defaults applied.");
            }
            cal.semiRealistic().resetToDefaults();
            saveSettings(auxConfig, cal);
            log.info("Wrote default semi-realistic settings to shared fragment");
        }

        // Rename legacy file to .bak.
        if (!legacyFile.renameTo(bakFile)) {
            log.warn("Could not rename legacy file to .bak: {}", legacyFile);
        } else {
            log.info("Renamed legacy file to: {}", bakFile);
        }
    }

    @CheckForNull
    private static File getLegacyFile(@Nonnull Profile profile) {
        return new File(new File(profile.getPath(), Profile.PROFILE),
                LEGACY_FILE_NAME);
    }

    // ======================== Logging ========================

    private static final Logger log =
            LoggerFactory.getLogger(RailDriverPreferencesManager.class);
}
