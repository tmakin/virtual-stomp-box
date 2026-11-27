#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.9"
# dependencies = ["bleak"]
# ///
"""List the WT901's Bluetooth services and characteristics, read every readable value, and flag
any firmware-update service. Read-only: nothing is written to the sensor.

    uv run tools/wit_ble_gatt.py                 # first device whose name starts with WT901
    uv run tools/wit_ble_gatt.py --name WT901BLE68

macOS: the app running this (Terminal, VS Code, ...) needs Bluetooth access under
System Settings > Privacy & Security > Bluetooth. Disconnect the phone app first: the sensor
takes one connection at a time.
"""
import argparse, asyncio, sys

UPDATE_SERVICES = {
    "0000fe59-0000-1000-8000-00805f9b34fb": "Nordic Secure DFU (buttonless)",
    "00001530-1212-efde-1523-785feabcd123": "Nordic legacy DFU",
    "8d53dc1d-1db7-4cd3-868b-8a527460aa84": "SMP (MCUmgr / mcuboot)",
}


async def find_sensor(name, timeout=10.0):
    from bleak import BleakScanner
    dev = await BleakScanner.find_device_by_filter(
        lambda d, adv: bool(d.name) and d.name.startswith(name), timeout=timeout)
    if dev is None:
        sys.exit(f"No device whose name starts with {name!r}. Is it on and not connected to the phone?")
    print(f"Found {dev.name} at {dev.address}")
    return dev


def show(value):
    text = value.decode("utf-8", "replace") if value and all(32 <= b < 127 for b in value) else ""
    return value.hex(" ") + (f'  "{text}"' if text else "")


async def read_value(client, char):
    if "read" not in char.properties:
        return ""
    try:
        return "  = " + show(bytes(await client.read_gatt_char(char)))
    except Exception as e:  # some reads need pairing or are refused
        return f"  (read failed: {type(e).__name__}: {e})"


async def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--name", default="WT901")
    a = ap.parse_args()
    from bleak import BleakClient
    dev = await find_sensor(a.name)
    async with BleakClient(dev) as client:
        found = []
        for svc in client.services:
            tag = UPDATE_SERVICES.get(svc.uuid.lower())
            if tag: found.append(f"{svc.uuid} {tag}")
            print(f"Service {svc.uuid}  {svc.description}" + (f"  <== {tag}" if tag else ""))
            for ch in svc.characteristics:
                print(f"  Char {ch.uuid}  [{', '.join(ch.properties)}]  {ch.description}"
                      + await read_value(client, ch))
                for d in ch.descriptors:
                    print(f"    Desc {d.uuid}  {d.description}")
        print("Update services:", "; ".join(found) if found else "none of FE59 / legacy DFU / SMP")


if __name__ == "__main__":
    asyncio.run(main())
