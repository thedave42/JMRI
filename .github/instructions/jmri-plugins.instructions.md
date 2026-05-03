---
applyTo: '**'
---

# JMRI Plug-ins (Extending JMRI Programs)

This file summarises the JMRI extension/plug-in mechanisms described
at <https://www.jmri.org/help/en/html/doc/Technical/plugins.shtml>.
Apply these conventions when adding new function to the existing
JMRI applications (DecoderPro, PanelPro, etc.) without rebuilding
them from scratch.

JMRI provides three supported ways to plug additional capabilities
into the existing applications:

1. **Script JMRI** — Jython / JavaScript scripting.
2. **Add Java code** — write a new tool inside the JMRI source tree
   following the standard structure.
3. **Implement a Service Provider** — supply an SPI implementation
   that JMRI discovers automatically.

See also the dedicated pages on adding a new system (a new hardware
connection that implements Turnouts, Sensors, etc.) and adding a new
type (something beyond Turnouts, Sensors, etc.).

## Script JMRI

Scripting (Jython / JavaScript) is often the easiest way to extend
JMRI. Limitations:

- Scripts run only late in application startup.
- Scripts cannot define new connection types.
- Scripts cannot add items to the Preferences window.

For the full scripting workflow see the JMRI scripting tools page.

Examples in the distribution that modify JMRI behaviour:

- `jython/AddButton.py` — add a script button to the main window.
- `jython/DisableOpsMode.py` — remove the ops-mode programming
  button from the main window.
- `jython/ReporterFontControl.py` — change panel-screen item
  appearance.

## Adding Java code

For larger functionality (ideally upstreamable into JMRI), write
Java code that:

1. Creates objects following the standard JMRI structures (see
   `jmri-structure.instructions.md`).
2. Persists those objects via `configurexml` classes that load and
   store them inside standard panel files (see
   `jmri-xml-persistence.instructions.md`).
3. Optionally provides a Swing GUI fired from an action class and
   triggered from a button or menu item (see
   `jmri-swing.instructions.md`).
4. Optionally fires that action at startup via the **Perform
   action…** entry on the Startup preferences pane (see SPI
   section below).
5. Optionally adds its own Preferences pane to store extra
   configuration.
6. Eventually has CI unit tests, Javadoc, and help pages.

A practical development order is:

- Get the runtime objects working first; create them initially via
  a script.
- Add the `configurexml` load/store classes so the objects can be
  persisted.
- Build a Swing GUI for creating the objects, invoked first from a
  one-line script and later attached to a menu/button via a
  `StartupActionFactory`.

### Where to put the source

Two acceptable locations:

- **Top-level package** — a new `java/src/mycooltool` directory
  alongside `java/src/jmri` and `java/src/apps`. Files start with
  `package mycooltool;`.
- **Within the JMRI tree** — a new `java/src/jmri/jmrit/cooltool`
  directory. Files start with `package jmri.jmrit.cooltool;`.

Both layouts work; choose the one that fits how the code will
eventually be merged or distributed.

### Reference example: `jmri.jmrit.sample`

The `jmri.jmrit.sample` package
([source](https://github.com/JMRI/JMRI/tree/master/java/src/jmri/jmrit/sample))
demonstrates the pattern. It contains:

- `SampleFunctionalClass` — the runtime object holding state.
- `configurexml.SampleFunctionalClassXml` — the persistence class
  that stores/loads `SampleFunctionalClass` to/from a panel file.
- `swing.SampleConfigPane` — a Swing configuration pane wired into
  the Preferences window.
- A complete set of basic test classes (constructor checks ready to
  be extended).

Use this package as a starting template for new functions.

## Implement a Service Provider

When the new functionality is a well-defined technical capability,
implement it as a Java **Service Provider** (SPI). JMRI then
discovers it automatically — no changes are needed in JMRI core
classes.

Services are packaged into a JAR that is added to the JMRI
classpath. See the JMRI Startup Scripts page for classpath details
and the standard `ServiceLoader` documentation for the JAR layout.

### JMRI extension points (SPI)

JMRI uses SPI for the following:

- **`StartupActionFactory`** — adds an entry to the **Perform
  action…** chooser on the Startup preferences pane (and to the
  **Attach Action to Button…** chooser). Existing entries seen by
  the user include items such as "Check for Updates", "Load
  Default Throttle Layout", "New Panel", "Open Analog Clock",
  "Open Audio Table", "Open Block Table", "Open Consisting
  Tool", etc. Implementations may also expose multiple actions
  (e.g. one per system connection). Example:
  `RosterFrameStartupActionFactory` (opens DecoderPro's roster
  window).
- **`StartupModelFactory`** — adds an entry to the **Add** dropdown
  in the Startup preferences pane. The standard menu shows: **Add
  button to main window…**, **Add script to button…**, **Open
  file…**, **Pause…**, **Perform action…**, **Run script…**, **Set
  route…**. Each `StartupModelFactory` provides the UI hooks for
  the user to set parameters for that particular start-time
  action. Examples:
  - `PerformActionModelFactory` — provides the **Perform Action…**
    item, makes registered `StartupActionFactory` entries
    selectable, remembers the choice, and invokes that
    `StartupActionFactory` during startup.
  - `CreateButtonModelFactory` — takes a chosen
    `StartupActionFactory` and attaches it to a main-window button
    for later use.
- **`ConnectionTypeList`** — defines a manufacturer entry in the
  Connection-configuration UI. Implement (with the supporting
  classes) to add a brand-new system connection type. See the
  Adding a New System page.
- **`InstanceInitializer`** — registers a factory that creates the
  default instance for a class managed by the `InstanceManager`.
- **`JsonServiceFactory`** — extends the JSON services exposed by
  the JMRI web services. See the `JsonServiceFactory` Javadoc.
- **`PreferencesPanel`** — adds an additional pane to the
  Preferences window.
- **`PreferencesManager`** — adds a new preferences manager. Use
  this when an extension must run very early in the JMRI startup
  sequence, since `PreferencesManager`s initialise before most
  other components.
- **`ToolsMenuAction`** — adds an item to the bottom of the main
  Tools menu. Implement `ToolsMenuAction` in a class that also
  inherits from `Action`, `AbstractAction`, or `JMenuItem`, then
  add the SPI annotation. Example:
  [`SampleToolsMenuItem`](https://github.com/JMRI/JMRI/blob/master/java/src/jmri/jmrit/sample/SampleToolsMenuItem.java)
  (some lines are commented out so it does not actually appear in
  the menu — uncomment and rebuild to see it).
- **`SignalMastAddPaneProvider`** — supplies the Add/Edit pane for
  a new SignalMast type. When you define a new `SignalMast`
  subclass, also register a `SignalMastAddPaneProvider` so the
  SignalMast Table can add and edit signals of the new type. See
  the nested `SignalMastAddPaneProvider` inside `DccSignalMastAddPane`
  for an example.
- **`HttpServlet` (with the `@WebServlet` annotation)** — adds a
  new servlet to the JMRI web server. The annotation must supply
  both `name` and `urlPatterns`.
- **`WebServerConfiguration`** — registers additional file paths,
  redirections, and explicitly blocked paths in the JMRI web
  server.

For the SPI registration pattern (`@ServiceProvider` annotations
generated into `META-INF/services/`, `ServiceLoader.load(...)`
lookup, etc.), see `jmri-patterns.instructions.md`.

## Distributing and installing the plug-in

You can contribute plug-in code upstream (preferred) or distribute
it as a separate JAR.

- **JMRI 5.7.1 and later** — the user drops the JAR into the
  `lib/` directory inside their **JMRI Settings Location**. From
  Help → File Locations → **Open Settings Location** the user can
  open that folder in a file manager. JARs placed there survive
  JMRI upgrades on every platform.
- **Before JMRI 5.7.1** — the JAR went into the `lib/` directory
  inside the JMRI program directory. On Linux and macOS this had
  to be redone after every JMRI upgrade or reinstall.

JMRI automatically picks up SPI services exposed by drop-in JARs
and wires them into the appropriate extension points without any
change to the core code.
