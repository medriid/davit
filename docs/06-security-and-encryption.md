# 06 · Security & encryption

## Threat model
This is a personal, local tool. The realistic risks are:
1. **Credentials written to disk in the clear** — avoided by default (nothing is
   stored) and, when you opt to save, by strong encryption.
2. **Credentials leaking off the machine** — avoided: daVIT talks only to
   `vtop.vit.ac.in`, binds to `127.0.0.1`, and has no analytics/telemetry.
3. **Someone reading the vault file** — mitigated by AES-256-GCM behind a master
   passphrase that is never stored.

Not in scope: a compromised OS / keylogger / someone who already has your master
passphrase.

## How daVIT does it (web build)
Desktops don't have a single clean "secure store" like a phone, so daVIT uses an
**encrypted vault file** (`web/crypto_vault.py` → `web/vault.enc`):

- **Cipher:** AES-256-GCM (authenticated encryption — detects tampering).
- **Key derivation:** `scrypt` (memory-hard) from your **master passphrase** +
  a random 16-byte salt. Params: `n=2^15, r=8, p=1`, 32-byte key.
- **Per-save randomness:** fresh salt + fresh 12-byte nonce every save.
- **On disk (`vault.enc`):** only `{ salt, nonce, ciphertext }` (all hex). The
  plaintext VTOP password is **never** written — verified in a test (the string
  does not appear in the file).
- **Master passphrase:** never stored anywhere. Forget it → vault is
  unrecoverable by design. Wrong passphrase and a tampered file both fail the
  same way (GCM auth failure), so the error doesn't leak which.

### API (`crypto_vault.py`)
```python
save_credentials(master, username, password)   # encrypt + write vault.enc
load_credentials(master) -> {username, password}  # or raise VaultError
vault_exists() -> bool
delete_vault() -> bool
```

### In the app
- `POST /api/vault/save`  `{master, username, password}` — encrypt & store.
- `POST /api/vault/unlock` `{master}` — returns only the **username** (never the
  password) so the UI can confirm the right vault.
- `POST /api/attendance` `{useVault:true, master}` — server decrypts in memory,
  logs in, discards the plaintext after the request.
- `POST /api/vault/delete` — remove the vault.

The UI exposes this as an optional "Save this login (encrypted)" section and an
"Unlock saved login" card.

## Operational notes
- `vault.enc` is per-machine and holds only your encrypted credentials. Don't
  commit it. (Add it to `.gitignore` when this becomes a repo.)
- Flask runs with `debug=True` for local dev — fine on `127.0.0.1`, but never
  expose this server to a network or the internet as-is.
- If you later want the phone-grade experience on desktop, swap the vault for the
  OS keyring (`keyring` → Windows Credential Manager); the `crypto_vault` API is
  small enough to reimplement behind the same functions.

## On Android
The app keeps your password, the VTOP session and the cached data sealed with
AES-256-GCM under a key generated inside the **Android Keystore**, which never
leaves it (`android/app/.../data/Vault.kt`). There's no biometric requirement on
the key, so background sync can still sign in. Signing out logs out of VTOP and
deletes the key, the credentials, the session and the cache.
