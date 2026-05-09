# Epic: RailDriver Function Momentary/Latching Compliance

## 1. Epic Name

**RailDriver Function Momentary/Latching — Respect Roster Entry Settings**

## 2. Goal

### Problem

JMRI allows operators to configure each DCC function as either **latching** (toggle on/off with
each press) or **non-latching / momentary** (active only while the button is held down) on a
per-roster-entry basis via the Labels & Media dialog. The horn (typically F2) is the canonical
example of a momentary function — press and hold to sound, release to silence.

The JMRI throttle GUI correctly respects these settings. When the operator clicks a function
button configured as momentary in the on-screen Function Panel, the function activates on
mouse-down and deactivates on mouse-release. When the same function is configured as latching,
a click toggles the state.

The RailDriver controller does **not** respect these settings. Every function button on the
RailDriver behaves as latching regardless of the roster entry configuration. Pressing the
RailDriver button mapped to the horn toggles the horn on; pressing it again toggles it off.
The operator cannot hold the horn button to get a momentary blast — they must press once to
activate and press again to deactivate, which is unnatural and breaks the simulation immersion
that the physical controller is designed to provide.

### Root Cause

The JMRI throttle GUI uses an internal `ToggleOrPressButtonModel` on each `FunctionButton` that
reads its lockable/momentary state from the roster entry. This button model handles press/release
behavior directly in the Swing layer — it never consults the `DccThrottle` object's
`functionMomentary` flags.

The RailDriver code, by contrast, reads `DccThrottle.getFunctionMomentary(functionNumber)` to
decide how to handle button presses. The problem is that nobody calls
`DccThrottle.setFunctionMomentary()` with the roster entry's lockable settings at throttle
acquire time. A freshly-acquired `DccThrottle` defaults all `functionMomentary` flags to `false`
(not momentary = latching), and the `FunctionPanel` reads the roster into its local button models
but never syncs those values back to the throttle object. As a result, `getFunctionMomentary()`
always returns `false`, and the RailDriver treats every function as latching.

The WiThrottle server has the same need and correctly solves it by explicitly calling
`throttle.setFunctionMomentary(funcNum, !rosterEntry.getFunctionLockable(funcNum))` when a
throttle is acquired (`ThrottleController.syncThrottleFunctions()`). The RailDriver code does
not perform this synchronization.

### Solution

When the RailDriver acquires a throttle (in the `addressListener.notifyAddressThrottleFound`
callback), sync the `DccThrottle` object's `functionMomentary` flags from the roster entry —
the same pattern used by WiThrottle's `ThrottleController.syncThrottleFunctions()`. This ensures
that the RailDriver's existing momentary/latching branch logic at `dispatchValueEvent` reads the
correct values from the throttle.

### Impact

- Momentary functions (horn, bell, coupler sounds, etc.) work naturally on the RailDriver —
  hold to activate, release to deactivate
- Latching functions (lights, cabin sounds, etc.) continue to toggle on press
- The physical controller matches the on-screen throttle behavior exactly
- Improved simulation immersion — the horn button feels like a horn button

## 3. User Personas

### Operator

The operator is a model railroader using a RailDriver Modern Desktop USB controller as a
physical throttle console to run trains in JMRI. They have configured their roster entries
with appropriate function labels, momentary/latching settings, and visibility flags via the
Labels & Media dialog. They expect the physical controller to behave identically to the
on-screen throttle — if the horn is configured as momentary in the roster, pressing and
holding the horn button on the RailDriver should sound the horn for exactly as long as the
button is held, just like clicking and holding the horn button in the JMRI Function Panel.

## 4. High-Level User Journeys

### Journey 1: Operator Sounds the Horn (Momentary Function)

1. Operator has a roster entry where F2 (Horn) is configured as **non-latching** (momentary)
   in the Labels & Media dialog.
2. Operator selects the roster entry in the JMRI throttle and acquires the locomotive.
3. Operator presses and **holds** the RailDriver button mapped to F2.
4. The horn sounds immediately and continues sounding while the button is held.
5. Operator releases the button.
6. The horn stops immediately.

**Current (broken) behavior:** Step 3 toggles the horn ON. Step 5 does nothing. The operator
must press the button a second time to toggle the horn OFF.

### Journey 2: Operator Toggles Headlights (Latching Function)

1. Operator has a roster entry where F0 (Headlight) is configured as **latching** (the default).
2. Operator presses the RailDriver button mapped to F0.
3. The headlight turns on and stays on after the button is released.
4. Operator presses the same button again.
5. The headlight turns off.

**This journey already works correctly** and must not regress.

### Journey 3: Operator Switches Locomotives

1. Operator is running locomotive A with F2 configured as momentary.
2. Operator selects locomotive B from the roster, where F2 is configured as latching.
3. The RailDriver now treats F2 as latching for locomotive B.
4. Operator switches back to locomotive A.
5. The RailDriver now treats F2 as momentary again.

The momentary/latching behavior must track the active roster entry, updating whenever the
throttle is acquired or the locomotive selection changes.

### Journey 4: Operator Runs a Locomotive Without a Roster Entry

1. Operator enters a DCC address directly (no roster entry selected).
2. All functions default to latching behavior (the DCC default).
3. The RailDriver behaves as it does today — no regression.

## 5. Business Requirements

### Functional Requirements

- When the RailDriver acquires a throttle that has an associated roster entry, the
  `DccThrottle.functionMomentary` flags must be synchronized from the roster entry's
  `functionLockable` settings before any button input is processed.
- Functions configured as momentary (non-latching) in the roster entry must activate on
  RailDriver button press and deactivate on button release.
- Functions configured as latching in the roster entry must toggle state on RailDriver
  button press (existing behavior, no change).
- When no roster entry is associated with the acquired throttle, all functions must default
  to latching behavior (existing DCC default, no change).
- When the operator switches to a different locomotive (new throttle acquired), the
  momentary flags must be re-synchronized from the new roster entry.
- The fix must not alter the behavior of the on-screen JMRI throttle Function Panel, the
  WiThrottle server, or any other throttle consumer.

### Non-Functional Requirements

- The synchronization must respect JMRI threading conventions — `setFunctionMomentary` calls
  must be dispatched to the GUI thread via `ThreadingUtil`.
- The fix must be confined to the RailDriver code (`jmri.jmrit.usb` package); it must not
  modify the `FunctionPanel`, `FunctionButton`, or `DccThrottle` implementations.
- Backward compatibility: locomotives with no roster entry, or roster entries with default
  (all-latching) settings, must behave identically to the current behavior.

## 6. Success Metrics

| Metric | Target |
|--------|--------|
| Momentary functions respond to press-and-hold on RailDriver | 100% of roster-configured momentary functions |
| Latching functions continue to toggle on press | No regression from current behavior |
| Function behavior matches on-screen throttle exactly | Parity for all configured functions |
| No threading violations or race conditions introduced | Zero new `warnOnce` thread-check messages |

## 7. Out of Scope

- Fixing the upstream issue in `FunctionPanel` where roster lockable settings are not
  propagated to `DccThrottle.setFunctionMomentary()` during initialization. That is a
  broader JMRI core concern; the RailDriver fix works around it the same way WiThrottle does.
- Adding UI for configuring per-function momentary/latching overrides on the RailDriver
  itself. The RailDriver inherits settings from the roster entry.
- Changing the default momentary/latching behavior for functions that have no roster entry
  configuration.
- Modifying how the RailDriver maps physical buttons to DCC function numbers (that is
  controlled by the existing button mapping configuration).

## 8. Business Value

**High** — The RailDriver controller is a premium physical input device whose primary value
proposition is realistic train operation. Having the horn button behave as a toggle rather
than a momentary switch fundamentally undermines that experience. This is a straightforward
fix (the pattern already exists in WiThrottle) that directly improves every operating session
for every RailDriver user who has configured momentary functions in their roster entries.
