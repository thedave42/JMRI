---
applyTo: '**'
---

# JMRI External System Connection Structure

This file summarises the JMRI system-connection structure described
at <https://www.jmri.org/help/en/html/doc/Technical/SystemStructure.shtml>.
Apply these conventions when reading, writing, or refactoring code in
this repository that adds, modifies, or interacts with `jmri.jmrix.*`
hardware connections (DCC systems, layout buses, etc.).

JMRI has a lot of historical variation in this area — older systems
do not always follow the structure below. Prefer the current pattern
when adding new connections or restructuring existing ones. See also
the JMRI **Multiple Connection Update** page for migration guidance.

## Code structure

Group code for one connection type (e.g. "LocoNet connections", "NCE
connections") into a dedicated package directly under `jmri.jmrix`,
for example:

- `jmri.jmrix.loconet`
- `jmri.jmrix.nce`

In the preferences dialog and `JmrixConfigPane`, this level is the
**manufacturer** selection. It provides a level of grouping that
keeps system-specific code separated from system-independent JMRI
code.

Within each manufacturer package, place specific hardware variants in
their own subpackages — the **connection mode** selection. Examples:

- `jmri.jmrix.loconet.locobuffer`,
  `jmri.jmrix.loconet.locobufferusb`,
  `jmri.jmrix.loconet.pr3`,
  `jmri.jmrix.loconet.locormi`
- `jmri.jmrix.nce.serialdriver`,
  `jmri.jmrix.nce.usbdriver`,
  `jmri.jmrix.nce.simulator`,
  `jmri.jmrix.nce.networkdriver`

Use further subpackages to group related functions. In particular,
Swing-based tools belong in a `swing` subpackage (or a deeper
subpackage of `swing`); see `jmri-swing.instructions.md`.
ConfigureXml code belongs in a `configurexml` subpackage.

## Normal operation: `SystemConnectionMemo`

After startup, the central object for any connection is its
`SystemConnectionMemo` (e.g. `LocoNetSystemConnectionMemo`,
`NceSystemConnectionMemo`). It exposes all connection-specific
objects and connection-specific implementations of the common JMRI
managers.

Although some of those managers may also be reachable directly via
`InstanceManager`, **prefer accessing them through the
`SystemConnectionMemo`**. That guarantees you get the consistent set
belonging to one specific connection, even when several connections
of the same type are configured.

A small number of tools work with the `SystemConnectionMemo`
instances themselves; obtain those from the `InstanceManager`.

## Port adapters

Connection I/O is handled by **PortAdapter** classes. The base
interface and abstract classes are:

- `jmri.jmrix.PortAdapter` — common interface.
- `jmri.jmrix.SerialPortAdapter` — Serial/USB connections.
- `jmri.jmrix.NetworkPortAdapter` — network connections.
- `jmri.jmrix.AbstractPortController` — common abstract base.
- `jmri.jmrix.AbstractSerialPortController` — abstract Serial/USB.
- `jmri.jmrix.AbstractNetworkPortController` — abstract network.
- `jmri.jmrix.AbstractStreamPortController` — stream-based;
  currently extends `AbstractPortController` directly.

System-specific subclasses inherit from these (e.g.
`loconet.LnPortController`, `loconet.LnNetworkPortController`).
Because Java has no multiple inheritance, the serial and network
descendants in one system cannot share a single common
system-specific base class, so some duplication is unavoidable.

> Naming caveat: the terminology shifts confusingly between
> "PortAdapter" and "PortController" — typically
> `Abstract*PortAdapter` ← `Sys*PortController` ← `Sys*PortAdapter`
> as you move down the hierarchy. Treat the two names as equivalent
> for now.

## Initialisation

JMRI does **not** persist `SystemConnectionMemo`s directly.
Configuration is built bottom-up: the most specific code (the
`Adapter`) connects to the hardware, then constructs the higher-level
managers, and finally creates and registers the
`SystemConnectionMemo`. The connection type is implied by the type of
the Adapter and what is on the other end of it.

### Objects involved at startup

- `ConnectionConfigXml` — created by the ConfigureXML system from
  the preferences XML; drives the load process.
- `ConnectionConfig` — registered for later persistence so the
  configuration can be written back out.
- `Adapter` — system-specific class that handles the hardware
  connection and (via its `configure()` method) creates the rest of
  the system.

### Profile XML form

A `<connection>` element in the profile XML names the
`ConnectionConfigXml` class, manufacturer, port, speed, system
prefix, user name, and any number of `<option>` entries. Example
(LocoNet over LocoBuffer-USB):

```xml
<connection xmlns=""
    class="jmri.jmrix.loconet.locobufferusb.configurexml.ConnectionConfigXml"
    disabled="no" manufacturer="Digitrax"
    port="/dev/tty.usbserial-LWPMMU13"
    speed="57,600 baud" systemPrefix="L" userName="LocoNet">
  <options>
    <option><name>CommandStation</name><value>DCS50 (Zephyr)</value></option>
    <option><name>TurnoutHandle</name><value>Normal</value></option>
  </options>
</connection>
```

### Simple initialisation sequence (LocoNet / LocoBuffer-USB example)

1. The configurexml mechanism constructs
   `jmri.jmrix.loconet.locobufferusb.configurexml.ConnectionConfigXml`
   (a child of `AbstractSerialConnectionConfigXml`, in turn a child
   of `AbstractConnectionConfigXml`).
2. `ConnectionConfigManager` calls `load(..)` on it. The base
   `AbstractSerialConnectionConfigXml.load` does:
   - `getInstance()` — overridden in the system-specific
     `ConnectionConfigXml` to assign a fresh `LocoBufferUsbAdapter`
     to its `adapter` member.
   - Read serial port info (port name, speed) and call
     `loadCommon(shared, perNode, adapter)`, which loads the four
     generic options (`option1`–`option4`), invokes
     `loadOptions(...)` (which by default calls
     `adapter.setOptionState(name, value)` for each `<option>`),
     sets the manufacturer name on the TrafficController, sets the
     system prefix and user name on the `SystemConnectionMemo`, and
     marks the adapter enabled or disabled.
   - `register()` — the system-specific override calls
     `this.register(new ConnectionConfig(adapter))`, which delegates
     to the `ConnectionConfig`'s own `register()`. That, defined on
     `AbstractConnectionConfig`, performs:

     ```java
     this.setInstance();
     InstanceManager.getDefault(jmri.ConfigureManager.class).registerPref(this);
     ConnectionConfigManager ccm =
         InstanceManager.getNullableDefault(ConnectionConfigManager.class);
     if (ccm != null) {
         ccm.add(this);
     }
     ```

     `setInstance()` ensures the `ConnectionConfig` has its own
     `Adapter` (sometimes the same instance as the
     `ConnectionConfigXml`'s, sometimes freshly created). Now there
     is a `ConnectionConfig` registered for persistence.
   - `adapter.openPort(portName, "JMRI")` opens the actual port.
   - `adapter.configure()` does final setup. For LocoBuffer-USB this
     looks roughly like:

     ```java
     setCommandStationType(getOptionState(option2Name));
     setTurnoutHandling(getOptionState(option3Name));

     // create traffic controller and connect it
     LnPacketizer packets = new LnPacketizer();
     packets.connectPort(this);

     // load the SystemConnectionMemo
     this.getSystemConnectionMemo().setLnTrafficController(packets);
     this.getSystemConnectionMemo().configureCommandStation(
         commandStationType, mTurnoutNoRetry, mTurnoutExtraSpace);
     this.getSystemConnectionMemo().configureManagers();

     // start operation
     packets.startThreads();
     ```

     This typically does three things: housekeeping + traffic
     controller creation, populating the `SystemConnectionMemo`, and
     starting threads.
3. A `LocoNetSystemConnectionMemo` is created and registered with
   the `InstanceManager`.
4. Later, `jmri.jmrix.ActiveSystemsMenu` and/or
   `jmri.jmrix.SystemsMenu` build the per-system main-menu entries:
   - Ask `InstanceManager` for all `ComponentFactory` instances
     (typically created by `SystemConnectionMemo` constructors and
     living in the `.swing` subpackage, e.g.
     `loconet.swing.LnComponentFactory`).
   - For each `ComponentFactory`, get its menu (e.g. `LocoNetMenu`)
     and add it to the GUI. The factory wires each `Action` to
     itself so that, when triggered, the action can ask the
     `SystemConnectionMemo` for the resources (TrafficController,
     SlotMonitor, etc.) it needs.

### Key lessons

- **Register managers and the `SystemConnectionMemo` exactly once.**
  Multiple registrations cause duplicates in auto-generated lists,
  menus, and tab sets.
- The `Adapter`/`PortController` hierarchy is where most of the
  per-system work lives; new connections will typically subclass one
  of `AbstractSerialPortController`, `AbstractNetworkPortController`,
  or `AbstractStreamPortController`.

### More-complex example: C/MRI

C/MRI extends the basic pattern by storing per-node parameters
(`<node>` elements with `<parameter>` children for `nodetype`,
`bitspercard`, `transmissiondelay`, `pulsewidth`,
`locsearchlightbits`, `cardtypelocation`, etc.) inside the
`<connection>` element. The same overall load sequence applies; the
`ConnectionConfigXml` subclass is responsible for reading and
applying the extra structured data.

## Configuration process

`jmrix.JmrixConfigPane` drives connection configuration in
preferences. The first-level (manufacturer) JComboBox is populated
from `ConnectionTypeList` providers registered via SPI:

- `target/classes/META-INF/services/jmri.jmrix.ConnectionTypeList`
  is generated automatically from class-level
  `@ServiceProvider(service = ConnectionTypeList.class)`
  annotations on classes such as
  `jmri.jmrix.loconet.LnConnectionTypeList`.
- Each `ConnectionTypeList` then supplies the second-level
  (connection mode) JComboBox entries — each corresponding to a
  specific `ConnectionConfig` implementation.

### Creating from scratch

When the user creates a new connection in the UI, JMRI constructs a
`ConnectionConfig`, which constructs a `PortAdapter`, similar to the
XML-load path. However, `register()` is called but `configure()` is
**not**, because we only want a configurable object, not a running
connection.

The connection's `details` `JPanel` is filled by `loadDetails()` on
the `ConnectionConfig`. Most cases delegate to one of:

- `AbstractSerialConnectionConfig` — serial links (port, baud, …).
- `AbstractNetworkConnectionConfig` — TCP/IP connections (address,
  port, …).
- `AbstractStreamConnectionConfig` — stream-based connections.
- `AbstractSimulatorConnectionConfig` — simulated connections.

### Changing options and modes

- The Swing panel for `option1`–`option4` writes directly into the
  `ConnectionConfig`/`PortAdapter`; it does not rerun `configure()`.
- Changing the connection mode in `JmrixConfigPane` calls
  `removeAll()` on the `details` panel and then
  `JmrixConfigPane.selection()` to repopulate it.

## Miscellaneous

- `jmri.swing.ConnectionLabel` is a `JLabel` that listens to a
  single connection and shows its status. It is used on the main
  splash screen and may be reused elsewhere.
