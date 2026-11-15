"""Custom IP: let friends join with your own address (play.yourdomain.com) instead of numbers.

Home internet IPs change now and then, so MC Hoster sends your current IP to the domain service
every time the server starts (Dynamic DNS):

- Namecheap: Domain List → Manage → Advanced DNS → turn on Dynamic DNS and copy the password.
  Then add an "A + Dynamic DNS Record" with the host you want (e.g. play, or @ for the bare domain).
- DuckDNS: free name.duckdns.org address; uses the token from duckdns.org.
- Other: any domain where you set the A record yourself; MC Hoster only shows what to put.

A dict like {"enabled": True, "service": "Namecheap", "host": "play", "domain": "example.com", "password": "..."}.
"""
from __future__ import annotations

import re
import socket
import urllib.parse

from . import core

SERVICES = ["Namecheap", "DuckDNS", "Other"]


def address(d: dict) -> str:
    if not d:
        return ""
    s = d.get("service")
    if s == "DuckDNS":
        name = d.get("host", "").strip().lower().removesuffix(".duckdns.org")
        return f"{name}.duckdns.org" if name else ""
    if s == "Namecheap":
        host, dom = d.get("host", "").strip().lower(), d.get("domain", "").strip().lower()
        if not dom:
            return ""
        return dom if host in ("", "@") else f"{host}.{dom}"
    return d.get("domain", "").strip().lower()


def validate(d: dict) -> str | None:
    if not d or not d.get("enabled"):
        return None
    a = address(d)
    if not a or not re.fullmatch(r"[a-z0-9]([a-z0-9-]*[a-z0-9])?(\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+", a):
        return "That address doesn't look right (like play.example.com)."
    s = d.get("service")
    if s == "Namecheap" and not d.get("password", "").strip():
        return "Paste the Dynamic DNS password from Namecheap."
    if s == "DuckDNS" and not d.get("password", "").strip():
        return "Paste your DuckDNS token."
    return None


def describe(d: dict) -> str:
    if not d or not d.get("enabled"):
        return "Off (friends use your IP)"
    return f"{address(d)} ({d.get('service')})"


def update(d: dict, ip: str) -> tuple[bool, str]:
    """Points the address at ip. (ok, message)"""
    s = d.get("service")
    try:
        if s == "Namecheap":
            host = d.get("host", "").strip() or "@"
            q = urllib.parse.urlencode({"host": host, "domain": d["domain"].strip(), "password": d["password"].strip(), "ip": ip})
            r = core.http_get("https://dynamicdns.park-your-domain.com/update?" + q, 15).decode("utf-8", "replace")
            if re.search(r"<ErrCount>\s*0\s*</ErrCount>", r):
                return True, f"Namecheap now points {address(d)} at {ip}."
            err = re.search(r"<Err1>(.*?)</Err1>", r, re.S)
            return False, "Namecheap said: " + (err.group(1).strip() if err else "unknown error")
        if s == "DuckDNS":
            name = d.get("host", "").strip().lower().removesuffix(".duckdns.org")
            q = urllib.parse.urlencode({"domains": name, "token": d["password"].strip(), "ip": ip})
            r = core.http_get("https://www.duckdns.org/update?" + q, 15).decode().strip()
            return (True, f"DuckDNS now points {address(d)} at {ip}.") if r.startswith("OK") else \
                (False, "DuckDNS said no. Check the name and token.")
        return True, f"Set an A record for {address(d)} to {ip} at your domain provider."
    except Exception as e:
        return False, f"Couldn't reach {s} ({e.__class__.__name__})."


def resolves_to(d: dict) -> str | None:
    try:
        return socket.gethostbyname(address(d))
    except Exception:
        return None


def join_address(d: dict, cfg: core.ServerConfig) -> str:
    """What friends type. Bedrock types the port in its own box."""
    a = address(d)
    return a if cfg.prov.bedrock else core.server_address(cfg, a)
