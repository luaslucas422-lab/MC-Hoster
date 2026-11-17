"""MC Hoster Premium: EUR 5 once, for life. Optional: everything MC Hoster had stays free.

The Premium features live in a private repository and are not part of this code. With a valid key, MC Hoster downloads
them from the payment service, checks they are signed by DarkerDawnFR, and loads them.
"""
from __future__ import annotations

import base64
import hashlib
import json
import sys
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

from . import core, profile

PAY_URL = "https://mc-hoster-pay.onrender.com"
PUBLIC_KEY = "o1hV+pCDv6jnH9xarOqFeQuy/DLtZuwt5V6h4RZvHww="  # only checks signatures; it can't make keys
PRICE = "€5"
NAME = "Premium"

FREE_MAX_PLAYERS = core.MAX_PLAYERS  # 1500, same as always
FREE_BACKUPS = 5

# what the Premium window lists: (feature, free, premium)
COMPARE = [
    ("Max players", f"up to {core.MAX_PLAYERS}", "up to 3000"),
    ("World backups kept", str(FREE_BACKUPS), "20"),
    ("Automatic backups", "every day", "every 6 hours"),
    ("A copy of every backup in a second folder (USB stick, OneDrive…)", "–", "✔"),
    ("Discord alerts: server online, offline, crashed, players joining", "–", "✔"),
    ("Player history: who played, when and for how long", "–", "✔"),
    ("Themes", "Classic, Modern", "+ Midnight, Ocean"),
    ("Your own background picture", "–", "✔"),
    ("Everything MC Hoster already has", "✔", "✔"),
]


def _unb64(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def _pub():
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
    return Ed25519PublicKey.from_public_bytes(base64.b64decode(PUBLIC_KEY))


def check_key(key: str) -> bool:
    key = (key or "").strip()
    if not key.startswith("MCH1-") or "." not in key:
        return False
    try:
        payload, sig = key[5:].split(".", 1)
        _pub().verify(_unb64(sig), _unb64(payload))
        return json.loads(_unb64(payload)).get("v") == 1
    except Exception:
        return False


_cache = None


def is_paid() -> bool:
    global _cache
    if _cache is None:
        _cache = check_key(profile.load().get("license", ""))
    return _cache


def activate(key: str) -> bool:
    global _cache
    if not check_key(key):
        return False
    profile.save(license=key.strip())
    _cache = True
    return True


# --------------------------------------------------------------------------- the Premium module (downloaded)

def _bundle() -> Path:
    return core.app_dir() / "premium" / "premium.zip"


_module = None


def _verify_bundle(data: bytes, sig_b64: str) -> bool:
    try:
        _pub().verify(base64.b64decode(sig_b64), hashlib.sha256(data).digest())
        return True
    except Exception:
        return False


def download_premium(log=print) -> bool:
    """Gets the newest Premium files (needs a valid key). True when a new one was saved."""
    if not is_paid():
        return False
    key = profile.load().get("license", "")
    url = f"{PAY_URL}/api/premium?v={core.VERSION}"
    req = urllib.request.Request(url, headers={"User-Agent": core.UA, "X-License": key})
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            data, sig = r.read(), r.headers.get("X-Signature", "")
    except Exception as e:
        log(f"Couldn't download Premium: {e}")
        return False
    if not _verify_bundle(data, sig):
        log("The Premium download wasn't signed correctly, so it wasn't used.")
        return False
    path = _bundle()
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists() and path.read_bytes() == data:
        return False
    tmp = path.with_suffix(".part")
    tmp.write_bytes(data)
    (path.parent / "premium.sig").write_text(sig)
    tmp.replace(path)
    return True


def premium():
    """The Premium module, or None (free, or not downloaded yet)."""
    global _module
    if _module is not None or not is_paid():
        return _module
    path = _bundle()
    sig = path.parent / "premium.sig"
    if not path.exists() or not sig.exists():
        return None
    data = path.read_bytes()
    if not _verify_bundle(data, sig.read_text().strip()):
        return None
    try:
        sys.path.insert(0, str(path))
        import importlib
        _module = importlib.import_module("mchoster_premium")
    except Exception:
        _module = None
    return _module


def update_in_background():
    """Checks for newer Premium files once per start; they are used the next time MC Hoster opens."""
    if is_paid():
        threading.Thread(target=lambda: download_premium(lambda *_: None), daemon=True).start()


def max_players() -> int:
    m = premium()
    return getattr(m, "MAX_PLAYERS", FREE_MAX_PLAYERS) if m else FREE_MAX_PLAYERS


def backups_kept() -> int:
    m = premium()
    return getattr(m, "BACKUPS_KEPT", FREE_BACKUPS) if m else FREE_BACKUPS


def backup_every_hours() -> int:
    m = premium()
    return getattr(m, "BACKUP_EVERY_HOURS", 24) if m else 24


# --------------------------------------------------------------------------- buying

def _api(path: str, method: str = "GET", timeout: float = 30) -> dict:
    req = urllib.request.Request(PAY_URL + path, method=method, data=b"" if method == "POST" else None,
                                 headers={"User-Agent": core.UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read().decode())


def start_checkout() -> dict:
    """{"url", "session"}; raises with a readable message if payments aren't on."""
    try:
        return _api("/api/checkout", "POST", timeout=70)  # the free service may need ~1 min to wake up
    except urllib.error.HTTPError as e:
        try:
            raise RuntimeError(json.loads(e.read().decode()).get("error") or f"The payment service said {e.code}.")
        except ValueError:
            raise RuntimeError(f"The payment service said {e.code}.")
    except Exception as e:
        raise RuntimeError(f"Couldn't reach the payment service ({e}).")


def wait_for_key(session: str, minutes: int = 20) -> str | None:
    end = time.time() + minutes * 60
    while time.time() < end:
        time.sleep(4)
        try:
            d = _api("/api/license?session=" + session)
        except Exception:
            continue
        if d.get("key"):
            return d["key"]
    return None
