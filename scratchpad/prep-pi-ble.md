# pinet BLE controller: diagnosis (2026-09-15)

## State found
- The kernel serdev path (`hci_uart_bcm serial0-0`) loaded the firmware fine at boot on 09-13:
  BCM4345C0 patch `BCM4345C0.raspberrypi,3-model-b-plus.hcd`, then `MGMT ver 1.23`.
- By 09-15, hci0 was `DOWN RAW` and `btmgmt info` showed 0 controllers. `hciconfig hci0 up` failed
  with `Operation not supported (95)`. No process held an HCI user channel, and dmesg logged no
  hci error after boot. Something between boot and 03:34 (`bluetooth.service` was restarted
  at 03:34:58) left the device in RAW mode, where the kernel removes it from mgmt.
- `hciuart.service` is inactive, which is expected. The kernel serdev driver owns the UART, so
  `btattach`/`hciattach` do not apply (there is no `/dev/serial0`).
- `vcgencmd get_throttled` = `0x0` right now. It is not asserting under-voltage, so there is no
  correlation to report.
- Config is clean: no `dtoverlay=disable-bt` or `miniuart-bt`.

## What I tried
- Rebinding the driver (3 times):
  `echo serial0-0 | sudo tee /sys/bus/serial/drivers/hci_uart_bcm/{unbind,bind}`
  Every time: `command 0xfc18 tx timeout`, `failed to write update baudrate (-110)`,
  `Reset failed (-110)`. The chip no longer answers at all. It is wedged at the UART or firmware
  level, and toggling BT_REG_ON through the driver probe does not recover it. hci0 is now
  `DOWN` with BD address 00:00:00:00:00:00. The rebind did not fix it and left it no better.
- Networking was not touched. Ping to 8.8.8.8 was OK afterwards.

## Root cause (best evidence)
The BCM43455 BT core is hung. First symptom: it fell into RAW/unconfigured state after
bluetoothd/HCI activity. It does not respond to HCI at any baud rate. Soft recovery within the
rules failed.

## Recommended fix (needs the user; not done)
- `sudo reboot` on pinet. **This drops the PC's internet for about 1 min**, so schedule it.
  If BT is still dead after the reboot, do a full power-off/unplug. WiFi and BT share the
  chip, but WiFi is unaffected right now.
- After boot, verify with `sudo btmgmt info; bluetoothctl show`.

## One-command restore next time (if the chip is merely RAW but still responsive)
```
echo serial0-0 | sudo tee /sys/bus/serial/drivers/hci_uart_bcm/unbind && sleep 3 && echo serial0-0 | sudo tee /sys/bus/serial/drivers/hci_uart_bcm/bind && sleep 8 && sudo btmgmt info && bluetoothctl show
```
If dmesg shows `0xfc18 tx timeout` / `Reset failed (-110)`, the only fix is a reboot or power cycle.

## RESOLVED 2026-09-15 12:59 (after user reboot)
The reboot alone did not fix it. Firmware loaded, but the controller came up **unconfigured**: it had the default
address 43:45:C0:00:1F:AC with no public address. `btmgmt info` hides unconfigured controllers, which is why it listed 0 items.
`bthelper@hci0` failed with `hciconfig up`: Operation not supported, because newer kernels won't open an unconfigured controller.
Fix, using the address bthelper would derive from the serial, which touches Bluetooth only:
    sudo btmgmt -i hci0 public-addr B8:27:EB:D4:E1:A6 && bluetoothctl power on
Verified: `btmgmt info` shows 1 controller, powered, LE. Network ping OK. This is not persistent: repeat after every boot
until bthelper is fixed (a udev/systemd unit that runs the btmgmt line would do it).
