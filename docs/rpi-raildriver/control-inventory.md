# RailDriver Modern Desktop control inventory

This document records the physical controls present on the RailDriver
Modern Desktop unit.

Items marked `<user-assignable>` have no built-in label/function; the JMRI integration
is free to map them to whatever DCC functionality the user wants.  The numbering is arbitrary and does not map to anything specific.

## Physical layout

The controller is viewed from above with the operator seated at the front
(bottom) edge.  Six zones are arranged left-to-right across the unit.

```
  ┌──────────────────────────────────────────────────────────────────────────────┐
  │                                                                              │
  │  ┌───────┐                                                                   │
  │  │  LCD  │  (speed display)                                                  │
  │  └───────┘                                                                   │
  │                                                                              │
  │  ┌───────────┐       ┌─────────────────┐  ┌─────────────────┐  ┌────────┐    │
  │  │ RANGE  EST│       │                 │  │    ┌──────┐     │  │  WIPER │    │
  │  │  ↕     ↕  │       │ REVERSER  THROT/│  │    │  RD  │     │  │SLW OFF │    │
  │  │           │ HORN  │    ↕     DYNBRK │  │    └──────┘     │  │   FULL │    │
  │  │ ALERT SAND│  ↕    │   FWD      ↕    │  │                 │  │   #12  │    │
  │  │  ●     ■  │       │    N            │  │ AUTO      IND   │  │        │    │
  │  │           │  #7   │   REV           │  │ BRAKE    BRAKE  │  │ LIGHTS │    │
  │  │  P    BELL│       │   #8      #9    │  │   ↕        ↕    │  │DIM OFF │    │
  │  │  ●     ●  │       │                 │  │  EMG     FULL   │  │   FULL │    │
  │  └───────────┘       └─────────────────┘  │  CS             │  │   #13  │    │
  │   #1–#6                                   │  SUP      REL   │  └────────┘    │
  │                                           │  REL            │                │
  │                                           │  #10      #11   │                │
  │                                           └─────────────────┘                │
  │                                                                              │
  │  ┌──── 28 blue buttons (2 rows × 14 columns) ──────────────────┐ ┌──┐  ╬     │
  │  │  top row:  [14] [15] [16] [17] ... [27]                     │ │42│  #43   │
  │  │  bot row:  [28] [29] [30] [31] ... [41]                     │ └──┘  hat   │
  │  └─────────────────────────────────────────────────────────────┘             │
  └──────────────────────────────────────────────────────────────────────────────┘
                              ▲ operator sits here ▲
```

### Zone descriptions (left to right)

**LCD speed display** — small rectangular LED/LCD window at the top-left
corner of the unit.  Shows locomotive speed.

**Left button panel (#1–#6)** — a 3-row × 2-column grid of labelled
switches and buttons in the upper-left area.

| Position       | #  | Label | Physical appearance       |
|----------------|----|-------|---------------------------|
| top-left       | 1  | RANGE | SPDT toggle switch        |
| top-right      | 2  | E-STOP| SPDT toggle switch        |
| middle-left    | 3  | ALERT | Red push-button           |
| middle-right   | 4  | SAND  | Blue push-button          |
| bottom-left    | 5  | P     | Green push-button         |
| bottom-right   | 6  | BELL  | Yellow push-button        |

**Horn (#7)** — a tall ball-top joystick lever protruding from the top
surface between the left button panel and the left lever base.  Rocks
forward/backward (SPDT momentary, up/off/down).

**Left lever base (#8, #9)** — a shared housing holding two levers
side-by-side, located to the right of the horn.

- Left lever (#8, Reverser): labelled **FORWARD** / **N** / **REVERSE**
  from top to bottom.  Three physical detents.
- Right lever (#9, Throttle / Dynamic Brake): labelled **DYN BRAKE** at
  the top and **THROTTLE** at the bottom.  Continuous travel; centre is
  idle, up applies dynamic braking, down applies throttle.

**Right lever base (#10, #11)** — a shared housing bearing the blue **RD**
(RailDriver) logo, located to the right of the left lever base.  Contains
two brake levers side-by-side.

- Left lever (#10, Auto Brake): labelled with positions **EMG** (red,
  top), **CS**, **SUP**, and **REL** (bottom).  Continuous travel across
  named positions.
- Right lever (#11, Independent Brake): labelled **FULL** (top) and
  **REL** (bottom).  Continuous travel with two bail-off positions.

**Right rotary panel (#12, #13)** — two rotary knobs stacked vertically on
the far-right side of the top surface.

- Upper knob (#12, Wiper): three positions — **SLOW**, **OFF**, **FULL**.
- Lower knob (#13, Lights): three positions — **DIM**, **OFF**, **FULL**.

**Front-panel controls (#14–#43)** — along the front edge closest to the
operator.

- 28 blue push-buttons (#14–#41) arranged in two rows of 14.  These
  carry printed labels for the Microsoft Train Simulator key-map (e.g.
  "Menu", "HUD", "Buzzer", "REP On", "Camera", etc.) which are **not**
  used by JMRI; all 28 are user-assignable.
- To the right of the button rows sit two additional controls:
  - #42 — a user-assignable SPDT momentary toggle (up/off/down).
  - #43 — a four-direction hat switch (up/right/down/left; one
    direction asserted at a time).

## Switches and buttons

| #  | Label              | Type                          | Notes |
|----|--------------------|-------------------------------|-------|
| 1  | Range              | SPDT momentary (up/off/down)  | Centre is rest. |
| 2  | E-Stop             | SPDT momentary (up/off/down)  | Centre is rest. |
| 3  | Alert              | Button                        | |
| 4  | Sand               | Button                        | |
| 5  | P                  | Button                        | ASSUMPTION: "P" likely means Pantograph based on JMRI's `RailDriverMenuItem.java` button-40 case ("pantoFn"). |
| 6  | Bell               | Button                        | |
| 7  | Horn               | SPDT momentary (up/off/down)  | |
| 42 | `<user-assignable>` | SPDT momentary (up/off/down) | |
| 43 | `<user-assignable>` | Hat switch (up/right/down/left) | Four directions; only one direction is asserted at a time. |
| 14–41 | `<user-assignable>` | 28 buttons in a 2 × 14 layout | The two rows along the front edge of the controller. |

## Analog / multi-position controls

| #  | Label                       | Type                                                        | Notes |
|----|-----------------------------|-------------------------------------------------------------|-------|
| 8  | Reverser                    | Analog with 3 physical detents (Forward, Neutral, Reverse) | Lever physically locks at each detent. |
| 9  | Throttle / Dynamic Brake    | Continuous analog, bipolar                                 | Down = throttle (centre→max). Centre = idle. Up = dynamic brake (centre→max). |
| 10 | Auto Brake                  | Continuous analog with specific positions SUP, CS, EMG     | Continuous between the named positions. |
| 11 | Independent Brake           | Continuous analog plus two bail-off positions              | |
| 12 | Wiper                       | Analog with 3 physical positions: Off, Slow, Full                         | |
| 13 | Lights                      | Analog with 3 physical positions: Off, Dim, Full                          | |
