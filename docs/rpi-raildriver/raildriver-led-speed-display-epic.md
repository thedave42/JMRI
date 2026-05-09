# Epic: RailDriver LED Speed Display

## 1. Epic Name

**RailDriver LED Speed Display — Context-Aware 7-Segment Feedback**

## 2. Goal

### Problem

The RailDriver Modern Desktop controller has a 3-digit 7-segment LED display mounted at the top
center of the console, directly in the operator's line of sight. This display is the operator's
primary hardware feedback indicator — it's the only output device on the physical controller.

Currently the display is underutilized. It shows "Pro" on startup, briefly flashes a function
label ("F3", "Bel", etc.) when the operator presses a button, and attempts to show a speed
percentage when the throttle lever moves — but that code has a bug (Java operator precedence
causes `(int) fraction * 100` to always display "000"). During normal operation — running a
train, braking, stopping — the display sits showing either a stale function label or "000".

The operator has no at-a-glance hardware feedback for the most important piece of information:
how fast is the train going right now? They must look away from the physical controller to the
computer screen to see speed, breaking the immersion of operating a physical console.

### Solution

Make the LED display context-aware, showing the most relevant information for what the operator
is currently doing:

- **At rest / no loco acquired**: Show "Pro" (current startup behavior).
- **Loco acquired, speed = 0**: Show the loco address (e.g. "42_" or "123") so the operator
  knows which loco is selected.
- **Moving (speed > 0)**: Show the current DCC speed step (e.g. " 45" or "126") updating
  in real time as the train accelerates and decelerates.
- **Function button pressed**: Briefly flash the function label (e.g. "F3", "Bel"), then
  return to the speed display after a short timeout.
- **Dynamic brake zone**: Show "DBr" when the throttle lever is in the dynamic brake zone
  (current behavior, preserved).

In semi-realistic mode, the display shows the engine's `currentSpeedStep` which reflects the
actual ramped speed (not the target), so the operator sees the speed climbing and falling in
real time as the step-rate scheduler ramps toward the target.

### Impact

- The operator gets constant, at-a-glance speed feedback from the physical controller
- The display becomes a useful instrument rather than a decoration
- The existing speed display bug is fixed (currently always shows "000")
- The loco address display at standstill confirms which loco is being controlled
- Function button labels remain visible briefly but don't permanently steal the display

## 3. User Personas

### Operator (Primary)

A model railroad enthusiast using a RailDriver Desktop controller with JMRI. They operate trains
by feel using the physical levers and buttons, and glance at the 7-segment LED display for
feedback. The display is at eye level when their hands are on the controls — it's the natural
place to look for status information, the same way a real locomotive engineer glances at the
speedometer.

The operator interacts with the display indirectly through their actions:
- **Throttle lever**: Moving it changes speed → the display should reflect current speed.
- **Function buttons**: Pressing one should briefly identify which function was toggled.
- **Acquiring/releasing a loco**: The display should confirm the selected address.

## 4. High-Level User Journeys

### Journey 1: Startup and loco acquisition

The operator launches JMRI and the RailDriver is detected. The LED shows "Pro" (JMRI is ready).
The operator selects a loco address (e.g. 42) in the throttle window. The LED changes to show
" 42" — confirming which loco the controller is now driving. The throttle is at zero, the train
is stationary.

### Journey 2: Accelerating and running

The operator pushes the throttle lever forward. In direct mode, the display immediately shows the
speed percentage climbing ("  5", " 12", " 34"...). In semi-realistic mode, the display shows
the current speed step ramping up gradually ("  1", "  3", "  7", " 15"...) as the step-rate
scheduler accelerates the train. The operator can see the acceleration rate — the numbers climb
faster when the throttle is wide open and slower with heavy load.

### Journey 3: Braking to a stop

The operator applies the air brake. The display shows the speed step counting down as the train
decelerates ("  45", " 38", " 29", " 18"...). When the train reaches zero, the display
switches to showing the loco address (" 42"), confirming the train has stopped and which loco is
selected.

### Journey 4: Function button feedback

The train is running at step 45, display shows " 45". The operator presses the Bell button. The
display briefly flashes "Bel" (≈1–2 seconds), then returns to showing " 45". If the operator
presses multiple function buttons quickly, the display shows each label briefly before returning
to the speed.

### Journey 5: Dynamic brake zone

The operator pushes the throttle lever past idle into the dynamic brake zone. The display shows
"DBr" while the lever is in this zone (current behavior). When the lever returns to the throttle
zone, the display resumes showing the current speed.

### Journey 6: Releasing a loco

The operator releases the loco from the throttle window. The display returns to "Pro",
indicating no loco is acquired and the controller is in standby.

### Journey 7: Semi-realistic mode — watching the ramp

The operator has a heavy train (load slider at 5) and pushes the throttle to full. The display
shows the speed step climbing slowly due to the load: "  1", "  2", "  3"... each number holding
for a noticeable beat before incrementing. They can physically see the inertia effect. When they
apply air brakes, they see the speed counting down at a pace that reflects the braking force —
fast with air brakes, slow when coasting.

## 5. Business Requirements

### Functional Requirements

- **FR-1**: When no loco is acquired, the display shall show "Pro" (existing behavior).
- **FR-2**: When a loco is acquired and the current speed is 0, the display shall show the
  loco's DCC address (up to 3 digits, right-aligned). Addresses > 999 shall show the last
  3 digits.
- **FR-3**: When the current speed is > 0, the display shall show the current DCC speed step
  as a numeric value (0–126 for 128-step, 0–28 for 28-step), right-aligned, updated each
  time the speed changes.
- **FR-4**: In semi-realistic mode, the display shall show `currentSpeedStep` from the engine
  (the actual ramped value), not the target speed step, so the operator sees the ramp in
  real time.
- **FR-5**: When a function button is pressed, the display shall show the function label
  (e.g. "F3", "Bel") for approximately 1.5 seconds, then revert to the speed or address
  display.
- **FR-6**: When the throttle lever is in the dynamic brake zone (value < 0), the display
  shall show "DBr" (existing behavior, preserved).
- **FR-7**: The existing speed display bug (`(int) fraction * 100` always producing "000")
  shall be fixed.
- **FR-8**: Display updates shall not flood the USB HID output. Speed updates should be
  rate-limited to avoid overwhelming the device (the RailDriver's HID output rate is limited).
- **FR-9**: The display shall update when speed changes due to any source: throttle lever
  movement, brake application, semi-realistic ramp ticks, or external speed changes from the
  DCC system.

### Non-Functional Requirements

- **NFR-1**: Display updates must not block the polling thread or layout thread. `setLEDs` is
  called from the polling thread and must remain non-blocking.
- **NFR-2**: The function label timeout should use JMRI threading conventions
  (`ThreadingUtil.runOnLayoutDelayed` or equivalent).
- **NFR-3**: No new dependencies. The `setLEDs` method and HID output infrastructure already
  exist.

## 6. Success Metrics

- **SM-1**: Speed display shows correct value within one ramp tick of a speed change.
- **SM-2**: Function label is visible for ≥ 1 second and ≤ 3 seconds before reverting.
- **SM-3**: Loco address is displayed correctly for addresses 1–9999.
- **SM-4**: Speed display bug is fixed — non-zero speeds show non-zero values.

## 7. Out of Scope

- **Configurable display content** (e.g. user choosing between speed step, percentage, or
  scale speed). Future enhancement — this epic establishes the baseline context-aware behavior.
- **Scale speed display** (converting speed steps to scale MPH/KPH using a speed table). Would
  require a speed profile or roster entry. Future enhancement.
- **LED brightness control**. The RailDriver hardware does not support brightness adjustment.
- **Multi-digit scrolling for long addresses**. Addresses > 999 show last 3 digits; scrolling
  is out of scope.
- **Changes to the `setLEDs` method itself**. The existing 7-segment rendering code is
  adequate.

## 8. Business Value

**Medium-High**

The LED display is the most visible and underutilized piece of hardware on the RailDriver
controller. Making it show useful, context-aware information transforms it from a decoration
into an instrument. The speed display in particular completes the feedback loop for the
semi-realistic throttle mode — the operator can now see the effect of load, braking, and
acceleration on speed without looking away from the physical controller. Fixing the speed
display bug addresses an existing broken feature.
