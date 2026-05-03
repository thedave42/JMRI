---
applyTo: '**'
---

# JMRI Use of Swing

This file summarises the JMRI Swing conventions described at
<https://www.jmri.org/help/en/html/doc/Technical/Swing.shtml>.
Apply these conventions when reading, writing, or refactoring Swing
GUI code in this repository.

> Note: There are proposals to move JMRI to other (non-Swing) GUI
> toolkits. Before extending or improving this Swing toolkit itself,
> ask on `jmri-developers` about the current status.

JMRI uses Java Swing for GUI development. Code is expected to use the
"Bean format" — set/get accessors, change call-backs, etc. — so JMRI
components compose cleanly into applications.

## General principles

- **Keep Swing code in `swing` subpackages.** Place Swing code in
  packages whose path contains `swing` (e.g.
  `jmri.jmrit.vsdecoder.swing`) rather than mixing it into the main
  package (e.g. `jmri.jmrit.vsdecoder`). This keeps the rest of the
  code non-GUI-specific so it can run with other toolkits or on
  headless systems. The same convention applies to ConfigureXml code,
  which lives in `.configurexml` subpackages.
- **Implement graphical tools as `JmriPanel` subclasses.** A
  `JmriPanel` is a `JPanel` with extra structure that JMRI
  applications can host directly. A `JmriPanel` subclass can be
  instantiated and placed into a properly laid-out window simply by
  creating a `JmriNamedPaneAction` referring to its class name.
  - Do **not** create dedicated `JmriJFrame` or `JFrame` subclasses
    with lots of specific function.
  - This pattern lets a tool panel be written once and embedded in
    many windows. It also greatly reduces startup-time class loading,
    since there are no separate `*Action` and `*Frame` classes and
    `JmriPanel` subclasses are not loaded merely because they appear
    in a menu.
- **Use Swing/AWT native event handling.** Do not write custom code
  to decode clicks, mouse-down, drags, etc. Use Swing classes such as
  `MouseAdapter` for mouse-pressed / mouse-clicked / mouse-released
  events. Native handling differs across platforms and hardware, and
  hand-rolled decoding is unlikely to match it.

### Recommended class structure

The recommended package/class layout for adding a new function with a
Swing UI looks like this (mirrors the diagram on the page):

- `jmri` package — the public interface, e.g. `NewFunction`.
- `jmri.implementation` — abstract default implementation
  (`AbstractNewFunction`) plus type-specific concrete classes
  (`NewFunctionTypeA`).
- `jmri.jmrit.ToolB` — alternative type-specific concrete class
  (`NewFunctionTypeB`) that implements `NewFunction` directly.
- `jmri.swing` — generic `NewFunctionPane` whose constructor takes a
  `NewFunction`. This pane is **both** the base class for
  type-specific panes **and** the default implementation used when
  code holds only a generic `NewFunction` reference.
- `jmri.implementation.swing` — `NewFunctionTypeAPane` extending
  `NewFunctionPane`, with a constructor taking `NewFunctionTypeA`.
- `jmri.jmrit.ToolB.swing` — `NewFunctionTypeBPane` extending
  `NewFunctionPane`, with a constructor taking `NewFunctionTypeB`.

In short: Swing code lives in `.swing` sibling packages, the generic
pane keys off the interface, and each type-specific pane sits next to
its type-specific implementation and takes that concrete type in its
constructor.

## Pattern for Swing window creation

Support code lives in the `jmri.util.swing` package.

### Life cycle of a `JmriPanel`

1. The constructor runs.
2. `initComponents` runs. Use this for connections to other
   components, since lower-level objects have all been created by
   this point. Subclasses for particular systems may add more
   `initComponents`-style methods that are invoked later.
3. `dispose` is called at the end. `JPanel` does not normally have a
   `dispose()`, but `JmriPanel` provides one for cleanup.

### Displaying a `JmriPanel`

Prefer creating `JmriPanel`s by name with `JmriNamedPaneAction`. This
greatly reduces the number of classes that have to be loaded just to
populate a menu.

Simplest form (for a menu item, button, etc.):

```java
new jmri.util.swing.JmriNamedPaneAction(
    "Log4J Tree",
    "jmri.jmrit.log.Log4JTreePane");
```

- 1st argument: human-readable name.
- 2nd argument: fully-qualified panel class name (a `String`, so the
  class is not loaded until first use).

Fuller form, with a `WindowInterface` and i18n label:

```java
new jmri.util.swing.JmriNamedPaneAction(
    Bundle.getMessage("MenuItemLogTreeAction"),
    new jmri.util.swing.sdi.JmriJFrameInterface(),
    "jmri.jmrit.log.Log4JTreePane");
```

- 1st argument: localised human-readable name (via `Bundle`; see the
  I18N page).
- 2nd argument: the context (a `WindowInterface`) in which to show
  the panel, e.g. a new plain window (SDI). See the
  [Window control](#window-control) section for the available
  options.
- 3rd argument: the `JmriPanel` class name.

See the `JmriNamedPaneAction` Javadoc for full details.

If specialised initialisation is needed beyond what `initComponents`
and `initContext(..)` can do (e.g. picking system connections),
extend `JmriNamedPaneAction`, add the appropriate constructors, and
override:

```java
@Override
public JmriPanel makePanel() { ... }
```

to perform the case-specific setup. See
`jmri.jmrix.loconet.swing.LnNamedPaneAction` for an example.

If `JmriNamedPaneAction` cannot be used at all, base a separate
`Action`-implementing class on `JmriAbstractAction`.

### Menus, toolbars, buttons

When you use `JmriPanel`s as above, JMRI also provides utilities for
building menus, toolbars and navigation trees from XML definitions:

- `jmri.util.swing.JMenuUtil` — menus
- `jmri.util.swing.JToolBarUtil` — toolbars
- `jmri.util.swing.JTreeUtil` — navigation trees

I18N for those menus, toolbars and trees is handled by the XML
content in the usual JMRI way (see the I18N page).

## Window control

JMRI provides three ways of embedding `JmriPanel`s in windows. Each
ships with a `WindowInterface` implementation that creates the
appropriate windows or sub-windows.

- `jmri.util.swing.sdi` — traditional JMRI single-document interface
  with multiple independent windows.
- `jmri.util.swing.multipane` — IDE-style multi-pane interface where
  each window is tiled with inter-related panes. Used by the new
  DecoderPro.
- `jmri.util.swing.mdi` — multi-document interface where one primary
  window holds multiple independent sub-windows. Available, but not
  currently used by any shipped JMRI app.

See the `jmri.util.swing` package Javadoc for more.

## Miscellaneous

- Prefer `jmri.util.swing.WrapLayout` over `java.awt.FlowLayout`.
  `WrapLayout` correctly handles wrapping content onto a second
  line; `FlowLayout` will frequently fail to display the second
  line.
- Prefer `jmri.util.swing.JmriJOptionPane` over
  `javax.swing.JOptionPane`. The standard `JOptionPane`'s modality
  blocks the entire JVM UI until closed, which conflicts with
  always-on-top frames and can leave dialogs hidden behind frames.
