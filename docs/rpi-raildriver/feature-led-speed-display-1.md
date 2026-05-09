---
goal: Implement context-aware speed, address and function feedback on the RailDriver LED display
version: 1
date_created: 2025-05-08
last_updated: 2025-05-08
owner: thedave42
status: 'In progress'
tags: [feature, raildriver, led, display, ui]
---

# Introduction

![Status: Planned](https://img.shields.io/badge/status-Planned-blue)

Implement context-aware feedback on the RailDriver's 3-digit 7-segment LED display so the
operator sees the most relevant information at a glance: current speed while moving, loco
address at standstill, brief function labels on button press, and "Pro" when idle. Fix the
existing speed display bug and integrate with the semi-realistic engine's live speed step.

Epic: [raildriver-led-speed-display-epic.md](./raildriver-led-speed-display-epic.md)

## 1. Requirements & Constraints

- **REQ-001**: Show "Pro" when no loco is acquired (FR-1, existing behavior preserved).
- **REQ-002**: Show loco address when acquired and speed = 0 (FR-2).
- **REQ-003**: Show current DCC speed step (0–126) when speed > 0, updating in real time (FR-3).
- **REQ-004**: In semi-realistic mode, show `engine.getCurrentSpeedStep()` (the ramped value, not the target) (FR-4).
- **REQ-005**: Flash function label for ~1.5s on button press, then revert to speed/address (FR-5).
- **REQ-006**: Show "DBr" when throttle lever is in dynamic brake zone (FR-6, existing behavior preserved).
- **REQ-007**: Fix `(int) fraction * 100` operator precedence bug that always displays "000" (FR-7).
- **REQ-008**: Rate-limit LED updates to avoid flooding the USB HID output (FR-8).
- **REQ-009**: Update display on speed changes from any source: lever, brakes, ramp ticks, DCC (FR-9).
- **CON-001**: `setLEDs` is called from the polling thread. Display state management must be thread-aware but `setLEDs` itself is safe to call from the polling thread (it only writes to the HID device).
- **CON-002**: The LED display is 3 digits. Addresses > 999 show last 3 digits. Speed steps fit naturally (0–126).
- **CON-003**: JMRI threading conventions apply — timeouts via `ThreadingUtil.runOnLayoutDelayed`, no `java.util.Timer`.
- **GUD-001**: The display should reflect the *current* state, not the *commanded* state. In semi-realistic mode this means showing the actual `currentSpeedStep`, not the `targetSpeedStep`.
- **GUD-002**: The display is the operator's primary hardware feedback. It should never show stale or incorrect information for more than one ramp tick (~300ms).
- **PAT-001**: Use an epoch counter for the function-label timeout so stale revert callbacks are cancelled when a new button press occurs.

## 2. Implementation Steps

### Phase 1: Introduce LED display state manager

- GOAL-001: Create a small state-management class (or inner logic in `RailDriverMenuItem`) that tracks the current display mode (IDLE, ADDRESS, SPEED, FUNCTION_FLASH, DYN_BRAKE) and provides a single `updateDisplay()` method that decides what to show based on the current mode and state.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-001 | Define an enum `LedDisplayMode` in `RailDriverMenuItem` with values: `IDLE`, `ADDRESS`, `SPEED`, `FUNCTION_FLASH`, `DYN_BRAKE`. Add a `private LedDisplayMode ledMode = LedDisplayMode.IDLE` field. | | |
| TASK-002 | Add a `private int functionFlashEpoch = 0` field for cancelling stale function-label revert callbacks. | | |
| TASK-003 | Add a `private String functionFlashLabel = ""` field to hold the current function label being flashed. | | |
| TASK-004 | Add a `private long lastLedUpdateMs = 0` field and a `private static final int LED_MIN_UPDATE_INTERVAL_MS = 100` constant for rate-limiting (REQ-008). | | |
| TASK-005 | Add method `private void updateLedDisplay()` that switches on `ledMode` and calls `setLEDs(...)` with the appropriate content. This is the single point of truth for what the display shows. Implementation: `IDLE` → "Pro"; `ADDRESS` → formatted loco address; `SPEED` → formatted speed step; `FUNCTION_FLASH` → `functionFlashLabel`; `DYN_BRAKE` → "DBr". Rate-limit: skip the `setLEDs` call if fewer than `LED_MIN_UPDATE_INTERVAL_MS` have elapsed since `lastLedUpdateMs` (unless mode changed). | | |
| TASK-006 | Add method `private String formatAddress()` that reads the current loco address from the active throttle frame's address panel. Returns 3-character right-aligned string (e.g. `" 42"`, `"123"`, `"999"` for address 9999 → `"999"`). Returns `"Pro"` if no address panel or no address. | | |
| TASK-007 | Add method `private String formatSpeed()`. In semi-realistic mode (`engine != null && engine.isDriving()`): return `String.format("%3d", engine.getCurrentSpeedStep())`. Otherwise: return `String.format("%3d", Math.round(throttle.getSpeedSetting() * maxSteps))` where `maxSteps` is derived from `throttle.getSpeedIncrement()`. Returns `"  0"` if throttle is null. | | |

### Phase 2: Wire up mode transitions

- GOAL-002: Connect the display mode transitions to the existing lifecycle and input events in `RailDriverMenuItem` so the display reflects the current state.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-008 | In `addressListener.notifyAddressThrottleFound()`: set `ledMode = LedDisplayMode.ADDRESS` and call `updateLedDisplay()`. This shows the address when a loco is first acquired. | | |
| TASK-009 | In `addressListener.notifyAddressReleased()`: set `ledMode = LedDisplayMode.IDLE` and call `updateLedDisplay()`. This returns to "Pro" when the loco is released. | | |
| TASK-010 | In `setupRailDriver()` where `setLEDs("Pro")` is called (line 295): set `ledMode = LedDisplayMode.IDLE` (no functional change, just initializes the state). | | |
| TASK-011 | In `dispatchValueEvent` "Axis 1" (throttle) handler (lines 1046–1073): replace the existing broken speed display code. When `value < 0`: set `ledMode = DYN_BRAKE`, call `updateLedDisplay()`. When `value >= 0` and speed > 0: set `ledMode = SPEED`, call `updateLedDisplay()`. When speed = 0: set `ledMode = ADDRESS`, call `updateLedDisplay()`. Remove the existing `String speed = String.format("%03d", (int) fraction*100); setLEDs(speed);` code (fixes REQ-007). | | |
| TASK-012 | In `dispatchValueEvent` button handlers (around line 1337–1343): when a function button is pressed (`isDown`): save `functionFlashLabel = ledString`, set `ledMode = FUNCTION_FLASH`, call `updateLedDisplay()`, bump `functionFlashEpoch++`, capture the epoch, and schedule a revert via `ThreadingUtil.runOnLayoutDelayed(() -> { if (epoch == functionFlashEpoch) { ledMode = (currentSpeed > 0) ? SPEED : ADDRESS; updateLedDisplay(); }}, 1500)`. | | |

### Phase 3: Semi-realistic ramp tick display updates

- GOAL-003: In semi-realistic mode, update the LED display on every ramp tick so the operator sees the speed changing in real time as the engine accelerates or decelerates.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-013 | In `SemiRealisticThrottleEngine.rampCallback()`, after `emitSpeedSetting(epoch)` (line ~913): fire a PropertyChange event `"currentSpeedStep"` with old and new values. This allows `RailDriverMenuItem` to observe speed changes without polling. | | |
| TASK-014 | In `RailDriverMenuItem`, register a PropertyChangeListener on the engine when it is created (in the throttle window binding code, ~line 393). On receiving `"currentSpeedStep"`: if `ledMode != FUNCTION_FLASH && ledMode != DYN_BRAKE`, set `ledMode = (newStep > 0) ? SPEED : ADDRESS` and call `updateLedDisplay()`. | | |
| TASK-015 | In `RailDriverMenuItem`, remove the engine PropertyChangeListener when the engine is detached or the throttle window is unbound, to prevent stale callbacks. | | |

### Phase 4: Tests

- GOAL-004: Add unit tests for the formatting methods and validate display mode transitions.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-016 | Test `formatSpeed()`: speed step 0 → "  0", speed step 45 → " 45", speed step 126 → "126". | | |
| TASK-017 | Test `formatAddress()`: address 42 → " 42", address 123 → "123", address 9999 → "999", null address → "Pro". | | |
| TASK-018 | Test mode transitions: IDLE → notifyThrottleFound → ADDRESS → speed > 0 → SPEED → speed = 0 → ADDRESS → notifyReleased → IDLE. | | |
| TASK-019 | Test function flash: SPEED mode → button press → FUNCTION_FLASH → timeout → SPEED. Verify epoch cancellation when a second button press occurs during the flash period. | | |
| TASK-020 | Test rate limiting: rapid successive calls to `updateLedDisplay()` produce at most one `setLEDs()` call per `LED_MIN_UPDATE_INTERVAL_MS`. | | |
| TASK-021 | Verify the speed display bug fix: with non-zero speed, the display shows a non-zero value (SM-4). | | |

### Phase 5: Documentation and cleanup

- GOAL-005: Update documentation and remove dead code.

| Task | Description | Completed | Date |
|------|-------------|-----------|------|
| TASK-022 | Remove the broken `String speed = String.format("%03d", (int) fraction*100)` code and the `setLEDs(speed)` call from the Axis 1 handler. This is superseded by `updateLedDisplay()`. | | |
| TASK-023 | Update the epic status document. | | |
| TASK-024 | Add inline comments explaining the LED display state machine transitions in `RailDriverMenuItem`. | | |

## 3. Alternatives

- **ALT-001**: **Show speed percentage (0–100%) instead of speed step (0–126)**. Considered because percentages are more intuitive to casual users. Rejected because (a) the speed step is what the DCC system actually commands, (b) in semi-realistic mode the step is what the engine ramps, and (c) the 3-digit display can show all step values (0–126) with no ambiguity. Percentage display could be added as a configurable option in a future epic.

- **ALT-002**: **Show scale speed (MPH/KPH)**. Requires a speed profile or roster entry to convert speed steps to scale speed. Out of scope for this epic — would be a valuable future enhancement once a speed profile mechanism exists.

- **ALT-003**: **Poll the engine speed on a timer instead of using PropertyChange events**. Simpler but wastes CPU cycles when speed is stable and introduces latency proportional to the poll interval. PropertyChange events are immediate and zero-cost when speed is stable.

- **ALT-004**: **Use `javax.swing.Timer` for the function flash timeout**. Rejected per JMRI threading conventions — use `ThreadingUtil.runOnLayoutDelayed`.

## 4. Dependencies

- **DEP-001**: `RailDriverMenuItem.java` — primary file. Already contains `setLEDs()`, `sendString()`, throttle lifecycle, and polling thread.
- **DEP-002**: `SemiRealisticThrottleEngine.java` — needs a new `"currentSpeedStep"` PropertyChange event fired from `rampCallback()` (TASK-013). Already has `PropertyChangeSupport`.
- **DEP-003**: `AddressPanel` — provides `getThrottle()`, `getCurrentAddress()`, `AddressListener` lifecycle. Already wired up in `RailDriverMenuItem`.
- **DEP-004**: `ThreadingUtil` — for `runOnLayoutDelayed` (function flash timeout).

## 5. Files

- **FILE-001**: `java/src/jmri/jmrit/usb/RailDriverMenuItem.java` — Add `LedDisplayMode` enum, state fields, `updateLedDisplay()`, `formatAddress()`, `formatSpeed()`, mode transition wiring, function flash with epoch. Fix speed display bug.
- **FILE-002**: `java/src/jmri/jmrit/usb/SemiRealisticThrottleEngine.java` — Add `"currentSpeedStep"` PropertyChange event in `rampCallback()`.
- **FILE-003**: `java/test/jmri/jmrit/usb/RailDriverMenuItemTest.java` — Add tests for formatting methods, mode transitions, rate limiting, bug fix.
- **FILE-004**: `docs/rpi-raildriver/raildriver-led-speed-display-epic.md` — Update status.

## 6. Testing

- **TEST-001**: `formatSpeed()` produces correct 3-character strings for speed steps 0, 45, 126.
- **TEST-002**: `formatAddress()` produces correct strings for addresses 1, 42, 123, 9999, and null.
- **TEST-003**: Mode transitions follow the state machine: IDLE → ADDRESS → SPEED → ADDRESS → IDLE.
- **TEST-004**: Function flash reverts after timeout; epoch cancellation prevents stale reverts.
- **TEST-005**: Rate limiting prevents excessive `setLEDs` calls.
- **TEST-006**: Speed display bug fix: non-zero speed produces non-zero display value (SM-4).
- **TEST-007**: Semi-realistic `rampCallback` fires `"currentSpeedStep"` PropertyChange event.

## 7. Risks & Assumptions

- **RISK-001**: The `setLEDs` method writes to the USB HID device, which may block briefly if the device is slow. Rate-limiting (TASK-004) mitigates this, but if the device is unresponsive the polling thread could be delayed. The existing code already calls `setLEDs` from the polling thread without protection, so this risk is pre-existing.
- **RISK-002**: The function flash timeout uses `runOnLayoutDelayed`, which posts to the layout thread. The revert callback must check the epoch counter to avoid reverting a display that has since changed mode (e.g., loco released during the flash). PAT-001 mitigates this.
- **RISK-003**: In direct mode (no semi-realistic engine), speed updates come only when the throttle lever moves, not when the DCC system changes speed externally. The display may show a stale value if another throttle changes the loco's speed. Addressed by FR-9 as a future enhancement if needed.
- **ASSUMPTION-001**: The `setLEDs` method is safe to call from the polling thread (it uses `sendMessage` which synchronizes on the HID device). This is the existing behavior.
- **ASSUMPTION-002**: `engine.getCurrentSpeedStep()` is safe to read from the polling thread. It's a plain `int` field — reads are atomic on all JVMs.
- **ASSUMPTION-003**: The PropertyChangeSupport on `SemiRealisticThrottleEngine` fires events on the layout thread (same thread as `rampCallback`). The listener in `RailDriverMenuItem` must be aware that it receives events on the layout thread, not the polling thread.

## 8. Related Specifications / Further Reading

- [RailDriver LED Speed Display Epic](./raildriver-led-speed-display-epic.md)
- [Semi-Realistic Throttle Plan](./semi-realistic-throttle-plan.md)
- [Additive Force Brake Model Plan](./feature-additive-force-brake-model-1.md)
