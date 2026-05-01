# RailDriver support on Raspberry Pi (aarch64 Linux)

> **Branch:** `rpi-raildriver`
> **Target platform:** Raspberry Pi 4, 64‑bit Raspberry Pi OS / Debian 12+, JMRI 5.15.x. Pi 5 is expected to work but is untested.
> **Author:** Investigation & design distilled from empirical testing on a Raspberry Pi 4 / Debian 13 / aarch64 host
> **Terminology:** Throughout this document, *the script* refers to `jython/RailDriver.py` (the Debug menu's existing entry). *The Jynstrument scripts* refers to the files under `jython/Jynstruments/ThrottleWindowToolBar/USBThrottle.jyn/`.

## 1. Problem statement

The JMRI source tree contains two RailDriver code paths:

| Path | Library | Wired into UI? | Works on Linux? |
|---|---|---|---|
| `jython/RailDriver.py` (Debug → RailDriver Throttle) | JInput → evdev | ✅ Debug menu | ❌ |
| `jython/Jynstruments/.../USBThrottle.jyn/` (Throttle Jynstrument) | JInput → evdev | ✅ Throttle toolbar | ❌ |
| `java/src/jmri/util/usb/RailDriverMenuItem.java` | hid4java → libhidapi (`/dev/hidraw*`) | ❌ no menu wiring | ✅ would work, blocked by native lib gap |

On a Pi 4 (aarch64 Debian 13) we empirically verified:

1. **JInput's Linux native is missing for aarch64.** `lib/jinput-2.0.9-natives-all.jar` ships only `libjinput-linux64.so` (x86_64). We unblocked this locally by building JInput's Linux plugin from source for aarch64 and dropping `libjinput-linux64.so` into `lib/linux/aarch64/`.
2. **Even with the JInput native present, the RailDriver's analog levers are invisible to the kernel input subsystem on Linux.** `evtest /dev/input/event4` shows only `EV_SYN`, `EV_KEY`, `EV_MSC` — there is **no `EV_ABS` section**. The throttle, brakes, reverser, bail-off, wiper rotary, and lights rotary never produce evdev events. Cause: the RailDriver's HID descriptor uses Vendor-Defined usage pages for its levers, which Linux's `hid-generic` driver does not map to `ABS_*` codes. The buttons use standard HID button usages and *do* come through evdev.
3. **All seven analog axes plus all buttons are available via raw HID** (`/dev/hidraw*`). We confirmed this with `xxd -c 14 /dev/hidraw0` across five separate captures totalling 2,046 reports / 680 unique payloads (committed under `docs/rpi-raildriver/test-data/`).
4. **The shipped `RailDriverModernDesktop.py` driver inside the USBThrottle Jynstrument is explicitly Windows-only** (lines 3–4 of the file say so). Its component identifiers (`"Axis 0"`, `"13"`, `"28"`, …) are HID report indices produced by JInput's Windows `HidRawEnvironmentPlugin`. JInput's Linux plugin produces `Component.Identifier.*` constants (`"Trigger"`, `"Base"`, `"x"`, `"Throttle"`, …) — completely different namespaces. Even if the file were renamed to match the Linux name lookup, every component-name comparison would still miss.
5. **The empirically validated 14-byte HID input report layout on a real RailDriver Modern Desktop is:**

   ```
   bytes 0..6   = 7 analog axes (8-bit unsigned)
                  0: reverser
                  1: throttle / dynamic brake combined lever (centre = 0)
                  2: auto (train) brake
                  3: independent (loco) brake
                  4: bail-off lever
                  5: wiper rotary
                  6: lights rotary
   bytes 7..13  = 56 buttons packed as 7 bytes × 8 bits
                  bytes 7..12 = buttons 0..47 (live; every bit observed firing)
                  byte 13     = buttons 48..55 (sentinel: firmware always emits 0x35;
                                see byte13-capture-test.md result block)
   ```

   This matches the parser in `RailDriverMenuItem.java` (lines 191–232) byte-for-byte. The kernel HID descriptor declares 56 buttons (`Usage Min = Button 1`, `Usage Max = Button 56`, packed as 56 × 1 bit; verified by `xxd /sys/class/hidraw/hidraw0/device/report_descriptor`) and `KEY=ffffffffffffff` in `/proc/bus/input/devices` — exactly matching the parser's 56-position decoding. There is no separate sentinel field declared in the descriptor; byte 13 is buttons 48..55 by HID numbering, but the firmware on this controller never sets them regardless of physical state (likely because the Modern Desktop has fewer than 56 distinct buttons in hardware, and PI Engineering uses a common report layout across the RailDriver product family).

   **Byte 13 confirmed-sentinel.** All 5 earlier captured logs (2,046 reports) plus the focused capture procedure in `docs/rpi-raildriver/byte13-capture-test.md` (2,323 reports under exhaustive sweep of every analog lever and every physical control on the unit) show byte 13 invariant at `0x35`. The parser's mapping of bits 48..55 is dead code in practice — the diff loop produces no events when the byte never changes — but it remains in place so that any future firmware revision that starts populating buttons 48..55 will surface immediately. See the §4.5 #1 byte-13 pin-down test for the regression guard.

### Conclusion drawn from the investigation

There is no realistic JInput-on-Linux path to a working RailDriver, because the analog inputs are not exposed via evdev at all. The hidraw path (already implemented in `RailDriverMenuItem.java`) is the only viable route and is empirically known to deliver every control we need.

## 2. Goals

1. End user can plug a RailDriver Modern Desktop into a Pi 4 running 64‑bit Raspberry Pi OS / Debian, launch JMRI, choose a menu item, and drive a locomotive.
2. The hid4java upgrade is *intended* not to regress the existing test suite on Windows, macOS, and Linux x86_64. Pre-merge verification on those platforms is **out of this branch's author's reach** and is therefore deferred to JMRI CI and to community testers (see §5 #2 and §5 #3 for the explicit acceptance criteria split). This branch's author tests only on Pi 4 (aarch64).
3. Required system steps (the udev rule and `plugdev` group membership) are documented in `help/en/html/hardware/raildriver/index.shtml`.
4. The JInput-based path is left intact and unmodified for non-Linux users (and for Linux users with controllers other than the RailDriver). RailDriver users on Linux are simply guided to the hidraw path.
5. A regression test exists that exercises the byte parser with a recorded report stream, so future refactors can't silently break it.

## 3. Non-goals (out of scope for this branch)

- Replacing the Jython `RailDriver.py` script (the existing Debug menu entry).
- Modifying the USBThrottle Jynstrument or its drivers.
- Adding RailDriver-specific functionality not present in `RailDriverMenuItem.java` today (advanced braking simulation, configurable button-to-function maps, etc.). Those can be follow-ups.
- Patching the Linux kernel or shipping a custom HID quirks driver.
- Supporting 32‑bit ARM (`armhf`). hid4java 0.5.0 already bundles a `linux-arm` native, and 0.8.0 ships both `linux-arm` and `linux-armel`. **However, we have not verified the 0.8.0 32-bit ARM natives load on Pi OS 32-bit.** Users on that platform should test before relying on the upgrade. Tracked as a risk in §6.
- Supporting RailDriver hardware revisions other than the Modern Desktop (VID `05F3` / PID `00D2`).
- **Fixing the device-detach NPE in `RailDriverMenuItem.hidDeviceDetached`.** The handler nulls `hidDevice` (line 466) but does not interrupt the polling thread. *In the current code path the listener is never wired up* — `hidServices.start()` is commented out at `RailDriverMenuItem.java:114` (inside the `if (!invokeOnMenuOnly)` block which is itself fully commented out, lines 110–125), so `hidDeviceDetached` never actually fires and the NPE doesn't manifest today. The bug becomes live the moment that block is re-enabled. Pre-existing latent issue; fixing it is a follow-up so this branch's diff stays focused on the Pi/aarch64 enablement.
- **Fixing the polling-loop busy-spin on read failure.** `RailDriverMenuItem.java:198–238` runs `hidDevice.read(buff_new)` in a tight `while (!thread.isInterrupted())` loop with **no sleep** and no `break`/`Thread.sleep` on `ret < 0`; on an error or closed device the loop pegs a CPU core. On a Pi 4 that's a noticeable thermal/battery cost. Documented as a known issue; not fixed in this branch (also tracked in §6).
- **Fixing the off-EDT Swing mutation.** `firePropertyChange` is invoked from the polling thread (line 215, 226), which dispatches into `propertyChange` and ultimately mutates Swing components (`throttleWindow.addPropertyChangeListener` at line 173, `throttle.setSpeedSetting`, `throttle.setFunction`, `setLEDs`, etc.). This is a Swing threading violation that pre-dates this branch. A proper fix would route dispatch through `SwingUtilities.invokeLater`. Out of scope; tracked in §6.
- **Multiple RailDrivers attached at once.** `hidServices.getHidDevice(VENDOR_ID, PRODUCT_ID, null)` returns the first device matching that VID/PID pair (`SERIAL_NUMBER` is `null` at `RailDriverMenuItem.java:40`, which makes hid4java skip the serial-number filter); whichever match the underlying enumeration returns first wins. Two-controller setups are unsupported in this branch.
- **Concurrent use of the script and built-in menu entries.** A user clicking both `RailDriver Throttle` and `RailDriver Throttle (built in)` in one session will get undefined behavior (on Linux: harmless because the script's JInput path can't see the levers anyway; on Windows/macOS: potential device-open contention). Documented but not guarded against in this branch.

## 4. Architecture

### 4.1 Single-component summary

Use the existing `jmri.util.usb.RailDriverMenuItem` class as the production code path. It already:

- Opens the device by VID `0x05F3` / PID `0x00D2` via hid4java (`HidServices.getHidDevice`).
- Spawns a polling thread that reads 14-byte input reports and fires `firePropertyChange("Value", name, value)` events, separating analog axes (`"Axis 0".."Axis 6"`) from button bits (`"0".."55"`, of which `"0".."47"` are real).
- Owns LED display (`setLEDs`) and speaker (`speakerOn`/`speakerOff`) helpers, with full seven-segment fonts for digits and A–Z.
- In its `propertyChange` handler (lines 482–765) translates each axis/button event into calls on `DccThrottle.setSpeedSetting`, `DccThrottle.setIsForward`, `DccThrottle.setFunction`, plus `AddressPanel.selectRosterEntry` / `dispatchAddress` / `setRosterSelectedIndex` (for the rocker and POV buttons) and `ThrottleWindow.nextThrottleFrame` / `previousThrottleFrame` (for frame navigation).
- Wires `LoadXmlThrottlesLayoutAction` so the RailDriver picks up the user's saved throttle layout if one exists.
- The button-to-function mapping baked into the Java code differs from the Jython script's:
   - Button 41 (Bell) → **F1** (not F3 as the script-based docs say).
   - Buttons 42/43 (Horn) → F2.
   - Buttons 34/35 (Gear up/down) → F3 (`shuntFn`).
   - Button 38 (Alerter) → F6.
   - Button 39 (Sander) → F7.
   - Button 40 (Pantograph) → F8.
   - Buttons 1..27 (most blue function buttons) → F1..F27 by HID bit position.
   - **Button 0 is silently dropped.** `RailDriverMenuItem.java:744` filters with `fNum > 0`, so the bit corresponding to button 0 never toggles F0. This appears to be a latent bug (or a deliberate "F0 reserved" choice that nobody documented). It is **not fixed in this branch** — flagged as a follow-up issue to investigate separately.
   - Any user docs added by this branch must reflect the Java code's mapping, not the script's.

What it lacks today, and which this branch will provide:

1. A menu entry that instantiates it.
2. A working `libhidapi` native on aarch64.
3. A udev rule (and JMRI documentation telling the user to install it).
4. A unit test of the byte-parsing loop.

### 4.2 Native-library strategy

`lib/hid4java-0.5.0.jar` contains only:
```
darwin/libhidapi.dylib
linux-amd64/libhidapi.so
linux-arm/libhidapi.so          # 32-bit armhf only
linux-x86/libhidapi.so
win32-amd64/hidapi.dll
win32-x86/hidapi.dll
win32-x86-64/hidapi.dll
libhidapipi.so                  # legacy duplicate of linux-arm
```
There is no `linux-aarch64/libhidapi.so`. JNA on aarch64 will fail to load the bundled native and `HidManager.getHidServices()` will throw `HidException`.

We will resolve this with a layered approach:

**Primary: upgrade `hid4java` to `0.8.0`, which bundles a `linux-aarch64` native.**

Verified contents of `org.hid4java:hid4java:0.8.0` (Maven Central, released 2024-04-14):

| Platform | Bundled native(s) |
|---|---|
| Linux aarch64 | `linux-aarch64/libhidapi.so` (115,520 B), `linux-aarch64/libhidapi-libusb.so` (131,144 B) |
| Linux x86_64 | `linux-amd64/libhidapi.so`, `linux-x86-64/libhidapi.so` (+ libusb variants) |
| Linux x86 | `linux-x86/libhidapi.so` (+ libusb) |
| Linux 32-bit ARM | `linux-arm/libhidapi.so`, `linux-armel/libhidapi.so` (+ libusb) |
| Linux RISC-V 64 | `linux-riscv64/` |
| macOS Intel | `darwin-x86-64/libhidapi.dylib` (61,776 B) |
| macOS Apple Silicon | `darwin-aarch64/libhidapi.dylib` (61,584 B) |
| Windows x86 / x86-64 / aarch64 | `win32-x86/`, `win32-x86-64/`, `win32-aarch64/hidapi.dll` |

Available versions on Maven Central with aarch64 coverage:

| Version | Released | aarch64 native? |
|---|---|---|
| 0.8.0 | 2024-04-14 | ✅ |
| 0.7.0 | 2020-09-10 | ✅ |
| 0.6.0 | 2020-07-18 | ❌ |
| 0.5.0 (current) | 2017-06-24 | ❌ |

Steps:
- Confirm group/artifact ID (`org.hid4java:hid4java`) is still the canonical home for releases ≥ 0.5.0 — yes, all four releases above were published under that GA.
- Verify API compatibility against 0.8.0's javadoc **before** swapping the jar. The exact call surface used by `RailDriverMenuItem.java` is small enough to enumerate up front:
   - `HidManager.getHidServices(HidServicesSpecification)`
   - `HidServicesSpecification.{setAutoShutdown, setScanInterval, setPauseInterval, setScanMode}`
   - `HidServices.{addHidServicesListener, getHidDevice(short, short, String), start, stop}`
   - `HidServicesListener.{hidDeviceAttached, hidDeviceDetached, hidFailure}`
   - `HidServicesEvent.getHidDevice`
   - `HidDevice.{open, isOpen, read(byte[]), getLastErrorMessage}` — `read(byte[])` is the historically-most-broken entry point across hidapi-binding versions and must be re-checked first
   - `HidException`
   - `ScanMode.SCAN_AT_FIXED_INTERVAL_WITH_PAUSE_AFTER_WRITE` (referenced at `RailDriverMenuItem.java:158`)
   These names are stable across 0.5 → 0.8 to the best of public knowledge. Any signature changes that appear in 0.8.0's javadoc will be addressed in the upgrade commit. **Document the result of this audit in the upgrade commit message** so that a future bisect can identify the API surface that was vetted.
- Replace `lib/hid4java-0.5.0.jar` with `lib/hid4java-0.8.0.jar`, update the `pom.xml` dependency version, and update direct references in the build (`build.xml`'s `hid4java-0.5.0.jar` `pathelement`), the macOS dist signing script (`scripts/diskimage-sign.sh` — see the macOS-native note below), and the regenerated SBOM (`lib/bom-Java-Maven.spdx`, produced by an external tool whose three `hid4java-0.5.0` references can also be hand-edited if the tool isn't on hand).
- Re-run JMRI's CI build matrix (Windows, macOS, Linux x86_64) to confirm no regressions.

**macOS native moved to two arch-specific paths.** In 0.5.0 the bundled macOS native was `darwin/libhidapi.dylib`. In 0.8.0 it splits into `darwin-aarch64/libhidapi.dylib` and `darwin-x86-64/libhidapi.dylib`. `scripts/diskimage-sign.sh:251` currently signs the single 0.5.0 path; under 0.8.0 it must sign both new paths. The exact path strings should be obtained from `unzip -l lib/hid4java-0.8.0.jar` rather than copied from the prose above, because the dist-signing path runs only on a release-engineering host with Apple credentials and a typo would not surface until release time (tracked in §6).

**linux-aarch64 native must be glibc-compatible with the oldest supported Pi OS / Debian.** Concrete bound: the highest required `GLIBC_*` symbol in `linux-aarch64/libhidapi.so` must be ≤ `GLIBC_2.31` (Debian 11 bullseye, the oldest 64-bit Pi OS realistically still in field use; Debian 12 bookworm ships `GLIBC_2.36` and is comfortably newer). Verify with `unzip -p lib/hid4java-0.8.0.jar linux-aarch64/libhidapi.so | objdump -T - | grep -oE 'GLIBC_[0-9.]+' | sort -uV | tail -1` before relying on the bundled native; if the requirement exceeds 2.31, fall back to the §6 mitigation (vendor a locally rebuilt `.so` against bullseye's glibc).

**Considered alternative (rejected): use system `libhidapi` via JNA library path override.**
- Users would `sudo apt install libhidapi-hidraw0` and JMRI's launcher would pass `-Djna.library.path=/usr/lib/aarch64-linux-gnu:/usr/lib/arm-linux-gnueabihf:/usr/lib`. JNA would then resolve the system `.so` instead of the (missing) bundled one.
- **Why rejected:** introduces an extra system package as an end-user prerequisite, ties JMRI to a distro-managed library version, and creates an asymmetric experience versus Windows/macOS where the bundled native is loaded transparently. Not surfaced in user-facing docs.
- We retain awareness of this approach as a contingency in the risk register (§6), not as a supported public path.

**Decision: dependency upgrade is the only supported approach** because it is identical to how Windows and macOS already work today (bundled native loaded transparently from the jar), and it gives one homogeneous user experience regardless of architecture.

### 4.3 Menu wiring

Add a new entry `RdBuiltIn` ("RailDriver Throttle (built in)") to the **Debug menu**, immediately following the existing `MenuRailDriverThrottle` script entry. Note that the new menu entry's label is sourced from `jmri.util.usb.Bundle.RdBuiltIn` (resolved by `RailDriverMenuItem`'s no-arg constructor at `RailDriverMenuItem.java:61`), **not** from `apps.AppsBundle` where the existing script's `MenuRailDriverThrottle` key lives. The `RdBuiltIn` key is already present in the base `Bundle.properties` and 4 of the 5 localized files (`Bundle_cs.properties`, `Bundle_de.properties`, `Bundle_fr.properties`, `Bundle_nl.properties`) — it was reserved for exactly this purpose and is currently dead code. **`Bundle_ca.properties` is missing the key**; the menu-wiring work in this branch will either add it or accept that Catalan falls back to the English string from the base bundle.

Concretely, in `java/src/apps/jmrit/DebugMenu.java`. The existing script entry is wrapped in a `try`/`catch` (lines 68–75 of the file) that disables the menu item if the script can't be located. The new entry must go **after** the catch block so that a script-load failure does not also prevent the Java menu item from appearing:

```java
add(new JSeparator());
try {
    add(new RunJythonScript(rb.getString("MenuRailDriverThrottle"),
            new File(FileUtil.findURL("jython/RailDriver.py").toURI())));
} catch (URISyntaxException | NullPointerException ex) {
    log.error("Unable to load RailDriver Throttle", ex);
    JMenuItem i = new JMenuItem(rb.getString("MenuRailDriverThrottle"));
    i.setEnabled(false);
    add(i);
}
add(new jmri.util.usb.RailDriverMenuItem());     // <-- new, outside the try/catch
```

The `RailDriverMenuItem()` no-arg constructor already pulls its label from `Bundle.getMessage("RdBuiltIn")`. The two entries coexist; users on Windows/macOS can still use the script if they prefer, and Linux users who hit the JInput-axis-invisibility wall can switch to the built-in Java path with a single menu click.

We deliberately do **not** delete the script entry in this branch — that migration is deferred until a future release once the Java path has been in users' hands and any rough edges have been reported.

### 4.4 Permissions / udev

The existing help page (`help/en/html/hardware/raildriver/index.shtml`) describes a generic udev rule. We will:

1. Ship a ready-to-install rules file at `lib/linux/udev/99-jmri-raildriver.rules`. This file is documentation/sample only — not loaded by the JVM. The location is chosen because the existing `package-linux` ant target (`build.xml:2787–2790`) copies `${libdir}/linux/` recursively into the Linux distribution tarball, so a new `udev/` subdirectory ships automatically with no build-system change. The macOS and Windows `package-*` targets use separate copy filesets and do not pull from `${libdir}/linux/`, so the rule does not leak into non-Linux distributions. The launcher template (`scripts/AppScriptTemplate:436–491`, used to generate the dist `PanelPro` / `DecoderPro` launchers) discovers native libraries by computing `${LIBDIR}/$OS/$ARCH` from `arch`/`uname` and only prepending it to `java.library.path` if that specific directory exists; `$ARCH` is never inferred from a directory listing, so the new `udev/` sibling under `lib/linux/` cannot be selected as a native-library directory.
   ```
   # JMRI RailDriver Modern Desktop (P.I. Engineering)
   #
   # The hidraw rule is what this branch's new built-in menu entry (hid4java path)
   # actually requires. The usb rule covers /dev/bus/usb/* for the unrelated
   # javax.usb path used by JMRI's Debug -> USB Browser tool (UsbUtil/UsbBrowserPanel),
   # which is what produced the "Access denied" errors in messages.log before this
   # rule was installed. Both rules together silence the existing log noise and
   # enable the new code path; users who only want the new menu entry can keep
   # only the hidraw rule.
   SUBSYSTEM=="usb",    ATTRS{idVendor}=="05f3", ATTRS{idProduct}=="00d2", \
       MODE="0660", GROUP="plugdev"
   SUBSYSTEM=="hidraw", ATTRS{idVendor}=="05f3", ATTRS{idProduct}=="00d2", \
       MODE="0660", GROUP="plugdev"
   ```
2. Update the help page with a Pi-OS / Debian section that:
   - tells the user to install the rule into `/etc/udev/rules.d/`,
   - reload (`sudo udevadm control --reload && sudo udevadm trigger`),
   - confirms `/dev/hidraw*` group becomes `plugdev`,
   - confirms membership: `sudo usermod -aG plugdev "$USER"` (and log out/in).
3. The help page user-facing copy describes one path only: install the udev rule, set group membership, restart. The bundled `libhidapi` native is JMRI's responsibility, not the user's. (The system `libhidapi-hidraw0` package is intentionally **not** mentioned to keep a single supported install procedure on Linux — see the rejected alternative in §4.2.)

### 4.5 Testing

`java/test/jmri/util/usb/RailDriverMenuItemTest.java` currently contains a single trivial constructor test. The branch will extend it with the cases below. Each case names the architectural seam it depends on; how those seams are introduced is an implementation concern.

0. **Byte-13 capture test (hardware required, run once before any code change).** *Resolved 2026-05-01 — see the result block at the bottom of `docs/rpi-raildriver/byte13-capture-test.md`.* Byte 13 is empirically a constant sentinel (`0x35`) across 2,323 reports under exhaustive sweep of every physical control on the unit; the device's HID descriptor declares it as buttons 48..55 but the firmware never sets them. The parser is correct as-is; the §4.5 #1 byte-13 sub-bullets stay in place. The §6 risk row has been updated to record the resolved interpretation. The capture procedure itself is left in `byte13-capture-test.md` for use against any future firmware revision or different RailDriver SKU.

1. **Byte-parser test (no hardware required).** The per-report decode loop currently lives inline inside the polling thread (`RailDriverMenuItem.java:201–232`); to be testable it needs to be reachable from a test as a pure function of two byte buffers. Inject a sequence of recorded 14‑byte reports (5 captures totalling 2,046 reports / 680 unique payloads, checked in under `docs/rpi-raildriver/test-data/hidraw-raildriver*.log` as `xxd -c 14` formatted text) into that decode entry point, and assert that:
   - changes in bytes 0–6 emit `firePropertyChange("Value", "Axis N", String value)`. The numerical value is computed by `(256 − vInt) / 256.0` per `RailDriverMenuItem.java:206–208`, where `vInt ∈ [0, 255]`. So axes 0/2/3/4/5/6 lie in the closed interval `[1/256, 1.0]` (≈ `[0.00390, 1.0]`); axis 1 has the additional `(2v − 1)` transform on line 211, producing values in the closed interval `[2/256 − 1, 1.0]` ≈ `[−0.992, 1.0]`. The test asserts these exact achievable ranges (closed lower bound, closed upper bound) — it must **not** assert `[−1.0, 1.0]` because `−1.0` is unreachable. The test asserts the numerical range only, **not** the physical-direction-to-value mapping (we have no synchronized photographic record of lever positions during capture).
   - changes in bytes 7–13 emit per-bit `firePropertyChange("Value", "<n>", "1"/"0")` events for `n ∈ [0, 55]` (via the parser's `i >= 7` else-branch with `n = 8*(i-7) + bit`).
   - **For the committed fixtures and for the device's current firmware**, byte 13 produces no events — every report has `byte13 == 0x35`. This is the production contract on the parser today (see §4.5 #0 / `byte13-capture-test.md` for the empirical confirmation). The pin-down test below complements this assertion as a regression detector.
   - **Synthetic-input parser-behaviour pin-down test.** Hand-construct two reports where only byte 13 differs (e.g. `0x35` → `0x36`, XOR `0x03`, two bits flipped) and assert that the parser emits exactly two events with names `"48"` and `"49"`. This locks in the parser's behaviour: byte 13 is treated as button bits 48..55 via the `i >= 7` else-branch at `RailDriverMenuItem.java:216`. The assertion is a **regression detector**: if a future firmware revision (or a different RailDriver SKU) starts populating buttons 48..55, the parser will emit the events and downstream code will see them; this test ensures that pathway is exercised in CI even though no committed fixture exercises it. **Do not invert this assertion.**
   - identical old/new buffers produce zero events.
   The test will parse `xxd -c 14` text into raw 14-byte reports rather than introducing a separate binary fixture format.
2. **No-device behaviour.** Verify that selecting the menu item with no RailDriver attached logs an info message and does not throw. With `setupHidServices` returning a non-null `hidServices` but `hidServices.getHidDevice(...)` returning `null` (no device matches VID/PID), the action listener at `RailDriverMenuItem.java:78–85` skips the `setupRailDriver()` block entirely and no further code runs, so this case exercises only the existing `protected void setupHidServices()` seam — no additional refactor needed.
3. **LED-encoding test (no hardware required).** Assert that `setLEDs(String)` produces the expected 7-byte report buffer for representative inputs: digits ("123"), letters ("Pro"), decimal-point handling ("8.8.8."), shorter strings, and unsupported characters. The seven-segment encoding is non-trivial (see `RailDriverMenuItem.java` lines 357–401 plus the `SevenSegment[]` and `SevenSegmentAlpha[]` tables), called every time `setupRailDriver` succeeds (line 133), and currently has zero coverage. The encoded output of `setLEDs` is currently sealed inside `private final void sendMessage(byte[], byte)` (line ~444) which writes directly to the `HidDevice`; to make this assertable without a device, the encoded buffer needs to be observable from a test (e.g. by widening `sendMessage` so a test subclass can capture it, or by extracting the encoding into a pure function). The architectural requirement is "the encoding is testable without an open `HidDevice`"; the precise mechanism is an implementation detail.
4. **Existing constructor test stays.**

We will not add an integration test that opens a real device; CI cannot guarantee one is plugged in. A `HidDevice` factory seam (which would let us drive `setupRailDriver` end-to-end without hardware) is intentionally **not** introduced here; it is tracked in a follow-up issue alongside the broader testability work (HidDevice abstraction, `setupRailDriver` extraction from the action listener, EDT-aware dispatch).

### 4.6 Help / documentation

- `help/en/html/hardware/raildriver/index.shtml`: add a "Raspberry Pi / Linux 64‑bit" section covering the udev rule, group membership, and mention that the built-in menu entry is recommended over the script on Linux.
- `help/en/html/hardware/raildriver/details.shtml`: add a paragraph noting the platform-specific lookup limitations of the JInput path on Linux and pointing to the Java built-in path.
- French (`help/fr/html/hardware/raildriver/index.shtml`): mirror the same change so the doc isn't out of sync. (Translation can be a stub with the English text and a `TODO translate` comment.)

## 5. Verification / acceptance criteria

1. On a clean Pi 4 (64‑bit Pi OS) with this branch built and installed via `build-and-install-to-jmri.sh`, after running the udev steps:
   - `Debug → RailDriver Throttle (built in)` opens a throttle window.
   - `~/.jmri/log/messages.log` shows `Got RailDriver hidDevice: ...`.
   - **Per-axis physical-control verification.** Sweep each lever in isolation and confirm the corresponding `firePropertyChange Axis N` line in the log matches the §1 #5 mapping (reverser → Axis 0, throttle/dyn-brake → Axis 1, auto-brake → Axis 2, indep brake → Axis 3, bail-off → Axis 4, wiper rotary → Axis 5, lights rotary → Axis 6). This is the one-time validation that the captured Windows-derived layout matches what `RailDriverMenuItem.propertyChange` (lines 482–765) was wired against. If any axis is misnumbered, downstream throttle behaviour will be wrong; this branch's response is to document the mismatch in §1 #5 and update `RailDriverMenuItem.propertyChange` (or, if the divergence is too deep, to defer the fix to a follow-up issue and ship the branch with the documentation correction only).
   - Moving the throttle/dyn-brake lever changes loco speed in some direction (sign of motion is fine; we do not assert which direction maps to acceleration in this branch — calibration / direction-correctness is explicitly deferred).
   - Moving the reverser switches direction.
   - Pressing the bell button toggles **F1** (per `RailDriverMenuItem.java:729–733` — note this is F1, not F3 as the script-based docs say).
   - Pressing the horn lever toggles F2.
   - Pressing one of the 28 blue function buttons toggles a function `Fn` whose number is determined by HID bit position (look for `FUNCTION N value: ...` lines in the log to read the live mapping).
   - The 7-segment display lights up `Pro` after a successful open.
2. On x86_64 Linux (Ubuntu 22.04 or Debian 12+), the new built-in menu entry is expected to work identically. **Hardware-attached verification on x86_64 Linux is deferred to community testers** — the author has no x86_64 Linux test rig.
3. On Windows and macOS:
   - Existing JInput script entry continues to work as it did before this branch.
   - The new built-in menu entry compiles and the Debug menu shows it.
   - `~/.jmri/log/messages.log` has no new errors at startup.
   - **Hardware-attached verification on Windows/macOS is deferred to community testers.**
4. `ant test-single -Dtest.includes=jmri.util.usb.RailDriverMenuItemTest` is green (canonical JMRI invocation; the `mvn -B test -Dtest=RailDriverMenuItemTest` form must also pass). The dotted fully-qualified class-name form is required; `build.xml:908–911` validates `${test.includes}` against the regex `.*Test$` and passes it directly to the JUnit 5 ConsoleLauncher's `-n` flag, so path-style values with a `.java` suffix are rejected.
5. `ant tests` produces the same number of failures as the same target on `master` (i.e. 0 net new failures introduced by this branch).
6. SpotBugs / archunit clean.

## 6. Risks & mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| hid4java upgrade introduces API breakage | Low | Medium | Pin `0.8.0`; run full build matrix; revert path is one-line in pom.xml |
| `hid4java-0.8.0`'s `linux-aarch64/libhidapi.so` is built against a glibc newer than the oldest supported Pi OS / Debian, causing `UnsatisfiedLinkError` at JNA load time | Low | High | **Concrete bound: required `GLIBC_*` symbol must be ≤ `GLIBC_2.31` (Debian 11 bullseye / 64-bit Pi OS bullseye, the oldest 64-bit Pi OS realistically still in field use; Debian 12 bookworm ships `GLIBC_2.36` and is comfortably newer).** Verify with `objdump -T` on the bundled `.so` before relying on the jar; if the requirement exceeds 2.31, vendor the `.so` rebuilt locally against bullseye's glibc, or fall back to system `libhidapi-hidraw0` (see rejected alternative in §4.2). |
| `hid4java-0.8.0`'s 32-bit ARM (`linux-arm`/`linux-armel`) natives untested on Pi OS 32-bit; users on that platform may regress vs the 0.5.0 native they were running | Medium | Medium | Document armhf as untested in §3; community testers verify before release. **Rollback to 0.5.0 is NOT a clean option** — it would re-introduce the aarch64 problem this branch exists to solve. Real fallbacks if 32-bit ARM regresses: (a) repackage 0.8.0's jar with 0.5.0's `linux-arm/libhidapi.so` substituted in; (b) pin to hid4java 0.7.0, which also bundles aarch64 and predates 0.8.0's 32-bit ARM toolchain changes. **Before relying on 0.7.0 as a fallback, verify its jar contents the same way 0.8.0 was verified** (`unzip -l` on the artifact from Maven Central) — confirm both `linux-aarch64/libhidapi.so` and `linux-arm/libhidapi.so` are present. The 0.7.0 release date (2020-09-10) is recorded above on faith; do not skip the verification step. |
| udev rule conflicts with existing user configuration | Low | Low | Ship as a non-installed sample under `lib/linux/udev/`; user installs explicitly |
| JNA cannot load bundled `libhidapi.so` on a future Pi OS / kernel | Medium | Medium | Internal contingency only: pivot to system-`libhidapi` + `-Djna.library.path` if it ever fails (not promoted to user-facing docs unless it actually breaks). See rejected alternative in §4.2. |
| macOS dist signing breaks because the 0.8.0 jar contains two arch-specific `darwin-*/libhidapi.dylib` files instead of the single `darwin/libhidapi.dylib` 0.5.0 had | Medium | Medium | `scripts/diskimage-sign.sh:251` to be updated to sign both new paths as part of the hid4java upgrade work; verify the next macOS dist build still produces a notarized DMG before merge |
| **The macOS-signing change cannot be rehearsed pre-merge.** `scripts/diskimage-sign.sh` runs only on a release-engineering host with Apple signing credentials; neither this branch's author nor JMRI CI has those credentials, so a typo in either of the two new `darwin-aarch64/...` / `darwin-x86-64/...` path strings will not surface until a release build. | Medium | Medium | The hid4java upgrade work must include the verbatim `unzip -l lib/hid4java-0.8.0.jar` output in the relevant commit message so reviewers can compare the exact path strings letter-for-letter. Acceptance criterion §5 #3 already gates merge on Windows/macOS not regressing, but the signing path itself rides on the next release build — flag it explicitly in the release-engineering handoff. |
| Pre-existing latent issue: `hidDeviceDetached` doesn't interrupt the polling thread. **Currently dormant** because `hidServices.start()` is commented out (`RailDriverMenuItem.java:114`, inside the fully-commented `if (!invokeOnMenuOnly)` block at lines 110–125), so the listener never fires today. Becomes live the moment that block is re-enabled. | Low (today; High if listener re-enabled) | Low (one stack trace, throttle window stops responding) | Documented as out-of-scope follow-up in §3; no in-branch fix |
| **Polling-loop busy-spin on read failure.** `RailDriverMenuItem.java:198–238` has no sleep and no `break` in the `ret < 0` branch — a closed device or transient read error pegs a CPU core. Noticeable thermal/battery cost on a Pi 4. | Medium (any unplug, any USB hiccup) | Medium (one core at 100%, throttle window stops responding to the device) | Documented as out-of-scope follow-up in §3; no in-branch fix. A future fix would add `Thread.sleep(50)` (or break out of the loop) on `ret < 0`. |
| **Off-EDT Swing mutation.** `firePropertyChange` is invoked from the polling thread (`RailDriverMenuItem.java:215, 226`), which dispatches into `propertyChange` and ultimately mutates Swing components and calls `setLEDs`/`setSpeedSetting`/`setFunction` from a non-EDT thread. Pre-existing Swing threading violation. | Medium (intermittent; Swing is forgiving until it isn't) | Medium (intermittent UI glitches; potential repaint/listener-list races) | Documented as out-of-scope follow-up in §3; no in-branch fix. A future fix wraps event dispatch in `SwingUtilities.invokeLater`. |
| Existing Windows/macOS users see no behavioural change but the new menu item appears confusing | Low | Low | Keep the existing script entry exactly as-is; new entry is clearly labelled "(built in)" |
| **Byte 13 interpretation: confirmed constant sentinel.** Resolved 2026-05-01 via `docs/rpi-raildriver/byte13-capture-test.md`: byte 13 = `0x35` invariant across 2,323 reports under exhaustive sweep of every analog lever and every physical control on the unit (bytes 0..12 all showed activity). Per the device's own HID descriptor (`Usage Min = Button 1`, `Usage Max = Button 56`, packed as 56 × 1 bit), byte 13 is declared as buttons 48..55 but the firmware never sets them. The parser's mapping for bits 48..55 is dead code in practice — the diff loop produces no events when the byte never changes. | Resolved | None | None — the §4.5 byte-13 pin-down test stays in place to flag any future firmware revision that starts populating buttons 48..55. |

## 7. References

- `java/src/jmri/util/usb/RailDriverMenuItem.java` — existing hid4java implementation
- `java/src/apps/jmrit/DebugMenu.java` — menu wiring point
- `java/test/jmri/util/usb/RailDriverMenuItemTest.java` — single-test starting point
- `help/en/html/hardware/raildriver/{index,details}.shtml` — user docs
- `lib/hid4java-0.5.0.jar` — current native library bundle (no aarch64); to be replaced by `lib/hid4java-0.8.0.jar` per §4.2
- `lib/jinput-2.0.9-natives-all.jar` — JInput natives (no aarch64; locally patched to add one outside this branch)
- `pom.xml` — dependency declarations
- Empirical investigation captures committed under `docs/rpi-raildriver/test-data/` (`hidraw-raildriver{,2,3,4,5}.log`, `xxd -c 14` formatted)
- Byte-13 capture-test procedure: `docs/rpi-raildriver/byte13-capture-test.md`
- Upstream JMRI install guidance: <https://www.jmri.org/install/Linux.shtml>
- JInput Linux plugin source: <https://github.com/jinput/jinput/blob/master/plugins/linux/src/main/java/net/java/games/input/LinuxNativeTypesMap.java> 

## 8. Out-of-band housekeeping

These items live alongside this work but are intentionally not committed in this branch:

- `build-and-install-to-jmri.sh` (already present in working tree, untracked) — local convenience for build/test on the Pi. Has proven useful (one command rebuilds JMRI from any branch and installs it over `~/JMRI`, preserving locally added natives). **TODO (follow-up issue, not this branch):** propose upstreaming a similar helper into JMRI's `scripts/` directory so other Pi developers benefit. Tracked separately from the RailDriver work.
- Local `evtest-raildriver.log` (the kernel-input-side capture used to prove the absence of `EV_ABS`). Kept on the dev host; available on request.
- The locally built aarch64 `libjinput-linux64.so` in `/home/pi/JMRI/lib/linux/aarch64/`. Solves an *unrelated* problem (the JInput path that we are not fixing in this branch) and is therefore out of scope here.
