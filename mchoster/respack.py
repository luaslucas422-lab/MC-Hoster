"""Server resource packs: find one on Modrinth or CurseForge (or any link), and get its SHA-1 so Minecraft can check it.

Minecraft downloads the pack itself from the link in server.properties, so it has to be a direct download link.
"""
from __future__ import annotations

import hashlib
import json
import re
import tempfile
import urllib.parse
from pathlib import Path

from . import core

MODRINTH = "https://api.modrinth.com/v2"


def search_modrinth(query: str, limit: int = 20) -> list[dict]:
    """[{id, title, desc, downloads, source}] resource packs on Modrinth."""
    facets = json.dumps([["project_type:resourcepack"]])
    url = f"{MODRINTH}/search?query={urllib.parse.quote(query)}&facets={urllib.parse.quote(facets)}&limit={limit}"
    hits = core.http_json(url, 20).get("hits", [])
    return [{"id": h["project_id"], "slug": h.get("slug", ""), "title": h.get("title", ""), "desc": h.get("description", ""),
             "downloads": h.get("downloads", 0), "source": "Modrinth"} for h in hits]


def modrinth_pack(project: str, mc_version: str = "") -> dict:
    """{url, sha1, name, version, source} of the newest file (for this Minecraft version if there is one)."""
    versions = []
    if mc_version:
        try:
            versions = core.http_json(f"{MODRINTH}/project/{project}/version?game_versions="
                                      + urllib.parse.quote(json.dumps([mc_version])), 20)
        except Exception:
            versions = []
    if not versions:
        versions = core.http_json(f"{MODRINTH}/project/{project}/version", 20)
    for v in versions:
        files = v.get("files", [])
        f = next((x for x in files if x.get("primary")), files[0] if files else None)
        if f:
            proj = core.http_json(f"{MODRINTH}/project/{project}", 20)
            return {"url": f["url"], "sha1": f.get("hashes", {}).get("sha1", ""), "name": proj.get("title", project),
                    "version": v.get("version_number", ""), "source": "Modrinth", "page": f"https://modrinth.com/resourcepack/{proj.get('slug', project)}"}
    raise LookupError("that pack has no files")


CF_LINK = re.compile(r"curseforge\.com/minecraft/(texture-packs|resource-packs|customization)/([A-Za-z0-9_\-]+)")


def curseforge_pack(link: str) -> dict:
    """A CurseForge pack from its page link (through CFWidget, which doesn't need a CurseForge key)."""
    m = CF_LINK.search(link)
    if not m:
        raise LookupError("paste the pack's CurseForge page, like https://www.curseforge.com/minecraft/texture-packs/<name>")
    d = core.http_json(f"https://api.cfwidget.com/minecraft/{m.group(1)}/{m.group(2)}", 30)
    f = d.get("download") or (d.get("files") or [None])[0]
    if not f:
        raise LookupError("that pack has no files")
    fid, name = int(f["id"]), f["name"]
    url = f"https://mediafilez.forgecdn.net/files/{fid // 1000}/{fid % 1000}/{urllib.parse.quote(name)}"
    return {"url": url, "sha1": "", "name": d.get("title", m.group(2)), "version": f.get("display", ""), "source": "CurseForge",
            "page": f"https://www.curseforge.com/minecraft/{m.group(1)}/{m.group(2)}"}


def sha1_of(url: str, progress=None) -> str:
    """Downloads the pack once to get its SHA-1 (Minecraft checks it, and re-downloads only when it changes)."""
    d = Path(tempfile.mkdtemp(prefix="mchoster-pack-"))
    f = core.download(url, d / "pack.zip", progress)
    h = hashlib.sha1(f.read_bytes()).hexdigest()
    try:
        if not f.read_bytes()[:2] == b"PK":
            raise LookupError("the link doesn't give a .zip file (it needs to be a direct download link)")
    finally:
        f.unlink(missing_ok=True)
    return h


def describe(rp: dict) -> str:
    if not rp or not rp.get("url"):
        return "None"
    return f"{rp.get('name') or 'Custom pack'}" + (f" ({rp.get('source')})" if rp.get("source") else "") + (" · required" if rp.get("required") else " · optional")
