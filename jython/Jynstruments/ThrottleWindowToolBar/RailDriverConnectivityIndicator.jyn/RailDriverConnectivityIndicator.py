# RailDriverConnectivityIndicator.jyn — passive toolbar indicator for
# RailDriver USB device connectivity.
#
# Two visual states:
#   - Connected (green "RD" icon)  when isRailDriverConnected() is true
#   - Disconnected (grey "RD" icon) when false
#
# Right-click popup with "Settings..." opens the RailDriver Settings frame.
# No left-click action — this is a passive indicator, not a toggle.
# The indicator is never disabled (setEnabled(false) is never called)
# so the right-click popup works in both visual states.
#
# Auto-installed by RailDriverMenuItem.attachThrottleWindow() idempotently.

import java
import jmri.jmrit.jython.Jynstrument as Jynstrument
import jmri.jmrit.usb.RailDriverMenuItem as RailDriverMenuItem
from java.awt.event import MouseAdapter
from java.beans import PropertyChangeListener
from javax.swing import JButton, JPopupMenu, JMenuItem, ImageIcon


class RailDriverConnectivityIndicator(Jynstrument):

    def getExpectedContextClassName(self):
        return "jmri.jmrit.throttle.ThrottleWindow"

    def init(self):
        self.menuItem = RailDriverMenuItem.getInstance()
        self.iconConnected = ImageIcon(
            self.getFolder() + "/icons/rd-connected.png")
        self.iconDisconnected = ImageIcon(
            self.getFolder() + "/icons/rd-disconnected.png")

        self.button = JButton(self.iconDisconnected)
        self.add(self.button)

        # Right-click popup with "Settings..." item
        popup = JPopupMenu()
        item = JMenuItem("Settings...")
        item.actionPerformed = self.openSettings
        popup.add(item)
        self.setPopUpMenu(popup)

        # Forward right-clicks on the inner button to the Jynstrument's
        # popup-menu trigger.
        self.button.addMouseListener(_PopupForwarder(self))

        if self.menuItem is not None:
            self.listener = _PCSListener(self)
            self.menuItem.addSettingsListener(self.listener)
        else:
            self.listener = None

        self._refreshState()

    def quit(self):
        if self.menuItem is not None and self.listener is not None:
            self.menuItem.removeSettingsListener(self.listener)
            self.listener.owner = None
        self.listener = None

    def openSettings(self, evt):
        try:
            cls = java.lang.Class.forName(
                "jmri.jmrit.usb.swing.RailDriverSettingsAction")
            action = cls.getDeclaredConstructor().newInstance()
            action.actionPerformed(evt)
        except Exception:
            pass

    # ----- internal -----

    def _onPCS(self, evt):
        if evt.getPropertyName() == "railDriverConnected":
            self._refreshState()

    def _refreshState(self):
        if self.menuItem is None:
            self.button.setIcon(self.iconDisconnected)
            self.button.setToolTipText("RailDriver not available")
            return
        if self.menuItem.isRailDriverConnected():
            self.button.setIcon(self.iconConnected)
            self.button.setToolTipText("RailDriver connected")
        else:
            self.button.setIcon(self.iconDisconnected)
            self.button.setToolTipText("RailDriver disconnected")


class _PCSListener(PropertyChangeListener):
    """Bridge from Java PCS events back into the Jynstrument.  Marshals onto
    the EDT because hidDeviceAttached/Detached fire from hid4java's worker
    thread and the listener mutates Swing state."""

    def __init__(self, owner):
        self.owner = owner

    def propertyChange(self, evt):
        from javax.swing import SwingUtilities
        owner = self.owner
        if owner is None:
            return
        def run():
            try:
                if self.owner is not None:
                    self.owner._onPCS(evt)
            except Exception:
                pass
        if SwingUtilities.isEventDispatchThread():
            run()
        else:
            SwingUtilities.invokeLater(run)


class _PopupForwarder(MouseAdapter):
    """Forwards right-click presses on the inner JButton to the
    Jynstrument's popup-menu trigger."""

    def __init__(self, owner):
        self.owner = owner

    def mousePressed(self, e):
        self._maybeShow(e)

    def mouseReleased(self, e):
        self._maybeShow(e)

    def _maybeShow(self, e):
        if e.isPopupTrigger():
            popup = self.owner.getPopUpMenu()
            if popup is not None:
                popup.show(e.getComponent(), e.getX(), e.getY())
