"""Run pyatv 0.14.5's official Companion fixture for Android integration tests.

Download tests/fake_device/companion.py from the v0.14.5 tag in postlund/pyatv,
then pass its path with --fixture. Test-only corrections below address the
fixture's incorrectly scheduled volume callback and stale RTI session after a
client process exits. Production protocol code is not patched.
"""

import argparse
import asyncio
import importlib.util
import json
import logging
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "app/src/main/python"))

from aiohttp import web
from pyatv.auth.server_auth import PIN_CODE
from pyatv.protocols.companion import MediaControlCommand


async def serve(fixture):
    spec = importlib.util.spec_from_file_location("pyatv_fake_companion", fixture)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    original = module.FakeCompanionService
    events = []

    class TestCompanionService(original):
        def connection_lost(self, error):
            if self in self.state.rti_clients:
                self.state.rti_clients.remove(self)
                self.state.rti_session_uuid = None
            super().connection_lost(error)

        def handle__hidc(self, message):
            events.append({"kind": "hid", **message["_c"]})
            super().handle__hidc(message)

        def handle__mcc(self, message):
            if message["_c"]["_mcc"] == MediaControlCommand.SetVolume.value:
                self.send_response(message, {})
                self.loop.call_soon(self.volume_changed, message["_c"]["_vol"] * 100)
                events.append({"kind": "volume", "level": message["_c"]["_vol"] * 100})
            else:
                super().handle__mcc(message)

    module.FakeCompanionService = TestCompanionService
    state = module.FakeCompanionState()
    factory = module.FakeCompanionServiceFactory(state, None, asyncio.get_running_loop())
    await factory.start(False)
    app = web.Application()

    async def status(request):
        return web.json_response({
            "paired": state.has_paired, "button": state.latest_button,
            "power": state.powered_on, "volume": state.volume, "text": state.rti_text,
            "clients": len(state.clients), "events": events,
        })

    app.router.add_get("/status", status)
    runner = web.AppRunner(app)
    await runner.setup()
    await web.TCPSite(runner, "127.0.0.1", 8872).start()
    print(json.dumps({"port": factory.port, "pin": str(PIN_CODE), "status": "http://127.0.0.1:8872/status"}), flush=True)
    try:
        await asyncio.Event().wait()
    finally:
        await factory.cleanup()
        await runner.cleanup()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--fixture", required=True, type=Path)
    args = parser.parse_args()
    logging.basicConfig(level=logging.WARNING)
    asyncio.run(serve(args.fixture))
