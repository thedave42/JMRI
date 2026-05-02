# RailDriver Phase 2 — Wire existing mappings to a real train

> **Parent plan:** [`plan.md`](plan.md)
> **Predecessor:** [`plan-impl-phase1.md`](plan-impl-phase1.md) (connect & verify MVP — already merged)
> **Branch:** `rpi-raildriver`
> **Target platform:** Raspberry Pi 4, 64-bit Raspberry Pi OS / Debian 12+, JMRI 5.15.x.
> **Phase 2 goal:** verify that every RailDriver control already wired in `RailDriverMenuItem.propertyChange` actually does the expected thing on a live JMRI throttle controlling a real DCC locomotive. Eliminate the X11 button-event warnings surfaced during phase 1 testing.

## 1. Scope

### In scope

1. **udev mitigation for the kernel input-subsystem leakage** that produced the `IllegalArgumentException: Nonexistent button N` X11 warnings during phase 1 testing. Tell the kernel/X server to ignore the RailDriver as an input device while leaving `/dev/hidraw0` untouched.
2. Optionally commit a sample udev rule at `lib/linux/udev/99-jmri-raildriver.rules` so it rides into the Linux dist tarball (parent §4.4 — partial; phase 2 is the right time since we already need to update the rule).
3. Live-train hardware verification of every control whose mapping already exists in the Java code:
   - **Reverser (Axis 0)** — direction control.
   - **Throttle (Axis 1, forward half)** — speed control.
   - **E-Stop switch (slots 36/37)** — emergency stop.
   - **Bell (slot 41)** — F1.
   - **Horn (slots 42/43)** — F2.
   - **Range switch (slots 34/35)** — F3 OFF/ON.
   - **Alert (slot 38)** — F6.
   - **Sand (slot 39)** — F7.
   - **P (slot 40)** — F8.
   - **Front-edge buttons (slots 1..27)** — F1..F27 by parser slot.
   - **SPDT #42 (slots 28/29)** — `selectRosterEntry()` / `dispatchAddress()`.
   - **Hat #43 (slots 30..33)** — roster-entry navigation + throttle-frame navigation.

### Out of scope (deferred to phase 3+)

- **Axis 2 (Auto Brake)** — currently logs only; functional mapping deferred to phase 3.
- **Axis 3 (Independent Brake primary)** — currently logs only; deferred.
- **Axis 4 (Independent Brake secondary / Bail-off)** — currently logs only; deferred.
- **Axis 5 (Wiper rotary)** — currently logs only; deferred. Also: Java's source comment / `log.info` label says "HEADLIGHT" — the inventory says byte 5 = Wiper. **The label swap will be fixed when this axis is wired in phase 3, not in phase 2.**
- **Axis 6 (Lights rotary)** — currently logs only; deferred. Java's comment says "WIPER" — inventory says byte 6 = Lights. Same swap-fix-in-phase-3 note.
- **Axis 1 dynamic-brake side** (lever above center / negative values) — `RailDriverMenuItem.java:587` has a `//TODO: dynamic braking` and only sets the LED to `DBr`; no throttle-side action. Deferred.
- **Front-edge button slot 0 silently dropped** by the `fNum > 0` filter — deferred (one of 28 user-assignable buttons; mapping decision tied to whatever phase 3 does with Axis 6 / F0 / headlight).
- **Hat #43 and SPDT #42 are hard-coded** to navigation/dispatch even though `control-inventory.md` marks them user-assignable. Phase 2 verifies the existing hard-coded behavior; making them user-configurable is a later phase.
- All latent issues already documented in parent §3 / §6: busy-spin on read failure, off-EDT Swing mutation, dormant `hidDeviceDetached` listener, "Range vs Gear" / "P likely Pantograph" labelling, etc.
- JUnit tests (parent §4.5) — phase 3+.
- Help / documentation updates (parent §4.6) — phase 3+.
- Cross-platform verification (Windows, macOS, x86_64 Linux) — community testers.
- macOS dist signing script update + SBOM regeneration — release-engineering work.

## 2. What we are *not* changing in code

This is the key shape of phase 2: **no Java source edits.** The Java mapping audit done after phase 1 confirmed that every control listed in §1 already has working dispatch logic in `RailDriverMenuItem.propertyChange` (`RailDriverMenuItem.java:482–765`). Phase 2 exercises that existing logic against a real loco; it does not extend it.

The only file changes in phase 2 are:

- `/etc/udev/rules.d/99-jmri-raildriver.rules` (live system file on the dev Pi).
- Optionally `lib/linux/udev/99-jmri-raildriver.rules` (new file in the repo; sample-only, not loaded by the JVM).

## 3. Steps

### 3.1 udev mitigation for kernel input-subsystem leakage

**Background.** During phase 1 testing, pressing RailDriver buttons produced `java.lang.IllegalArgumentException: Nonexistent button 17` warnings on the AWT-XAWT thread (every stack frame in `java.desktop/sun.awt.X11.*`, none in `jmri.*`). Cause: the Linux kernel's `hid-generic` driver double-exposes the RailDriver as both `/dev/hidraw0` (which JMRI reads correctly) and `/dev/input/event4` (which the X server interprets as a mouse with extended-range button numbers that exceed `MouseEvent`'s accepted range). The leakage is independent of JMRI.

**Fix.** Add an `input` subsystem rule that strips the kernel-input flags from any `/dev/input/event*` node belonging to the RailDriver, so libinput / X11 stop claiming it as an input device. Hidraw access is unaffected.

Updated `/etc/udev/rules.d/99-jmri-raildriver.rules`:

```
# JMRI RailDriver Modern Desktop (P.I. Engineering)
#
# 1. Make the raw HID node group-readable for plugdev (this is what JMRI
#    actually opens via hid4java).
# 2. Make the legacy USB node group-readable (covers the unrelated
#    javax.usb path used by JMRI's Debug -> USB Browser tool).
# 3. Tell libinput / X11 to ignore the kernel-synthesised input device
#    so RailDriver button presses don't generate spurious mouse events
#    (and the resulting "Nonexistent button N" AWT exceptions).

SUBSYSTEM=="usb",    ATTRS{idVendor}=="05f3", ATTRS{idProduct}=="00d2", \
    MODE="0660", GROUP="plugdev"
SUBSYSTEM=="hidraw", ATTRS{idVendor}=="05f3", ATTRS{idProduct}=="00d2", \
    MODE="0660", GROUP="plugdev"
SUBSYSTEM=="input",  ATTRS{idVendor}=="05f3", ATTRS{idProduct}=="00d2", \
    ENV{ID_INPUT}="0", ENV{ID_INPUT_MOUSE}="0", ENV{ID_INPUT_KEY}="0", \
    ENV{LIBINPUT_IGNORE_DEVICE}="1"
```

Apply on the dev Pi:

```sh
sudo tee /etc/udev/rules.d/99-jmri-raildriver.rules >/dev/null <<'EOF'
# (rule contents above)
EOF
sudo udevadm control --reload
sudo udevadm trigger
# unplug and re-plug the RailDriver to force re-evaluation
```

**Verification (no JMRI needed):**

- `ls /dev/input/by-id/ 2>/dev/null | grep -i rail` should show no entry, or
- `xinput list 2>/dev/null | grep -i rail` should show nothing, or
- `udevadm info -q property /dev/input/event4 | grep ID_INPUT` should show `ID_INPUT=0`.

If any of those still report the RailDriver as an input device after a re-plug, the rule isn't matching — re-check `idVendor`/`idProduct` against `lsusb -d 05f3:00d2`.

### 3.2 Optional: ship the udev rule with the distribution

Commit `lib/linux/udev/99-jmri-raildriver.rules` (verbatim contents from §3.1) so it rides into the Linux dist tarball via the existing `package-linux` ant target (parent §4.4 — `build.xml:2787–2790` already copies `${libdir}/linux/` recursively). Users installing the dist would need to copy it to `/etc/udev/rules.d/` themselves; phase 2 does not add an installer script.

This step is **optional in phase 2** — the phase-1 dev Pi gets the rule via §3.1 regardless. Skip if you want to keep phase 2 narrow; add later as part of the help-docs / packaging push.

### 3.3 Pre-test setup (real-loco environment)

1. Confirm DCC system is connected and JMRI sees it (`PowerControlButton` reports `ON`).
2. Pick a roster entry for a decoder-equipped locomotive and note its function map (which F-numbers do what — bell, horn, light, etc.). The Java mapping (per `RailDriverMenuItem.java:482–765`) is fixed; the *visible effect* depends on the loco's function definitions.
3. Open a Throttle window via `Actions → New Throttle`, dispatch the loco's address.
4. Confirm the throttle frame can drive the loco from its on-screen controls (tap the speed slider; tap F1; etc.). If the on-screen throttle doesn't work, the RailDriver test won't either — fix DCC / decoder issues first.
5. Click `Debug → RailDriver Throttle (built in)`. Confirm `Got RailDriver hidDevice: ...` in `~/.jmri/log/messages.log` and `Pro` on the 7-segment display.

### 3.4 Per-control verification

Tail the log in a separate terminal:
```
tail -f ~/.jmri/log/messages.log
```

For each test below, **the polling-thread `firePropertyChange` log line is the empirical confirmation that the device produced the event**; the *real* phase-2 acceptance is that JMRI then drives the loco / throttle window as expected.

| # | Control | Action | Expected JMRI/loco behavior |
|---|---|---|---|
| 1 | Reverser (Axis 0) | Lever to FORWARD | Throttle window's direction indicator → forward; loco moves forward when speed > 0 |
| 2 | Reverser | Lever to REVERSE | Direction indicator → reverse; loco moves reverse when speed > 0 |
| 3 | Reverser | Lever to NEUTRAL | Direction unchanged (Java only switches when value crosses 0.45/0.55 thresholds; centre is a deadband) |
| 4 | Throttle (Axis 1) | Sweep idle → full forward | Throttle window speed slider tracks the lever; loco accelerates |
| 5 | Throttle | Lift past centre into dyn-brake half | LED display shows `DBr`; loco speed does **not** change (TODO in code; deferred to phase 3) |
| 6 | E-Stop (slots 36/37) | Flip up or down | Loco emergency-stops (`setSpeedSetting(-1)`); throttle slider goes to 0 |
| 7 | Bell (slot 41) | Press | F1 toggles in throttle window; bell sound plays if loco's F1 = bell |
| 8 | Horn (slots 42/43) | Press up or down | F2 toggles; horn plays if loco's F2 = horn |
| 9 | Range (slots 34/35) | Switch up | F3 → OFF in throttle window |
| 10 | Range | Switch down | F3 → ON in throttle window |
| 11 | Alert (slot 38) | Press | F6 toggles |
| 12 | Sand (slot 39) | Press | F7 toggles |
| 13 | P (slot 40) | Press | F8 toggles (likely no-op on a diesel; expected) |
| 14 | Front-edge buttons | Press 3–4 representative buttons across the 28-button grid | Corresponding F1..F27 toggle in throttle window. Buttons mapped to F-numbers above the loco's defined function count silently no-op (per `fNum < throttle.getFunctions().length` guard) — note that's expected. |
| 15 | Front-edge slot 0 | Press the back-row col-01 front-edge button | Polling thread emits `firePropertyChange("Value", 0, "1")` log line; **no F-toggle** (per phase-1 known limitation; slot 0 is dropped by the `fNum > 0` filter). |
| 16 | SPDT #42 up (slot 28) | Toggle up | `selectRosterEntry()` populates address panel with currently-selected roster entry; LED shows `sel <addr>` |
| 17 | SPDT #42 down (slot 29) | Toggle down | `dispatchAddress()` releases the address; LED shows `dis <addr>` |
| 18 | Hat #43 up (slot 30) | Press | Address-panel roster combo selects the previous entry; LED shows `Prev N` |
| 19 | Hat #43 down (slot 32) | Press | Roster combo selects the next entry; LED shows `Next N` |
| 20 | Hat #43 right (slot 31) | Press | Switches to next throttle frame (if more than one open); LED shows `NXT` |
| 21 | Hat #43 left (slot 33) | Press | Switches to previous throttle frame; LED shows `PRE` |

For analog controls (#1–#5), repeat each sweep at least twice to confirm the polling thread did not die mid-test (the phase-1 EDT-race null-guard regression check).

### 3.5 X11-warning acceptance

Re-run §3.4 with `tail -f ~/.jmri/log/messages.log | grep -E 'Nonexistent|XToolkit'` in a third terminal. **Expected: no matches.** If matches still appear, the udev rule didn't take — re-apply §3.1 and re-plug the controller before continuing.

## 4. Phase 2 acceptance criteria

1. `/etc/udev/rules.d/99-jmri-raildriver.rules` matches §3.1 exactly; `udevadm info -q property /dev/input/event*` reports `ID_INPUT=0` for the RailDriver's event node (or no event node exists).
2. After re-plugging the controller and launching `DecoderPro` / `PanelPro`:
   - `Got RailDriver hidDevice: ...` appears in `messages.log`.
   - `Pro` lights up on the 7-segment display.
3. Every numbered row in §3.4 produces the documented behavior on a real DCC loco. Items #5 and #15 produce the *non-action* described (LED-only / no F-toggle) — those are documented expectations, not failures.
4. `messages.log` for the test session contains:
   - Zero `IllegalArgumentException: Nonexistent button N` warnings.
   - Zero stack frames in `jmri.util.usb.RailDriverMenuItem.*` from any exception.
   - Zero `NullPointerException` involving `activeThrottleFrame` / `getAddressPanel`.
5. The polling thread remains alive across the full test session (≥ 5 minutes of intermittent control activity); moving the same control twice late in the session still produces a log entry and the documented action.

## 5. Phase 2 deliverables

**Required:**
1. Updated `/etc/udev/rules.d/99-jmri-raildriver.rules` on the dev Pi (live system file; not a repo commit).
2. A signed-off run log (or session notes) covering every row in §3.4 against a real locomotive.

**Optional (commitable):**
3. `lib/linux/udev/99-jmri-raildriver.rules` (new file) — the same rule, committed to the repo as a sample for the dist tarball (per §3.2). One file added; no code changes.

No changes to:
- `RailDriverMenuItem.java`
- `DebugMenu.java`
- `pom.xml`, `build.xml`
- Any test, help, or build artifact

## 6. Known phase-2 limitations (accepted)

- **Test #5 (dynamic-brake half of throttle) is a pass-as-no-op.** The Java code has a TODO; loco speed will not change when the lever is past centre. Documented expectation.
- **Test #15 (slot 0) is a pass-as-no-op.** F0 is not toggled. Documented expectation.
- **Tests for axes 2/3/4/5/6 are not in §3.4.** Those axes still log to `messages.log` (`AUTOBRAKE value: ...`, `INDEPENDBRK value: ...`, `BAILOFF value: ...`, `HEADLIGHT value: ...` — note Java's HEADLIGHT label is on byte-5 = inventory's *Wiper*, swapped relative to the inventory; phase 3 fixes the label, phase 3 wires the behavior).
- **Hat #43 and SPDT #42 hard-coded.** Inventory marks them user-assignable; phase 2 verifies only the existing hard-coded behavior. User-configurability is a later phase.
- **All latent issues from parent §3 / §6 untouched** (busy-spin, off-EDT Swing mutation, hidDeviceDetached, button-0 drop). Same status as phase 1.

## 7. What unlocks phase 3

Phase 3 picks up the per-axis work explicitly deferred in §1:

- Implement Axis 2 (Auto Brake), Axis 3 (Indep Brake primary), Axis 4 (Bail-off). Design decision needed: function-key mapping vs. direct speed-clamp vs. extended throttle-API.
- Implement Axis 5 (Wiper rotary) and Axis 6 (Lights rotary). Fix the HEADLIGHT/WIPER label swap as part of the implementation. Likely targets: Wiper → an F-key; Lights → F0 with off/dim/full driving headlight states.
- Decide slot-0 → F0 policy (collides with Lights → F0 if both adopted).
- Implement dynamic-brake side of Axis 1.

Phase 4+ takes the remaining parent-plan items: tests (parent §4.5), help docs (parent §4.6), cross-platform release-engineering work (parent §4.2 / §6 — macOS signing, SBOM, glibc-bound mitigation if the bundled aarch64 native's `GLIBC_2.33`/`GLIBC_2.34` proves a problem on bullseye Pi OS), and the latent-issue fixes from parent §3 / §6.

## 8. Cross-references

- Parent: [`plan.md`](plan.md)
- Phase 1: [`plan-impl-phase1.md`](plan-impl-phase1.md)
- Canonical bit-for-bit map: [`control-inventory.md`](control-inventory.md)
- Java mapping audit (the source for §1's "what's already mapped" list): the conversation following phase 1 acceptance; key reference is `RailDriverMenuItem.java:482–765`.
