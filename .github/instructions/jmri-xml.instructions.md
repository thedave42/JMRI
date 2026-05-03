---
applyTo: '**'
---

# JMRI XML Usage, Schema and Viewing

This file consolidates three JMRI documentation pages:

- <https://www.jmri.org/help/en/html/doc/Technical/XmlUsage.shtml>
- <https://www.jmri.org/help/en/html/doc/Technical/XmlSchema.shtml>
- <https://www.jmri.org/help/en/html/doc/Technical/XmlView.shtml>

Apply these conventions when reading, writing, validating, viewing,
or extending JMRI XML — decoder definitions, panel/configuration
files, persistence files, and website content.

## Why JMRI uses XML

JMRI uses XML for several distinct purposes:

- Decoder definitions (`xml/decoders/*.xml`).
- Persistence: saving/loading panels, tables, configuration, and
  preferences (see the JMRI XML Persistence page).
- Generating parts of the JMRI website from source data.

XML files can be edited in any text editor; a dedicated XML editor
helps a lot. JMRI maintains a list of recommended editors on the
XmlEditors page.

## File-format definitions: schema vs DTD

- **JMRI 2.9.4 and later**: file formats are defined by **XML
  Schema** (XSD).
- **Earlier versions**: DTDs were used. Reading those older files
  is still supported.

Schemas live in `xml/schema/`; DTDs live in `xml/DTD/`. Both must be
locatable when JMRI parses a file — they supply default values for
missing attributes and other necessary information.

JMRI writes schema references using public URLs such as
`http://jmri.org/xml/schema/layout-2-9-6.xsd`:

- The JMRI program automatically maps these URLs to the local copies
  via a custom resolver.
- A web browser fetches the schema directly from the JMRI web
  servers when needed.

DTD locations were resolved from `<!DOCTYPE>` declarations using a
historical, multi-format lookup procedure (documented on the
XmlDtdUsage page).

## Schema location and access

Schemas are kept in a single place — `xml/schema/` — rather than
beside each XML file, because the latter would require keeping
copies in too many directories up to date. The `jmri.jmrit.XmlFile`
class supports locating schemas for the XML parser at runtime.

The current schema set is browsable at
<https://www.jmri.org/xml/schema/>. The most heavily used file is
[`layout.xsd`](http://jmri.org/xml/schema/layout.xsd) (panel files).

## Modifying JMRI schema

Whenever you change what JMRI writes (and therefore reads) in an
XML file, you **must** do all three of:

1. Change the read/write code.
2. Update the schema file(s) so the XML format can still be
   validated.
3. Provide new test XML files (and adjust existing ones if
   necessary).

Do not skip steps 2 and 3 — they are critical for long-term
stability.

JMRI strongly values **backward compatibility**: any newer JMRI
should still load files written by older JMRI versions. Prefer
additive changes whenever possible.

### Additive changes (existing files still validate)

1. Change your code.
2. Add the new elements/attributes to the most-recent version of the
   schema file(s).
3. Validate the changed schema:

   ```sh
   xmllint -noout -schema http://www.w3.org/2001/XMLSchema.xsd myChangedFile.xsd
   ```

4. Run `ant headlesstest` to verify older test files still load.
   Fix anything that breaks. (If you cannot keep them loading,
   either rework the change or version the schema.)
5. Add a test file using the new content. If it can be loaded
   without a screen, place it in a `load` test subdirectory; at a
   minimum, place it in a `valid` subdirectory so the schema check
   exercises it.

### Schema versioning (breaking changes)

Versioning preserves the ability to read older files when the new
format is incompatible with the old.

You **do not** need a new version if existing files still validate
against the modified schema — just commit the changes.

You **do** need a new version when existing files would no longer
validate. Steps:

1. Copy the current schema to a new file named with the next JMRI
   release, e.g. copy `types/turnouts-2-9-6.xsd` to
   `types/turnouts-3-7-3.xsd` ahead of the JMRI 3.7.3 release. Apply
   your changes there.
2. If the file is included from a parent schema (e.g.
   `layout-2-9-6.xsd`), copy that parent too, update its `include`
   reference, and commit it.
3. Update the Java code that writes the schema reference at the top
   of output files. Panel files are written by
   `src/jmri/configurexml/ConfigXmlManager.java` — change the
   `static final public String schemaVersion = "-3-7-3"` (or
   similar) line.
4. Run `ant headlesstest` and fix failures. When `LoadAndStoreTest`
   reports an inLine/outLine compare failure, update the schema
   version in the reference file's header, e.g. in
   `java/test/jmri/configurexml/loadref/BlockAndSignalMastTest.xml`
   line 3:

   ```xml
   <layout-config
     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
     xsi:noNamespaceSchemaLocation="http://jmri.org/xml/schema/layout-4-7-2.xsd">
   ```

5. If there is an associated XSLT stylesheet, rename it in a
   coordinated way and update its content for the new XML.

Provide enough test XML files for the unit tests to exercise both
the old and new schemas (see "Checking JMRI schema" below).

> The unlabelled (no version suffix) schema is the **primordial,
> oldest, now-obsolete** schema. For example, `layout.xsd` is older
> than `layout-2-9-6.xsd` — do not assume `layout.xsd` is the
> default for new files.

## Checking JMRI schema

JMRI schema must be kept semantically correct; problems compound.
Use these tools when changing schema:

- W3C online schema validation tool — good for technical correctness
  (does not chase nested includes from DocBook or other JMRI
  schemas, but still useful).
- The JMRI **Validate XML File** tool under the Debug menu — use
  often during development.
- Same check from the command line:

  ```sh
  ./runtest.csh apps/jmrit/XmlFileValidateRunner xml/decoders/0NMRA.xml
  ```

  A PowerShell equivalent exists; see the JUnit page.

- Linux/macOS quick check via `xmllint`:

  ```sh
  cd xml
  xmllint -schema schema/aspecttable.xsd -noout signals/sample-aspects.xml
  ```

  `xmllint` can validate the schema itself by passing
  `-schema http://www.w3.org/2001/XMLSchema.xsd`.

Schema files should reference the JMRI standard stylesheet in their
header so a browser can render them readably:

```xml
<?xml-stylesheet href="schema2xhtml.xsl" type="text/xsl"?>
```

### JUnit testing

Add unit tests for any schema/format change:

1. **`SchemaTest`** in your test-tree package — validates a typical
   XML file against the schema. See
   [test/jmri/configurexml/SchemaTest.java](https://github.com/JMRI/JMRI/blob/master/java/test/jmri/configurexml/SchemaTest.java).
   Place new sample files in the `valid/` subdirectory; create one if
   it does not exist and append it to `SchemaTest`. Place
   intentionally-malformed examples in `invalid/` (with a comment
   explaining what makes them invalid) to exercise specific failure
   modes.
2. **`LoadAndStoreTest`** when working on configurexml (panel) files
   that do not require a display. See
   [test/jmri/configurexml/LoadAndStoreTest.java](https://github.com/JMRI/JMRI/blob/master/java/test/jmri/configurexml/LoadAndStoreTest.java).
   It loads each file in `load/`, stores it back to `temp/` in the
   local preferences directory, and compares input and output. If
   the comparison fails but the output is otherwise correct, copy
   the output to a `loadref/` directory; `LoadAndStoreTest`
   compares against `loadref/` files when present (see
   [test/jmri/configurexml/loadref](https://github.com/JMRI/JMRI/tree/master/java/test/jmri/configurexml/loadref)).
   Tests run headless on Jenkins — do **not** add tests that pop
   windows.
3. Optional **custom JUnit tests** that load a sample file and
   check the resulting JMRI objects' state.

If your code change breaks loading of an older test file, **do not
modify the older file**. Instead:

- add an updated reference output in `loadref/`, or
- version the schema so the older format still loads, or
- improve your code.

Backward compatibility matters. Do not remove or modify existing XML
test files — they protect older formats.

## Developing JMRI schema

Preferred organisation: each `*Xml` class in the code is the unit of
reuse — schema is structured around the classes that read/write it.
Many classes descend from `jmri.configurexml.XmlAdapter`.

By convention, each schema element identifies the class that
reads/writes it via `<xs:annotation><xs:appinfo>`:

```xml
<xs:annotation>
    <xs:documentation>
        Some human readable docs go here
    </xs:documentation>
    <xs:appinfo>
        <jmri:usingclass configurexml="false">jmri.managers.DefaultSignalSystemManager</jmri:usingclass>
    </xs:appinfo>
</xs:annotation>
```

For schema examples, see the JMRI XML Schema Examples page.

### Venetian Blinds pattern

JMRI is migrating to the **Venetian Blinds** schema-design pattern:
top-level elements written by classes have named types; elements
nested inside them are defined anonymously inside those types. See
[`types/sensors.xsd`](http://jmri.org/xml/schema/types/sensors.xsd)
for an example — it defines a type for `sensors` (used by
`SensorManager`s) which itself contains an anonymous `Sensor`
element with an anonymous `comment` element.

This keeps the schema aligned with the reader/writer classes and
limits the number of named types.

A small set of cross-cutting elements and attribute groups live in
[`types/general.xsd`](http://jmri.org/xml/schema/types/general.xsd).

### Elements vs attributes

When designing new storage:

- **Data** → use a child **element** (e.g. comments, speed values,
  user names, system names).
- **Modifier describing the data** → an **attribute** is acceptable
  (e.g. label colour, turnout-inverted flag, which icon to load).

JMRI XML originally leaned heavily on attributes due to old JDOM
limitations; those are gone. Move toward elements where
appropriate.

### Pre-defined data types

Use the standard types provided by the JMRI schema rather than
reinventing them — they are validated and maintained centrally.
Examples:

| Type                  | Meaning                                      |
|-----------------------|----------------------------------------------|
| `systemNameType`      | System names (validation will be tightened) |
| `userNameType`        | User names, non-empty                        |
| `nullUserNameType`    | User names; empty allowed                    |
| `beanNameType`        | Either user or system name                   |
| `turnoutStateType`    | `closed`, `thrown`                           |
| `signalColorType`     | red, yellow, …                               |
| `trueFalseType`       | `true`, `false`                              |
| `yesNoType`           | `yes`, `no`                                  |
| `yesNoMaybeType`      | `yes`, `no`, `maybe`                         |

For the full list, browse
[`types/general.xsd`](http://jmri.org/xml/schema/types/general.xsd).

## Copyright, author and revision metadata

JMRI is moving to **DocBook**-format Copyright/Author/Revision blocks
in instance files. Sample (mirroring the structure used in
[`xml/decoders/0NMRA.xml`](https://www.jmri.org/xml/decoders/0NMRA.xml)):

```xml
<copyright>
    <year>2009</year>
    <year>2010</year>
    <holder>JMRI</holder>
</copyright>
<authorgroup>
    <author>
        <personname>
            <firstname>Sample</firstname>
            <surname>Name</surname>
        </personname>
        <email>name@com.domain</email>
    </author>
</authorgroup>
<revhistory>
    <revision>
        <revnumber>1</revnumber>
        <date>2009-12-28</date>
        <authorinitials>initials</authorinitials>
    </revision>
</revhistory>
```

The schema for these blocks lives at
[`schema/docbook`](https://www.jmri.org/xml/schema/docbook).

## External standards and future direction

OASIS-standard schemas of interest:

- **DocBook** — used for `author`, `address`, `revhistory`, etc.
  JMRI ships a small DocBook subset at
  <http://jmri.org/xml/schema/docbook/docbook.xsd> (the full
  DocBook 5.0 schema is too slow to parse and not always consistent
  with our other tooling). The standard DocBook namespace is used,
  so a fuller schema can be substituted later.
- **UBL** — defines elements for parties, devices, model numbers,
  etc.
- **OpenDocument (OODF)** — has spreadsheet-computation elements,
  but ships only Relax-NG schemas, which limits its usefulness here.

JMRI cannot assume internet access on user machines, so external
schemas cannot simply be referenced as remote entities — they must
be packaged or vendored.

## Viewing JMRI XML files

Most JMRI-created XML files render in a browser via XSLT
stylesheets shipped with JMRI (see
[the sample panel file](https://jmri.org/xml/samples/TwoColumnMachine.xml)
for an example of the formatted output).

### Two viewing methods

1. **Open the file in a web browser.** Often double-clicking the
   file or dragging it onto the browser is enough; on some
   platforms use File → Open. XSLT support varies:
   - Mozilla, Safari, Opera, and Chrome generally work.
   - Recent versions of Internet Explorer/Edge work; very early IE
     versions are problematic.
   - Firefox is platform-dependent.
   - Browser settings can affect XSLT behaviour. See the
     `xml/XSLT/README` notes on the JMRI site for background.
2. **Use the JMRI mini web server** (JMRI 2.9.4+). The XML file
   must also have been written by JMRI 2.9.4 or later (older files
   should be loaded and re-stored once with a current JMRI).

   Steps:
   - Start DecoderPro or PanelPro (no layout connection needed).
   - Start the web server:
     - PanelPro: **Tools → Servers → Start JMRI Web Server**
     - DecoderPro: **Actions → Start Web Server**
   - Open <http://localhost:12080> in any browser.

   The home page (header **My Layout** with Panels / Roster /
   Operations menus and Throttles / Open Windows / Utilities /
   File Access sections) provides two key links at the bottom
   right under **File Access**:
   - `/dist` — files inside the JMRI distribution directory.
   - `/prefs` — files inside the user preferences directory. Key
     entries here include:
     - panel files (`<your-name>.xml`) saved from PanelPro,
     - `roster.xml` listing locos from DecoderPro.

   Sub-directories are browsable by clicking through. Once you have
   located the file, view, print, or save it from the browser as
   normal.
