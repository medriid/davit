"""
daVIT — local VTOP attendance viewer (web build).

Run:  py app.py     then open  http://127.0.0.1:5000

You type your VTOP username + password in the browser; this server logs in to
VTOP for you (auto-solving the captcha) and shows your attendance table.
Credentials are never stored unless you explicitly save them to the encrypted
vault (AES-256-GCM, master-passphrase protected — see crypto_vault.py).
"""
from flask import Flask, render_template, request, jsonify

from vtop_client import (VtopClient, AuthError, CaptchaError, RecaptchaError, VtopError)
import crypto_vault as vault

app = Flask(__name__)


@app.route("/")
def index():
    return render_template("index.html")


@app.post("/api/attendance")
def api_attendance():
    data = request.get_json(silent=True) or {}
    username = (data.get("username") or "").strip()
    password = data.get("password") or ""
    semester = (data.get("semester") or "").strip() or None

    # Allow fetching straight from the encrypted vault without re-typing.
    if data.get("useVault"):
        try:
            creds = vault.load_credentials(data.get("master") or "")
            username, password = creds["username"], creds["password"]
        except vault.VaultError as e:
            return jsonify(ok=False, error=str(e)), 401

    if not username or not password:
        return jsonify(ok=False, error="Enter both username and password."), 400

    client = VtopClient()
    try:
        client.login(username, password)
        result = client.get_attendance(semester)
        return jsonify(ok=True, **result)
    except AuthError as e:
        return jsonify(ok=False, error=str(e)), 401
    except RecaptchaError as e:
        return jsonify(ok=False, error=str(e)), 503
    except (CaptchaError, VtopError) as e:
        return jsonify(ok=False, error=str(e)), 502
    except Exception as e:  # noqa
        return jsonify(ok=False, error=f"Unexpected error: {e}"), 500
    finally:
        client.logout()


# ---- Encrypted credential vault -------------------------------------------
@app.get("/api/vault/status")
def vault_status():
    return jsonify(exists=vault.vault_exists())


@app.post("/api/vault/save")
def vault_save():
    data = request.get_json(silent=True) or {}
    master = data.get("master") or ""
    username = (data.get("username") or "").strip()
    password = data.get("password") or ""
    if not (master and username and password):
        return jsonify(ok=False, error="Need username, password and a master passphrase."), 400
    try:
        vault.save_credentials(master, username, password)
        return jsonify(ok=True)
    except vault.VaultError as e:
        return jsonify(ok=False, error=str(e)), 400


@app.post("/api/vault/unlock")
def vault_unlock():
    data = request.get_json(silent=True) or {}
    try:
        creds = vault.load_credentials(data.get("master") or "")
        # Return only the username to the UI; never echo the password back.
        return jsonify(ok=True, username=creds["username"])
    except vault.VaultError as e:
        return jsonify(ok=False, error=str(e)), 401


@app.post("/api/vault/delete")
def vault_delete():
    return jsonify(ok=True, deleted=vault.delete_vault())


if __name__ == "__main__":
    app.run(host="127.0.0.1", port=5000, debug=True)
