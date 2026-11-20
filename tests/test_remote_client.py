"""Transport behavior tests; no Apple TV hardware or credentials required."""

import asyncio
import json
from pathlib import Path
import sys
from types import SimpleNamespace
import unittest
from unittest.mock import AsyncMock, Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers.aead import ChaCha20Poly1305
from chacha20poly1305_reuseable import ChaCha20Poly1305Reusable
from pyatv import exceptions
from pyatv.const import FeatureState, InputAction, KeyboardFocusState, PowerState, Protocol
import remote_client as transport
from companion_compat import AndroidCompanionRemoteControl
from pyatv.protocols.companion import HidCommand


class TransportTests(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        self.client = object.__new__(transport.RemoteClient)
        self.client.loop = asyncio.get_running_loop()
        self.client.pairing = None
        self.client.muted = False
        self.client.previous_volume = None
        self.remote = SimpleNamespace(**{name: AsyncMock() for name in (
            "up", "down", "left", "right", "select", "menu", "home", "play_pause", "volume_up", "volume_down",
        )})
        self.client.device = SimpleNamespace(
            remote_control=self.remote,
            power=SimpleNamespace(power_state=PowerState.On, turn_on=AsyncMock(), turn_off=AsyncMock()),
            keyboard=SimpleNamespace(text_focus_state=KeyboardFocusState.Focused, text_set=AsyncMock()),
            features=SimpleNamespace(get_feature=Mock(return_value=SimpleNamespace(state=FeatureState.Available))),
            audio=SimpleNamespace(volume=37, set_volume=AsyncMock(), volume_up=AsyncMock(), volume_down=AsyncMock()),
            close=Mock(return_value=set()),
        )

    async def test_all_navigation_commands(self):
        for name in ("up", "down", "left", "right", "select", "menu", "home"):
            await self.client.command(name)
            getattr(self.remote, name).assert_awaited_once_with(action=InputAction.SingleTap)

    async def test_home_double_tap_and_hold(self):
        await self.client.command("home", "double")
        await self.client.command("home", "hold")
        self.assertEqual([call.kwargs["action"] for call in self.remote.home.await_args_list], [InputAction.DoubleTap, InputAction.Hold])

    async def test_power_commands(self):
        device = self.client.device
        await self.client.command("sleep")
        await self.client.command("wake")
        device.power.turn_off.assert_awaited_once()
        device.power.turn_on.assert_awaited_once()

    async def test_mute_restores_exact_volume(self):
        audio = self.client.device.audio
        await self.client.command("mute")
        self.assertTrue(self.client.muted)
        audio.volume = 0
        await self.client.command("mute")
        self.assertFalse(self.client.muted)
        self.assertEqual([call.args[0] for call in audio.set_volume.await_args_list], [0, 37])

    async def test_mute_never_invents_restore_volume(self):
        self.client.device.audio.volume = 0
        with self.assertRaises(exceptions.NotSupportedError):
            await self.client.command("mute")
        self.client.device.audio.set_volume.assert_not_awaited()

    async def test_mute_rejects_unavailable_audio(self):
        self.client.device.features.get_feature.return_value.state = FeatureState.Unavailable
        with self.assertRaises(exceptions.NotSupportedError):
            await self.client.command("mute")

    async def test_volume_buttons_use_hid(self):
        self.client.device.features.get_feature.return_value.state = FeatureState.Unavailable
        await self.client.command("volume_up")
        await self.client.command("volume_down")
        self.remote.volume_up.assert_awaited_once_with()
        self.remote.volume_down.assert_awaited_once_with()

    async def test_volume_update_completes_before_mute_remembers_level(self):
        audio = self.client.device.audio
        reported = asyncio.Event()

        async def lower_and_wait_for_report():
            await reported.wait()
            audio.volume = 32

        audio.volume_down.side_effect = lower_and_wait_for_report
        pending = asyncio.create_task(self.client.command("volume_down"))
        await asyncio.sleep(0)
        self.assertFalse(pending.done(), "A HID acknowledgement must not finish before the volume report")
        reported.set()
        await pending
        await self.client.command("mute")
        audio.volume = 0
        await self.client.command("mute")
        self.assertEqual([call.args[0] for call in audio.set_volume.await_args_list], [0, 32])

    async def test_text_is_sent_without_modification(self):
        text = "Привет, Apple TV! 🛋️"
        await self.client.command("text", text=text)
        self.client.device.keyboard.text_set.assert_awaited_once_with(text)

    async def test_keyboard_requires_focused_field(self):
        self.client.device.keyboard.text_focus_state = KeyboardFocusState.Unfocused
        with self.assertRaises(exceptions.NotSupportedError):
            await self.client.command("text", text="hello")
        self.client.device.keyboard.text_set.assert_not_awaited()

    async def test_unknown_commands_are_rejected(self):
        for name in ("__class__", "close", "shell", ""):
            with self.assertRaises(ValueError):
                await self.client.command(name)

    async def test_unknown_input_actions_are_rejected(self):
        with self.assertRaises(ValueError):
            await self.client.command("home", "invalid")
        self.remote.home.assert_not_awaited()

    async def test_disconnected_commands_are_rejected(self):
        self.client.device = None
        with self.assertRaises(exceptions.ConnectionFailedError):
            await self.client.command("select")

    async def test_invalid_pin_is_rejected_before_network(self):
        for pin in ("123", "12345", "abcd", "١٢٣٤", "１２３４", None):
            with self.assertRaises(ValueError):
                await self.client.finish_pairing(pin)

    async def test_pairing_preserves_leading_zero(self):
        pairing = SimpleNamespace(pin=Mock(), finish=AsyncMock(), close=AsyncMock(), has_paired=True,
                                  service=SimpleNamespace(credentials="secret-credentials"))
        self.client.pairing = pairing
        result = await self.client.finish_pairing("0123")
        pairing.pin.assert_called_once_with(123)
        self.assertEqual(result["credentials"], "secret-credentials")
        pairing.close.assert_awaited_once()
        self.assertIsNone(self.client.pairing)

    async def test_failed_pairing_closes_session(self):
        pairing = SimpleNamespace(pin=Mock(), finish=AsyncMock(side_effect=exceptions.PairingError("bad PIN")), close=AsyncMock())
        self.client.pairing = pairing
        with self.assertRaises(exceptions.PairingError):
            await self.client.finish_pairing("1111")
        pairing.close.assert_awaited_once()
        self.assertIsNone(self.client.pairing)

    async def test_disconnect_closes_pairing_and_connection(self):
        device = self.client.device
        pairing = SimpleNamespace(close=AsyncMock())
        self.client.pairing = pairing
        await self.client.disconnect()
        pairing.close.assert_awaited_once()
        device.close.assert_called_once()
        self.assertIsNone(self.client.device)
        self.assertIsNone(self.client.pairing)

    async def test_missing_credentials_never_connect(self):
        with patch.object(transport.pyatv, "connect", new_callable=AsyncMock) as connect:
            with self.assertRaises(exceptions.AuthenticationError):
                await self.client.connect({"credentials": ""})
            connect.assert_not_awaited()


class SerializationTests(unittest.TestCase):
    def test_manual_config(self):
        record = {"id": "living-room", "name": "Гостиная", "address": "192.168.1.10", "port": 49152, "credentials": "secret"}
        config = transport.RemoteClient.config(record)
        self.assertEqual(config.name, "Гостиная")
        self.assertEqual(config.get_service(Protocol.Companion).credentials, "secret")
        self.assertEqual(config.get_service(Protocol.Companion).port, 49152)

    def test_invalid_addresses_and_ports(self):
        for host, port in (("not-an-ip", 123), ("::1", 123), ("192.168.1.2", 0), ("192.168.1.2", 65536)):
            with self.assertRaises(ValueError):
                transport.RemoteClient.config({"id": "tv", "name": "TV", "address": host, "port": port})

    def test_error_messages_do_not_leak_secrets(self):
        for exception in (RuntimeError("secret-key 1234"), OSError("secret-key"), exceptions.PairingError("PIN 1234")):
            def fail():
                raise exception
            response = transport._response(fail)
            self.assertFalse(json.loads(response)["ok"])
            self.assertNotIn("1234", response)
            self.assertNotIn("secret", response)

    def test_timeout_category(self):
        def fail():
            raise TimeoutError()
        self.assertEqual(json.loads(transport._response(fail))["error"], "timeout")

    def test_android_aead_is_public_cryptography_implementation(self):
        self.assertIs(ChaCha20Poly1305Reusable, ChaCha20Poly1305)

    def test_encryption_authentication_and_tamper_rejection(self):
        key = ChaCha20Poly1305.generate_key()
        cipher = ChaCha20Poly1305Reusable(key)
        nonce = bytes(12)
        packet = cipher.encrypt(nonce, b"Companion message", b"header")
        self.assertEqual(cipher.decrypt(nonce, packet, b"header"), b"Companion message")
        with self.assertRaises(InvalidTag):
            cipher.decrypt(nonce, packet[:-1] + bytes([packet[-1] ^ 1]), b"header")


class CompanionActionTests(unittest.IsolatedAsyncioTestCase):
    async def test_double_tap_emits_two_complete_hid_clicks(self):
        api = SimpleNamespace(hid_command=AsyncMock())
        remote = AndroidCompanionRemoteControl(api)
        await remote.home(InputAction.DoubleTap)
        self.assertEqual([call.args for call in api.hid_command.await_args_list], [
            (True, HidCommand.Home), (False, HidCommand.Home),
            (True, HidCommand.Home), (False, HidCommand.Home),
        ])

    async def test_hold_uses_down_delay_up(self):
        api = SimpleNamespace(hid_command=AsyncMock())
        with patch("companion_compat.asyncio.sleep", new_callable=AsyncMock) as delay:
            await AndroidCompanionRemoteControl(api).home(InputAction.Hold)
            delay.assert_awaited_once_with(1)
        self.assertEqual([call.args for call in api.hid_command.await_args_list], [(True, HidCommand.Home), (False, HidCommand.Home)])

    async def test_cancelled_hold_releases_button(self):
        api = SimpleNamespace(hid_command=AsyncMock())
        with patch("companion_compat.asyncio.sleep", new_callable=AsyncMock, side_effect=asyncio.CancelledError):
            with self.assertRaises(asyncio.CancelledError):
                await AndroidCompanionRemoteControl(api).select(InputAction.Hold)
        self.assertEqual([call.args for call in api.hid_command.await_args_list], [(True, HidCommand.Select), (False, HidCommand.Select)])

    async def test_unknown_action_does_not_send_hid(self):
        api = SimpleNamespace(hid_command=AsyncMock())
        with self.assertRaises(NotImplementedError):
            await AndroidCompanionRemoteControl(api)._press_button(HidCommand.Home, object())
        api.hid_command.assert_not_awaited()


if __name__ == "__main__":
    unittest.main()
