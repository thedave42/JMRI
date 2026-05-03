---
applyTo: '**'
---

# JMRI Application Preferences

This file summarises the JMRI preferences architecture described at
<https://www.jmri.org/help/en/html/doc/Technical/AppPreferences.shtml>.
Apply these conventions when reading, writing, or refactoring code in
this repository that defines, stores, retrieves, or exposes
preferences.

## Overview

JMRI preferences are stored in **two formats** (XML elements and Java
Preferences) within **three spaces** (shared profile, private profile,
and the per-user settings directory).

The access structure is modelled on NetBeans IDE's project
"Auxiliary Configuration" and preferences mechanism.

Two SPI APIs implement preference access and UI:

- `jmri.spi.PreferencesManager` — code-side access to and control of
  preferences.
- `jmri.swing.PreferencesPanel` — UI within the JMRI Preferences
  window.

## Preference spaces

### The current profile

Most preferences live inside a single **Profile**. Each profile has a
location, a name, and an identity. The identity combines a "safe"
filename-friendly version of the name with eight random hex digits to
ensure uniqueness.

- Profile locations should use the `.jmri` extension. JMRI defaults
  to adding it; the extension is not enforced. The extension lets
  iOS/macOS assign a Uniform Type Identifier (UTI). See the JMRI
  Startup Scripts page for UTI details.

A profile contains two spaces:

- **Shared** — preferences within the profile that are shared across
  every computer running that profile (e.g. the `LocoNet` connection
  named "LocoNet"). Stored at the root of the profile directory.
- **Private** — preferences within the profile used only on a single
  computer (e.g. that the `LocoNet` connection uses port `COM3`).
  Stored under directories named `jmri-UUID-ID`, where `UUID` is a
  unique identifier for the computer and `ID` is the random portion
  of the profile identity. A separate private directory exists per
  user per computer.

### Settings directory

The **settings directory** holds preferences that are *not* tied to
any single profile and apply only on one computer (e.g. how a JMRI
application picks which profile to use). It is per-user on a single
computer.

See the JMRI Profile File Structure page for details.

## Preference formats

Preferences are stored either as XML elements or as Java
Preferences.

### XML elements

Stored across two files within a single space, both using the same
structure but with different intent:

- `profile.xml` — preferences explicitly set by the user via the
  Preferences UI. These typically have significant runtime impact
  and may require a restart. Both shared and private versions are
  retained within a profile.
- `preferences.xml` — same intent and format as `profile.xml`, but
  for preferences that apply to all profiles for a single user on a
  single computer (lives in the settings directory).
- `user-interface.xml` — implicit preferences such as window size
  and position, table sort order, and similar UI state captured and
  restored automatically. **Private only** within a profile.

A manager for any of these files manages a category of preferences
(connection configurations, table state, …) using
`jmri.profile.AuxiliaryConfiguration`. Its `getConfigurationFragment`
/ `putConfigurationFragment` / `removeConfigurationFragment` methods
take the element (or its name), and a flag indicating whether it is
shared or private.

XML elements for JMRI preferences **must** have valid, resolvable
namespaces. They do not have to be defined under jmri.org.

> Older docs sometimes referred to a single `properties.xml`; the
> current files are `profile.xml`, `preferences.xml`, and
> `user-interface.xml`.

### Java Preferences

Use [`java.util.prefs.Preferences`](https://docs.oracle.com/javase/8/docs/api/java/util/prefs/Preferences.html)
for simple preferences (numbers, booleans, strings) — or lists of
simple preferences — that are meaningful only once per profile.

Java Preferences are persisted by JMRI in `Properties` format:

- `profile.properties` — within both shared and private spaces of a
  profile.
- `preferences.properties` — for settings that apply to all profiles
  for a single user on one computer (settings directory).

JMRI separates Java Preferences into **namespaces** based on package
name. This lets two packages reuse the same key (e.g. the JMRI Web
Server and the Simple Server both expose a `port` property) without
collision.

## Accessing preferences

- If another class already manages the preference you want, use that
  class's API to read/write it.
- If no manager exists, create one to manage the preference on
  behalf of other classes.
- **Direct access to the preference files is strongly discouraged.**
  Two managers writing the same file directly can clobber one
  another.
- Use `jmri.profile.ProfileUtils` to obtain the
  `AuxiliaryConfiguration` and `Preferences` for a profile. Pass
  `null` as the profile to get application-wide (settings-directory)
  preferences.

## Preferences managers

`jmri.spi.PreferencesManager` implementations are the primary
handlers for retrieving and storing preferences.

- Each manager implements the `PreferencesManager` interface so it
  can be discovered.
- The JMRI configuration manager loads them through Java's
  `ServiceLoader` API, instantiating every registered manager. This
  allows third-party JARs to ship their own managers (annotate with
  `@ServiceProvider(service = PreferencesManager.class)`; see
  `jmri-patterns.instructions.md`).
- Discovery order is non-deterministic. The `PreferencesManager` API
  exposes methods to declare that other specific managers must be
  initialised first; use these to express dependencies rather than
  relying on load order.

## Preferences panels

`jmri.swing.PreferencesPanel` implementations provide the UI within
the Preferences window.

- The Preferences window uses `ServiceLoader` to instantiate every
  registered panel (third-party JARs can supply panels).
- Panels can supply hints to **group** related panels together, but
  there is no ordering hint. A small set of well-known groups is
  shown in a specific order; every other group is ordered
  alphabetically.

## Scripts

Sample scripts in the distribution showing preference access:

- `jython/PreferencesExamples.py` — Jython sample code that also
  demonstrates pulling in another Jython script
  (`jython/preferences.py`).
- `jython/zeroconf-preferences.js` — JavaScript/ECMAScript script
  that adjusts ZeroConf network preferences.
