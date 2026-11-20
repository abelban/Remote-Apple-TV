"""In-process Apple TV transport. All pyatv objects belong to one live event loop.

Kotlin calls the JSON functions from a single worker, never the Android UI thread.
Discovery normally uses Android NSD; unicast discovery is a manual-IP fallback.
Pairing secrets are returned only to the Android Keystore-backed device store.
"""

import asyncio
import ipaddress
import json
import logging
import threading

import pyatv
from pyatv import exceptions
from pyatv.conf import AppleTV, ManualService
from pyatv.const import (
    FeatureName,
    FeatureState,
    InputAction,
    KeyboardFocusState,
    PairingRequirement,
    Protocol,
)
from companion_compat import install as install_companion_actions

install_companion_actions()

# pyatv DEBUG logs may contain pairing PINs. Never enable them in this app.
logging.getLogger("pyatv").setLevel(logging.CRITICAL)


class RemoteClient:
    def __init__(self):
        self.loop = asyncio.new_event_loop()
        self.thread = threading.Thread(target=self._run, name="AppleTV", daemon=True)
        self.thread.start()
        self.device = None
        self.pairing = None
        self.muted = False
        self.previous_volume = None

    def _run(self):
        asyncio.set_event_loop(self.loop)
        self.loop.run_forever()

    def submit(self, coroutine, timeout=20):
        future = asyncio.run_coroutine_threadsafe(
            asyncio.wait_for(coroutine, timeout), self.loop
        )
        try:
            return future.result(timeout + 2)
        except BaseException:
            future.cancel()
            raise

    @staticmethod
    def config(record):
        address = ipaddress.ip_address(record["address"])
        if address.version != 4:
            raise ValueError("IPv4 required")
        port = int(record["port"])
        if not 1 <= port <= 65535:
            raise ValueError("Invalid Companion port")
        config = AppleTV(address, record["name"])
        config.add_service(
            ManualService(
                record["id"],
                Protocol.Companion,
                port,
                record.get("properties", {}),
                credentials=record.get("credentials") or None,
                pairing_requirement=PairingRequirement.Mandatory,
            )
        )
        return config

    async def disconnect(self):
        if self.pairing:
            pairing, self.pairing = self.pairing, None
            await pairing.close()
        if self.device:
            device, self.device = self.device, None
            tasks = device.close()
            if tasks:
                await asyncio.gather(*tasks, return_exceptions=True)
        self.muted = False
        self.previous_volume = None

    async def begin_pairing(self, record):
        await self.disconnect()
        self.pairing = await pyatv.pair(
            self.config(record), Protocol.Companion, self.loop, name="Android Remote"
        )
        try:
            await self.pairing.begin()
        except BaseException:
            await self.disconnect()
            raise
        return {}

    async def finish_pairing(self, pin):
        if not isinstance(pin, str) or len(pin) != 4 or not pin.isascii() or not pin.isdigit():
            raise ValueError("A four-digit PIN is required")
        if not self.pairing:
            raise exceptions.PairingError("Pairing has expired")
        pairing = self.pairing
        try:
            pairing.pin(int(pin))
            await pairing.finish()
            if not pairing.has_paired:
                raise exceptions.PairingError("Pairing was rejected")
            return {"credentials": pairing.service.credentials}
        finally:
            self.pairing = None
            await pairing.close()

    async def connect(self, record):
        await self.disconnect()
        if not record.get("credentials"):
            raise exceptions.AuthenticationError("Pairing is required")
        self.device = await pyatv.connect(self.config(record), self.loop)
        return self.snapshot()

    def snapshot(self):
        if self.device is None:
            return {"connected": False}
        return {
            "connected": True,
            "power": self.device.power.power_state.name,
            "keyboard": self.device.keyboard.text_focus_state == KeyboardFocusState.Focused,
            "can_mute": self.device.features.get_feature(FeatureName.SetVolume).state
            == FeatureState.Available,
            "muted": self.muted,
        }

    async def command(self, command, action="tap", text=""):
        if self.device is None:
            raise exceptions.ConnectionFailedError("Apple TV is disconnected")
        remote = self.device.remote_control
        actions = {
            "tap": InputAction.SingleTap,
            "double": InputAction.DoubleTap,
            "hold": InputAction.Hold,
        }
        if action not in actions:
            raise ValueError("Unknown input action")
        if command in {"up", "down", "left", "right", "select", "menu", "home"}:
            await getattr(remote, command)(action=actions[action])
        elif command == "play_pause":
            await getattr(remote, command)()
        elif command in {"volume_up", "volume_down"}:
            # HID acknowledgement precedes the asynchronous reported volume.
            # Await Audio's update when available so a following mute remembers
            # the new level rather than the previous key press's cached value.
            audio_available = self.device.features.get_feature(FeatureName.Volume).state == FeatureState.Available
            await getattr(self.device.audio if audio_available else remote, command)()
            self.muted = False
        elif command == "wake":
            await self.device.power.turn_on()
        elif command == "sleep":
            await self.device.power.turn_off()
        elif command == "mute":
            if not self.snapshot()["can_mute"]:
                raise exceptions.NotSupportedError("Volume control unavailable")
            if self.muted:
                if self.previous_volume is None:
                    raise exceptions.NotSupportedError("Previous volume is unknown")
                await self.device.audio.set_volume(self.previous_volume)
                self.muted = False
            else:
                # Never invent a restore volume: preserve the reported audio level.
                level = self.device.audio.volume
                if level <= 0:
                    raise exceptions.NotSupportedError("Previous volume is unknown")
                await self.device.audio.set_volume(0)
                self.previous_volume = level
                self.muted = True
        elif command == "text":
            if self.device.keyboard.text_focus_state != KeyboardFocusState.Focused:
                raise exceptions.NotSupportedError("No text field is focused")
            await self.device.keyboard.text_set(text)
        else:
            raise ValueError("Unknown command")
        return self.snapshot()

    async def discover_host(self, host):
        address = ipaddress.IPv4Address(host)
        devices = await pyatv.scan(
            self.loop, timeout=5, hosts=[str(address)], protocol={Protocol.Companion}
        )
        result = []
        for device in devices:
            service = device.get_service(Protocol.Companion)
            if service:
                result.append(
                    {
                        "id": device.identifier or str(address),
                        "name": device.name,
                        "address": str(address),
                        "port": service.port,
                        "properties": dict(service.properties),
                    }
                )
        return {"devices": result}


_client = None
_client_lock = threading.Lock()


def _get_client():
    global _client
    with _client_lock:
        if _client is None:
            _client = RemoteClient()
    return _client


def _response(operation):
    try:
        result = operation()
        return json.dumps({"ok": True, **(result or {})}, ensure_ascii=False)
    except (exceptions.NotSupportedError, NotImplementedError):
        return json.dumps({"ok": False, "error": "unsupported"})
    except (exceptions.AuthenticationError, exceptions.PairingError):
        return json.dumps({"ok": False, "error": "authentication"})
    except (TimeoutError, asyncio.TimeoutError):
        return json.dumps({"ok": False, "error": "timeout"})
    except (ConnectionError, OSError, exceptions.ConnectionFailedError):
        return json.dumps({"ok": False, "error": "connection"})
    except ValueError:
        return json.dumps({"ok": False, "error": "invalid_input"})
    except Exception:
        # Exception strings can contain credentials; expose only an error category.
        return json.dumps({"ok": False, "error": "transport"})


def begin_pairing(record):
    return _response(lambda: _get_client().submit(_get_client().begin_pairing(json.loads(record))))


def finish_pairing(pin):
    return _response(lambda: _get_client().submit(_get_client().finish_pairing(pin)))


def connect(record):
    return _response(lambda: _get_client().submit(_get_client().connect(json.loads(record))))


def command(name, action="tap", text=""):
    return _response(lambda: _get_client().submit(_get_client().command(name, action, text), timeout=8))


def discover_host(host):
    return _response(lambda: _get_client().submit(_get_client().discover_host(host), timeout=10))


def disconnect():
    return _response(lambda: _get_client().submit(_get_client().disconnect(), timeout=5))


def status():
    async def snapshot():
        return _get_client().snapshot()

    return _response(lambda: _get_client().submit(snapshot(), timeout=3))
