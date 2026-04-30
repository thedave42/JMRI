# RailDriver support on Raspberry Pi (aarch64 Linux)

> **Branch:** `rpi-raildriver`
> **Status:** Plan / not yet implemented
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
   bytes 7..12  = 6 button-bitmap bytes (48 button positions)
   byte 13      = constant 0x35 (sentinel / version byte)
   ```

   This matches the parser in `RailDriverMenuItem.java` (lines 191–232) byte-for-byte, with one cosmetic inaccuracy: the parser treats byte 13 as a button bitmap (`i >= 7`). It is in fact a constant, so the diff loop produces no events for that byte and the inaccuracy is harmless.

### Conclusion drawn from the investigation

There is no realistic JInput-on-Linux path to a working RailDriver, because the analog inputs are not exposed via evdev at all. The hidraw path (already implemented in `RailDriverMenuItem.java`) is the only viable route and is empirically known to deliver every control we need.

## 2. Goals

1. End user can plug a RailDriver Modern Desktop into a Pi 4 running 64‑bit Raspberry Pi OS / Debian, launch JMRI, choose a menu item, and drive a locomotive.
2. The hid4java upgrade is *intended* not to regress the existing test suite on Windows, macOS, and Linux x86_64. Pre-merge verification on those platforms is **out of this branch's author's reach** and is therefore deferred to JMRI CI and to community testers (see §6 #2 and §6 #3 for the explicit acceptance criteria split). This branch's author tests only on Pi 4 (aarch64).
3. Required system steps (the udev rule and `plugdev` group membership) are documented in `help/en/html/hardware/raildriver/index.shtml`.
4. The JInput-based path is left intact and unmodified for non-Linux users (and for Linux users with controllers other than the RailDriver). RailDriver users on Linux are simply guided to the hidraw path.
5. A regression test exists that exercises the byte parser with a recorded report stream, so future refactors can't silently break it.

## 3. Non-goals (out of scope for this branch)

- Replacing the Jython `RailDriver.py` script (the existing Debug menu entry).
- Modifying the USBThrottle Jynstrument or its drivers.
- Adding RailDriver-specific functionality not present in `RailDriverMenuItem.java` today (advanced braking simulation, configurable button-to-function maps, etc.). Those can be follow-ups.
- Patching the Linux kernel or shipping a custom HID quirks driver.
- Supporting 32‑bit ARM (`armhf`). hid4java 0.5.0 already bundles a `linux-arm` native, and 0.8.0 ships both `linux-arm` and `linux-armel`. **However, we have not verified the 0.8.0 32-bit ARM natives load on Pi OS 32-bit.** Users on that platform should test before relying on the upgrade. Tracked as a risk in §7.
- Supporting RailDriver hardware revisions other than the Modern Desktop (VID `05F3` / PID `00D2`).
- **Fixing the device-detach NPE in `RailDriverMenuItem.hidDeviceDetached`.** The handler nulls `hidDevice` (line 466) but does not interrupt the polling thread. *In the current code path the listener is never wired up* — `hidServices.start()` is commented out at `RailDriverMenuItem.java:114` (inside the `if (!invokeOnMenuOnly)` block which is itself fully commented out, lines 110–125), so `hidDeviceDetached` never actually fires and the NPE doesn't manifest today. The bug becomes live the moment that block is re-enabled. Pre-existing latent issue; fixing it is a follow-up so this branch's diff stays focused on the Pi/aarch64 enablement.
- **Fixing the polling-loop busy-spin on read failure.** `RailDriverMenuItem.java:198–238` runs `hidDevice.read(buff_new)` in a tight `while (!thread.isInterrupted())` loop with **no sleep** and no `break`/`Thread.sleep` on `ret < 0`; on an error or closed device the loop pegs a CPU core. On a Pi 4 that's a noticeable thermal/battery cost. Documented as a known issue; not fixed in this branch (also tracked in §7).
- **Fixing the off-EDT Swing mutation.** `firePropertyChange` is invoked from the polling thread (line 215, 226), which dispatches into `propertyChange` and ultimately mutates Swing components (`throttleWindow.addPropertyChangeListener` at line 173, `throttle.setSpeedSetting`, `throttle.setFunction`, `setLEDs`, etc.). This is a Swing threading violation that pre-dates this branch and is preserved by the §5 Commit 3 mechanical refactor. A proper fix would route dispatch through `SwingUtilities.invokeLater`. Out of scope; tracked in §7.
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
   - **Button 0 is silently dropped.** `RailDriverMenuItem.java:744` filters with `fNum > 0`, so the bit corresponding to button 0 never toggles F0. This appears to be a latent bug (or a deliberate "F0 reserved" choice that nobody documented). It is **not fixed in this branch** and is **not part of the §5 Commit 3 mechanical refactor** — flagged as a follow-up issue to investigate separately, so Commit 3 truly contains no behavioural change.
   - Any user docs in §5 Commit 6 must reflect the Java code's mapping, not the script's.

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
- Verify API compatibility: `RailDriverMenuItem.java` uses `HidServices`, `HidServicesSpecification`, `HidServicesEvent`, `HidServicesListener`, `HidDevice`, `HidManager`, `HidException`, `ScanMode`. These names are stable across 0.5 → 0.8. Any minor signature changes (e.g. read-buffer types) will be addressed in commit 2 if they appear.
- Update `lib/hid4java-0.5.0.jar` → `lib/hid4java-0.8.0.jar` and update direct references in the relevant build files. The exact list of files to touch is enumerated in §5 Commit 2.
- Re-run JMRI's CI build matrix (Windows, macOS, Linux x86_64) to confirm no regressions.

**Considered alternative (rejected): use system `libhidapi` via JNA library path override.**
- Users would `sudo apt install libhidapi-hidraw0` and JMRI's launcher would pass `-Djna.library.path=/usr/lib/aarch64-linux-gnu:/usr/lib/arm-linux-gnueabihf:/usr/lib`. JNA would then resolve the system `.so` instead of the (missing) bundled one.
- **Why rejected:** introduces an extra system package as an end-user prerequisite, ties JMRI to a distro-managed library version, and creates an asymmetric experience versus Windows/macOS where the bundled native is loaded transparently. Not surfaced in user-facing docs.
- We retain awareness of this approach as a contingency in the risk register (§7), not as a supported public path.

**Decision: dependency upgrade is the only supported approach** because it is identical to how Windows and macOS already work today (bundled native loaded transparently from the jar), and it gives one homogeneous user experience regardless of architecture.

### 4.3 Menu wiring

Add a new entry `RdBuiltIn` ("RailDriver Throttle (built in)") to the **Debug menu**, immediately following the existing `MenuRailDriverThrottle` script entry. Note that the new menu entry's label is sourced from `jmri.util.usb.Bundle.RdBuiltIn` (resolved by `RailDriverMenuItem`'s no-arg constructor at `RailDriverMenuItem.java:61`), **not** from `apps.AppsBundle` where the existing script's `MenuRailDriverThrottle` key lives. The `RdBuiltIn` key is already present in the base `Bundle.properties` and 4 of the 5 localized files (`Bundle_cs.properties`, `Bundle_de.properties`, `Bundle_fr.properties`, `Bundle_nl.properties`) — it was reserved for exactly this purpose and is currently dead code. **`Bundle_ca.properties` is missing the key**; §5 Commit 2 (which performs the menu wiring) will either add it or accept that Catalan falls back to the English string from the base bundle.

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

We deliberately do **not** delete the script entry in this branch — that migration is deferred until a future release once the Java path has been in users' hands and any rough edges have been reported. (This was previously listed in §8 as an open decision; it's now a settled deferral.)

### 4.4 Permissions / udev

The existing help page (`help/en/html/hardware/raildriver/index.shtml`) describes a generic udev rule. We will:

1. Ship a ready-to-install rules file at `lib/linux/udev/99-jmri-raildriver.rules` (this file is documentation/sample only, not loaded by the JVM; build/launcher implications are covered in §5 Commit 5).
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

`java/test/jmri/util/usb/RailDriverMenuItemTest.java` currently contains a single trivial constructor test. Extend it with:

1. **Byte-parser test (no hardware required).** Inject a sequence of recorded 14‑byte reports (5 captures totalling 2,046 reports / 680 unique payloads, checked in under `docs/rpi-raildriver/test-data/hidraw-raildriver*.log` as `xxd -c 14` formatted text) into a refactored protected method `processReportDelta(byte[] oldR, byte[] newR)`, and assert that:
   - changes in bytes 0–6 emit `firePropertyChange("Value", "Axis N", String value)`. The numerical value is computed by `(256 − vInt) / 256.0` per `RailDriverMenuItem.java:206–208`, where `vInt ∈ [0, 255]`. So axes 0/2/3/4/5/6 lie in `[1/256, 1.0]` (i.e. roughly `(0.00390, 1.0]`); axis 1 has the additional `(2v − 1)` transform on line 211, producing values in `[2/256 − 1, 1.0]` ≈ `[−0.992, 1.0]`. The test asserts these exact achievable ranges (closed lower bound, closed upper bound) — it must **not** assert `[−1.0, 1.0]` because `−1.0` is unreachable. The test asserts the numerical range only, **not** the physical-direction-to-value mapping (we have no synchronized photographic record of lever positions during capture).
   - changes in bytes 7–12 emit per-bit `firePropertyChange("Value", "<n>", "1"/"0")` events.
   - **For the committed fixtures specifically**, byte 13 produces no events — every report has `byte13 == 0x35`. The test asserts this empirical property of the captures, not a contract on the parser.
   - **Synthetic-input parser-behaviour pin-down test.** Hand-construct two reports where only byte 13 differs (e.g. `0x35` → `0x36`, XOR `0x03`, two bits flipped) and assert that the parser emits exactly two events with names `"48"` and `"49"`. This **does not** assert correct behaviour — it pins down the *current* behaviour, which treats byte 13 as button bits 48..55 because of the `i >= 7` else-branch at `RailDriverMenuItem.java:216`. The test exists so that any future change which silently flips this (e.g. an inadvertent off-by-one in a re-roll of the loop, or someone "fixing" the parser without updating downstream filters) is caught. **Cleaning up the parser to skip byte 13 properly is tracked as a follow-up issue, not done in this branch** — when that follow-up lands, the assertion in this test inverts to "no events are emitted." That re-targeting is a one-line test change at the same time as the parser fix, so the pin-down test does not block the eventual cleanup.
   - identical old/new buffers produce zero events.
   The test will parse `xxd -c 14` text into raw 14-byte reports rather than introducing a separate binary fixture format.
2. **No-device behaviour.** Verify that selecting the menu item with no RailDriver attached logs an info message and does not throw. With `setupHidServices` returning a non-null `hidServices` but `hidServices.getHidDevice(...)` returning `null` (no device matches VID/PID), the action listener at `RailDriverMenuItem.java:78–85` skips the `setupRailDriver()` block entirely and no further code runs, so this test exercises only the existing `protected void setupHidServices()` seam — no additional refactor needed.
3. **LED-encoding test (no hardware required).** Assert that `setLEDs(String)` produces the expected 7-byte report buffer for representative inputs: digits ("123"), letters ("Pro"), decimal-point handling ("8.8.8."), shorter strings, and unsupported characters. The seven-segment encoding is non-trivial (see `RailDriverMenuItem.java` lines 357–401 plus the `SevenSegment[]` and `SevenSegmentAlpha[]` tables), called every time `setupRailDriver` succeeds (line 133), and currently has zero coverage. **Test seam required:** §5 Commit 3 makes `sendMessage(byte[], byte)` package-private and overridable. The test subclasses `RailDriverMenuItem` to capture the buffer instead of writing it, so no `HidDevice` is needed.
4. ~~**Default-throttle-layout fall-through smoke test.**~~ **Dropped.** A previous revision proposed asserting that `setupRailDriver` brings up a throttle window when no default layout exists. That cannot be made hardware-independent without adding a `HidDevice` factory seam to §5 Commit 3 — the action listener at `RailDriverMenuItem.java:78–85` only invokes `setupRailDriver` after `hidServices.getHidDevice(...)` returns non-null, and `setupRailDriver` itself calls `setLEDs("Pro") → sendMessage → hidDevice.write` on its first line (line 133), which NPEs against a null device. Adding a full `HidDevice` injection seam exceeds the "no behavioural change" boundary set for Commit 3. Deferred to a follow-up issue covering the broader testability work (HidDevice abstraction, `setupRailDriver` extraction from the action listener, EDT-aware dispatch).
5. **Existing constructor test stays.**

We will not add an integration test that opens a real device; CI cannot guarantee one is plugged in.

### 4.6 Help / documentation

- `help/en/html/hardware/raildriver/index.shtml`: add a "Raspberry Pi / Linux 64‑bit" section covering the udev rule, group membership, and mention that the built-in menu entry is recommended over the script on Linux.
- `help/en/html/hardware/raildriver/details.shtml`: add a paragraph noting the platform-specific lookup limitations of the JInput path on Linux and pointing to the Java built-in path.
- French (`help/fr/html/hardware/raildriver/index.shtml`): mirror the same change so the doc isn't out of sync. (Translation can be a stub with the English text and a `TODO translate` comment.)

## 5. Detailed work breakdown

The branch will land as a sequence of small, reviewable commits.

> Plan revision marker: `2026-04-30-r8`. When commit messages reference a `§5 Commit N`, they refer to this revision. If a future revision renumbers commits, the marker bumps so historical references can still be traced (see also §7 risk on plan/code drift). Revision r8 superseded r7 with the following substantive changes: (1) corrected the `ant test-single -Dtest.includes=...` invocation to use the dotted fully-qualified class name (the path-style with `.java` form is rejected by `build.xml:908–911`); (2) reframed the §3 device-detach NPE bullet to note the listener is currently dormant, and added explicit non-goal entries for the polling-loop busy-spin and off-EDT Swing mutation, with matching §7 risk rows; (3) tightened §5 Commit 3 to be strictly mechanical (no behavioural changes, including no byte-13 fix and no F0 fix); (4) inverted the synthetic byte-13 test from "asserts no events" to "pins down the current behaviour of two events at indices 48/49"; (5) dropped the default-throttle smoke test from §4.5 (would require a `HidDevice` factory seam beyond Commit 3's scope); (6) made the axis-range assertion precise (closed `[1/256, 1.0]` for axes 0/2/3/4/5/6, closed `[2/256 − 1, 1.0]` for axis 1); (7) clarified §4.3 that the new menu label resolves from `jmri.util.usb.Bundle.RdBuiltIn`, not `apps.AppsBundle`; (8) added a §7 row for the unrehearseable macOS-signing path with a mitigation requiring `unzip -l` output in the Commit 1 message; (9) pinned the linux-aarch64 glibc bound at `GLIBC_2.31` (Debian 11 bullseye) with an `objdump -T` pre-commit gate; (10) added a `scripts/AppScriptTemplate:436–491` cite to §5 Commit 5's launcher claim.

### Commit 0 — Test-data fixtures (already merged on `rpi-raildriver` as `c21d79f5391`; **no action required for this branch**)
- `docs/rpi-raildriver/test-data/hidraw-raildriver{,2,3,4,5}.log` — five `xxd -c 14`-formatted captures of the RailDriver Modern Desktop's HID input reports on a Pi 4 / aarch64 / Debian 13 host (2,046 reports / 680 unique payloads). These are the empirical basis for the byte-layout claims in §1 and the fixture pool for the unit tests in §5 Commit 4.
- The plan itself is iterated in this issue (#1), not in a checked-in markdown file. The commit message of `c21d79f5391` says `Refs: #1`.
- No source changes. A reviewer checking out `rpi-raildriver` fresh has nothing to do here; this entry exists to anchor later commits' fixture references and to keep the §5 Commit numbering aligned with the actual git history.

### Commit 1 — Upgrade `hid4java` to 0.8.0
- **Pre-commit gate (do not stage the binary jar yet):** drop `hid4java-0.8.0.jar` into `lib/` alongside the existing 0.5.0 jar in your local working tree, point `pom.xml` at 0.8.0, and run `ant test-single -Dtest.includes=jmri.util.usb.RailDriverMenuItemTest` on the dev host to confirm the new jar's classes load and the existing test still passes. Only after that succeeds do the binary swap and the file updates below.
- **Capture the 0.8.0 jar's native-library inventory in the commit message.** Run `unzip -l lib/hid4java-0.8.0.jar | grep -E 'hidapi|\.so$|\.dylib$|\.dll$'` and paste the output into the Commit 1 message. The macOS dist-signing change below depends on the **exact** path strings (`darwin-x86-64/libhidapi.dylib`, `darwin-aarch64/libhidapi.dylib`); pasting the listing makes those strings reviewable against ground truth and avoids release-engineering surprises (see §7 row on unrehearsed macOS signing).
- **Capture the linux-aarch64 native's glibc requirement.** Run `unzip -p lib/hid4java-0.8.0.jar linux-aarch64/libhidapi.so > /tmp/h.so && objdump -T /tmp/h.so | grep -oE 'GLIBC_[0-9.]+' | sort -uV | tail -1` and paste the result into the Commit 1 message. Required: ≤ `GLIBC_2.31` (Debian 11 bullseye, the oldest 64-bit Raspberry Pi OS still in field use). If the requirement exceeds 2.31, escalate to the §7 mitigation (vendor a locally rebuilt `.so`) before merging.
- Replace `lib/hid4java-0.5.0.jar` with `lib/hid4java-0.8.0.jar` (verified to contain `linux-aarch64/libhidapi.so`).
- Update `pom.xml` dependency `<version>` to `0.8.0`.
- Update the exact-version reference in `build.xml:203` (`<pathelement location="${libdir}/hid4java-0.5.0.jar"/>`).
- Update `scripts/diskimage-sign.sh:251` — currently signs `JMRI/lib/hid4java-0.5.0.jar darwin/libhidapi.dylib`. In 0.8.0 the macOS native moved to two arch-specific paths (`darwin-aarch64/libhidapi.dylib`, `darwin-x86-64/libhidapi.dylib`), so the script needs to sign **both**, plus update the jar filename.
- Regenerate `lib/bom-Java-Maven.spdx`. The file's header records `Creator: Tool: spdx-sbom-generator-source-code` — that is the external tool that produced it. Re-run that tool, or hand-edit the three `hid4java-0.5.0` references (`SPDXID: SPDXRef-Package-hid4java-0.5.0`, the `PackageDownloadLocation` URL, and the `DEPENDS_ON` relationship line). No JMRI build target invokes that tool; this step is manual.
- Verify `RailDriverMenuItem.java` still compiles unchanged (the public hid4java API surface used by JMRI — `HidServices`, `HidServicesSpecification`, `HidServicesEvent`, `HidServicesListener`, `HidDevice`, `HidManager`, `HidException`, `ScanMode` — is stable across 0.5 → 0.8).
- Run the existing test (project is single-module Maven, single-module ant): `ant test-single -Dtest.includes=jmri.util.usb.RailDriverMenuItemTest` (or equivalently `mvn -B test -Dtest=RailDriverMenuItemTest`) on x86_64 Linux and aarch64. **Note:** the `test.includes` value is a JUnit 5 class-name regex consumed by `-pre-single-test` (see `build.xml:908–911` where the value is matched against `.*Test$` and then passed verbatim as the JUnit ConsoleLauncher `-n` argument). Path-style values like `jmri/util/usb/...Test.java` fail the predicate and abort the target.

### Commit 2 — Wire `RailDriverMenuItem` into the Debug menu
- One-line addition to `java/src/apps/jmrit/DebugMenu.java`.
- Optionally add the missing `RdBuiltIn` key to `java/src/jmri/util/usb/Bundle_ca.properties`. Without this, Catalan-locale users see the English fallback "RailDriver Throttle (built in)" — which is acceptable but worth flagging in the commit message.

### Commit 3 — Refactor `RailDriverMenuItem` for testability
**Strictly mechanical: no behavioural change.** This commit only widens visibility and extracts methods so the §5 Commit 4 tests have seams to attach to. It does **not** fix the byte-13 over-read, the F0/button-0 drop, the polling-loop busy-spin, or the off-EDT Swing mutation — all four are tracked as separate follow-up issues so that a `git revert` of this commit cleanly undoes only the visibility/extraction changes.

Concretely:
- Extract the inner per-report body from the polling thread (`RailDriverMenuItem.java:201–232`) into a package-private method `void processReportDelta(byte[] oldR, byte[] newR)` that fires the same `firePropertyChange` events. The polling thread becomes a thin wrapper that calls `processReportDelta(buff_old, buff_new)` after each successful `hidDevice.read`, then copies `buff_new` into `buff_old` (the per-byte copy at line 230 moves into the wrapper or stays at the end of `processReportDelta`, whichever keeps the diff smaller — both are byte-for-byte equivalent).
- Lower `private final void sendMessage(byte[] buff, byte command)` to package-private (`final` retained) so the §5 Commit 4 LED-encoding test can subclass and capture the buffer without opening a real `HidDevice`. (`setupHidServices` is already `protected` per `RailDriverMenuItem.java:89`, so no change is required there for the no-device test.)
- No edits to `propertyChange`, `setupRailDriver`, `hidDeviceAttached`/`hidDeviceDetached`, the `SevenSegment[]` tables, or the button-to-function `switch` block.

### Commit 4 — Tests
- Extend `RailDriverMenuItemTest.java` per §4.5: byte-parser tests against committed fixtures (axis-range and per-bit-button assertions), the synthetic byte-13 pin-down test, the LED-encoding test (using the package-private `sendMessage` seam from §5 Commit 3), the no-device behaviour test, and the existing constructor test.
- The fixtures under `docs/rpi-raildriver/test-data/hidraw-raildriver*.log` (already on the branch — see §5 Commit 0) provide the input report sequences. The test will parse the `xxd -c 14` text format directly so no separate binary blob is needed.
- This commit adds **no new test seams** — it consumes only the seams introduced in §5 Commit 3 (`processReportDelta` + `sendMessage`) plus the pre-existing `protected setupHidServices` seam at `RailDriverMenuItem.java:89`. The previously listed "default-throttle-layout fall-through smoke test" was dropped (see §4.5 #4 for rationale); it would require a `HidDevice` factory seam that exceeds Commit 3's mechanical-only scope.

### Commit 5 — udev rule sample file
- Add `lib/linux/udev/99-jmri-raildriver.rules`.
- This file is documentation; not loaded by the JVM.
- No build wiring needed for the Linux dist: `build.xml`'s `package-linux` target already copies `${libdir}/linux/` recursively into `dist/Linux/JMRI/lib/linux/` (`build.xml:2787–2790`), so the new `udev/` subdirectory ships in the `.tgz` automatically.
- macOS and Windows dist targets use **separate copy filesets** (e.g. `package-macosx`, `package-windows`) and do not pull from `${libdir}/linux/`. Re-confirm this when staging the commit, but no change is expected — the udev rule will not leak into non-Linux distributions.
- The launcher script discovers natives by setting `SYSLIBPATH=${LIBDIR}/$OS` (e.g. `lib/linux`) and then prepending `${LIBDIR}/$OS/$ARCH` (e.g. `lib/linux/aarch64`) only if it exists (`scripts/AppScriptTemplate:436–491`, used to generate the dist `PanelPro` / `DecoderPro` launchers). `$ARCH` is determined from `arch`/`uname` against the running CPU, **not** from a directory listing, so an extra `udev/` sibling under `lib/linux/` cannot be selected as a native-library directory and does not interfere with native-library discovery.

### Commit 6 — Help doc updates
- `help/en/html/hardware/raildriver/index.shtml`: add a "Raspberry Pi / Linux 64‑bit" section that recommends the new built-in menu entry, documents the udev install steps, and the `plugdev` group membership / log-out-log-back-in step. **Strict single-path policy from §4.4: do NOT mention `libhidapi-hidraw0` or `-Djna.library.path` in user-facing copy.**
- `help/en/html/hardware/raildriver/details.shtml`: explain the platform split (Linux uses the built-in Java path; Windows/macOS may continue to use either the script or the built-in path).
- `help/fr/html/hardware/raildriver/index.shtml`: stub mirroring the English changes (and the same single-path policy). Keep a `TODO translate` comment.

### Commit 7 — Verification on Pi 4 (no commit; verification-only step)
- Capture a `script.log` showing the menu item opening the device, the polling thread starting, and lever values arriving.
- Capture a screenshot/log of operating a locomotive end-to-end against DCC++.
- **Post the verification artifacts as comments on this issue (#1)**, not as a checked-in markdown file. This keeps the iteration surface in one place. Tag the comments with the plan revision marker from the §5 preamble so historical readers can correlate.

## 6. Verification / acceptance criteria

1. On a clean Pi 4 (64‑bit Pi OS) with this branch built and installed via `build-and-install-to-jmri.sh`, after running the udev steps:
   - `Debug → RailDriver Throttle (built in)` opens a throttle window.
   - `~/.jmri/log/messages.log` shows `Got RailDriver hidDevice: ...`.
   - Moving the throttle/dyn-brake lever changes loco speed in some direction (sign of motion is fine; we do not assert which direction maps to acceleration in this branch).
   - Moving the reverser switches direction.
   - Pressing the bell button toggles **F1** (per `RailDriverMenuItem.java:729–733` — note this is F1, not F3 as the script-based docs say).
   - Pressing the horn lever toggles F2.
   - Pressing one of the 28 blue function buttons toggles a function `Fn` whose number is determined by HID bit position (look for `FUNCTION N value: ...` lines in the log to read the live mapping).
   - The 7-segment display lights up `Pro` after a successful open.
2. ~~On x86_64 Linux (Ubuntu 22.04 or Debian 12), the same menu item works identically.~~ Not part of the in-branch acceptance criteria — the author has no x86_64 Linux test rig. To be community-verified before release.
3. On Windows and macOS:
   - Existing JInput script entry continues to work as it did before this branch.
   - The new built-in menu entry compiles and the Debug menu shows it.
   - `~/.jmri/log/messages.log` has no new errors at startup.
   - **Hardware-attached verification on Windows/macOS is deferred to community testers.**
4. `ant test-single -Dtest.includes=jmri.util.usb.RailDriverMenuItemTest` is green (canonical JMRI invocation; the `mvn -B test -Dtest=RailDriverMenuItemTest` form must also pass). The dotted fully-qualified class-name form is required; `build.xml:908–911` validates `${test.includes}` against the regex `.*Test$` and passes it directly to the JUnit 5 ConsoleLauncher's `-n` flag, so path-style values with a `.java` suffix are rejected.
5. `ant tests` produces the same number of failures as the same target on `master` (i.e. 0 net new failures introduced by this branch).
6. SpotBugs / archunit clean.

## 7. Risks & mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| hid4java upgrade introduces API breakage | Low | Medium | Pin `0.8.0`; run full build matrix; revert path is one-line in pom.xml |
| `hid4java-0.8.0`'s `linux-aarch64/libhidapi.so` is built against a glibc newer than the oldest supported Pi OS / Debian, causing `UnsatisfiedLinkError` at JNA load time | Low | High | **Concrete bound: required `GLIBC_*` symbol must be ≤ `GLIBC_2.31` (Debian 11 bullseye / 64-bit Pi OS bullseye, the oldest 64-bit Pi OS realistically still in field use; Debian 12 bookworm ships `GLIBC_2.36` and is comfortably newer).** §5 Commit 1's pre-commit gate runs `objdump -T` on the bundled `.so` and pastes the highest required `GLIBC_` symbol into the commit message; merge is blocked if it exceeds 2.31. Internal contingency if it does: vendor the `.so` rebuilt locally against bullseye's glibc, or fall back to system `libhidapi-hidraw0` (see rejected alternative in §4.2). |
| `hid4java-0.8.0`'s 32-bit ARM (`linux-arm`/`linux-armel`) natives untested on Pi OS 32-bit; users on that platform may regress vs the 0.5.0 native they were running | Medium | Medium | Document armhf as untested in §3; community testers verify before release. **Rollback to 0.5.0 is NOT a clean option** — it would re-introduce the aarch64 problem this branch exists to solve. Real fallbacks if 32-bit ARM regresses: (a) repackage 0.8.0's jar with 0.5.0's `linux-arm/libhidapi.so` substituted in; (b) pin to hid4java 0.7.0, which also bundles aarch64 and predates 0.8.0's 32-bit ARM toolchain changes. |
| udev rule conflicts with existing user configuration | Low | Low | Ship as a non-installed sample under `lib/linux/udev/`; user installs explicitly |
| JNA cannot load bundled `libhidapi.so` on a future Pi OS / kernel | Medium | Medium | Internal contingency only: pivot to system-`libhidapi` + `-Djna.library.path` if it ever fails (not promoted to user-facing docs unless it actually breaks). See rejected alternative in §4.2. |
| macOS dist signing breaks because the 0.8.0 jar contains two arch-specific `darwin-*/libhidapi.dylib` files instead of the single `darwin/libhidapi.dylib` 0.5.0 had | Medium | Medium | `scripts/diskimage-sign.sh:251` updated in §5 Commit 1 to sign both new paths; verify the next macOS dist build still produces a notarized DMG before merge |
| **macOS-signing change in §5 Commit 1 cannot be rehearsed pre-merge.** `scripts/diskimage-sign.sh` runs only on a release-engineering host with Apple signing credentials; neither this branch's author nor JMRI CI has those credentials, so a typo in either of the two new `darwin-aarch64/...` / `darwin-x86-64/...` path strings will not surface until a release build. | Medium | Medium | §5 Commit 1's commit message includes the verbatim `unzip -l lib/hid4java-0.8.0.jar` output covering all `.dylib`/`.so`/`.dll` members; reviewers compare the exact path strings letter-for-letter. Acceptance criterion §6 #3 already gates merge on Windows/macOS not regressing, but the signing path itself rides on the next release build — flag it explicitly in the release-engineering handoff. |
| Pre-existing latent issue: `hidDeviceDetached` doesn't interrupt the polling thread. **Currently dormant** because `hidServices.start()` is commented out (`RailDriverMenuItem.java:114`, inside the fully-commented `if (!invokeOnMenuOnly)` block at lines 110–125), so the listener never fires today. Becomes live the moment that block is re-enabled. | Low (today; High if listener re-enabled) | Low (one stack trace, throttle window stops responding) | Documented as out-of-scope follow-up in §3; no in-branch fix |
| **Polling-loop busy-spin on read failure.** `RailDriverMenuItem.java:198–238` has no sleep and no `break` in the `ret < 0` branch — a closed device or transient read error pegs a CPU core. Noticeable thermal/battery cost on a Pi 4. | Medium (any unplug, any USB hiccup) | Medium (one core at 100%, throttle window stops responding to the device) | Documented as out-of-scope follow-up in §3; no in-branch fix. A future fix would add `Thread.sleep(50)` (or break out of the loop) on `ret < 0`. |
| **Off-EDT Swing mutation.** `firePropertyChange` is invoked from the polling thread (`RailDriverMenuItem.java:215, 226`), which dispatches into `propertyChange` and ultimately mutates Swing components and calls `setLEDs`/`setSpeedSetting`/`setFunction` from a non-EDT thread. Pre-existing Swing threading violation; preserved by §5 Commit 3's mechanical refactor. | Medium (intermittent; Swing is forgiving until it isn't) | Medium (intermittent UI glitches; potential repaint/listener-list races) | Documented as out-of-scope follow-up in §3; no in-branch fix. A future fix wraps event dispatch in `SwingUtilities.invokeLater`. |
| Existing Windows/macOS users see no behavioural change but the new menu item appears confusing | Low | Low | Keep the existing script entry exactly as-is; new entry is clearly labelled "(built in)" |
| `RailDriverMenuItem.java` has an inactive code path treating byte 13 as buttons | Negligible | None | No spurious events because byte 13 is constant in observed firmware; the §5 Commit 4 synthetic byte-13 test pins this behaviour down so a future refactor can't silently change it; do not refactor the parser in this branch |
| Plan iteration in this issue and commit messages drift apart over time (renumbered commits, edited body, etc.) | Medium | Low | The §5 preamble carries a "plan revision marker"; commit messages reference §5 Commit N **of a specific marker**, so historical correlation is possible even after the issue body is rewritten. Marker bumps when commits are renumbered. |

## 8. Decisions still to make

- ~~Exact target version of `hid4java`~~ — **decided: 0.8.0** (verified to bundle `linux-aarch64/libhidapi.so` from Maven Central jar inspection).
- ~~Whether to deprecate `MenuRailDriverThrottle` (script) in this release~~ — **decided: defer until next release** (see §4.3).
- ~~Whether to add a CLI option / preference to silence a JInput-platform-mismatch warning at startup~~ — **dropped: no such warning exists.** Empirical check of `~/.jmri/log/messages.log` on the dev Pi 4 (with the locally built aarch64 JInput native present) shows only INFO-level `jinput.TreeModel` lines at startup; no WARN or ERROR. `TreeModel.loadSystem` does have a "platform support not available" log path, but it only fires when the JInput native fails to load — a condition the JInput-side fix (out of scope of this branch) addresses. Removing this from the open-questions list.

_All decisions for this branch are now resolved. Future open questions will be tracked in follow-up issues, not here._

## 9. References

- `java/src/jmri/util/usb/RailDriverMenuItem.java` — existing hid4java implementation
- `java/src/apps/jmrit/DebugMenu.java` — menu wiring point
- `java/test/jmri/util/usb/RailDriverMenuItemTest.java` — single-test starting point
- `help/en/html/hardware/raildriver/{index,details}.shtml` — user docs
- `lib/hid4java-0.5.0.jar` — pre-Commit 1 native library bundle (no aarch64); replaced by `lib/hid4java-0.8.0.jar` in §5 Commit 1
- `lib/jinput-2.0.9-natives-all.jar` — JInput natives (no aarch64; locally patched to add one outside this branch)
- `pom.xml` — dependency declarations
- Empirical investigation captures committed under `docs/rpi-raildriver/test-data/` (`hidraw-raildriver{,2,3,4,5}.log`, `xxd -c 14` formatted)
- Upstream JMRI install guidance: <https://www.jmri.org/install/Linux.shtml>
- JInput Linux plugin source: <https://github.com/jinput/jinput/blob/master/plugins/linux/src/main/java/net/java/games/input/LinuxNativeTypesMap.java> 

## 10. Out-of-band housekeeping

These items live alongside this work but are intentionally not committed in this branch:

- `build-and-install-to-jmri.sh` (already present in working tree, untracked) — local convenience for build/test on the Pi. Has proven useful (one command rebuilds JMRI from any branch and installs it over `~/JMRI`, preserving locally added natives). **TODO (follow-up issue, not this branch):** propose upstreaming a similar helper into JMRI's `scripts/` directory so other Pi developers benefit. Tracked separately from the RailDriver work.
- Local `evtest-raildriver.log` (the kernel-input-side capture used to prove the absence of `EV_ABS`). Kept on the dev host; available on request.
- The locally built aarch64 `libjinput-linux64.so` in `/home/pi/JMRI/lib/linux/aarch64/`. Solves an *unrelated* problem (the JInput path that we are not fixing in this branch) and is therefore out of scope here.
