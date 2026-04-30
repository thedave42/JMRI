# RailDriver Modern Desktop control inventory

This document records the physical controls present on the RailDriver
Modern Desktop unit.

Items marked `<user-assignable>` have no built-in label/function; the JMRI integration
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
| 42 (user SPDT)     | unknown                | unknown       | n/a |
| 43 (hat switch)    | 624–688 (rocker / POV) | `selectRosterEntry`, `dispatchAddress`, frame nav | n/a |
