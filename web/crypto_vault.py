"""
Encrypted credential vault for daVIT.

Credentials encrypted with AES-256 and stored only on this machine:

  * Credentials are encrypted at rest with AES-256-GCM.
  * The key is derived from a master passphrase you choose, via scrypt
    (memory-hard KDF) with a random per-vault salt.
  * The plaintext VTOP password is never written to disk — only the AES-GCM
    ciphertext, the salt and the nonce are stored (in vault.enc).
  * GCM is authenticated, so tampering with the file is detected on unlock.

The master passphrase is never stored anywhere. If you forget it, the vault is
unrecoverable by design (that's the point).

For a future native app, the equivalent is the OS secure store the original uses
(Android Keystore / iOS Keychain) — see docs/06-security-and-encryption.md.
"""
from __future__ import annotations

import json
import os
import secrets

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

_VAULT_PATH = os.path.join(os.path.dirname(__file__), "vault.enc")

# scrypt parameters. n=2**15 is a sensible interactive default.
_SCRYPT_N = 2 ** 15
_SCRYPT_R = 8
_SCRYPT_P = 1
_KEY_LEN = 32          # AES-256
_SALT_LEN = 16
_NONCE_LEN = 12        # AES-GCM standard nonce


class VaultError(Exception):
    pass


def _derive_key(passphrase: str, salt: bytes) -> bytes:
    kdf = Scrypt(salt=salt, length=_KEY_LEN, n=_SCRYPT_N, r=_SCRYPT_R, p=_SCRYPT_P)
    return kdf.derive(passphrase.encode("utf-8"))


def vault_exists(path: str = _VAULT_PATH) -> bool:
    return os.path.exists(path)


def save_credentials(master_passphrase: str, username: str, password: str,
                     path: str = _VAULT_PATH) -> None:
    """Encrypt {username, password} under the master passphrase and write it."""
    if not master_passphrase:
        raise VaultError("A master passphrase is required.")
    salt = secrets.token_bytes(_SALT_LEN)
    nonce = secrets.token_bytes(_NONCE_LEN)
    key = _derive_key(master_passphrase, salt)
    plaintext = json.dumps({"username": username, "password": password}).encode("utf-8")
    ciphertext = AESGCM(key).encrypt(nonce, plaintext, None)
    blob = {
        "v": 1,
        "kdf": {"name": "scrypt", "n": _SCRYPT_N, "r": _SCRYPT_R, "p": _SCRYPT_P},
        "salt": salt.hex(),
        "nonce": nonce.hex(),
        "ct": ciphertext.hex(),
    }
    tmp = path + ".tmp"
    with open(tmp, "w") as f:
        json.dump(blob, f)
    os.replace(tmp, path)


def load_credentials(master_passphrase: str, path: str = _VAULT_PATH) -> dict:
    """Return {'username', 'password'} or raise VaultError (wrong passphrase,
    tampered file, or no vault)."""
    if not vault_exists(path):
        raise VaultError("No saved credentials. Save them first.")
    with open(path) as f:
        blob = json.load(f)
    try:
        salt = bytes.fromhex(blob["salt"])
        nonce = bytes.fromhex(blob["nonce"])
        ct = bytes.fromhex(blob["ct"])
        key = _derive_key(master_passphrase, salt)
        plaintext = AESGCM(key).decrypt(nonce, ct, None)
    except Exception:
        # Wrong passphrase or tampered ciphertext both land here (GCM auth fail).
        raise VaultError("Wrong master passphrase, or the vault file is corrupt.")
    return json.loads(plaintext.decode("utf-8"))


def delete_vault(path: str = _VAULT_PATH) -> bool:
    if vault_exists(path):
        os.remove(path)
        return True
    return False
