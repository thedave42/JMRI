---
applyTo: '**'
---

# JMRI Code Patterns and Organization

This file summarises the JMRI code patterns described at
<https://www.jmri.org/help/en/html/doc/Technical/Patterns.shtml>.
Apply these conventions when reading, writing, or refactoring code in
this repository.

JMRI has evolved over time, so older code does not always reflect the
currently-preferred structure. The patterns below are the recommended
ones; prefer them when adding or restructuring code.

## Names, NamedBeans and Managers

`NamedBean` is the basic JMRI object that represents something on the
layout (e.g. a specific Sensor or Turnout).

- "Bean" because it is a unit of interaction — multiple pieces of
  code work with it, it can be loaded/saved, etc.
- "Named" so it is unique and retrievable: there is only one Turnout
  named `LT01`, representing one specific addressed object. See the
  companion `jmri-names.instructions.md` (Names page) for the full
  naming rules.

All device classes (`Sensor`, `Turnout`, …) and their concrete
implementations (`LnSensor`, `XNetTurnout`, …) inherit from
`jmri.NamedBean`.

### Naming and handles

Access a specific `NamedBean` through its manager, which itself comes
from the `InstanceManager`:

```java
TurnoutManager tm = InstanceManager.getDefault(TurnoutManager.class);
Turnout t = tm.getTurnout("LT12");
```

Users often refer to a `NamedBean` by user name and may later move
that user name to a different bean ("Yard East Turnout" might be
`LT12` today, `CT5` tomorrow). To handle this transparently, store
references using `NamedBeanHandle` objects rather than direct
references:

```java
NamedBeanHandle<Sensor> handle =
    InstanceManager.getDefault(NamedBeanHandleManager.class)
                   .getNamedBeanHandle(name, sensor);
```

where `name` is the user-supplied string (system or user name) and
`sensor` is the concrete `Sensor` object.

When you need the underlying bean:

```java
Sensor s = handle.getBean();
```

**Always call `getBean()` every time** you need the bean. Do not
cache the returned reference. If a "move" or "rename" happens, the
`NamedBeanHandle` is updated and the next `getBean()` call returns
the correct reference.

### Bean properties

`NamedBean`s usually carry state expressed as Java Bean properties
(e.g. a `Sensor` is `Active`, `Inactive`, `Unknown`, or
`Inconsistent`). Use the standard `PropertyChangeListener` pattern in
Java or Jython to observe state changes. Example: a `Turnout`
configured for feedback registers as a listener on the feedback
`Sensor`'s state property and updates its own `KnownState` property.

The available properties are normally defined in the abstract base
class. For instance `AbstractTurnout` defines `CommandedState`,
`KnownState`, `feedbackchange`, `locked`, and others. These property
names are not system-dependent.

Some properties are run-time only (e.g. is the turnout thrown or
closed) while others are configuration settings the user selects and
that persist between sessions (e.g. turnout feedback mode).

### Editing and saving NamedBeans

`NamedBean`s are created and configured by explicit user actions.
Most of that UI lives in the `jmri.jmrit.beantable` package, using
the generic `BeanTableFrame` / `BeanTablePane` / `BeanTableModel`
classes specialised per bean type (e.g. `TurnoutTableAction`). The
configuration options shown in the table and edit dialog are
type-specific (`Turnout`) but **not** system-specific.

When the user saves the configuration (or panel) XML, beans are
persisted via the system- and object-specific `*ManagerXml` classes
(e.g. `LnTurnoutManagerXml`, `OlcbTurnoutManagerXml`). These rely
heavily on shared code in abstract base classes such as
`AbstractTurnoutManagerConfigXML`, but may add system-specific
behaviour and cooperate with the system-specific manager (e.g.
`OlcbTurnoutManager`).

The base class handles persisting user settings entered through the
BeanTable.

### System-specific properties

Adding a system-specific property must use a generic API because
`jmri.jmrit.beantable` cannot depend on `jmri.jmrix.*` packages
(see `jmri-structure.instructions.md`).

All `NamedBean`s expose `setProperty(String key, Object value)` and
`getProperty(String key)`. These arbitrary properties are persisted
into the XML by the `ManagerXml` base class — no extra persistence
code is needed. Basic types (`Integer`, `Boolean`, …) work directly.
Custom types must provide:

- a `toString()` that produces a parseable representation, and
- a public constructor taking a single `String` that round-trips
  that representation.

To let the user edit these properties, the system-specific `Manager`
declares them by returning suitable `NamedBeanPropertyDescriptor`
objects from `Manager.getKnownBeanProperties()`. The descriptor tells
the BeanTable which extra columns to display, what data type each
holds, and the localised header text. System-specific columns are
hidden by default; a checkbox (only shown when system-specific
properties exist) reveals them. Column header text must be a
localised string from the relevant `Manager`'s `Bundle`.

## Service providers

JMRI uses Java's Service Provider Interface (SPI) mechanism so the
code can discover available extensions automatically rather than
hard-coding them.

For example, annotating a class with:

```java
@ServiceProvider(service = PreferencesManager.class)
```

automatically registers it with the JMRI Preferences System without
any change to the preferences classes themselves. This supports
incremental, modular builds.

Available SPI patterns (refer to the linked Javadoc on the JMRI site
for full details):

- `ConnectionTypeList`
- `HttpServlet` (Java standard, not JMRI-defined)
- `InstanceInitializer` — lets the `InstanceManager` create an
  instance of a class on demand.
- `JsonServiceFactory`
- `PreferencesManager`
- `PreferencesPanel`
- `SignalMastAddPane` — type-specific pane for adding/editing
  `SignalMast` concrete objects.
- `StartupActionFactory`
- `StartupModelFactory`
- `WebManifest`
- `WebServerConfiguration`

See the JMRI Plug-in page for additional SPI extension points.

### Registration

SPI providers must be registered. JMRI uses entries under
`target/classes/META-INF/services/`, which are generated
automatically from the source-level `@ServiceProvider` annotations
during the build and packaged into `jmri.jar`.

### Access

Look up providers via the standard Java `ServiceLoader`:

```java
java.util.ServiceLoader.load(OurServiceClass.class)
    .forEach(ourServiceObject -> {
        // use ourServiceObject
    });
```

## JavaScript and TypeScript code

- JavaScript intended for the JMRI web server goes in `web/js/`.
  This is the directory served by the JMRI web server.
- TypeScript intended for the web server goes in `web/ts/`. The
  `ant typescript` target compiles it into `web/js/`. A TypeScript
  compiler must be installed locally to run that target.
- JavaScript used for scripting (rather than serving) should
  normally go in `jython/`, although users can run scripts from any
  location.
