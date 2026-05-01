# Byte-13 capture test (RailDriver Modern Desktop)

## Purpose

Resolve the open question in `plan.md` §1 #5: is byte 13 of the RailDriver's
14-byte HID input report a **constant sentinel** (always `0x35` regardless of
device state) or a **live button bitmap** that happens to read `0x35` because
the captures so far did not toggle the cab's latching switches?

The test is a 5-minute hardware procedure. It is run once, before any
implementation work in the `rpi-raildriver` branch, on a Raspberry Pi 4 with the
RailDriver Modern Desktop attached.

## Why this matters

- If byte 13 changes when latching switches are toggled, the parser at
  `RailDriverMenuItem.java:198–238` is correct as-is and §1 #5 of `plan.md`
  stays as written (byte 13 is part of the button bitmap; bits 48..55).
- If byte 13 is invariant under every physical state the controller can be
  put in, it is a true constant sentinel; the parser's mapping of bits 48..55
  is dead code (but harmless — a never-changing byte produces no events from
  the diff loop). The §6 risk row in `plan.md` is then updated to record the
  resolved interpretation.

The §4.5 byte-13 pin-down test (assert that synthetic byte-13 changes emit
events `"48"` / `"49"`) stays in place either way; see `plan.md` §4.5 #1 for
the rationale.

## Pre-conditions

- RailDriver Modern Desktop (VID `05F3` / PID `00D2`) connected to the Pi.
- JMRI is **NOT** running (it would also have `/dev/hidraw0` open and
  interleave reads).
- Current user is in `plugdev` (verify with `id | tr ',' '\n' | grep
  plugdev`). On this dev host the udev rule at
  `/etc/udev/rules.d/70-jmri-usb.rules` already grants the access; on a clean
  Pi OS install run the rule from `lib/linux/udev/99-jmri-raildriver.rules`
  first.
- The controller has been left untouched long enough for any momentary
  buttons to have returned to their natural position. **Note the starting
  state of every physical control** (which way the gear toggle is pointing,
  which positions the rocker switches are in, where the levers are sitting,
  etc.). A photo is the easiest way.

Verify the device is the expected one:

```sh
lsusb | grep -i raildriver
ls -l /dev/hidraw0
```

Expected output for the device line:

```
crw-rw---- 1 root plugdev 242, 0 ... /dev/hidraw0
```

## Procedure

Run the capture as a stream and toggle each control in turn, with a few-second
pause between toggles so the resulting byte-13 transitions are easy to see in
the timestamp ordering of the dump. The full capture is appended to a single
log file.

### 1. Start the capture

Open one terminal and run:

```sh
mkdir -p docs/rpi-raildriver/test-data
cd docs/rpi-raildriver/test-data
script -q -c \
    'xxd -c 14 /dev/hidraw0' \
    byte13-capture.raw </dev/null
```

`xxd -c 14` reads from the hidraw character device and writes one 14-byte HID
report per line. `script` records the live stream (with timestamps in
`script`'s own log) into a file. The capture runs until `Ctrl-D` / `Ctrl-C`.

If `xxd` is missing:

```sh
sudo apt install -y xxd
```

### 2. Establish baseline

Without touching the controller for ~10 seconds, let the capture record the
device's resting state. This forms the byte-13 reference value (expected to
be `0x35`).

### 3. Toggle every physical control in isolation

In the **other** terminal (or just at the controller), do each of the
following in turn. Wait ~3 seconds between actions so the reports line up
visually with the action.

For each control, **fully exercise its position range**: a rocker that has
two positions gets toggled UP then DOWN then back to its starting position;
a three-way switch gets walked through every position; a momentary button
gets pressed and released.

The control list below is intentionally exhaustive — it is easier to test
every control once than to guess which ones are latching:

1. **Reverser lever** (analog, byte 0): sweep full forward → centre → full
   reverse → back to centre.
2. **Throttle / dyn-brake combined lever** (analog, byte 1): sweep full
   throttle → centre → full dyn-brake → back to centre.
3. **Auto (train) brake lever** (analog, byte 2): sweep release → full apply
   → emergency (if separate detent) → back to release.
4. **Independent (loco) brake lever** (analog, byte 3): sweep release → full
   apply → back to release.
5. **Bail-off lever** (analog, byte 4): press fully forward → release.
6. **Wiper rotary** (analog, byte 5): walk through every detent.
7. **Headlight / lights rotary** (analog, byte 6): walk through every
   detent.
8. **Bell button** (momentary): press → release.
9. **Horn lever** (momentary, two positions — up and down): press up → release;
   press down → release.
10. **Alerter button** (momentary): press → release.
11. **Sander button** (momentary): press → release.
12. **Pantograph button** (momentary): press → release.
13. **Gear-up button** (momentary): press → release.
14. **Gear-down button** (momentary): press → release.
15. **Each of the 28 numbered blue function buttons** (momentary): press →
    release. A representative sample of 4–5 (e.g. F1, F8, F14, F21, F28) is
    enough — they are all generated by the same byte-bitmap mechanism.
16. **Three-way reverser switch / range selector** (latching, on the cab
    panel — distinct from the reverser lever): walk through every position,
    pause in each position, then return to the starting position.
17. **Any rocker switch on the cab panel** (latching): toggle to each
    position in turn.
18. **Gear toggle** if it is a switch rather than a button (latching):
    toggle to each position in turn.
19. **Any other control** that has a fixed mechanical state (anything that
    "stays where you put it"): exercise it.

### 4. Stop the capture

In the capture terminal, `Ctrl-C` then `Ctrl-D` to close `script`. The raw
hidraw dump is in `byte13-capture.raw`; the wrapper transcript is in
`typescript` (or whatever name `script` chose).

### 5. Analyze byte 13

```sh
cd docs/rpi-raildriver/test-data
awk '{print substr($8,3,2)}' byte13-capture.raw | sort -u
```

`$8` in the `xxd -c 14` output is the field representing bytes 12 and 13
(four hex characters); `substr($8,3,2)` extracts byte 13 specifically.

Also run:

```sh
awk '{print $8}' byte13-capture.raw | sort -u
```

to see the distinct byte-12-and-13 pairs (for sanity-checking that buttons
in byte 12 fired during the capture, which confirms the controller was
actually emitting reports during the toggle phase).

### 6. Interpret the result

| `awk` output for byte 13 | Interpretation | Action |
|---|---|---|
| `35` only | Byte 13 is genuinely invariant. Plan §1 #5's "constant sentinel" reading is correct. | Update `plan.md` §6 risk row to record "byte 13 confirmed sentinel via byte13-capture-test on `<date>`; no further action". `plan.md` §1 #5 needs no edit (it already treats this case). |
| `35` plus any other value(s) | Byte 13 is part of the button bitmap. Plan §1 #5's "byte 13 is part of the button bitmap" reading is correct. The parser is already correct. | Update `plan.md` §6 risk row to record "byte 13 confirmed live button bitmap via byte13-capture-test on `<date>`; parser is correct as-is". `plan.md` §1 #5 needs no edit. |
| Anything else (e.g. byte 13 changed but byte 12 never did) | Capture or analysis went wrong. | Re-run from §3 with deliberate button-12 presses interspersed to confirm the capture pipeline is working end-to-end. |

### 7. Record the result in this file

Append a short block at the end of this file with:

- the date / commit SHA at which the test was run,
- the host (`uname -a` output),
- the kernel HID descriptor (`cat /sys/class/hidraw/hidraw0/device/report_descriptor | xxd | head`),
- the `awk` output (both queries from §5),
- the resolved interpretation.

This makes the question settled-in-tree, not a tribal-knowledge fact about
the captures committed under `test-data/`.

## Cleanup

The capture file (`byte13-capture.raw`) and any `script` transcript are
disposable. Either commit them under `docs/rpi-raildriver/test-data/` (if the
result is interesting and reproduces a particular interaction) or delete
them. The result-recording block in §7 above is the durable artefact.

## Cross-references

- `plan.md` §1 #5 — the layout description being settled by this test
- `plan.md` §4.5 #0 — the test slot in the testing section
- `plan.md` §6 — the risk row to be updated based on the result
- `RailDriverMenuItem.java:198–238` — the parser whose interpretation is at
  stake
- `docs/rpi-raildriver/test-data/hidraw-raildriver*.log` — the existing
  captures that left this question open

---

## Result — 2026-05-01

| Field | Value |
|---|---|
| Date | 2026-05-01 |
| Branch / commit | `rpi-raildriver` @ `95f5ec978e7dc986cc9b2e46ed6c82180d31f788` |
| Host | `Linux rpi-jmri 6.12.75+rpt-rpi-v8 #1 SMP PREEMPT Debian 1:6.12.75-1+rpt1 (2026-03-11) aarch64` |
| Distro | Debian GNU/Linux 13 (trixie) on Raspberry Pi 4, 64-bit |
| Device | `lsusb`: `05f3:00d2 PI Engineering, Inc. RailDriver Modern Desktop`, attached as `/dev/hidraw0` |
| Capture | `docs/rpi-raildriver/test-data/byte13-capture.raw` (and cleaned `byte13-capture.clean`); 2,323 reports |
| JMRI running? | No (verified before capture) |

### HID report descriptor

```
00000000: 050c 0901 a101 a102 0600 ff09 0115 0026  ...............&
00000010: ff00 3500 46ff 0075 0895 0781 0205 0919  ..5.F..u........
00000020: 0129 3815 0025 0135 0045 0175 0195 3881  .)8..%.5.E.u..8.
00000030: 02c0 a102 0600 ff09 0275 0895 0815 0026  .........u.....&
00000040: ff00 3500 46ff 0091 02c0 c0              ..5.F......
```

Decoded (input report, 14 bytes total):

- Vendor-Defined usage page `0xFF00`, Usage `0x01`, Logical Min 0, Logical Max 255, Report Size 8 bits, **Report Count 7** → bytes 0..6 (the 7 analog axes — vendor-defined, which is exactly why Linux `hid-generic` does not surface them as `ABS_*` codes).
- Button usage page (`0x09`), Usage Min = Button 1, **Usage Max = Button 56**, Logical Min/Max 0/1, Report Size 1 bit, **Report Count 56** → 56 bits packed as 7 bytes → bytes 7..13.

There is no separate sentinel byte declared in the HID descriptor. Byte 13 corresponds to HID buttons 49..56 (or, in `RailDriverMenuItem.java`'s 0-indexed numbering, buttons 48..55).

### Capture procedure (as run)

`script -q -c 'xxd -c 14 /dev/hidraw0' byte13-capture.raw </dev/null` for ~3 minutes 24 seconds. Toggled in turn: every analog lever through its full range; every rotary detent on the wiper and lights selectors; every named momentary button (Bell, both Horn directions, Alerter, Sander, Pantograph, Gear up/down); a representative sample of the 28 numbered blue function buttons; every panel rocker / latching switch; everything else on the controller that has a fixed mechanical state.

(`xxd` auto-coloured its output because `script` made stdout look like a tty; the cleaned file `byte13-capture.clean` was produced with `sed -E 's/\x1b\[[0-9;]*m//g; s/\r$//' byte13-capture.raw | grep -E '^[0-9a-f]+:'`.)

### Analysis output

```
$ awk '{print substr($8,3,2)}' byte13-capture.clean | sort -u
35

$ awk '{print substr($8,3,2)}' byte13-capture.clean | sort | uniq -c
   2323 35

$ awk '{print $8}' byte13-capture.clean | sort | uniq -c | sort -rn
   2255 0035
     22 0135
     16 0235
     15 0835
     15 0435
```

Distinct values seen in each byte:

| Byte | Role | Distinct values | Notes |
|---|---|---|---|
| 0 | reverser (analog) | 20 | swept full forward → centre → full reverse |
| 1 | throttle/dyn-brake (analog) | 32 | swept full throttle → centre → full dyn-brake |
| 2 | auto brake (analog) | 32 | swept release → full apply |
| 3 | indep brake (analog) | 30 | swept release → full apply |
| 4 | bail-off (analog) | 18 | exercised |
| 5 | wiper rotary (analog) | 14 | walked detents |
| 6 | lights rotary (analog) | 14 | walked detents |
| 7 | buttons 0..7 | 9 (`00, 01, 02, 04, 08, 10, 20, 40, 80`) | every bit fired in isolation |
| 8 | buttons 8..15 | 9 | every bit fired in isolation |
| 9 | buttons 16..23 | 9 | every bit fired in isolation |
| 10 | buttons 24..31 | 10 (incl. `c0` = two bits at once) | every bit fired |
| 11 | buttons 32..39 | 10 (incl. `03` = two bits at once) | every bit fired |
| 12 | buttons 40..47 | 5 (`00, 01, 02, 04, 08`) | bits 0–3 fired (Pantograph / Bell / Horn-up / Horn-down) |
| **13** | **buttons 48..55 / sentinel** | **1 (`35` only)** | **never changed across 2,323 reports** |

Bytes 7..12 saw isolated single-bit activity for every bit in their range, plus byte 11 saw `0x03` (two simultaneous bits) and byte 10 saw `0xc0` (two simultaneous bits). So every button bit in range 0..47 demonstrably round-tripped through the capture pipeline. Byte 13 simply never moved.

### Resolved interpretation: byte 13 is a constant sentinel (always `0x35`)

The HID descriptor declares it as buttons 49..56, but the firmware on this controller never sets those bits regardless of physical state. The four bits that are always set (`0x35 = 0b00110101`, bits 0/2/4/5) likely correspond to phantom / unconnected button slots in the firmware (the Modern Desktop has fewer than 56 distinct buttons in hardware; the descriptor declares 56 because PI Engineering uses a common report layout across the RailDriver product family).

The earlier "suspicious bit-count alignment" (4 bits set in `0x35` ↔ 4 latching switches in the "down" state at `jstest` time) was numerically coincidental.

### Implications for `plan.md`

- §1 #5 — drop the latching-switches hedge; record the resolved finding (byte 13 = sentinel; HID descriptor declares it as buttons 49..56 but firmware never sets them).
- §6 — collapse the byte-13 risk row to a one-line "Resolved" entry pointing here.
- §4.5 #0 — done; this file is the artefact.
- §4.5 #1 byte-13 pin-down test — stays in place. It now serves as a regression detector that fires if a future firmware revision starts populating buttons 49..56.
- §4.5 #1 byte-parser per-bit assertion — should pin down bytes 7..12 (events for `n ∈ [0, 47]`); byte 13 in the committed fixtures is genuinely sentinel and emits no events. The "for the committed fixtures specifically, byte 13 produces no events" sub-bullet is now the production contract on the captures, not a hedge.
