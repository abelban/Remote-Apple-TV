"""Build the upstream zeroconf distribution without its optional Cython extensions.

Run with a Python 3.11 environment containing pip and wheel. The package's Python
sources are unchanged. Upstream's poetry builder still tags this pure build as
platform-specific, so verify that there are no binaries, then correct WHEEL and
let wheel pack regenerate RECORD.
"""

import os
from pathlib import Path
import subprocess
import sys
import tempfile

root = Path(__file__).resolve().parents[1]
output = root / "app" / "wheels"
output.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix="remote-zeroconf-") as temporary:
    work = Path(temporary)
    env = {**os.environ, "SKIP_CYTHON": "1"}
    subprocess.run([
        sys.executable, "-m", "pip", "wheel", "--no-deps", "--no-binary", "zeroconf",
        "--no-cache-dir", "--wheel-dir", str(work), "zeroconf==0.131.0",
    ], env=env, check=True)
    wheel = next(work.glob("*.whl"))
    subprocess.run([sys.executable, "-m", "wheel", "unpack", str(wheel), "--dest", str(work)], check=True)
    unpacked = work / "zeroconf-0.131.0"
    binaries = [p for p in unpacked.rglob("*") if p.suffix in {".so", ".dylib", ".pyd", ".dll"}]
    if binaries:
        raise RuntimeError("Expected a pure-Python build")
    metadata = unpacked / "zeroconf-0.131.0.dist-info" / "WHEEL"
    metadata.write_text("Wheel-Version: 1.0\nGenerator: upstream-poetry-pure-python\nRoot-Is-Purelib: true\nTag: py3-none-any\n")
    subprocess.run([sys.executable, "-m", "wheel", "pack", str(unpacked), "--dest-dir", str(output)], check=True)
    print("Built", output / "zeroconf-0.131.0-py3-none-any.whl")
