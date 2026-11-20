"""Backport Companion double-tap and hold actions to the Android-compatible release.

pyatv 0.14.5 exposes these actions in its interface but implements only SingleTap
for Companion. Use the same HID down/up sequence as newer upstream releases.
Keep this adapter local to our pinned transport; authentication is unchanged.
"""

import asyncio

from pyatv.const import InputAction
from pyatv.protocols import companion


class AndroidCompanionRemoteControl(companion.CompanionRemoteControl):
    async def _press_button(self, command, action=InputAction.SingleTap):
        if action not in (InputAction.SingleTap, InputAction.DoubleTap, InputAction.Hold):
            raise NotImplementedError("Unknown input action")
        for _ in range(2 if action == InputAction.DoubleTap else 1):
            await self.api.hid_command(True, command)
            try:
                if action == InputAction.Hold:
                    await asyncio.sleep(1)
            finally:
                await self.api.hid_command(False, command)


def install():
    companion.CompanionRemoteControl = AndroidCompanionRemoteControl
