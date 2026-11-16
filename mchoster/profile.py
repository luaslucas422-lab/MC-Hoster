"""Who you are in MC Hoster: the terms you agreed to, the name other people see, and your Minecraft name."""
from __future__ import annotations

import json

from . import core

TERMS = """MC Hoster - Terms and Conditions

Please read this before you use MC Hoster.

1. What MC Hoster is. MC Hoster helps you run a Minecraft server from your own PC. It is a free hobby program made by DarkerDawnFR. It is not made by, or connected to, Mojang, Microsoft or Minecraft.

2. Your server, your responsibility. You run your server on your own computer and internet. You are responsible for everything that happens on it, for who you let join, and for following the Minecraft EULA and the law where you live. Opening a server to the internet has risks, such as strangers finding your IP address.

3. Plugins and mods. Some plugins and mods are made by other people and can be harmful. Only use official sites and people you trust. MC Hoster is not responsible for what they do.

4. Backups. Servers can break, worlds can get damaged and PCs can fail. Keep your own backups of worlds you care about.

5. Online parts. Feedback, Server sharing and Hosted servers send what you type (your in-app name, your Minecraft name, your message, and for a hosted server its name and icon) to the MC Hoster online service. Do not share anything private or anything against the law. Posts can be removed at any time.

6. No warranty. MC Hoster is provided "as is", with no promises that it works, is safe, or is free of bugs. To the most the law allows, DarkerDawnFR is not responsible for any loss, damage, lost worlds, banned accounts, slow PCs or anything else that comes from using MC Hoster.

7. Changes. These terms can change in a new version. If you keep using MC Hoster you agree to the new terms.

By ticking the box you say you have read this and agree."""


def _file():
    return core.app_dir() / "profile.json"


def load() -> dict:
    try:
        d = json.loads(_file().read_text(encoding="utf-8"))
        return d if isinstance(d, dict) else {}
    except Exception:
        return {}


def save(**kw) -> dict:
    d = load()
    d.update(kw)
    try:
        _file().write_text(json.dumps(d, indent=2), encoding="utf-8")
    except OSError:
        pass
    return d


def done(p: dict | None = None) -> bool:
    p = load() if p is None else p
    return bool(p.get("terms") and p.get("name"))


def valid_display_name(n: str) -> bool:
    return 2 <= len(n.strip()) <= 24


def is_owner(p: dict | None = None) -> bool:
    """Lucas, in game DarkerDawnFR: the one who can read the feedback (the online service checks a key too)."""
    p = load() if p is None else p
    return (p.get("name", "").strip().lower() == "lucas" and p.get("mc", "").strip().lower() == "darkerdawnfr")
