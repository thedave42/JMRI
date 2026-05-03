---
applyTo: '**'
---

# JMRI Application Structure

This file summarises the JMRI application structure described at
<https://www.jmri.org/help/en/html/doc/Technical/AppStructure.shtml>.
Apply these conventions when reading, writing, or refactoring code in
this repository that defines or modifies a JMRI application's startup
or top-level structure.

For the structure of the JMRI library itself (packages, dependencies,
managers), see `jmri-structure.instructions.md` which mirrors the
companion "Introduction to JMRI Library Structure" page.

## Shipped applications

JMRI ships several main applications:

- **DecoderPro** — `apps.gui3.dp3.DecoderPro3`. Example of the "new
  structure" application form.
- **PanelPro** — `apps.PanelPro.PanelPro`. Example of the "original
  structure" application form.
- **JmriFaceless** — `apps.JmriFaceless`. A version of PanelPro
  optimised for headless machines (e.g. Raspberry Pi). Uses the
  original structure.

## New application structure

The currently-recommended form has the main application class extend
`apps.gui3.Apps3`.

Most customisation for a new application consists of overriding
`apps.Apps` methods that control the display during startup: main
image, name, program link, etc. The new application class can also
override implementations that create menus, load help, configure
preferences, and so on.

### Startup sequence (DecoderPro example)

```java
public static void main(String args[]) {
    preInit(args);
    DecoderPro3 app = new DecoderPro3(args);
    app.start();
}

public static void preInit(String[] args) {
    apps.gui3.Apps3.preInit(applicationName);
    setConfigFilename("DecoderProConfig3.xml", args);
}
```

- `apps.gui3.Apps3.preInit` initialises basic running conditions
  (logging, console, etc.).
- `apps.gui3.Apps3.setConfigFilename` (inherited from
  `apps.AppsBase`) sets the configuration file path. It uses, in
  order: system properties, launch arguments, or the supplied
  default.
- The `DecoderPro3` constructor delegates up to the `Apps3`
  constructor, which handles GUI initialisation and relies on the
  `AppsBase` constructor for the rest.
- `Apps3.start()` drives the program's dynamic behaviour after
  construction.

### Useful milestones

- **Windows, toolbars and menus** — Gui3 support (see the JMRI Swing
  page) defines toolbars and menus. Examples:
  - `apps.gui3.dp3.DecoderPro3#getMenuFile()` loads
    `xml/config/parts/jmri/jmrit/roster/swing/RosterFrameMenu.xml`.
  - `apps.gui3.dp3.DecoderPro3#getToolbarFile()` loads
    `xml/config/parts/jmri/jmrit/roster/swing/RosterFrameToolBar.xml`.
- **Load configuration** —
  `apps.AppsBase#setAndLoadPreferenceFile()` (the
  `jmri.ConfigureManager` step) loads the configuration file. This
  loads and activates many user-level objects, and is the start of
  loading system connections.

For details, see the `apps.gui3.Apps3` Javadoc.

## Older application structure

The original form has the main application class extend `apps.Apps`.

Customisation again consists primarily of overriding `apps.Apps`
methods that control startup display, with optional overrides for
menus, help, preferences, etc.

### Startup sequence (PanelPro example)

1. `PanelPro#main(..)` starts and performs initial interactions by
   invoking methods from `apps.Apps`.
2. It constructs a `PanelPro` object whose behaviour is mostly
   inherited from `apps.Apps`.
3. It calls `apps.Apps#createFrame` to complete the setup.

For details, see the `apps.Apps` Javadoc.

## Minimal application structure

(This section may be out of date.)

`apps.SampleMinimalProgram` provides a minimal example of starting a
program that uses JMRI with a hard-coded layout configuration. See
the internal comments for more information.

The preferred approach, however, is to use the JMRI configuration
system to read a configuration file and perform initialisation.
`apps.SampleMinimalProgram` contains commented-out code showing how
to do this.

The JMRI applications themselves use a more powerful **profile**
mechanism inherited from `apps.Apps` and `apps.AppsBase`.
