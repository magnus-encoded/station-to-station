"""Opt-in Pi peripheral for the Android central/check-in radio test.

Run: sudo /usr/bin/python3 gossip_v2_server.py --timeout 150
Requires BlueZ with GATT/LE advertising support, python3-dbus, python3-gi and
python3-cryptography. Uses the existing adapter configuration; changes no settings.
PASS means a nonce-bound Pass and its direct, signed request were verified here.
"""
import argparse
import base64
import hashlib
import os
import signal
import time

import dbus
import dbus.service
from dbus.mainloop.glib import DBusGMainLoop
from gi.repository import GLib
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

SERVICE = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7721"
CHALLENGE = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7722"
PASS = "7b7e6f2a-7601-4b1a-9e2c-2a6f6f0b7723"
PROPS = "org.freedesktop.DBus.Properties"
OBJECTS = "org.freedesktop.DBus.ObjectManager"
GATT_SERVICE = "org.bluez.GattService1"
GATT_CHAR = "org.bluez.GattCharacteristic1"
ADVERTISEMENT = "org.bluez.LEAdvertisement1"
ROOT = "/io/github/stationtostation/gossiptest"


def b64(value):
    return base64.b64encode(value).decode("ascii")


def verify(key, signature, payload):
    public = serialization.load_der_public_key(base64.b64decode(key, validate=True))
    if not isinstance(public, ec.EllipticCurvePublicKey) or not isinstance(public.curve, ec.SECP256R1):
        raise ValueError("expected P-256 key")
    public.verify(base64.b64decode(signature, validate=True), payload, ec.ECDSA(hashes.SHA256()))


def verify_request(packet, nonce):
    lines = packet.decode("utf-8").split("\n")
    if len(lines) != 3 or lines[0] != "station-to-station/gossip-pass/2":
        raise ValueError("expected exactly one Fact in v2 Pass")
    sender, proof = lines[1].split("\t")
    verify(sender, proof, ("station-to-station/gossip-auth/2\n" + b64(nonce)).encode())
    record = lines[2].split("\t")
    if len(record) != 12 or len(lines[2].encode()) > 8192:
        raise ValueError("invalid Fact framing")
    fact_id, *fields, signature = record
    payload = ("station-to-station/gossip-fact/2\n" + "\n".join(fields)).encode()
    if hashlib.sha256(payload).hexdigest() != fact_id:
        raise ValueError("Fact id mismatch")
    verify(fields[3], signature, payload)
    if fields[3] != sender or fields[6] != "request" or fields[7] != "-1":
        raise ValueError("expected direct own request")
    created, expires = int(fields[4]), int(fields[5])
    if not (0 <= created < expires and expires - created <= 108_000_000
            and created <= int(time.time() * 1000) < expires):
        raise ValueError("request outside validity interval")
    base64.b64decode(fields[8], validate=True).decode("utf-8")
    return fact_id


class Export(dbus.service.Object):
    def __init__(self, bus, path, interface, properties):
        super().__init__(bus, path)
        self.path = path
        self.interface, self.properties = interface, properties

    @dbus.service.method(PROPS, in_signature="s", out_signature="a{sv}")
    def GetAll(self, interface):
        return self.properties if interface == self.interface else {}


class Challenge(Export):
    @dbus.service.method(GATT_CHAR, in_signature="a{sv}", out_signature="ay")
    def ReadValue(self, options):
        device = str(options.get("device", ""))
        offset = int(options.get("offset", 0))
        if not device:
            raise dbus.exceptions.DBusException("device missing", name="org.bluez.Error.Failed")
        if offset == 0:
            nonce = os.urandom(32)
            proof = server.key.sign(("station-to-station/gossip-challenge-proof/2\n" + b64(nonce)).encode(), ec.ECDSA(hashes.SHA256()))
            payload = ("station-to-station/gossip-challenge/2\n" + b64(nonce) + "\n" + server.author + "\n" + b64(proof)).encode()
            server.connections[device] = [nonce, payload, bytearray()]
            print("Challenge read by " + device, flush=True)
        connection = server.connections.get(device)
        if connection is None or offset > len(connection[1]):
            raise dbus.exceptions.DBusException("invalid offset", name="org.bluez.Error.InvalidOffset")
        return dbus.ByteArray(connection[1][offset:])


class Passing(Export):
    @dbus.service.method(GATT_CHAR, in_signature="aya{sv}", out_signature="")
    def WriteValue(self, value, options):
        device = str(options.get("device", ""))
        connection = server.connections.get(device)
        if connection is None:
            raise dbus.exceptions.DBusException("read challenge first", name="org.bluez.Error.NotPermitted")
        chunk = bytes(value)
        if len(connection[2]) + len(chunk) > 40_000:
            server.connections.pop(device, None)
            raise dbus.exceptions.DBusException("Pass too large", name="org.bluez.Error.InvalidValueLength")
        connection[2].extend(chunk)
        if not chunk:
            server.connections.pop(device, None)
            try:
                fact_id = verify_request(bytes(connection[2]), connection[0])
            except Exception as error:
                print(f"FAIL verification: {type(error).__name__}: {error}", flush=True)
                server.finish(False)
                raise dbus.exceptions.DBusException("invalid request", name="org.bluez.Error.NotPermitted")
            print(f"PASS: verified direct own request {fact_id}; {len(connection[2])} bytes", flush=True)
            # Leave time for BlueZ to send the final ATT response before unregistering.
            server.finish(True)


class Application(dbus.service.Object):
    @dbus.service.method(OBJECTS, out_signature="a{oa{sa{sv}}}")
    def GetManagedObjects(self):
        return {obj.path: {obj.interface: obj.properties} for obj in server.objects}


class Advert(Export):
    @dbus.service.method(ADVERTISEMENT, in_signature="", out_signature="")
    def Release(self):
        pass


class Server:
    def __init__(self, adapter, timeout):
        self.bus = dbus.SystemBus()
        self.loop = GLib.MainLoop()
        self.success = False
        self.connections = {}
        self.bus.add_signal_receiver(self.device_changed, dbus_interface=PROPS,
                                     signal_name="PropertiesChanged", path_keyword="path")
        self.key = ec.generate_private_key(ec.SECP256R1())
        self.author = b64(self.key.public_key().public_bytes(serialization.Encoding.DER, serialization.PublicFormat.SubjectPublicKeyInfo))
        managed = dbus.Interface(self.bus.get_object("org.bluez", "/"), OBJECTS).GetManagedObjects()
        candidates = [path for path, interfaces in managed.items()
                      if "org.bluez.GattManager1" in interfaces and "org.bluez.LEAdvertisingManager1" in interfaces
                      and (adapter is None or str(path).endswith("/" + adapter))]
        if not candidates:
            raise RuntimeError("no adapter with GATT and LE advertising managers")
        obj = self.bus.get_object("org.bluez", candidates[0])
        self.gatt = dbus.Interface(obj, "org.bluez.GattManager1")
        self.advertising = dbus.Interface(obj, "org.bluez.LEAdvertisingManager1")
        service = ROOT + "/service0"
        self.objects = [Export(self.bus, service, GATT_SERVICE,
                               {"UUID": SERVICE, "Primary": dbus.Boolean(True)})]
        for cls, name, uuid, flags in ((Challenge, "challenge", CHALLENGE, ["read"]),
                                       (Passing, "pass", PASS, ["write"])):
            self.objects.append(cls(self.bus, service + "/" + name, GATT_CHAR,
                {"UUID": uuid, "Service": dbus.ObjectPath(service), "Flags": dbus.Array(flags, signature="s")}))
        self.application = Application(self.bus, ROOT)
        self.advert = Advert(self.bus, ROOT + "/advert", ADVERTISEMENT,
            {"Type": "peripheral", "ServiceUUIDs": dbus.Array([SERVICE], signature="s")})
        self.app_registered = self.advert_registered = False
        GLib.timeout_add_seconds(timeout, self.expired)

    def device_changed(self, interface, changed, invalidated, path=None):
        if interface == "org.bluez.Device1" and changed.get("Connected") == False:
            self.connections.pop(str(path), None)

    def finish(self, success):
        self.success = success
        GLib.timeout_add(1000, self.loop.quit)

    def expired(self):
        print("FAIL: timed out waiting for direct own request", flush=True)
        self.loop.quit()
        return False

    def failed(self, error):
        print("FAIL registration: " + str(error), flush=True)
        self.loop.quit()

    def app_ready(self):
        self.app_registered = True
        self.advertising.RegisterAdvertisement(self.advert.path, {}, reply_handler=self.ready, error_handler=self.failed)

    def ready(self):
        self.advert_registered = True
        print("READY: gossip v2 peripheral advertising " + SERVICE, flush=True)

    def run(self):
        self.gatt.RegisterApplication(ROOT, {}, reply_handler=self.app_ready, error_handler=self.failed)
        try:
            self.loop.run()
        finally:
            if self.advert_registered:
                self.advertising.UnregisterAdvertisement(self.advert.path)
            if self.app_registered:
                self.gatt.UnregisterApplication(ROOT)
        return 0 if self.success else 1


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--timeout", type=int, default=150)
    parser.add_argument("--adapter", help="e.g. hci0; default first capable adapter")
    args = parser.parse_args()
    if args.timeout < 1:
        parser.error("timeout must be positive")
    DBusGMainLoop(set_as_default=True)
    server = Server(args.adapter, args.timeout)
    signal.signal(signal.SIGTERM, lambda *_: server.loop.quit())
    signal.signal(signal.SIGINT, lambda *_: server.loop.quit())
    raise SystemExit(server.run())
