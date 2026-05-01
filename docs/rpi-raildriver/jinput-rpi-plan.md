# JInput on Raspberry Pi 4 — RailDriver MVP plan

## 1. Problem statement

Find out, on a Raspberry Pi 4 (aarch64) running Linux, whether the **upstream
JInput library** (`net.java.jinput:jinput-parent:2.0.11-SNAPSHOT`, built locally
from `/home/pi/jinput`) can read the analog axes, buttons, and switches of a
PI Engineering RailDriver Modern Desktop USB controller through JMRI's existing
USB plumbing (`jmri.jmrix.jinput.TreeModel`).

The deliverable is a new Jython driver file:

    /home/pi/jmri-src/jython/Jynstruments/ThrottleWindowToolBar/USBThrottle.jyn/PIEngineeringRailDriverModernDesktop.py

modelled on the existing Windows-only `RailDriverModernDesktop.py`, but
adapted to how JInput actually identifies components on Linux. The MVP only
prints the events it receives to JMRI's System Console; it does not act on a
throttle. That print stream is what answers the question.

## 2. Approach

The existing `RailDriverModernDesktop.py` was written for a Windows-only fork of
JInput that exposed a `HidRawEnvironmentPlugin`. That plugin **does not exist
in upstream JInput 2.0.11-SNAPSHOT** (verified: `grep -r HidRaw /home/pi/jinput`
returns no matches). On Linux ARM64 the only available plugin is
`net.java.games.input.LinuxEnvironmentPlugin` (`/home/pi/jinput/plugins/linux/...`),
backed by the locally-built `libjinput-linux64.so` already installed at
`/home/pi/JMRI/lib/linux/aarch64/libjinput-linux64.so`.

`LinuxEnvironmentPlugin` enumerates `/dev/input/event*` (evdev) and
`/dev/input/js*` (legacy joystick); it only suppresses a js* duplicate of
an event* device when the **names match AND component count matches AND
component identifiers match in order** (`LinuxEnvironmentPlugin.java:213-232`).
For this RailDriver, both nodes report the same name and 56 buttons each,
but `LinuxJoystickDevice` numbers buttons sequentially as
`Button._0..Button._55` (`LinuxEnvironmentPlugin.java:245+` — the
`getButtonIdentifier(int)` switch), whereas `LinuxEventDevice` maps each
evdev `BTN_*` code through `LinuxNativeTypesMap` (so `BTN_TRIGGER` →
`Button.TRIGGER`, `BTN_THUMB` → `Button.THUMB`, etc.). Identifiers will
not line up, dedupe will not trigger, and JMRI will see **two `PI
Engineering RailDriver Modern Desktop` controllers** — both routed to the
same driver file by `USBThrottle.formatDriverName()`.

Component naming on Linux is also different from Windows. `LinuxComponent`
(`LinuxComponent.java:50-53`) sets the component's name to
`Identifier.getName()`, which for `Component.Identifier.Axis` constants
(`Component.java:131-279`) is lowercase strings like `x`, `y`, `z`, `rx`,
`ry`, `rz`, `slider`, `pov`, `unknown`, and for `Identifier.Button._N`
(`Component.java:291-349`) is plain integer strings `"0"`..`"31"`. The
existing Windows driver's component strings (`"31"`, `"36"`, `"Axis 0"`,
`"Axis 1"`, ...) are DirectInput-style and will not match anything emitted
by JInput on Linux.

JMRI's `TreeModel` already polls every controller every 20 ms in a worker
thread (`TreeModel.java:130-191`) and re-fires each event as a
`PropertyChangeEvent` named `"Value"` on the Swing thread
(`TreeModel.java:206-247`). `USBThrottle.py:54-59` already prints
`Component "<name>" value changed to <value>` for every event whose
`getController()` matches the controller selected from the Jynstrument popup
menu. **That existing print is the data stream the MVP relies on.**

The new driver file therefore needs to do almost nothing beyond:

* Be importable under the exact name `PIEngineeringRailDriverModernDesktop`,
  which is what `USBThrottle.formatDriverName()` (`USBThrottle.py:527-537`)
  produces from the kernel-reported device name `"PI Engineering RailDriver
  Modern Desktop"` (verified in `/proc/bus/input/devices` on the target Pi).
* Expose a `USBDriver` class with the same attribute surface that
  `USBThrottle.py:62-407` reads from `self.driver.*`. Every attribute is set
  to a value that **cannot** match any real Linux JInput component
  (empty string for component names, neutral defaults for values), so no
  throttle/function actions ever fire while we observe the device.

No extra `PropertyChangeListener` is registered from the driver. (Considered
and rejected: the driver does not receive the selected `Controller` instance,
so it cannot disambiguate when JInput exposes two same-name controllers, and
`USBThrottle.py:494-525` has no driver-side cleanup hook so any listener
would leak across re-selections.) Per-component metadata (analog vs. digital,
identifier class, dead zone, etc.) is read from JMRI's `Debug → USB Input
Control` window, which uses the same `TreeModel` and shows everything
JInput discovered without us having to add code.

## 3. Success / failure criteria

The kernel-side ground truth on this Pi has been measured (see §4): the
RailDriver appears as `event4` (`EV=0x13` — `EV_SYN | EV_KEY | EV_MSC`, no
`EV_ABS`) and `js0` (declared `0 axes ()` and `56 buttons`). Operating the
analog levers produces **zero events** on either node; only the cab buttons
fire. JInput's `LinuxEnvironmentPlugin` cannot read events that the kernel
does not emit, so the analog levers are unreachable through the upstream
JInput Linux stack on this kernel. The MVP test is therefore not asking
"can JInput read the levers?" (the answer is already known to be no on this
kernel), it is **independently confirming** that fact end-to-end through
JMRI's USB plumbing, and verifying that the buttons work.

* **Expected outcome (success):** JMRI's System Console emits one
  `Component "<name>" value changed to <float>` line per cab-button press
  and release for the selected RailDriver controller, with `<name>` being
  the lowercase JInput identifier name (`"0"`..`"9"`, `Trigger`, `Thumb`,
  `Top`, `Pinkie`, `Base 1`..`Base 6`, `A`/`B`/`C`/`X`/`Y`/`Z`, `Left
  Thumb`/`Right Thumb`, etc. — matched against the constants in
  `Component.java:283-502`). Operating any analog lever produces no events.
  `Debug → USB Input Control` shows the RailDriver controller(s) with
  button components only and zero axis components. This confirms the
  Linux/JInput/JMRI plumbing works correctly and bounds the analog gap to
  the kernel.
* **Surprise outcome (full success):** Any axis-style component
  (`Identifier.Axis.*`) appears in `Debug → USB Input Control` for the
  RailDriver, or moving a lever produces a `Component "x"` (or similar)
  value-changed line. This would contradict the §4 measurements and means
  either the kernel HID profile changed since §4 or `libjinput-linux64.so`
  is reading additional bytes that `evtest`/`jstest` ignore. Investigate.
* **Failure:** No RailDriver controller appears in JMRI's USB controller
  list at all, or the buttons do not produce events. Conclusion: native
  library load, JNI binding, device permissions, or `LinuxEnvironmentPlugin`
  itself is broken on this build. Re-run §4 pre-flight checks first; if
  those still pass, the regression is in the Java side
  (`libjinput-linux64.so`, `LinuxEnvironmentPlugin`, or JMRI's `TreeModel`).

## 4. Pre-flight checks — measured baseline

Already executed on the target Pi. Recorded here as the ground truth the
MVP measures itself against.

| Check | Result |
|---|---|
| `lsusb` | `Bus 001 Device 006: ID 05f3:00d2 PI Engineering, Inc. RailDriver Modern Desktop` |
| `/proc/bus/input/devices` block | `Name="PI Engineering RailDriver Modern Desktop"`, `Bus=0003 Vendor=05f3 Product=00d2 Version=0100`, `Handlers=event4 js0`, `EV=13` (= `EV_SYN | EV_KEY | EV_MSC`; **no `EV_ABS`**), `KEY=ffffffffffffff 0 0 0 0` (56 button bits) |
| `id pi` | `groups=...,996(input),...` ✅ in `input` group |
| Device node perms | `/dev/input/event4` and `/dev/input/js0` both `crw-rw---- root:input` (mode 0660). `/dev/hidraw0` is `crw-rw---- root:plugdev` (mode 0660) and `pi` is in `plugdev`. |
| `evtest` | installed at `/usr/bin/evtest` |
| `jstest` (`joystick` package) | installed at `/usr/bin/jstest` |
| `libjinput-linux64.so` | `/home/pi/JMRI/lib/linux/aarch64/libjinput-linux64.so`, ELF aarch64, 67 488 bytes |

### Live event capture results

* **`evtest /dev/input/event4`** — declared support: 56 keys (`BTN_0..BTN_9`,
  six unnamed codes 266-271, `BTN_LEFT..BTN_TASK`, eight unnamed codes
  280-287, `BTN_TRIGGER..BTN_BASE6`, three unnamed codes 300-302, `BTN_DEAD`,
  `BTN_SOUTH..BTN_TR`) plus `EV_MSC MSC_SCAN`. **No `EV_ABS` codes
  declared.** Pressing cab buttons fires correct `MSC_SCAN` + `EV_KEY`
  press/release pairs (e.g. `BTN_2`, `BTN_4`, `BTN_5`, `BTN_6` produced
  scan codes 0x90003, 0x90005, 0x90006, 0x90007). **Sweeping the reverser,
  throttle, auto-brake, and dynamic-brake levers full range produced zero
  events of any kind.**
* **`jstest --event /dev/input/js0`** — declared `0 axes ()` and 56 buttons.
  Initial-state `type 129` snapshot (`JS_EVENT_INIT | JS_EVENT_BUTTON`)
  shows buttons 16, 18, 20, 21 as `value 1` while all others are `value 0`
  — these correspond to the latching switches that were physically in the
  "on" position when jstest opened the device. Pressing cab buttons fires
  correct `type 1` (button) events with press/release value pairs. **Moving
  any analog lever produced zero `type 2` (axis) events.**

### What this baseline implies

The kernel HID-generic driver does not surface any analog data from this
device on either evdev or the legacy joystick interface — the bytes in the
HID input report that carry lever positions are not mapped to any kernel
input code. JInput's `LinuxEnvironmentPlugin` therefore cannot read the
analog levers regardless of which device node it picks. It will read the
56 buttons cleanly. The remaining MVP work is to confirm that the
JInput → JMRI → Jython chain transports those button events end-to-end and
to record exactly which JInput identifier names the buttons land under.

### Re-run trigger

Re-execute §4 if any of the following change: kernel upgrade, USB
re-plug into a different port, JMRI native library rebuild, addition of a
udev rule for `05f3:00d2`, or installation of a vendor/quirk kernel module.

## 5. Driver implementation (the MVP)

File: `/home/pi/jmri-src/jython/Jynstruments/ThrottleWindowToolBar/USBThrottle.jyn/PIEngineeringRailDriverModernDesktop.py`

Structure:

* Top-of-file comment block stating: target device, jinput version, the two
  Linux observations driving the design (no `HidRawEnvironmentPlugin`;
  Linux-style component names), what the file is for (passive observation
  only — does not drive a throttle), and how to read its output.
* `print` banner on import that includes the file name, jinput plugin name
  expected (`LinuxEnvironmentPlugin`), and a one-line reminder that this
  driver does NOT bind any throttle action.
* `class USBDriver:` with `__init__` that:
  * Sets every `componentXxx` field that `USBThrottle.py` reads
    (`USBThrottle.py:62-407` — full list: `componentNextThrottleFrame`,
    `componentPreviousThrottleFrame`, `componentNextRunningThrottleFrame`,
    `componentPreviousRunningThrottleFrame`, `componentNextRosterBrowse`,
    `componentPreviousRosterBrowse`, `componentRosterSelect`,
    `componentThrottleRelease`, `componentSpeed`, `componentSpeedSet`,
    `componentSpeedIncrease`, `componentSpeedDecrease`,
    `componentDirectionForward`, `componentDirectionBackward`,
    `componentDirectionSwitch`, `componentEStopSpeed`,
    `componentEStopSpeedBis`, `componentStopSpeed`, `componentSlowSpeed`,
    `componentCruiseSpeed`, `componentMaxSpeed`, `componentF0` ..
    `componentF29`) to the empty string `""`.
  * Sets the matching `valueXxx` / `valueXxxOff` fields and the analog tuning
    fields (`valueSpeedTrigger`, `componentSpeedMultiplier`,
    `valueSpeedSetMinValue`, `valueSpeedSetMaxValue`) to harmless defaults
    that match the existing Windows driver's defaults, so even if a stray
    component string ever did match by accident, no destructive throttle
    action would fire.
* No imports of `jmri.jmrix.jinput.TreeModel`, no `PropertyChangeListener`,
  no threads. The driver is a pure config bag.

The actual per-event console output comes from the existing
`USBThrottle.py:59` line:

    print "Component \""+component+"\" value changed to ",value

Selecting the RailDriver from the Jynstrument's popup menu sets
`self.desiredController` and from then on every poll-loop event for that
specific controller object is printed. Output appears in JMRI's
`Help → System Console` (which captures stdout/stderr).

## 6. Test procedure

1. Re-run §4 only if a §4 re-run trigger has fired; otherwise the baseline
   stands.
2. Start JMRI fresh (DecoderPro, PanelPro — anything that loads a profile is
   fine; the USB plumbing is profile-independent). The RailDriver must be
   plugged in **before** JMRI starts: `TreeModel`
   (`TreeModel.java:255-280`) populates its controller list once during
   `loadSystem()` and `USBThrottle` builds its popup menu once in `init()`
   (`USBThrottle.py:458-467`).
3. Open `Debug → USB Input Control`. Per §2 we expect **two** top-level
   `PI Engineering RailDriver Modern Desktop [...]` controllers (one
   evdev-backed, one js-backed) because identifier sets do not line up.
   Expand each and record:
   * Top-level node text (which includes `Controller.Type`).
   * The full list of components: name, identifier `toString()`, and the
     identifier class as displayed (Axis vs Button vs Key vs POV).
   * Axis count for each — per §4 baseline this should be **zero** on both,
     but recording it confirms JInput agrees with the kernel.
   * Wiggle each lever in this window — confirm no axis component appears
     or moves (negative confirmation).
4. Open `Help → System Console` and clear it.
5. Open a throttle window (`Tools → Throttles → New Throttle`).
6. Add the USB Throttle Jynstrument: drag `USBControl.png` from the
   `USBThrottle.jyn` Jynstrument list onto the throttle toolbar.
7. Right-click the Jynstrument's icon. The popup menu should list every
   controller `TreeModel` discovered. The two RailDriver entries will have
   identical visible names; both will resolve to the same driver file.
8. For each RailDriver entry in turn:
   a. Select it. Verify the System Console shows
      `Trying to import driver by name "PIEngineeringRailDriverModernDesktop.py" ...`
      followed by `...driver "PIEngineeringRailDriverModernDesktop.py" imported`
      (`USBThrottle.py:506-510`). If instead it says "driver by name not
      found" and falls through to type-based or `Default.py` import, the
      file name or its `USBDriver` class is wrong — fix and retry.
   b. Operate every physical control on the RailDriver in turn:
      reverser lever (full range), throttle lever (full range), auto-brake
      lever, independent-brake lever, bail-off lever, dynamic-brake lever,
      wiper rocker, lights rocker, three-way reverser switch (if present),
      every numbered cab button, the alerter / sander / pantograph / bell
      buttons, the horn lever (down and up), the e-stop strip, the four-way
      pad, the zoom rocker, the range / gear toggle, the headlight rotary
      (if present).
   c. Tabulate every distinct `Component "<name>" value changed to <v>` line
      that appears, against the physical control that produced it. Per the
      §4 baseline, expect button events only and zero events from the
      analog levers.

## 7. Outcome interpretation

Compare the table from step 8c against the §4 baseline:

* **Expected:** Both RailDriver entries emit button events for cab-button
  presses and emit nothing for lever movement. The two entries will
  emit *different identifier names* for the same physical button — the
  evdev-backed entry uses the `Identifier.Button` constants that
  `LinuxNativeTypesMap` chose for each `BTN_*` code (e.g.
  `Trigger`/`Thumb`/`Top`/`Pinkie`/`A`/`B`/...), while the js-backed entry
  uses sequential `"0"`..`"55"` (`getButtonIdentifier`). Recording both
  mappings is the most useful artifact this MVP produces — it is the
  reference table any future RailDriver Linux driver will need.
* **Surprise — axis on either entry:** any axis component reacting to
  lever movement contradicts the §4 baseline. Re-check kernel state with
  `evtest` and `jstest` immediately, and treat as a §3 surprise outcome.
* **No events at all from JMRI:** native library load, JNI binding, or
  TreeModel polling is broken. Check the System Console for stack traces
  on JMRI startup; verify `libjinput-linux64.so` actually got loaded
  (`net.java.games.input.LinuxEnvironmentPlugin` log line).

## 8. Next-step options if analog axes are not reachable

(Listed for context only — out of scope for this MVP, no implementation
work happens here. Any of these would be its own follow-up plan.)

* Add a kernel HID quirk / report-descriptor fixup for vendor `05f3`
  product `00d2` so the analog bytes are mapped to `ABS_*` codes by
  `hid-input.c`. Smallest change with the broadest payoff because it lets
  every userspace input consumer (JInput, SDL, browser Gamepad API, ...)
  read the levers without further work.
* Run a userspace daemon that opens `/dev/hidraw0`, parses RailDriver
  reports, and re-publishes them via `uinput` as a synthetic joystick that
  JInput can then see normally. **`/dev/hidraw0` is already accessible to
  the `pi` user via the `plugdev` group** (verified in §4), so this path
  needs no privilege escalation beyond starting the daemon as `pi`.
* Add an upstream-quality `HidRawEnvironmentPlugin` (or equivalent) to
  JInput itself so RailDriver-style devices can be read directly without a
  kernel quirk. Largest effort.

## 9. Out of scope

* Any throttle / function / direction wiring. The MVP is observation-only.
* Calibration, dead-zone tuning, double-tap behaviour, LED display output.
* Any modification of `USBThrottle.py`, `TreeModel.java`, or the JInput
  Linux native code.
* Any work in the two existing plan files in this folder
  (`capture-plan.md`, `plan.md`) — both are deliberately untouched.
