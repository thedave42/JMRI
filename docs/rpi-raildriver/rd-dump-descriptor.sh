#!/usr/bin/env bash
# Phase 1: Dump the RailDriver USB descriptors to ~/rd-capture/.
#
# Produces:
#   01-lsusb-v.log              -- output of lsusb -v -d 05f3:00d2
#   02-report-descriptor.log    -- xxd dump of the device's HID report descriptor
#                                  read straight from sysfs
#
# This is the trixie-friendly replacement for `usbhid-dump` (which was dropped
# from Debian 13). For the parsed pretty-print form, build usbhid-dump from
# https://github.com/DIGImend/usbhid-dump.
#
# Usage:
#   docs/rpi-raildriver/rd-dump-descriptor.sh
#
# Pre-flight:
#   - Quit JMRI completely so it isn't holding the device.
#   - sudo apt install -y usbutils  (provides lsusb)
#
# See issue #1 for the broader capture plan.

set -euo pipefail

VID="${RD_VID:-05f3}"
PID="${RD_PID:-00d2}"
OUTDIR="${RD_OUTDIR:-${HOME}/rd-capture}"

mkdir -p "$OUTDIR"

echo ">>> Output directory : $OUTDIR"
echo ">>> Looking for USB device $VID:$PID..."

if ! command -v lsusb >/dev/null 2>&1; then
    echo "ERROR: lsusb not found. Install with: sudo apt install -y usbutils" >&2
    exit 1
fi

if ! lsusb -d "$VID:$PID" >/dev/null 2>&1; then
    echo "ERROR: USB device $VID:$PID not found by lsusb." >&2
    echo "       Is the RailDriver plugged in? Try: lsusb | grep -i 05f3" >&2
    exit 1
fi

# --- 01: lsusb -v ---------------------------------------------------------
LSUSB_LOG="$OUTDIR/01-lsusb-v.log"
echo ">>> Dumping lsusb -v to $LSUSB_LOG"
sudo lsusb -v -d "$VID:$PID" > "$LSUSB_LOG" 2>&1
n=$(wc -l < "$LSUSB_LOG")
echo "    $n lines"

# --- 02: report descriptor from sysfs -------------------------------------
# Find the sysfs device directory for this VID. Multiple matches are possible
# (one per attached device with that vendor); we pick the first whose
# idProduct matches PID.
DEVPATH=""
shopt -s nullglob
for vfile in /sys/bus/usb/devices/*/idVendor; do
    [[ -r "$vfile" ]] || continue
    v=$(< "$vfile")
    [[ "$v" == "$VID" ]] || continue
    pdir=$(dirname "$vfile")
    if [[ -r "$pdir/idProduct" ]]; then
        p=$(< "$pdir/idProduct")
        if [[ "$p" == "$PID" ]]; then
            DEVPATH="$pdir"
            break
        fi
    fi
done
shopt -u nullglob

if [[ -z "$DEVPATH" ]]; then
    echo "ERROR: no /sys/bus/usb/devices/* entry has idVendor=$VID idProduct=$PID" >&2
    exit 1
fi
# Resolve sysfs symlink to its real path so `find` will traverse children.
# /sys/bus/usb/devices/<id> is a symlink into /sys/devices/...; without -L,
# `find` would refuse to descend.
REAL_DEVPATH=$(readlink -f "$DEVPATH")
echo ">>> sysfs device path: $DEVPATH"
echo "    (real path:        $REAL_DEVPATH)"

RD_FILE=$(sudo find "$REAL_DEVPATH" -name report_descriptor 2>/dev/null | head -n 1 || true)
if [[ -z "$RD_FILE" ]]; then
    echo "ERROR: no 'report_descriptor' file under $REAL_DEVPATH" >&2
    echo "       (this is unusual; the device may not be a HID device" >&2
    echo "       on this kernel, or hid-generic isn't bound)" >&2
    exit 1
fi
echo ">>> report descriptor file: $RD_FILE"

RD_LOG="$OUTDIR/02-report-descriptor.log"
sudo xxd "$RD_FILE" > "$RD_LOG"
n=$(wc -l < "$RD_LOG")
echo "    $n lines, $(wc -c < "$RD_LOG") bytes"

echo
echo "DONE. Files:"
ls -la "$LSUSB_LOG" "$RD_LOG"
