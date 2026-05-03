---
applyTo: '**'
---

# JMRI XML Persistence

This file summarises the JMRI XML persistence documentation at
<https://www.jmri.org/help/en/html/doc/Technical/XmlPersistance.shtml>.
Apply these conventions when reading, writing, or refactoring code in
this repository that stores objects to or loads them from JMRI XML
files (preferences, panels, configuration, decoder data, etc.).

For the schema/format rules, validation tooling, and viewing
conventions that go with these files, see
`jmri-xml.instructions.md`.

## Overview

JMRI uses XML to persist internal structures — most prominently the
preferences and panel files. Persistence is **not** automatic; it is
implemented by explicit code:

- Objects that need to persist register themselves with a
  `jmri.ConfigureManager`. The default implementation that stores to
  / loads from XML files is `jmri.configurexml.ConfigXmlManager`.
- On **store**, the manager walks its registered objects and
  delegates to a persistence class for each one. The persistence
  class is located by appending `Xml` to the class name and looking
  in a `configurexml` subpackage — so `a.b.Foo` is stored by
  `a.b.configurexml.FooXml`. If no such class is found an error is
  issued.
- The persistence class adds JDOM XML content describing the object.
- On **load**, the manager reads the XML and inspects each element's
  `class` attribute. That class is loaded and handed the element to
  process.

The basic structure is clean; in practice the `*Xml` classes contain
a lot of replication and special-case handling, which is why they
need extensive JUnit and CI testing.

## Where persistence code lives

Persistence classes live in `configurexml` subpackages alongside the
classes they serialise. Conventions:

- Persistence class names mirror the class they persist with `Xml`
  appended (e.g. `Foo` → `FooXml`).
- Many persistence classes descend from
  `jmri.configurexml.XmlAdapter` /
  `jmri.configurexml.AbstractXmlAdapter` (or shared abstract bases
  such as `jmri.managers.configurexml.AbstractSensorManagerConfigXML`)
  to reuse common store/load behaviour.
- **Keep persistence logic in the `*Xml` classes.** Do not add
  storage-format translation code into the primary classes
  (`SerialSensorManager`, `InternalSensorManager`, …). Keeping the
  two separate keeps the runtime classes flexible and lets the
  persistence code be tested and debugged independently.

### Example: Lights and managers

`LightManager` knows about `Light`s. There are several `Light`
implementations (`LnLight`, `cmri.serial.SerialLight`,
`powerline.SerialLight`) and matching managers (`LnLightManager`,
`cmri.serial.SerialLightManager`,
`powerline.SerialLightManager`).

Each manager is persisted through a `*ManagerXml` class located in a
`configurexml` subpackage:

- `jmri.jmrix.loconet.configurexml.LnLightManagerXml`
- `jmri.jmrix.cmri.serial.configurexml.SerialLightManagerXml`
- `jmri.jmrix.powerline.configurexml.SerialLightManagerXml`

Because each `Light` manager only handles a single concrete `Light`
type, the manager-level persistence code stores and loads the
individual `Light`s directly. For types where one manager carries
multiple concrete classes (e.g. `SignalHead`s), there are also
per-object `*Xml` classes alongside the manager's `*Xml` class.

## Adding state to a persisted class

To store more state on an existing persisted class, edit its `*Xml`
persistence class — not the primary class.

A useful workflow is to create a sample panel file containing the
objects you care about so the structure is visible. Each persisted
element carries a `class` attribute identifying the responsible
persistence class:

```xml
<sensors class="jmri.jmrix.cmri.serial.configurexml.SerialSensorManagerXml">
    <sensor systemName="CS3001" />
</sensors>
<sensors class="jmri.managers.configurexml.InternalSensorManagerXml">
    <sensor systemName="IS21" />
</sensors>
<signalheads class="jmri.configurexml.AbstractSignalHeadManagerXml">
    <signalhead class="jmri.configurexml.DoubleTurnoutSignalHeadXml"
                systemName="IH1P">
        <turnout systemName="CT10" userName="1-bit pulsed green" />
        <turnout systemName="CT2"  userName="1-bit pulsed red" />
    </signalhead>
</signalheads>
```

In this snippet there are two sensor managers (one C/MRI, one
internal); SignalHeads use a single manager but each concrete
`SignalHead` class has its own per-object persistence class.

To extend, e.g., the C/MRI sensor information, modify
`jmri.jmrix.cmri.serial.configurexml.SerialSensorManagerXml` (and
`jmri.managers.configurexml.InternalSensorManagerXml` for the
internal flavour).

When adding new attributes/elements:

- Update the corresponding XML schema (and add tests). See
  `jmri-xml.instructions.md` for the schema-modification workflow.
- Store boolean values as the strings `"true"` / `"false"`.
- Use the parsing helpers on `AbstractXmlAdapter` to read values
  with consistent error handling:
  - `getAttributeBooleanValue(...)`
  - `getAttributeIntegerValue(...)`
  - `getAttributeDoubleValue(...)`
- **Prefer child elements over attributes** for stored data. Old
  code leans heavily on attributes (a JDOM-era artefact); attributes
  are still acceptable for modifiers, but data should normally live
  in elements. Element-based content is easier on schemas, XSLT
  stylesheets, and human readers (see
  `jmri-xml.instructions.md` for the elements-vs-attributes rule).
- Look for an inheritance relationship that already does the work.
  E.g. `LnSensorManagerXml` extends
  `jmri.managers.configurexml.AbstractSensorManagerConfigXML`,
  which handles almost everything.

## Handling errors

Parse errors, missing data, and exceptions must be reported through
the JMRI `ErrorHandler`. It accumulates reports and presents them to
the user when appropriate. Do **not** silently swallow errors or
print to stdout/stderr. See `getAttributeBooleanValue` for an
example of the pattern.

## Persisting enum values

`AbstractXmlAdapter` provides an **`EnumIO`** helper for storing and
loading enums by name. Declare a static map for the enum, then run
all writes/reads through it.

```java
static final EnumIO<MyEnum> enumMap = new EnumIoNamesNumbers<>(MyEnum.class);

// store
element.setAttribute("name", "" + enumMap.outputFromEnum(myEnumValue));

// load
myEnumValue = enumMap.inputFromAttribute(element.getAttribute("name"));
```

Variants:

- `EnumIoNames` — stores/loads enum element names only.
- `EnumIoNamesNumbers` — stores names; on load also accepts a small
  integer `n` (translated to the `n`-th enum value) so older numeric
  files keep working.
- `EnumIoOrdinals` — stores/loads via the enum's ordinal number.
  Provides full backwards compatibility with files written this
  way.
- `EnumIoMapped` — arbitrary user-supplied mapping; multiple input
  values can map to the same enum value (e.g. both `"3"` and
  `"Ralph"` map to `Ralph`).

See the `AbstractXmlAdapter` Javadoc for full details.

## Persisting references to NamedBeans

Classes should hold references to `NamedBean`s through
`NamedBeanHandle`s (see `jmri-patterns.instructions.md`). If you
are adding persistence to a class that does not yet do this, fix
that first — it saves trouble later.

To store a `NamedBeanHandle` reference, write the value of
`handle.getName()` (the name the user knows it by) to the XML.

To load:

1. Read the stored name back from the XML.
2. Look up the `NamedBean` from the appropriate manager (typically
   `manager.get(String)`).
3. Create a fresh handle via the manager:

   ```java
   NamedBeanHandle<MyBean> handle =
       InstanceManager.getDefault(jmri.NamedBeanHandleManager.class)
                      .getNamedBeanHandle(name, bean);
   ```

## Class migration

When a class is moved to a new package, its fully-qualified name is
already written into existing user XML files. Just renaming would
break loading of those files (and any user-written code that
references the class).

To migrate cleanly:

- Move the `*Xml` persistence class to its new location alongside
  the class it persists.
- Add a mapping from the old fully-qualified name to the new one in
  `java/src/jmri/configurexml/ClassMigration.properties`.
- Optionally, leave behind an empty `*Xml` stub at the old location
  that inherits from the new one and is marked
  `@Deprecated`. This keeps third-party code (which may inherit
  from the old class) working. The stub can be removed after a
  reasonable transition period.

There is also a service-oriented variant. See
`jmri.configurexml.ClassMigration` and
`jmri.jmrix.pi.configurexml.RaspberryPiClassMigration` for an
example.

## Schema management

JMRI controls the semantics of these XML files with **XML Schema**.
For example, layout (panel) information is stored as a
`<layout-config>` element whose content is defined by schemas under
`xml/schema/`.

For all schema-modification, validation, and JUnit-test workflow
details, see `jmri-xml.instructions.md`.
