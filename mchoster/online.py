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


def send_feedback(text: str, kind: str = "idea") -> None:
    _call("POST", "/feedback", {"text": text[:2000], "kind": kind})


def inbox(key: str) -> list[dict]:
    return _call("GET", "/feedback", key=key).get("items", [])


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
