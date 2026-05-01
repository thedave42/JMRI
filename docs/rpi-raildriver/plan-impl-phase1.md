# RailDriver Phase 1 — MVP: connect & verify

> **Parent plan:** [`plan.md`](plan.md)
> **Branch:** `rpi-raildriver`
> **Target platform:** Raspberry Pi 4, 64-bit Raspberry Pi OS / Debian 12+, JMRI 5.15.x.
> **Phase 1 goal:** empirically confirm the `hidraw → hid4java → RailDriverMenuItem` path works end-to-end on Pi 4 / aarch64, and physical control movements produce visible debug output in `~/.jmri/log/messages.log`. No throttle-driving behavior is asserted at this phase.

## 1. Scope

### In scope

1. `hid4java` aarch64 native available at runtime (drop-in 0.8.0 jar + minimal `pom.xml`/`build.xml` refs).
2. Debug-menu wiring so the new `RailDriverMenuItem` is launchable.
3. udev rule installed **locally on the dev Pi** so `/dev/hidraw*` is openable.
4. One-line defensive guard in `RailDriverMenuItem.propertyChange` so the polling thread keeps emitting log output across the existing EDT race.
5. Manual smoke test on the Pi.

### Out of scope (deferred to phase 2+)

- JUnit tests of any kind (parent §4.5 — byte-parser, LED-encoding, no-device, synthetic pin-down).
- Help-page text and French translation (parent §4.6).
- Shipping the udev rule in the distribution (`lib/linux/udev/99-jmri-raildriver.rules`) — phase 2 packaging task.
- `scripts/diskimage-sign.sh` macOS dual-dylib path updates (parent §4.2 / §6).
- SBOM regeneration (`lib/bom-Java-Maven.spdx`).
- Cross-platform verification (Windows, macOS, x86_64 Linux) — parent §5 #2/#3, deferred to community testers.
- Per-axis correctness vs `control-inventory.md` beyond visible byte-position smoke (parent §5 #1).
- Driving an actual throttle (`setSpeedSetting`, `setFunction`, LED feedback) — phase 1 does not assert any throttle-side behavior beyond the device opening and `setLEDs("Pro")` lighting up.
- All pre-existing latent issues from parent §3/§6 (busy-spin on read failure, off-EDT Swing mutation, dormant `hidDeviceDetached` NPE, button-0 silently dropped, "Gear vs Range" labelling, P-as-Pantograph assumption). **Untouched in phase 1.**

## 2. What's already there (no work required)

The polling thread at `RailDriverMenuItem.java:201–232` already emits INFO-level log lines **before** invoking `firePropertyChange`:

- Line 214: `log.info("firePropertyChange(\"Value\", {}, {})", "Axis N", vDouble)` for each analog byte change.
- Line 225: `log.info("firePropertyChange(\"Value\", {}, {})", "<n>", "1"/"0")` for each button-bit change.

Phase 1 therefore does **not** need to add any new debug-trace logging. The existing INFO lines write directly to `~/.jmri/log/messages.log` and to the JMRI system console, which is exactly the verification surface the MVP requires.

## 3. Steps

### 3.1 hid4java native available on aarch64 (parent §4.2 — partial)

- Download `hid4java-0.8.0.jar` from Maven Central (`org.hid4java:hid4java:0.8.0`).
- Verify the aarch64 native is present:
  ```
  unzip -l lib/hid4java-0.8.0.jar | grep linux-aarch64
  ```
  must show `linux-aarch64/libhidapi.so` and `linux-aarch64/libhidapi-libusb.so`.
- Verify glibc requirement (per parent §6 risk row):
  ```
  unzip -p lib/hid4java-0.8.0.jar linux-aarch64/libhidapi.so \
      | objdump -T - | grep -oE 'GLIBC_[0-9.]+' | sort -uV | tail -1
  ```
  Required: `≤ GLIBC_2.31`. If it exceeds 2.31, fall back per parent §6 (vendor a locally rebuilt `.so`); do **not** proceed to step 3.5 with an unverified native.
- Replace `lib/hid4java-0.5.0.jar` → `lib/hid4java-0.8.0.jar`.
- `pom.xml`: bump the `org.hid4java:hid4java` dependency version `0.5.0` → `0.8.0`.
- `build.xml`: update the `pathelement` referencing `hid4java-0.5.0.jar` → `hid4java-0.8.0.jar`.
- **Out of phase-1 scope:** SBOM regeneration, `scripts/diskimage-sign.sh` macOS path updates, API-surface audit doc in commit message. Those are needed for cross-platform release builds; phase 1 only needs a local Pi build to compile and link.

### 3.2 Menu wiring (parent §4.3 — full)

In `java/src/apps/jmrit/DebugMenu.java`, after the existing `try/catch` block that wraps the script entry (lines 68–75 of that file), add one line:

```java
add(new jmri.util.usb.RailDriverMenuItem());
```

The menu label "RailDriver Throttle (built in)" is sourced from `jmri.util.usb.Bundle.RdBuiltIn`, which is already present in the base `Bundle.properties` (and 4 of 5 localized files; Catalan falls back to English — accept as phase-1 punt).

### 3.3 Defensive guard in `propertyChange` (phase-1-specific)

**Why this is needed.** `setupRailDriver` (lines 131–243) sets `activeThrottleFrame` inside a `SwingUtilities.invokeLater` block (lines 163–176) for the default-layout path, and starts the polling thread immediately afterward (line 242). The polling thread can fire its first `Value` event before the EDT processes the invokeLater, in which case `propertyChange` reaches `RailDriverMenuItem.java:531` (`activeThrottleFrame.getAddressPanel().getThrottle()`) with `activeThrottleFrame == null` and throws NPE. The NPE propagates back into the polling thread's lambda, which has no surrounding `try/catch`, and the polling thread dies. Subsequent control movements produce no log output until the user closes and re-opens the menu entry.

**The fix (one line).** At the top of the `case "Value":` block in `propertyChange` (currently `RailDriverMenuItem.java:528`), add a null-guard that bails out before the dereference:

```java
case "Value":
    if (activeThrottleFrame == null) {
        return; // EDT race: polling-thread log line already fired; throttle dispatch will resume once the throttle frame is established
    }
    String oldValue = event.getOldValue().toString();
    String newValue = event.getNewValue().toString();
    DccThrottle throttle = activeThrottleFrame.getAddressPanel().getThrottle();
    ...
```

This is the minimum surgical change needed to keep the MVP usable across the EDT race. It is **not** a fix for the deeper off-EDT / Swing-threading issues (parent §3 / §6); those remain phase 2+ work. Once `activeThrottleFrame` is established, the guard is a no-op and production behavior is unchanged.

### 3.4 udev rule (local install only)

On the dev Pi:

```sh
sudo tee /etc/udev/rules.d/99-jmri-raildriver.rules >/dev/null <<'EOF'
# JMRI RailDriver Modern Desktop (P.I. Engineering) — phase 1 dev install
SUBSYSTEM=="usb",    ATTRS{idVendor}=="05f3", ATTRS{idProduct}=="00d2", \
    MODE="0660", GROUP="plugdev"
SUBSYSTEM=="hidraw", ATTRS{idVendor}=="05f3", ATTRS{idProduct}=="00d2", \
    MODE="0660", GROUP="plugdev"
EOF
sudo udevadm control --reload && sudo udevadm trigger
groups | grep -q plugdev || { sudo usermod -aG plugdev "$USER"; echo "Log out and back in for plugdev membership."; }
ls -l /dev/hidraw*   # confirm group plugdev
```

Phase 1 does **not** commit a `lib/linux/udev/99-jmri-raildriver.rules` file to the repo. That ship-with-distribution step is phase 2.

### 3.5 Build, install, launch

- `./build-and-install-to-jmri.sh` (or equivalent local build path).
- Plug in the RailDriver Modern Desktop.
- Launch `DecoderPro` or `PanelPro` on the Pi.
- In a second terminal: `tail -f ~/.jmri/log/messages.log`.

## 4. MVP acceptance criteria

1. `Debug → RailDriver Throttle (built in)` exists in the menu and is enabled.
2. Clicking it produces in `messages.log`:
   - `Got RailDriver hidDevice: ...` (existing log line on successful device open).
   - No `HidException`, no `UnsatisfiedLinkError`.
3. The controller's 7-segment display lights up `Pro` (existing `setLEDs("Pro")` behavior at line 133).
4. **Per-axis smoke** — sweep each lever in turn and confirm the corresponding `firePropertyChange("Value", Axis N, ...)` line appears in the log:
   - Reverser → `Axis 0`
   - Throttle / dynamic brake → `Axis 1`
   - Auto brake → `Axis 2`
   - Independent brake primary → `Axis 3`
   - Independent brake secondary / bail-off → `Axis 4`
   - Wiper rotary → `Axis 5`
   - Lights rotary → `Axis 6`

   Cross-reference to `control-inventory.md` for which byte each axis lives in. Phase 1 only verifies that movement on each axis produces *some* log activity at the expected `Axis N` name; range correctness, direction correctness, and per-position byte values are out of scope (parent §4.5 testing work).
5. **Per-button smoke** — press a representative sample of physical controls and confirm log shows `firePropertyChange("Value", "<n>", "1")` followed by `... "0"` per press:
   - Any front-edge button (parser slots 0..27)
   - SPDT #42 up and down (slots 28/29)
   - Hat #43 in each of the four cardinal directions (slots 30..33)
   - Range up/down (slots 34/35)
   - E-Stop up/down (slots 36/37)
   - Alert (slot 38), Sand (slot 39), P (slot 40), Bell (slot 41)
   - Horn up/down (slots 42/43)

   Cross-reference each `<n>` to `control-inventory.md` to confirm the press lands on the expected parser slot.
6. **Polling-thread liveness** — moving the same control twice in succession produces two distinct log entries (regression check on the §3.3 defensive guard; if the guard is missing or the EDT race kills the thread, only the first movement logs).
7. JMRI does not crash; the throttle window can be closed and the menu entry re-clicked within the same session without taking down the JVM.

## 5. Phase-1 deliverables

Commits:

1. `lib/hid4java-0.8.0.jar` — binary added.
2. `lib/hid4java-0.5.0.jar` — binary removed.
3. `pom.xml` — one-version bump.
4. `build.xml` — one `pathelement` update.
5. `java/src/apps/jmrit/DebugMenu.java` — one-line menu add (plus the existing `try/catch` placement caveat from parent §4.3 — the new `add(...)` goes **after** the catch).
6. `java/src/jmri/util/usb/RailDriverMenuItem.java` — one-line null guard at the top of the `case "Value":` block.

Out of band (not committed in phase 1):

- Local `/etc/udev/rules.d/99-jmri-raildriver.rules` on the Pi.

## 6. Known limitations accepted in phase 1

From parent §3 / §6, untouched here:

- **Polling-loop busy-spin** on `hidDevice.read() < 0`. If the controller is unplugged during the test session, expect one CPU core to peg at 100% until JMRI is closed.
- **Off-EDT Swing mutation** in `propertyChange`. Pre-existing; intermittent UI glitches possible.
- **`hidDeviceDetached` listener dormant** — `hidServices.start()` is commented out (parent §3, line 65 reference); device-detach during a session does not trigger any cleanup. Existing behavior; phase 1 does not change it.
- **Button 0 (slot 0) silently dropped** — the `fNum > 0` filter at line 744 means the first front-edge button never toggles a function. In phase 1's smoke test, pressing this button still produces the `firePropertyChange("Value", "0", "1")` log line (the polling thread emits it before any throttle dispatch), so the MVP can verify the bit fires; only the throttle-function dispatch is suppressed.
- **`activeThrottleFrame` may be null briefly** even with the §3.3 guard, during the EDT race window. The user-visible effect under phase 1 is that the very first event after menu-click may produce a polling-thread log line but no throttle dispatch. Acceptable for the MVP.

## 7. What unlocks phase 2

Phase 2 (and beyond) takes its scope from the parent plan §4.4 onward:

- Ship the udev rule sample at `lib/linux/udev/99-jmri-raildriver.rules` and confirm it rides the `package-linux` ant target into the Linux dist tarball (parent §4.4).
- Help-page updates: `help/en/html/hardware/raildriver/{index,details}.shtml` cross-linking `control-inventory.md`; French stub (parent §4.6).
- `scripts/diskimage-sign.sh:251` updated to sign both `darwin-aarch64/libhidapi.dylib` and `darwin-x86-64/libhidapi.dylib` paths (parent §4.2 / §6).
- `lib/bom-Java-Maven.spdx` regeneration.
- JUnit tests (parent §4.5): byte-parser test with the committed fixtures, firmware-unused-bits pin-down (slots 44–55), no-device behavior, LED-encoding test.
- Cross-platform verification by community testers (parent §5 #2 / #3): Windows, macOS, x86_64 Linux smoke.
- Latent-issue follow-ups (parent §3 / §6): busy-spin sleep/break on `read < 0`, EDT-aware dispatch via `SwingUtilities.invokeLater`, `hidDeviceDetached` interrupt of polling thread, button-0 → F0 mapping decision, "Gear vs Range" labelling cleanup, "P as Pantograph" assumption documentation in user-facing docs.

## 8. Cross-references

- Parent plan: [`plan.md`](plan.md) — full architecture and phase-2+ scope.
- Canonical bit-for-bit physical-control / HID-layout reference: [`control-inventory.md`](control-inventory.md) — used by §4 acceptance criteria.
- Native verification (glibc bound): parent §6 risk row.
- Test deferrals: parent §4.5 (all phase 2+).
