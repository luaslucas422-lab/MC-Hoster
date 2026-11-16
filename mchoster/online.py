"""The online side: feedback, Server sharing and Hosted servers all talk to one small web service (see server/).

If ONLINE_URL is empty the service isn't set up yet and every call says so instead of failing.
"""
from __future__ import annotations

import json
import urllib.error
import urllib.request

from . import core, profile

ONLINE_URL = ""  # e.g. "https://mc-hoster.onrender.com", filled in once the service is running


class Offline(Exception):
    pass


def configured() -> bool:
    return bool(ONLINE_URL)


def _call(method: str, path: str, body: dict | None = None, key: str = "", timeout: float = 20):
    if not ONLINE_URL:
        raise Offline("The online part of MC Hoster isn't switched on yet.")
    p = profile.load()
    h = {"User-Agent": core.UA, "Content-Type": "application/json", "X-Name": p.get("name", "")[:24],
         "X-Mc": p.get("mc", "")[:16], "X-Version": core.VERSION}
    if key:
        h["X-Admin-Key"] = key
    req = urllib.request.Request(ONLINE_URL.rstrip("/") + path, method=method, headers=h,
                                 data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode("utf-8") or "{}")
    except urllib.error.HTTPError as e:
        try:
            msg = json.loads(e.read().decode()).get("error")
        except Exception:
            msg = None
        raise Offline(msg or f"The service said {e.code}.")
    except Exception as e:
        raise Offline(f"Couldn't reach the MC Hoster service ({e}).")


def send_feedback(text: str, kind: str = "idea", images: list[str] | None = None) -> None:
    """images: up to 3 pictures as base64 JPEG."""
    _call("POST", "/feedback", {"text": text[:6000], "kind": kind, "images": (images or [])[:3]}, timeout=60)


def inbox() -> list[dict]:
    """All feedback that was sent (everyone can read it, there is no key)."""
    return _call("GET", "/feedback", timeout=40).get("items", [])


def posts() -> list[dict]:
    return _call("GET", "/posts").get("items", [])


def add_post(title: str, body: str, server: str = "") -> None:
    _call("POST", "/posts", {"title": title[:80], "body": body[:2000], "server": server[:60]})


def hosted() -> list[dict]:
    return _call("GET", "/hosted").get("items", [])


def publish_hosted(name: str, kind: str, version: str, players: int, icon_b64: str = "") -> None:
    _call("POST", "/hosted", {"name": name[:40], "kind": kind[:30], "version": version[:20],
                              "players": int(players), "icon": icon_b64})


def unpublish_hosted(name: str) -> None:
    _call("DELETE", "/hosted", {"name": name[:40]})


def encode_photo(path: str) -> str:
    """A picture as a small base64 JPEG (at most 1024 px wide, so it is quick to send)."""
    import base64
    import io

    from PIL import Image
    im = Image.open(path).convert("RGB")
    im.thumbnail((1024, 1024))
    buf = io.BytesIO()
    im.save(buf, "JPEG", quality=80)
    return base64.b64encode(buf.getvalue()).decode()


def thumb_png(b64: str) -> str:
    """A small base64 PNG of a sent picture, for the window (it can't show JPEG itself)."""
    import base64
    import io

    from PIL import Image
    im = Image.open(io.BytesIO(base64.b64decode(b64))).convert("RGB")
    im.thumbnail((300, 300))
    buf = io.BytesIO()
    im.save(buf, "PNG")
    return base64.b64encode(buf.getvalue()).decode()
