---
applyTo: '**'
---

# JMRI Names and Naming

This file summarises the JMRI naming conventions described at
<https://www.jmri.org/help/en/html/doc/Technical/Names.shtml>.
Apply these conventions when reading, writing, or refactoring code in
this repository that creates, parses, compares, or displays JMRI
object names.

## Why names exist

Names are needed in addition to direct Java references because:

1. User input (typed in fields, scripts, etc.) must be mapped to
   objects. Sometimes the user gives free-form names like "East
   Lockport Turnout"; other times the input is a hardware-specific
   identifier such as "LocoNet Turnout 23".
2. Configurations are stored symbolically in XML files. Names are the
   convenient way to connect objects and references in those
   configurations.

## Items with names

Many things have names: Turnouts, Sensors, and other layout elements
tied to specific hardware, plus more virtual objects like Routes that
exist only inside the program.

## System names and user names

JMRI uses both system names and user names to reference things.

- **User name** — entirely free-form string chosen by the user. Must
  be unique across user names but may be anything otherwise (e.g.
  "West Yard Lead", "Turnout 32", "Green Wire from Controller").
- **System name** — a shorthand unique identifier with a clear,
  defined mapping to the underlying object/hardware (e.g.
  "LocoNet Turnout 23"). JMRI code maps these to and from whatever
  the hardware needs.

Since JMRI 4.7.4 (March 2017), user names cannot have leading or
trailing whitespace; entered whitespace is trimmed off.

## System name format

A system name is built from three parts:

1. **Connection prefix** — a single uppercase or lowercase letter,
   optionally followed by one or more digits. No special characters.
   Examples: `S`, `s`, `S2`, `s2` are all valid and distinct.
2. **Type letter** — a single uppercase letter identifying the kind
   of model-railroad object (e.g. `T` for Turnout, `S` for Sensor).
3. **System-specific suffix** — identifies a specific object within
   the system; format is system- and type-specific (sometimes a
   simple number, sometimes a complex string).

The simple connection-prefix form is used so the prefix and type
letter can always be located even when the full list of hardware
systems is not known.

Examples (assuming the default connection prefixes):

- `LT23` — LocoNet Turnout 23.
- `L2T23` — Turnout 23 on the second LocoNet connection.
- `CS2012` — C/MRI Sensor (input), node 2 pin 12.

There is no requirement that suffixes be assigned in numeric order;
`CS1001`, `CS1021`, `CS3044`, `LS1001`, `cS1001`, etc. may all coexist.

### Connection prefix

Originally a single uppercase letter (e.g. `L` for LocoNet, `N` for
NCE). The default letter for each system is listed below. Most users
have a single connection and use the default.

The prefix can be reconfigured in preferences to any other uppercase
or lowercase letter, or a letter followed by digits (e.g. `N1`,
`n2`). This allows multiple connections of the same kind, and resolves
overlaps such as the multiple uses of `D` or `M`.

Three special cases always use a fixed prefix and type letter
regardless of any configured connection:

- OBlocks always use `OB`.
- Transponding tags always use `LD`.
- RailComm tags always use `RD`.

Some older implementations used non-conforming prefixes such as
`DX`, `DCCPP`, `DP`, `MR`, `MC`, `PI`, `TM`. These need to be migrated
using JMRI's documented migration process.

#### Default connection letters

(Some are placeholders without an underlying implementation.)

| Letter | Default system(s) |
|--------|-------------------|
| A | CTI Acela; Bachrus Speedometer |
| B | Direct DCC control |
| C | C/MRI serial |
| D | SRCP; Anyma DX512; DCC++; DCC4PC |
| E | EasyDCC |
| F | RFID tag readers |
| G | ProTrak Grapevine |
| H | (unassigned) |
| I | Internal (objects with no associated hardware) |
| J | JMRI network connections |
| K | Maple Systems |
| L | LocoNet (Digitrax) |
| M | OpenLCB / CBUS®; MRC; Marklin CS2 |
| N | NCE (also Wangrow currently) |
| O | Oak Tree Systems |
| P | Powerline (X10, Insteon); Raspberry Pi native pins |
| Q | QSI programmer interface |
| R | RPS system |
| S | SPROG |
| T | Lionel TMCC; TAMS |
| U | ESU ECoS |
| V | TracTronics SECSI |
| W | Reserved for Wangrow (still combined with NCE) |
| X | XpressNet (Lenz, Atlas, Hornby, etc.) |
| Z | Zimo MX-1; IEEE 802.15.4; Z21 |

### Device type letters

(Some are placeholders.)

- `A` — Audio (sound sample placed in 3D space).
- `B` — Block. System names mostly start with `IB`.
- `C` — String of characters (text, file name).
- `D` — iDentity (rolling-stock ID tag). Always Internal; system
  names start with `ID`. Hardware reports via Reporters.
- `F` — signal mast / signal system (defines aspects available to
  signal masts).
- `G` — signal Group (set of signal Heads on a mast). New items
  should use `IG…` (e.g. `IG12`); single-letter system names are no
  longer compatible with system-name validation since JMRI 4.13.4.
- `H` — signal Head (one part of a signal). Includes panel
  indicators acting as signal aspects. System names mostly start
  with `IH`.
- `L` — Light (output controlling layout lights).
- `M` — Memory (temporary storage shown on panels). System names
  mostly start with `IM`.
- `N` — eNtry/exit destination points.
- `O` — rOutes (sets multiple Turnout states at once). Prior to
  4.19.2 the letter `R` was shared between Reporters and Routes.
- `P` — Power manager (layout, district, subdistrict). The system-
  specific suffix distinguishes them (e.g. `LPB` for main layout
  power; `LPS42` for subdistrict channel 2 on card 4).
- `Q` — LogixNG (Logix Next Generation). System names start with
  `IQ`.
- `R` — Reporters (general layout reporting: transponding,
  RFID, etc.).
- `S` — Sensors (general-purpose ACTIVE/INACTIVE inputs, often
  block occupancy detectors).
- `T` — Turnout (general-purpose layout output).
- `V` — analog Variable (integer or floating value, e.g. current
  meter).
- `W` — Warrant (information to run an automated train).
- `X` — logiX (logic equations). System names mostly start with
  `IX`.
- `Y` — sectionS (map of trackwork). System names mostly start with
  `IY`.
- `Z` — transit (group of Sections forming a path through the
  layout). System names mostly start with `IZ`.

### System-specific suffix

Internal objects (no hardware correspondence) may also be addressed,
e.g. a signal head implemented from outputs `LT1`, `LT2`, `LT3`
might be `IH3`.

Each hardware system specifies its own suffix format. See the
hardware-specific documentation pages for full details (C/MRI,
LocoNet, Grapevine, XpressNet, CBUS, NCE, powerline, etc.).

## Naming conventions for automated use

Some higher-level tools generate names for the objects they create
(e.g. a Sensor Group is implemented as a series of Routes named
`SENSOR GROUP:my group:LS1`, `SENSOR GROUP:my group:LS2`, ...).

To keep auto-generated names from colliding with user-entered ones,
two informal rules apply:

- Users should not use `:` (colon, 0x3A), `"` (double quote, 0x22), or
  `$` (dollar sign, 0x24) in system or user names. Automatic tools
  should use at least one of these characters in names they generate.
  Quotes should always be used in pairs to allow nesting.
- Auto-generating tools should embed the tool name into any system
  names they create (e.g. `SENSOR GROUP` above).

Tools currently using this approach include:

- `Logix` — auto-generated names of the form `IX:AUTO:0001`.
- `SENSOR GROUPS` (`jmri.jmrit.sensorgroup`).
- SignalHeads, particularly the SE8C signal head.
- USS CTC (`jmri.jmrit.ussctc`).

## Notes

- A few devices (e.g. the DCC programmer) are not really named
  because there is no concept of more than one yet.
- `Conditionals` are a special case: although they have "System
  Names" and "User Names", they do not fully obey these conventions.
  See the `ConditionalManager` Javadoc.
- The system-name convention does not specifically distinguish
  multiple adapters of the same type. Either give the second one a
  separate connection letter (e.g. `L`, then `M`) or use a modifier
  (e.g. `L`, then `L2`). Both approaches are supported.
- There is no provision for a single program to handle more than
  one layout.

## For programmers

### Normalised form of names

User names are kept in a normal form that disallows leading or
trailing whitespace. Always use:

```java
String userName = NamedBean.normalizeUserName(input);
```

when creating a user name from human input or any other source. Do
**not** call `String.toUpperCase()`, `String.strip()`, or any other
formatting operation directly — having those scattered across the
code is unmaintainable.

System names vary by type and manager, so two manager-specific
methods exist:

- `manager.validateSystemNameFormat(input, locale)`
- `manager.makeSystemName(input)`

These belong on the manager because managers know about the complete
set of NamedBeans and the system-specific format rules.

An individual `NamedBean` constructor (e.g.
`jmri.jmrix.internal.InternalTurnout`) cannot access those manager
methods. If such a constructor is given a system name it cannot
parse, it must throw `NamedBean.BadSystemNameException`. The
constructor must **not** transform the system name in any way — it
either uses it as given or throws.

See the Javadoc for `normalizeUserName`, `validateSystemNameFormat`,
`makeSystemName`, `BadUserNameException`, and `BadSystemNameException`.

### Preferred input mechanisms

Prefer input methods that already handle normalisation and
validation rather than parsing names directly:

- `jmri.swing.NamedBeanComboBox` — combo-box selector backed by a
  manager.

  ```java
  selectComboBox = new NamedBeanComboBox<>(
      InstanceManager.getDefault(SensorManager.class),
      currentSensor,
      NamedBean.DisplayOptions.DISPLAYNAME);
  // ...
  selectComboBox.getSelectedItem();
  ```

- `jmri.jmrit.picker.PickListModel` / `PickSinglePanel` — table-style
  picker that returns a `NamedBeanHandle<T>`.

  ```java
  PickListModel tableModel = PickListModel.sensorPickModelInstance();
  PickSinglePanel panel = new PickSinglePanel(tableModel);
  // ...
  panel.getSelectedBeanHandle();
  ```

  Listen for selection changes by adding a `ListSelectionListener` to
  `panel.getTable().getSelectionModel()`.

These let the user pick by either system or user name and yield a
`NamedBeanHandle` that records the chosen form. See the code-patterns
page for more on `NamedBeanHandle` and `NamedBeanHandleManager`.

### System-name comparisons

Always use one of the provided comparators rather than rolling your
own:

- `NamedBeanComparator` — comparator over the `NamedBean` objects.
  Preferred. Can perform type-specific sorting of the suffix because
  it has access to the bean.
- `SystemNameComparator` — comparator over `String` values
  (deprecated). Sorts the suffix only with an alphanumeric-by-chunks
  algorithm because it has no system-specific knowledge.

Both comparators sort first by connection prefix (grouping objects
from one system), then by type letter alphabetically, then by the
system-specific suffix.

If you create a system with complex suffix information, override
`NamedBean.compareSystemNameSuffix()` in your `NamedBean` subclass
so type-aware ordering works. See `jmri.jmrix.cmri.serial.SerialTurnout`
and its test for an example.
