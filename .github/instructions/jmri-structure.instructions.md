---
applyTo: '**'
---

# JMRI Library Structure

This file summarises the JMRI code organisation described at
<https://www.jmri.org/help/en/html/doc/Technical/IntroStructure.shtml>.
Apply these conventions when reading, writing, or refactoring code in
this repository.

## Static structure

Test source code and distributed source code are kept in separate
directories under the development directory:

- `java/src` — distributed source code.
- `java/test` — test source code.

In addition to source code, JMRI expects the following top-level
directories:

- `help/` — the in-program help files.
- `jython/` — sample and test scripts.
- `lib/` — jar files and linkable libraries that JMRI relies on.
- `resources/` — image and sound resources used by the library and
  available for user selection.
- `web/` — files for JMRI's built-in web servers.
- `xml/` — XML files for decoder and programmer definitions, signal
  system definitions, schemas/DTDs used to validate JMRI's XML
  persistence, and others.

There are also specific files used by the build process, to control
logging, and containing the JMRI license.

## Package and class structure

Top-level packages and the rules that govern them:

- **`jmri`** — interfaces and base class implementations for the common
  JMRI objects. This is the basic interface to the overall JMRI library
  and its capabilities. Code in `jmri` should depend on no other JMRI
  code, though it may depend on externals (log4j, etc.). There must be
  no AWT or Swing code here.
- **`jmrit`** — commonly useful tools and extensions. May depend on
  `jmri.*` and externals. Must not depend on `jmrix.*`.
- **`jmrix`** — code specific to a particular external system,
  including implementations of `jmri` interfaces specific to a system
  plus system-specific tools. May depend on `jmri` and externals, but
  not on `jmrit`. Only system-specific code should access classes
  inside `jmrix` subpackages.
- **`jmris`** — all code for the server implementation of the JMRI
  interfaces.
- **`managers`** — abstract and default implementations of the various
  JMRI type managers (the concrete classes from `InstanceManager`).
  Historically a separate package rather than rolled into
  `jmri.implementations`.
- **`implementations`** — abstract and default implementations of the
  `jmri` objects. No system-specific or Swing code allowed here. Kept
  separate from `jmri` itself so that the `jmri` package stays simple
  for people who just want to use the library.
- **`util`** — general service classes that are not user-level tools.
  Should not depend on `jmri.jmrit` or `jmri.jmrix`. The
  `jmri.util.swing` subpackage provides Swing utilities. These utility
  classes are an exception to the "no cross-tree references" rule and
  are generally available for use.
- **`apps`** — separate from the `jmri` package tree; contains
  application classes and base classes that may use `jmri`, `jmrit`,
  and `jmrix` classes along with anything else. This package breaks
  the dependency between `jmrix` and `jmrit` classes — the `apps`
  package is responsible for creating general and system-specific tool
  objects for an application.

### Cross-tree dependency rules

The tree structure is important: packages should not reference across
the tree.

- Code in `jmri.jmrit` may reference classes in the `jmri` package, but
  must not reference classes in `jmri.jmrix` directly.
- Classes should reference the interfaces in `jmri`, not the specific
  details of classes in `jmri.managers` and `jmri.implementations`.
- It is tempting to violate this rule for expediency, but doing so
  makes JMRI much harder to maintain and improve as a large group.

These rules are enforced by ArchUnit tests:

- `jmri.ArchitectureCheck` — runnable via `./runtest.csh
  jmri.ArchitectureCheck` (a PowerShell script is also available; see
  the developer unit testing page). This highlights many existing
  structure violations as well as new ones, including historical
  issues that have not yet been cleaned up.
- `jmri.ArchitectureTest` — run as part of CI, catches new violations
  of a subset of the constraints.

There are also conventions for where Swing GUI code and persistence
(store/load) code is located, to limit how embedded they become in the
main code.

### Example: implementing a Turnout

A turnout involves multiple classes:

- `jmri.Turnout` — the basic interface. This is what layout-automation
  code should expect to deal with; it's what you get when you make a
  request from the `TurnoutManager`, etc.
- `jmri.implementation.AbstractTurnout` — provided for convenience when
  implementing the `Turnout` interface for specific hardware; supplies
  the basic implementation.
- `jmri.jmrix.loconet.LnTurnout` — a specific implementation for
  LocoNet-connected turnouts. There are many other implementations for
  different layout connections.

For interfaces in the `jmri` package that may be implemented by many
hardware types, `jmri.InstanceManager` satisfies requests for
implemented objects by providing access to a hardware-specific
`Manager`, from which hardware-specific items can be retrieved.

Hardware implementations in subpackages of `jmrix` are also accessed
via `SystemConnectionMemo` classes, which provide access to
generally-defined objects. Other code should generally not reference
those specific implementations directly.

## Dynamic (object) structure

Once created, many JMRI objects can be accessed either by type (e.g.
"the default configuration manager" via the `InstanceManager`) or by
name (e.g. "the East Yard Entrance turnout" via a type-specific
`Manager`).

The `InstanceManager` is the key central point for this navigation:

- It provides access to key objects, particularly the Managers that
  mediate access to Turnouts, Sensors, etc.
- It automatically handles initialisation of many parts of JMRI via
  several mechanisms:
  - `InstanceManagerAutoDefault`
  - `InstanceInitializer`

  and, when needed, provides for post-creation initialisation via
  `InstanceManagerAutoInitialize`.
- It disposes of suitable `Disposable` objects at the end of their
  lifespan when the `InstanceManager` (or a particular collection in
  it) is cleared.

JMRI makes extensive use of the Factory pattern via objects called
"Manager" objects.

### Example: accessing a Turnout

To get a specific `Turnout` instance representing something on the
layout, request it from a `TurnoutManager`, which itself is obtained
from the `InstanceManager`:

```java
TurnoutManager manager = InstanceManager.getDefault(TurnoutManager.class);
Turnout turnout = manager.getTurnout("IT12");
```

The generic `Manager<T>` class provides the basic interface, which is
extended for specifics (e.g. `TurnoutManager`), which then has specific
implementations.

In many cases there is a single underlying implementation for a manager
(e.g. one `LogixManager`, one `MemoryManager`).

In other cases there may be multiple system-specific managers — for
example `LnTurnoutManager` for LocoNet, `NceTurnoutManager` for NCE,
and `InternalTurnoutManager` for internal turnouts. These are handled
by making them clients of a single `ProxyManager` subclass (e.g.
`ProxyTurnoutManager`), which uses name lookup to delegate to the
individual managers.

## Object referencing

Code can hold a direct Java reference to a specific object. In some
cases — typically GUIs or persistence — objects must instead be
referred to by name. JMRI `NamedBean` objects have both:

- A **system name** that cannot change.
- A **user name** that can change.

For example, the user name "East Yard" for a turnout might refer to
`LI1` at one point and later refer to `NT12`. The `NamedBeanHandle`
class takes care of this by remembering which name is being used for
an object, and keeping track of how that name may be moved from one
object to another.
