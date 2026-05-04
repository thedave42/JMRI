---
applyTo: '**'
---

# JMRI Threading

This file summarises the JMRI threading conventions described at
<https://www.jmri.org/help/en/html/doc/Technical/Threads.shtml>.
Apply these conventions when reading, writing, or reviewing code in
this repository that creates threads, schedules timed events,
interacts with the GUI, or handles `InterruptedException`.

JMRI processes most activity on a **single thread — the Java Swing
event thread**. Most code (and most contributors) never need to
think about threading: events flow through the Swing event thread,
methods get invoked, and threading takes care of itself. The rules
below apply when you do need to step outside that single-thread
model.

## When you need a separate thread

The single-threaded model covers almost everything. Reach for an
extra thread only when one of these applies:

- A long-running operation must not block the rest of JMRI from
  responding (file load, large XML parse, network call, etc.).
- A state machine that talks to layout hardware is easier to
  express on its own thread (e.g. read, wait, decide, write
  loops).
- You are interfacing with existing third-party code that itself
  uses threads.

Even in those cases, prefer to push the cross-thread plumbing
through `jmri.util.ThreadingUtil` (and friends) rather than
hand-rolling `SwingUtilities.invokeLater(...)` /
`new Thread(...)` calls.

## `jmri.util.ThreadingUtil`

`jmri.util.ThreadingUtil` is the canonical entry point for
**"run this on the right thread"** scheduling. It separates the
**GUI thread** (Java Swing) from the **layout thread** (sensors,
turnouts, etc.). The two are currently the same physical thread,
but the API distinguishes them so they can be split later without
touching every call site.

Pick the most-likely-correct one when coding:

| Operation                                               | Method                                          |
|---------------------------------------------------------|-------------------------------------------------|
| Run on GUI thread now (block until done)                | `ThreadingUtil.runOnGUI(ThreadAction)`          |
| Run on GUI thread later (non-blocking)                  | `ThreadingUtil.runOnGUIEventually(ThreadAction)`|
| Run on GUI thread after a delay (ms)                    | `ThreadingUtil.runOnGUIDelayed(action, ms)`     |
| Run on layout thread now / later / delayed              | `ThreadingUtil.runOnLayout*` mirror methods     |

Migrate older `javax.swing.SwingUtilities.invokeLater(r)` /
`invokeAndWait(r)` call sites to the JMRI methods when you touch
them — keeps the codebase uniform and ready for a future
GUI/layout split.

Example: do work off-thread, then publish a frame on the GUI
thread:

```java
frame = new JmriJFrame();           // declared as instance variable
// spend a long time reading data and configuring `frame`
ThreadingUtil.runOnGUI(() -> {
    frame.setVisible(true);
});
```

### Thread-affinity annotations

When the thread context of a method matters, **annotate it** so
static checkers and reviewers can verify the contract:

| Annotation              | Meaning                                                              |
|-------------------------|----------------------------------------------------------------------|
| `@InvokeOnGuiThread`    | Caller must invoke this on the Swing / GUI thread.                   |
| `@InvokeOnLayoutThread` | Caller must invoke this on the layout thread.                        |
| `@InvokeOnAnyThread`    | Method is thread-safe and may be invoked on any thread.              |

`@ThreadSafe`, `@NotThreadSafe`, `@Immutable`, and `@GuardedBy`
(the `javax.annotation.concurrent` set, also recognised by
SpotBugs) are also useful — see the JMRI SpotBugs page.

### Runtime thread checks

For self-defence at the top of a critical method, assert the
expected thread:

```java
ThreadingUtil.requireGuiThread(log);
// or
ThreadingUtil.requireLayoutThread(log);
```

The check costs a small amount of time, so apply it sparingly:

- only at the entry to a meaningful chunk of work, not inside
  tight loops; and/or
- guard with `if (log.isDebugEnabled()) { ... }` when used inside
  performance-sensitive code.

A wrong-thread call records the **first** occurrence via
`Log4JUtil.warnOnce` with a traceback. Subsequent occurrences are
suppressed — see `jmri-logging.instructions.md` for the
`warnOnce` test-handling rules.

## Launching your own thread

If you must spawn a `Thread` directly:

- **Put it in the JMRI thread group.** Pass
  `ThreadingUtil.getJmriThreadGroup()` as the first argument to
  the `Thread` constructor. This separates JMRI threads from
  system / library threads in debuggers and thread dumps.
- **Terminate cleanly at end of test.** Every JUnit test that
  starts threads must shut them down before returning; lingering
  threads interfere with later tests. See
  [Ending threads](#ending-threads) below for the mechanism.
- **Never busy-wait.** If you need to wait for a condition,
  prefer a `BlockingQueue` (see
  [Other items of interest](#other-items-of-interest)) or
  `Object.wait()` / `Condition.await()` over polling sleeps.

## Ending threads

This is mostly about `interrupt()` and the JMRI rule for
handling `InterruptedException`.

> **Bottom line: never swallow `InterruptedException`.**

When a method you call throws `InterruptedException`, choose the
**best** option you can implement, in this order:

1. **Treat it as a deliberate request to stop** what your code is
   doing. `InterruptedException` is not an error — some other
   piece of code asked your operation to end. Tidy up and return
   to normal operation.
2. **Propagate it.** If your code is a low-level part that can't
   reliably terminate the work, declare
   `throws InterruptedException` and let the caller decide.
3. **Re-assert the interrupt** when neither of the above is
   possible (e.g. when overriding a Java-defined method whose
   signature you can't change):

   ```java
   try {
       wait();
   } catch (InterruptedException e) {
       Thread.currentThread().interrupt();
   }
   ```

   Execution continues, but the next blocking call on this thread
   immediately receives an interrupt too — giving a higher level
   another chance to handle it.

For background, see the IBM developerWorks article *Dealing with
InterruptedException* linked from the JMRI Threads page.

## Timed events

For "do X after N ms" or "do X every N ms":

- Prefer `ThreadingUtil.runOnGUIDelayed(...)` or
  `ThreadingUtil.runOnLayoutDelayed(...)`. They schedule the
  action on the correct JMRI thread.
- Use `jmri.util.TimerUtil` for the periodic / non-thread-affine
  cases that aren't a clean fit for the `runOn*Delayed` methods.

**Do not use `java.util.Timer` directly.** Each `Timer` instance
spawns a thread that is hard to terminate cleanly (memory leaks,
test interference) and runs the action on its own thread — not
the GUI or layout thread, which is almost never what JMRI code
expects. `javax.swing.Timer` is similarly best avoided in favour
of `runOnGUIDelayed`.

## Other items of interest

- **`BlockingQueue` over manual locking.** A
  `java.util.concurrent.BlockingQueue` (or related concurrent
  collection) often replaces hand-rolled `synchronized` /
  `wait` / `notify` plumbing entirely, and is much easier to
  reason about. See
  [`java/test/jmri/util/ThreadingDemoAndTest.java`](https://github.com/JMRI/JMRI/tree/master/java/test/jmri/util/ThreadingDemoAndTest.java)
  for a worked example covering `join`, `interrupt`, and a
  `BlockingQueue`.
- **`jmri.util.PropertyChangeEventQueue`** lets a single consumer
  listen to many `NamedBean` objects without missing or
  overlapping notifications.
  [`jmri.jmrit.automat.Siglet`](https://github.com/JMRI/JMRI/tree/master/java/src/jmri/jmrit/automat/Siglet.java)
  uses it.

## Debugging: thread dumps

A thread dump is the first tool to reach for when JMRI hangs or
behaves oddly under load.

### Preferred: `jvisualvm` (Oracle JVM)

1. Run `jvisualvm` from another terminal.
2. In the upper-left tree, double-click the running JMRI
   application (PanelPro, DecoderPro, …) under **Local**.
3. Open the **Threads** tab on the right.
4. Click **Thread Dump** in the upper right.
5. The dump opens in a new tab. Copy / paste it into an editor or
   email — there is no direct save button.

### Fallbacks

- **Linux / macOS, foreground from a terminal:** press
  Ctrl-Backslash (`Ctrl-\`) in the terminal that started JMRI.
- **Linux / macOS, detached (e.g. launched from an icon):** find
  the PID and send `SIGQUIT`:

  ```sh
  ps | grep java | grep apps | awk '{print $1}'
  # or in one line:
  kill -s QUIT $(ps | grep java | grep apps | awk '{print $1}')
  ```

  The output appears in Console (macOS) or stdout / log file.
- **NetBeans / Eclipse:** menu commands in the IDE's run /
  debug toolbar.

When running under Ant or an IDE you may see more than one dump in
the output: one from JMRI plus one from the surrounding tooling.
The JMRI dump is the one you want; the others can be ignored.
