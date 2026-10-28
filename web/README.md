# daVIT · web build

Local VTOP companion: logs in for you (auto-solving the captcha) and shows your
attendance. Part of daVIT; see `../docs/` for the VTOP API reference, the
captcha solver and the security model. Needs the captcha weights in
`model/captcha_model.json` (not in the repo; see `../docs/05-captcha-solver.md`).

## Run
```bash
py -m pip install -r requirements.txt
py app.py
```
Open http://127.0.0.1:5000 and enter your VTOP username + password.

## Files
- `app.py` — Flask server (`/`, `/api/attendance`, `/api/vault/*`).
- `vtop_client.py` — VTOP login flow + attendance scrape.
- `captcha_solver.py` — the on-device captcha model, in numpy (`model/captcha_model.json`).
- `crypto_vault.py` — optional AES-256-GCM credential vault (`vault.enc`).
- `templates/index.html` — the UI.

## Credentials
Not stored unless you opt in. The "Save this login (encrypted)" option writes an
AES-256-GCM vault (`vault.enc`) unlocked only by a master passphrase you choose —
details in `../docs/06-security-and-encryption.md`. Nothing ever leaves your
machine except the login to `vtop.vit.ac.in`.

## Extending
Each extra data type (marks, timetable, exams…) is one method on `VtopClient`
plus a route and a render function. All endpoints + parsing are in
`../docs/04-vtop-api-reference.md`. Stay read-only.
