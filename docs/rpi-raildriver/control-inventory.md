# RailDriver Modern Desktop control inventory

This document records the physical controls present on the RailDriver
Modern Desktop unit being used to develop the Linux/aarch64 support
described in issue #1.

The list and labels are taken **verbatim** from the user's specification
(issue #1 thread, message of 2026-04-29 18:03 PDT). Where the
specification has obvious typos or ambiguities, the assumed meaning is
called out explicitly with the prefix "ASSUMPTION:".

Each item below carries the user's original numbering. Items marked
`<user-assignable>` have no built-in label/function; the JMRI integration
is free to map them to whatever DCC functionality the user wants.

## Switches and buttons

| #  | Label              | Type                          | Notes |
|----|--------------------|-------------------------------|-------|
| 1  | Range              | SPDT momentary (up/off/down)  | Centre is rest. |
| 2  | E-Stop             | SPDT momentary (up/off/down)  | Centre is rest. |
| 3  | Alert              | Button                        | |
| 4  | Sand               | Button                        | |
| 5  | P                  | Button                        | ASSUMPTION: "P" likely means Pantograph based on JMRI's `RailDriverMenuItem.java` button-40 case ("pantoFn"). To be confirmed by Phase 2 captures. |
| 6  | Bell               | Button                        | |
| 7  | Horn               | SPDT momentary (up/off/down)  | ASSUMPTION: spec wrote "SPDP"; treated as a typo for SPDT to match items 1, 2, 42. |
| 42 | `<user-assignable>` | SPDT momentary (up/off/down) | |
| 43 | `<user-assignable>` | Hat switch (up/right/down/left) | Four directions; only one direction is asserted at a time. |
| 14–41 | `<user-assignable>` | 28 buttons in a 2 × 14 layout | The two rows along the front edge of the controller. |

## Analog / multi-position controls

| #  | Label                       | Type                                                        | Notes |
|----|-----------------------------|-------------------------------------------------------------|-------|
| 8  | Reverser                    | Analog with 3 physical detents (Forward, Neutral, Reverse) | Lever physically locks at each detent. |
| 9  | Throttle / Dynamic Brake    | Continuous analog, bipolar                                 | Down = throttle (centre→max). Centre = idle. Up = dynamic brake (centre→max). |
| 10 | Auto Brake                  | Continuous analog with specific positions SUP, CS, EMG     | Continuous between the named positions. |
| 11 | Independent Brake           | Continuous analog plus two bail-off positions              | ASSUMPTION: spec wrote "ball off and on", treated as a typo for "bail off and on". |
| 12 | Wiper                       | 3-position switch: Off, Slow, Full                         | |
| 13 | Lights                      | 3-position switch: Off, Dim, Full                          | |

## What is **not** in the user spec (do not invent these)

(None known. Item count after the 2026-04-29 18:13 PDT clarification: 43 numbered items.)

## Mapping to the existing JMRI Java code's hard-coded button cases

The hard-coded button mapping in `jmri.util.usb.RailDriverMenuItem.java`
predates this inventory. Tentative correspondence (subject to Phase 2
verification):

| User item | Java case (line) | Java's name | Maps to DCC fn |
|-----------|------------------|-------------|----------------|
| 1 (Range up/down)  | 689–702 | `shuntFn` (gear up/down) | F3 |
| 2 (E-Stop up/down) | 703–709 | "Emergency Brake up/down" | speed = -1 |
| 3 (Alert)          | 711–716 | `alertFn`     | F6 |
| 4 (Sand)           | 717–722 | `sandFn`      | F7 |
| 5 (P)              | 723–728 | `pantoFn`     | F8 |
| 6 (Bell)           | 729–734 | `bellFn`      | F1 |
| 7 (Horn up/down)   | 735–739 | `hornFn` (cases 42/43) | F2 |
| 14–41 (28 buttons) | default branch (612–622, gated by `fNum > 0` on line 744) | direct F-mapping | F0..F27, except button 0 is silently dropped (see issue #1 §4.1) |
| 42 (user SPDT)     | unknown                | unknown       | n/a |
| 43 (hat switch)    | 624–688 (rocker / POV) | `selectRosterEntry`, `dispatchAddress`, frame nav | n/a |

## What's still unverified

- The 2 × 14 layout of items 14–41 is the user's stated layout; not yet
  cross-checked against the HID descriptor or against per-bit captures.
- The byte→item and bit→item mappings have not yet been measured. That
  is the explicit goal of issue #1 Phase 2 (`docs/rpi-raildriver/rd-record.sh`).
- The mapping from the JMRI Java code's button indices (0..43) to this
  inventory's item numbers is tentative until the Phase 2 fixtures
  arrive.
