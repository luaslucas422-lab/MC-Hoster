"""MC Hoster Paid: EUR 5, once, for life. A key from the payment service (pay/) unlocks it; it is checked offline."""
from __future__ import annotations

import base64
import json
import time
import urllib.error
import urllib.request

from . import core, profile

PAY_URL = "https://mc-hoster-pay.onrender.com"
PUBLIC_KEY = "o1hV+pCDv6jnH9xarOqFeQuy/DLtZuwt5V6h4RZvHww="
PRICE = "€5"

FREE_MAX_PLAYERS = 100
FREE_BACKUPS, PAID_BACKUPS = 5, 20

# what the Paid version converter window lists: (feature, free, paid)
COMPARE = [
    ("Max players", f"up to {FREE_MAX_PLAYERS}", f"up to {core.MAX_PLAYERS}"),
    ("World backups kept", str(FREE_BACKUPS), str(PAID_BACKUPS)),
    ("Automatic backups", "every day", "every 6 hours"),
    ("Themes", "Classic, Modern", "Classic, Modern, Midnight, Ocean"),
    ("Your own background picture", "–", "✔"),
    ("Everything else (all server types, Gh0st AC, plugins, Server Recovery…)", "✔", "✔"),
]


def _unb64(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


def check_key(key: str) -> bool:
    key = (key or "").strip()
    if not key.startswith("MCH1-") or "." not in key:
        return False
    try:
        from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey
        payload, sig = key[5:].split(".", 1)
        Ed25519PublicKey.from_public_bytes(base64.b64decode(PUBLIC_KEY)).verify(_unb64(sig), _unb64(payload))
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


def max_players() -> int:
    return core.MAX_PLAYERS if is_paid() else FREE_MAX_PLAYERS


def backups_kept() -> int:
    return PAID_BACKUPS if is_paid() else FREE_BACKUPS


def backup_every_hours() -> int:
    return 6 if is_paid() else 24


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
