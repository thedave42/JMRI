---
applyTo: '**'
---

# JMRI Portable File Access

This file summarises the JMRI portable file-access conventions
described at
<https://www.jmri.org/help/en/html/doc/Technical/FileNames.shtml>.
Apply these conventions when reading, writing, or refactoring code in
this repository that handles file or directory paths — particularly
paths persisted to XML, properties, or other configuration files.

For the underlying directory layout (Settings, Profile, User Files,
Roster, Scripts, Program Locations) see
`jmri-profile-structure.instructions.md`.

## Why portable names

JMRI uses files for icons, images, panel files, scripts, decoder
definitions, and much more. Many of those references are stored in
XML files. JMRI is cross-platform, and users routinely move XML
files between computers (potentially of different OS types) and
expect them to continue to work.

To make this possible, JMRI uses **pseudo-URL prefixes** in stored
filenames instead of machine-specific absolute paths. The local JMRI
instance expands the prefix into the appropriate absolute path on
the current computer.

## Portable prefixes

Use these prefixes whenever a path is stored or compared internally:

| Prefix        | Meaning                                                                                  |
|---------------|------------------------------------------------------------------------------------------|
| `program:`    | Path relative to the JMRI installation directory. (Was `resources:` in JMRI ≤ 2.13.1.)   |
| `preference:` | Path relative to the User Files / preferences directory. (Was `file:` in JMRI ≤ 2.13.1.) |
| `profile:`    | Path relative to the currently-selected profile directory.                               |
| `settings:`   | Path relative to the per-computer Settings directory.                                    |
| `home:`       | Path relative to the user's home directory.                                              |

Component separators are written as `/` and converted locally as
needed.

If a stored name has no prefix, JMRI treats it as a relative path
below the **program** directory (preserved for backward
compatibility). Absolute pathnames also work but are **not**
cross-platform portable, so avoid persisting them.

## Implementation

`jmri.util.FileUtil` provides the translation routines. Always go
through these helpers — do not parse or assemble portable paths
manually.

```java
// portable name → absolute pathname for use with java.io / java.nio
String absolute = jmri.util.FileUtil.getExternalFilename(pName);

// File or absolute pathname → portable form with the appropriate prefix
String portable = jmri.util.FileUtil.getPortableFilename(file);
String portable = jmri.util.FileUtil.getPortableFilename(filename);
```

`getExternalFilename(...)` returns a syntactically-valid path on the
current computer; the file or its parent directories are not
guaranteed to exist.

### Usage rules

- Call `getExternalFilename(...)` **before** passing a stored
  filename to any standard Java I/O (`File`, `Path`, `FileReader`,
  …).
- Call `getPortableFilename(...)` **whenever you receive** a file or
  filename from a Java class — particularly before persisting it to
  an XML file.
- Both calls are idempotent: invoking them on already-converted
  values is a no-op pass-through, so chaining them is safe.

See the `jmri.util.FileUtil` Javadoc for the complete list of
supported prefixes and helper methods.
