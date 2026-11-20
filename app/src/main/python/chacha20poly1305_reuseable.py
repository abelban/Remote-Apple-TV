"""Android compatibility adapter for pyatv's optional AEAD optimisation.

The older optional package subclasses OpenSSL implementation details which are
not available in cryptography 42's Rust backend. Use the public, authenticated
ChaCha20-Poly1305 implementation directly. The API and wire format are identical;
only the optional reuse of an internal context is omitted. No cipher code is
implemented or modified here.
"""

from cryptography.hazmat.primitives.ciphers.aead import (
    ChaCha20Poly1305 as ChaCha20Poly1305Reusable,
)

__all__ = ["ChaCha20Poly1305Reusable"]
