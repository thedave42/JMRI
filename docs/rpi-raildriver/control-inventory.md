# RailDriver Modern Desktop control inventory

This document records the physical controls present on the RailDriver
Modern Desktop unit and the byte/bit location of each control's signal
in the device's 14-byte HID input report.

Items marked `<user-assignable>` have no built-in label/function; the JMRI integration
is free to map them to whatever DCC functionality the user wants.  The numbering is arbitrary and does not map to anything specific.

The "HID location" entries below were empirically determined via the
structured-capture protocol in `capture-plan.md` (5 independent runs
analysed by `rd-analyze.sh`; see the per-run `mapping.md` files under
`captures/run-*/`). For analog controls only the physical extremes are
captured — named/center positions are intentionally not measured per the
protocol's design (they get configured at calibration time).

## Physical layout

The controller is viewed from above with the operator seated at the front
(bottom) edge.  Six zones are arranged left-to-right across the unit.

```
  ┌──────────────────────────────────────────────────────────────────────────────┐
  │                                                                              │
  │  ┌───────┐                                                                   │
  │  │  LCD  │                                                                   │
  │  └───────┘                                                                   │
  │                                                                              │
  │  ┌───────────┐       ┌─────────────────┐  ┌─────────────────┐  ┌────────┐    │
  │  │ RANGE  EST│       │                 │  │    ┌──────┐     │  │  WIPER │    │
  │  │  ↕     ↕  │       │ REVERSER  THROT/│  │    │  RD  │     │  │OFF SLW │    │
  │  │           │ HORN  │    ↕     DYNBRK │  │    └──────┘     │  │   FULL │    │
  │  │ ALERT SAND│  ↕    │   FWD      ↕    │  │                 │  │   #12  │    │
  │  │  ●     ■  │       │    N            │  │ AUTO      IND   │  │        │    │
  │  │           │  #7   │   REV           │  │ BRAKE    BRAKE  │  │ LIGHTS │    │
  │  │  P    BELL│       │   #8      #9    │  │   ↕        ↕    │  │OFF DIM │    │
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

- Upper knob (#12, Wiper): three positions — **OFF**, **SLOW**, **FULL**
  (in that physical order along the knob's rotation; **OFF** and **FULL**
  are the physical extremes, **SLOW** is the middle position).
- Lower knob (#13, Lights): three positions — **OFF**, **DIM**, **FULL**
  (in that physical order; **OFF** and **FULL** are the physical extremes,
  **DIM** is the middle position).

**Front-panel controls (#14–#43)** — along the front edge closest to the
operator.

- 28 blue push-buttons (#14–#41) arranged in two rows of 14.  These
  carry printed labels for the Microsoft Train Simulator key-map (e.g.
  "Menu", "HUD", "Buzzer", "REP On", "Camera", etc.) which are **not**
  used by JMRI; all 28 are user-assignable.
- To the right of the button rows sit two additional controls:
  - #42 — a user-assignable SPDT momentary toggle (up/off/down).
  - #43 — a four-direction hat switch (up/right/down/left). Two adjacent
    cardinal directions can be asserted simultaneously when the hat is
    rolled toward a corner; the device has no separate diagonal bits.

## Switches and buttons

| #  | Label              | Type                          | HID location                                                                                              | Notes |
|----|--------------------|-------------------------------|-----------------------------------------------------------------------------------------------------------|-------|
| 1  | Range              | SPDT momentary (up/off/down)  | byte 11 bit `0x04` (up) / `0x08` (down)                                                                   | Centre is rest. |
| 2  | E-Stop             | SPDT momentary (up/off/down)  | byte 11 bit `0x10` (up) / `0x20` (down)                                                                   | Centre is rest. |
| 3  | Alert              | Button                        | byte 11 bit `0x40`                                                                                        | |
| 4  | Sand               | Button                        | byte 11 bit `0x80`                                                                                        | |
| 5  | P                  | Button                        | byte 12 bit `0x01`                                                                                        | ASSUMPTION: "P" likely means Pantograph based on JMRI's `RailDriverMenuItem.java` button-40 case ("pantoFn"). |
| 6  | Bell               | Button                        | byte 12 bit `0x02`                                                                                        | |
| 7  | Horn               | SPDT momentary (up/off/down)  | byte 12 bit `0x04` (up) / `0x08` (down)                                                                   | |
| 42 | `<user-assignable>` | SPDT momentary (up/off/down) | byte 10 bit `0x10` (up) / `0x20` (down)                                                                   | |
| 43 | `<user-assignable>` | Hat switch (up/right/down/left) | byte 10 bit `0x40` (up) / `0x80` (right); byte 11 bit `0x01` (down) / `0x02` (left)                       | Four cardinal directions in hardware. Two adjacent cardinal directions can be asserted simultaneously when the hat is rolled toward a corner; the device has no separate diagonal bits. |
| 14–41 | `<user-assignable>` | 28 buttons in a 2 × 14 layout | (see "Front-edge button grid HID layout" below)                                                          | The two rows along the front edge of the controller. |

## Analog / multi-position controls

| #  | Label                       | Type                                                        | HID location                                                                                                       | Notes |
|----|-----------------------------|-------------------------------------------------------------|--------------------------------------------------------------------------------------------------------------------|-------|
| 8  | Reverser                    | Analog with 3 physical detents (Forward, Neutral, Reverse)  | byte 0 — full Forward `0x42`, full Reverse `0xcf`                                                                  | Lever physically locks at each detent. The Neutral byte value is intentionally not captured (calibrated at runtime). |
| 9  | Throttle / Dynamic Brake    | Continuous analog, bipolar                                  | byte 1 — full Throttle `0xdd`, full Dynamic Brake `0x3a`                                                           | Down = throttle (centre→max). Centre = idle. Up = dynamic brake (centre→max). The Idle byte value is intentionally not captured (calibrated at runtime). |
| 10 | Auto Brake                  | Continuous analog with specific positions SUP, CS, EMG      | byte 2 — fully RELEASED `0xb7`, EMG `0x4f`                                                                         | Continuous between the named positions. SUP/CS/REL byte values are intentionally not captured (calibrated at runtime). |
| 11 | Independent Brake           | Continuous analog plus two bail-off positions               | byte 3 (primary axis) — full release `0xc0`, full application `0x41`; byte 4 (secondary) — rest band `0x95..0xa8`, bail-off positions push byte 4 up to `0xd4` | Bail-off positions are captured as transient byte-4 excursions, not as discrete byte values. |
| 12 | Wiper                       | Analog with 3 physical positions: Off, Slow, Full           | byte 5 — Off `0x66`, Full `0xba`                                                                                   | The Slow (middle) byte value is intentionally not captured. |
| 13 | Lights                      | Analog with 3 physical positions: Off, Dim, Full            | byte 6 — Off `0x52`, Full `0x9c`                                                                                   | The Dim (middle) byte value is intentionally not captured. |

## Front-edge button grid HID layout

The 28 buttons are documented by physical position rather than inventory
item number, because the inventory's `14..41` numbering is not anchored to
any specific corner of the grid. The HID locations below are empirically
determined; the inventory-number assignment to each physical position
remains a separate convention to be chosen by integrators.

| Row \\ Col | 01 | 02 | 03 | 04 | 05 | 06 | 07 | 08 | 09 | 10 | 11 | 12 | 13 | 14 |
|-----------|----|----|----|----|----|----|----|----|----|----|----|----|----|----|
| **back**  | b7 `0x01` | b7 `0x02` | b7 `0x04` | b7 `0x08` | b7 `0x10` | b7 `0x20` | b7 `0x40` | b7 `0x80` | b8 `0x01` | b8 `0x02` | b8 `0x04` | b8 `0x08` | b8 `0x10` | b8 `0x20` |
| **front** | b8 `0x40` | b8 `0x80` | b9 `0x01` | b9 `0x02` | b9 `0x04` | b9 `0x08` | b9 `0x10` | b9 `0x20` | b9 `0x40` | b9 `0x80` | b10 `0x01` | b10 `0x02` | b10 `0x04` | b10 `0x08` |

Pattern: bit `0x01` of byte 7 is `back` row column 01; bits increment
through `0x80` of each byte, then continue in the next byte. The grid
occupies bytes 7, 8, 9, and bits `0x01..0x08` of byte 10. Byte 10's high
nibble is shared with SPDT #42 and hat #43 up/right.

## HID input report layout

One-line summary of all 14 bytes of the device's input report (offsets
0..13):

| Byte | Role |
|------|------|
| 0  | Reverser (analog: `0x42` full Forward .. `0xcf` full Reverse) |
| 1  | Throttle / Dynamic Brake (analog: `0xdd` full Throttle .. `0x3a` full Dynamic Brake) |
| 2  | Auto Brake (analog: `0x4f` fully EMG .. `0xb7` fully RELEASED) |
| 3  | Independent Brake primary axis (analog: `0xc0` full release .. `0x41` full application) |
| 4  | Independent Brake secondary / bail-off indicator (analog: rest band `0x95..0xa8`, bail-off `..0xd4`) |
| 5  | Wiper (analog: `0x66` Off .. `0xba` Full) |
| 6  | Lights (analog: `0x52` Off .. `0x9c` Full) |
| 7  | Bit-packed: front-edge buttons back-01 (`0x01`) .. back-08 (`0x80`) |
| 8  | Bit-packed: back-09 (`0x01`) .. back-14 (`0x20`); front-01 (`0x40`); front-02 (`0x80`) |
| 9  | Bit-packed: front-03 (`0x01`) .. front-10 (`0x80`) |
| 10 | Bit-packed: front-11 (`0x01`) .. front-14 (`0x08`); SPDT #42 up (`0x10`) / down (`0x20`); hat #43 up (`0x40`) / right (`0x80`) |
| 11 | Bit-packed: hat #43 down (`0x01`) / left (`0x02`); Range up (`0x04`) / down (`0x08`); E-Stop up (`0x10`) / down (`0x20`); Alert (`0x40`); Sand (`0x80`) |
| 12 | Bit-packed: P (`0x01`); Bell (`0x02`); Horn up (`0x04`) / down (`0x08`); bits `0x10..0x80` unused in observed captures |
| 13 | Constant `0x35` in every observed report — likely a fixed report-id / status marker; no observed control affects it. |
