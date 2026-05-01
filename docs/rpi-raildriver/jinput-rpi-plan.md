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
`/dev/input/js*` (legacy joystick); it only suppresses a js* duplicate of an
event* device when the **names AND component lists match**
(`LinuxEnvironmentPlugin.java:206-238`). For a device whose evdev profile and
js profile differ, both controllers will appear.

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

For the device on this Pi (`Bus=0003 Vendor=05f3 Product=00d2`), the kernel
currently reports `EV=0x13` on `/dev/input/event4`, i.e. only `EV_SYN`,
`EV_KEY`, `EV_MSC` — **no `EV_ABS` bit** — and a separate `js0` handler
exists. This means the kernel HID-generic driver is not translating the
RailDriver's analog levers into evdev absolute axes. There is therefore a
real chance that JInput sees the device only as a button-bag.

* **Success — full:** JMRI's System Console emits `Component "<name>" value
  changed to <float>` lines where `<name>` includes axis-style identifiers
  (`x`, `y`, `z`, `rx`, `ry`, `rz`, `slider`, ...) or `Debug → USB Input
  Control` shows `Component.Identifier.Axis` entries for the RailDriver,
  AND moving each physical lever produces value changes on a corresponding
  axis component. Buttons and switches also produce `"0"`..`"N"` events.
* **Success — partial:** Only buttons/switches produce events; no axis
  components are exposed by either the event4-backed or js0-backed
  controller. Conclusion: JInput's `LinuxEnvironmentPlugin` cannot reach the
  analog levers without additional kernel-side work, and the next-step
  options listed in §6 apply.
* **Failure:** No RailDriver controller appears in JMRI's USB controller list
  at all. Conclusion: native library, JNI loading, device permissions, or
  `LinuxEnvironmentPlugin` itself is broken on this build. Diagnose with §4
  pre-flight checks before suspecting the device.

## 4. Pre-flight checks (before opening JMRI)

Run from a shell on the Pi, in any order:

1. `lsusb | grep -i raildriver` — confirm the device is enumerated by USB
   (expect `ID 05f3:00d2 PI Engineering, Inc. RailDriver Modern Desktop`).
2. `cat /proc/bus/input/devices` — locate the
   `N: Name="PI Engineering RailDriver Modern Desktop"` block, record its
   `Handlers=` line (expected: one `event*` and one `js*`) and its `EV=`
   bitmask. `EV` bit `0x08` (`EV_ABS`) being absent means evdev exposes no
   absolute axes.
3. `id` — confirm the user that will run JMRI is in the `input` group.
   `/dev/input/event*` and `/dev/input/js*` are owned `root:input` mode
   `0660`; without group membership JInput will silently see fewer or zero
   controllers.
4. `evtest /dev/input/eventN` (use the N from step 2) — operate every lever,
   button, and switch in turn. Record what kernel events fire (`EV_KEY`
   codes, any `EV_ABS` codes, `EV_MSC` `MSC_SCAN` values). This establishes
   the **kernel-visible** ground truth that JInput is bounded by.
5. If the `joystick` package is installed, run `jstest /dev/input/jsN` and
   operate the controls. If it is not installed, skip — do **not** rely on
   `cat /dev/input/jsN | xxd`, which gives unmapped raw frames and is hard
   to interpret.
6. Confirm the locally-built native library is in place:
   `ls -l /home/pi/JMRI/lib/linux/aarch64/libjinput-linux64.so` and
   `file` it to verify it is `aarch64` ELF. Rebuild via
   `/home/pi/jinput/build-and-install-to-jmri.sh` if missing or stale.

The RailDriver must be plugged in **before** JMRI starts. JMRI's `TreeModel`
(`TreeModel.java:255-280`) populates its controller list once during
`loadSystem()`; the `USBThrottle` Jynstrument builds its popup menu once
in `init()` (`USBThrottle.py:458-467`). A late hot-plug will not appear
without restarting JMRI.

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

1. Complete §4 pre-flight checks.
2. Start JMRI fresh (DecoderPro, PanelPro — anything that loads a profile is
   fine; the USB plumbing is profile-independent).
3. Open `Debug → USB Input Control`. Expand every `PI Engineering RailDriver
   Modern Desktop [...]` node. Record:
   * How many top-level RailDriver controllers appear (1 or 2 — both are
     plausible per `LinuxEnvironmentPlugin.java:206-238`).
   * For each, its `Controller.Type` (`STICK`, `GAMEPAD`, `UNKNOWN`, ...).
   * The full list of components: name, identifier `toString()`, and the
     identifier class as displayed (Axis vs Button vs Key vs POV).
   * Whether any axis component reacts to lever movement when you wiggle a
     lever in this window.
4. Open `Help → System Console` and clear it.
5. Open a throttle window (`Tools → Throttles → New Throttle`).
6. Add the USB Throttle Jynstrument: drag `USBControl.png` from the
   `USBThrottle.jyn` Jynstrument list onto the throttle toolbar.
7. Right-click the Jynstrument's icon. The popup menu should list every
   controller `TreeModel` discovered (one entry per controller — there may be
   two RailDriver entries; both will resolve to the same driver file).
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
      that appears, against the physical control that produced it.
9. Repeat step 8 for the second RailDriver entry, if `TreeModel` exposed two.

## 7. Outcome interpretation

Compare the table from step 8c against the kernel ground-truth from §4 step
4 (`evtest`):

* If `evtest` showed only `EV_KEY` events for the levers and JMRI's console
  shows the same buttons but no axis events, this is the expected
  consequence of `EV=0x13`: the kernel does not surface lever positions, so
  JInput cannot either. Result: **partial success** — buttons/switches read
  fine, analog axes are unreachable through the current Linux JInput stack.
* If `evtest` showed `EV_ABS` events for the levers, there is a kernel-side
  axis path and JMRI/JInput should show them too; if it does not, the bug is
  in `LinuxEventDevice` / `libjinput-linux64.so`, not the kernel.
* If `jstest` (when available) showed axes that `evtest` did not, JInput
  may be able to reach them through the js0-backed controller; that is why
  step 8 must be repeated for every RailDriver entry exposed by `TreeModel`.

## 8. Next-step options if analog axes are not reachable

(Listed for context only — out of scope for this MVP, no implementation
work happens here.)

* Add a kernel HID quirk / report-descriptor fixup for vendor `05f3`
  product `00d2` so the analog bytes are mapped to `ABS_*` codes by
  `hid-input.c`. This is the smallest change with the broadest payoff.
* Run a userspace daemon that opens `/dev/hidraw0`, parses RailDriver
  reports, and re-publishes them via `uinput` as a synthetic joystick that
  JInput can then see normally.
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
