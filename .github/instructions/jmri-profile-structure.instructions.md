---
applyTo: '**'
---

# JMRI Profile Directory Structure

This file summarises the JMRI profile-directory tutorial at
<https://www.jmri.org/help/en/html/doc/Technical/ProfileFileStructure.shtml>.
Apply these conventions when reading, writing, or refactoring code in
this repository that locates, reads, or writes JMRI configuration,
profile, roster, or user files.

For the complementary preferences APIs (formats, managers, panels)
see `jmri-preferences.instructions.md`.

## Portable location names

JMRI defines pseudo-URL prefixes that stand in for machine-specific
absolute paths inside XML files and other persisted data. The local
JMRI instance expands them into a real path; component separators are
always written as `/` and converted locally.

| Portable name | Meaning                                  |
|---------------|------------------------------------------|
| `settings:`   | Settings Location (per OS + user account)|
| `profile:`    | Currently-selected Profile Location      |
| `preference:` | User Files Location                      |
| `scripts:`    | Scripts Location                         |
| `program:`    | Program (install) Location               |

Prefer these prefixes in any persisted file path. See the JMRI
FileNames documentation and the `jmri.util.FileUtil` Javadoc for the
full list and the `getExternalFilename(...)` API.

## Locations and contents

### Settings Location (`settings:`)

The only fixed location, determined by the OS type and logged-in
user. JMRI always knows where to find it. Always present:

- `nodeIdentity.xml` — generated on first use; identifies this
  Settings Location uniquely (distinguishes other accounts, OS
  partitions, or computers).
- `profiles.xml` — lists every profile folder JMRI knows about and
  its location, plus the search paths for profiles and which one is
  the default for newly-created profiles. `settings:` is always
  searched but does not have to be the default.
- `preferences/` — preferences that apply to all profiles for the
  user on this computer. May be absent if no such preferences have
  been written.
- `log/` — `session.log`, `messages.log`, and rotated previous
  versions.
- `<App>Config*.properties` — per-app files (e.g.
  `DecoderProConfig3.properties`, `PanelProConfig2.properties`,
  `SoundProConfig2.properties`, `LccProConfig.properties`). Created
  on first run of each app; they record:
  - the last-used profile folder for that app,
  - whether to autostart with that profile, and
  - how long to display the Profile Selector if not auto-starting.

Optional: created profile folders (since JMRI 4.13.4 these have a
`.jmri` extension), user files, and a `roster/` folder with
`roster.xml`.

### Profile Location (`profile:`)

The profile chosen by the Profile Selector for the current session.

The profile root contains:

- `profile.xml` — shared profile-level preferences.
- `profile.properties` — shared Java Preferences in `Properties`
  format.
- One or more node-specific subfolders whose names begin with
  `jmri-` (the per-computer "private" space; see the Preferences
  page). Each contains local versions of:
  - `profile.xml`
  - `profile.properties`
  - `user-interface.xml` — implicit UI state (window sizes/positions,
    column widths, table sort order, etc.).

Optional content within the profile:

- Tool-specific subfolders (e.g. throttle preferences).
- User Files (panels, etc.) when User Files Location is set to the
  profile.
- A `roster/` folder and `roster.xml`.

### User Files Location (`preference:`)

Holds the user's panels and other user-authored files. Optionally
also contains `roster/` and `roster.xml`.

The user is free to relocate it via Preferences → File Locations.

### Roster Location

Contains:

- `roster.xml` — a recreatable index of the XML files in `roster/`.
  Recreate it via Actions → Recreate Roster Index if missing or
  corrupt.
- `roster/` — one XML file per loco (with stored CV values, etc.),
  `.bak` backup copies, a `consist/` folder, and copies of media
  files attached to roster entries, throttles, etc.

> Older JMRI versions stored roster images in a `resources/` folder
> within the User Files Location; long-time installs may have images
> in both places.

By default, Roster Location follows User Files Location. It can be
relocated via Preferences → Roster → Roster → Location Set/Reset; the
Reset button reverts it to following User Files.

### Scripts Location (`scripts:`)

Defaults to the `jython/` sample-scripts folder under the Program
Location. Users authoring their own scripts should change this to a
location outside `program:` (Preferences → File Locations).

### Program Location (`program:`)

Set by the JMRI installer; only changeable by reinstalling.

> **Never store user-created files anywhere under `program:`** —
> they will likely be lost on the next JMRI upgrade.

## User Files Location defaults and implications

The default depends on when JMRI was first installed:

- Before profiles existed (pre-JMRI 3.8): User Files Location was
  the same as Settings Location, and that arrangement is preserved
  on upgrade.
- After profiles (JMRI 3.8+): the default User Files Location for
  new installations is the same as the Profile Location.

### Implications when used with profiles

- Creating a new profile sets User Files Location = Profile
  Location, so the profile starts with an empty User Files set.
- Copying a profile whose User Files Location is **inside** the
  Profile Location produces an independent private copy of the user
  files. Edits in one do not affect the other.
- Copying a profile whose User Files Location is **outside** the
  Profile Location produces a profile that **shares** user files
  with the source. Edits are seen by both.

### Separating User Files and Roster Location

Splitting the two allows e.g. a single roster shared across multiple
profiles (one per layout) while keeping per-layout user files
isolated.

### Sharing across computers

Because User Files (and entire profiles) can live outside the JMRI
file tree, they can be shared between computers.

- **Shared file system / NAS** — appealing but risky:
  - The host or server must always be online.
  - Use on a laptop away from home/Internet is impossible.
  - JMRI is **not** designed for simultaneous file access; corruption
    risk is high.
  - Recovery from corruption is hard without a comprehensive backup
    strategy.
- **Cloud-synced sharing (Dropbox, Google Drive, OneDrive, …)** —
  preferred when sharing is needed:
  - Each machine keeps a full local copy synced via the cloud.
  - Only one machine needs to be online at a time.
  - Laptop-while-disconnected use is fine; sync resumes later.
  - These services typically avoid simultaneous file access, so
    corruption risk is lower.
  - Per-file version history (e.g. Dropbox's 30-day window) makes
    recovery easier. JMRI's Dropbox setup page documents the
    approach; the same advice applies to similar services.

For sharing entire profiles across computers, configure
Preferences → Config Profiles → Search Paths to include the synced
location and (optionally) make it the default for new profiles, then
manually move profile folders into it. JMRI creates per-machine
`jmri-…` subfolders so each computer can store its own
machine-specific overrides (e.g. COM port names) without disturbing
the shared content.
