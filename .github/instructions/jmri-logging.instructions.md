---
applyTo: '**'
---

# JMRI Logging

This file summarises the JMRI logging documentation at
<https://www.jmri.org/help/en/html/doc/Technical/Logging.shtml>.
Apply these conventions when reading, writing, or reviewing logging
calls and logging configuration in this repository.

JMRI uses the Apache **Log4J 2** library through the **SLF4J 2.x**
facade (Log4J 2.20 via SLF4J 2.0.7 as of summer 2023). Libraries that
use `java.util.logging` (e.g. jmDNS) are bridged through the
`jul-to-slf4j` adapter.

For viewing log output, see the JMRI Debugging and System Logging
help page.

## Logging levels

Use the SLF4J level whose meaning matches the situation. The Log4J
`FATAL` level is **not** exposed by SLF4J and is not used by JMRI.

| Level   | API call         | When to use                                                                                                                      |
|---------|------------------|----------------------------------------------------------------------------------------------------------------------------------|
| `ERROR` | `log.error(..)`  | The desired operation is not going to happen — explain why. Reserve for serious problems that should be looked at every time, typically indicating a possible JMRI fault. |
| `WARN`  | `log.warn(..)`   | The program continues but something has gone wrong; often "this operation may not have done all you wanted". Use when the cause is incorrect user input. |
| `INFO`  | `log.info(..)`   | Routine messages visible during normal operation. Keep to a minimum; ideally none after startup completes.                       |
| `DEBUG` | `log.debug(..)`  | Detailed messages used only while debugging. Volume can slow the program when fully enabled.                                     |
| `TRACE` | `log.trace(..)`  | Very detailed messages, more verbose than DEBUG (e.g. every character on a transmission). Typically enabled per class only.      |

## Configuration

JMRI applications initialise Log4J from a logging configuration file
(`*_lcf.xml`) by convention:

- `default_lcf.xml` — the standard runtime configuration shipped at
  the repo root (heavily commented). Distributed in the **Program
  Location**, but a per-user copy in the **Settings Location** is
  preferred (find it via Help → File Locations).
- `tests_lcf.xml` — equivalent file used while running JUnit tests.

> JMRI < 5.5.4 used `default.lcf` instead. Edit that file for older
> releases following its in-file comments.

Generated logs go under the directory named by the `jmri.log.path`
system property, defaulting to the `log/` subdirectory inside the
preferences directory.

### Appenders

The configuration file defines three named appenders:

- **`A1`** — console on the local computer (stdout/stderr). Also
  reachable via the **Console** item on the JMRI Help menu.
- **`R`** — single rolling-on-restart file, by default
  `session.log`. Reset every JMRI restart.
- **`T`** — a set of rolling files, by default `messages.log`. Rolls
  when the file reaches 1 MB and keeps two older versions; this set
  spans program restarts, so it captures messages emitted right at
  startup or shutdown.

### Standard output logging

The console appender uses a `PatternLayout` such as:

```xml
<Console name="A1" target="SYSTEM_ERR">
    <PatternLayout
        pattern="%d{ABSOLUTE} %-37.37c{8} %-5p - %m [%t]%n"/>
</Console>
```

Example output:

```
2015-10-28 20:31:52,307 jmri.jmrit.powerpanel.PowerPane  WARN - No power manager instance found, panel not active [AWT-EventQueue-0]
```

The columns are:

- `2015-10-28 20:31:52,307` — local time of the log event.
- `jmri.jmrit.powerpanel.PowerPane` — class that emitted it.
- `WARN` — severity.
- `No power manager instance found, panel not active` — the
  message.
- `[AWT-EventQueue-0]` — emitting thread.

### Setting per-logger levels

To raise or lower the level for a specific logger, add a `<Logger>`
element near the bottom of the configuration file alongside the
existing ones, for example:

```xml
<Logger name="jmri.jmrit.operations" level="DEBUG"/>
```

JMRI's **Display / Edit Log Categories** UI (`Log4JTreePane`) can
also adjust live levels at runtime, but those changes do **not**
write back to the configuration file and do not persist across
sessions.

## Coding conventions

### Declaring the logger

Add this private static field at the bottom of each class that logs:

```java
private static final org.slf4j.Logger log =
    org.slf4j.LoggerFactory.getLogger(MyClass.class);
```

Some classes import `org.slf4j.Logger` and
`org.slf4j.LoggerFactory` instead — that is fine. If logging is
temporarily commented out, it is acceptable to also comment out the
`log` field so it can easily be reinstated.

### Emitting log messages

For literal strings:

```java
log.debug("message");
```

For messages that include variable values, **always use SLF4J
parameterised form** so string concatenation is skipped when the
event is filtered out:

```java
log.debug("Found {}", numberEntries);
```

- Do **not** call `.toString()` explicitly on the arguments — SLF4J
  invokes it correctly only when needed.
- If computing a parameter is expensive, guard the call:

  ```java
  if (log.isDebugEnabled()) {
      log.debug("Found {}", numberEntries());
  }
  ```

### One-shot info / warn

To emit an INFO or WARN message only the first time it occurs, use
the JMRI service helpers:

```java
Log4JUtil.infoOnce(log, "This info message will only be output once.");
Log4JUtil.warnOnce(log, "The warning with arguments {} {}", "A", "B");
```

Note: `warnOnce` and `infoOnce` need special handling in unit and CI
tests; see the JUnit testing page section on `warnOnce` for
specifics.

### Logging exceptions

Always include both a meaningful local message and the throwable
itself so the localised exception text and full stack trace are
captured:

```java
log.error("my local text " + exception.getLocalizedMessage(), exception);
```

### Scripting

The same SLF4J / Log4J interface is reachable from JMRI scripts —
both Jython (e.g. `jython/ThrottleFunctionForTurnout.py`) and
JavaScript (e.g. `jython/test/JavaScriptTest.js`) — using the same
`LoggerFactory` API.
