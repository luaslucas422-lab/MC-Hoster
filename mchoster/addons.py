"""Tab 5: preset plugins (Paper, Purpur, Pufferfish, Spigot) and mods (Fabric).

Each preset is downloaded from Modrinth (or Hangar, PaperMC's plugin site) for the server's exact
version, with its required dependencies. Removing a preset deletes the files it added.
"""
from __future__ import annotations

import json
import urllib.parse
from dataclasses import dataclass, field
from pathlib import Path

from . import core

MODRINTH = "https://api.modrinth.com/v2"
HANGAR = "https://hangar.papermc.io/api/v1"
FABRIC_API_ID = "P7dR8mSH"

# Modrinth loader names each server type can run, best match first.
LOADERS = {
    "paper": ["paper", "spigot", "bukkit"],
    "purpur": ["purpur", "paper", "spigot", "bukkit"],
    "pufferfish": ["paper", "spigot", "bukkit"],
    "spigot": ["spigot", "bukkit"],
    "fabric": ["fabric"],
}


@dataclass
class Addon:
    id: str
    name: str
    desc: str
    # kind ("bukkit"/"fabric") -> list of files to install; each file is a list of places to try, in order:
    # "mr:<modrinth slug>", "hangar:<hangar slug>", "geysermc:<project>:<platform>" (download.geysermc.org)
    # or "jenkins:<job url>:<file regex>" (newest build on a Jenkins server)
    sources: dict
    ports: list = field(default_factory=list)  # (port, "TCP"/"UDP", what for)
    note: str = ""

    def files_for(self, kind: str) -> list[list[str]]:
        return self.sources.get(kind, [])


ADDONS = [
    Addon("voicechat", "Simple Voice Chat", "Proximity voice chat: hear players near you.",
          {"bukkit": [["mr:simple-voice-chat"]], "fabric": [["mr:simple-voice-chat"]]},
          ports=[(24454, "UDP", "Voice chat")], note="Players need the Simple Voice Chat mod too"),
    Addon("vanish", "Vanish", "/vanish makes staff invisible, also in the player list.",
          {"bukkit": [["mr:supervanish", "hangar:SuperVanish", "mr:sayanvanish", "hangar:SayanVanish", "mr:vanishnopacket"]],
           "fabric": [["mr:vanish"]]}),
    Addon("rosestacker", "RoseStacker", "Stacks mobs, items, blocks and spawners so big farms lag less.",
          {"bukkit": [["mr:rosestacker", "hangar:RoseStacker"]]}),
    Addon("essentials", "EssentialsX", "/home, /spawn, /tpa, /warp, kits, nicknames and more.",
          {"bukkit": [["mr:essentialsx", "hangar:EssentialsX"]]}),
    Addon("luckperms", "LuckPerms", "Ranks and permissions (/lp editor).",
          {"bukkit": [["mr:luckperms"]], "fabric": [["mr:luckperms"]]}),
    Addon("worldedit", "WorldEdit", "Build fast: //wand, //set, //copy, //paste.",
          {"bukkit": [["mr:worldedit"]], "fabric": [["mr:worldedit"]]}),
    Addon("coreprotect", "CoreProtect", "Logs every block change so you can roll back griefing.",
          {"bukkit": [["mr:coreprotect", "hangar:CoreProtect"]]}),
    Addon("ledger", "Ledger", "Logs every block change so you can roll back griefing.",
          {"fabric": [["mr:ledger"]]}),
    Addon("geyser", "Geyser + Floodgate", "Bedrock players (phone, console) can join this Java server.",
          {"bukkit": [["mr:geyser", "geysermc:geyser:spigot"], ["mr:floodgate", "geysermc:floodgate:spigot"]],
           "fabric": [["mr:geyser", "geysermc:geyser:fabric"], ["mr:floodgate", "geysermc:floodgate:fabric"]]},
          ports=[(19132, "UDP", "Bedrock players (Geyser)")], note="Bedrock players join on port 19132"),
    Addon("viaversion", "ViaVersion", "Players on newer Minecraft versions can join too.",
          {"bukkit": [["mr:viaversion", "hangar:ViaVersion"]]}),
    Addon("chunky", "Chunky", "Pre-generates the world (/chunky start) so exploring lags less.",
          {"bukkit": [["mr:chunky"]], "fabric": [["mr:chunky"]]}),
    Addon("spark", "spark", "Finds what is lagging the server (/spark profiler).",
          {"bukkit": [["mr:spark", "hangar:spark", r"jenkins:https://ci.lucko.me/job/spark/:spark-.*-bukkit\.jar"]],
           "fabric": [["mr:spark"]]}),
    Addon("bluemap", "BlueMap", "A 3D map of your world in the browser.",
          {"bukkit": [["mr:bluemap"]], "fabric": [["mr:bluemap"]]},
          ports=[(8100, "TCP", "BlueMap web map")], note="Open http://localhost:8100"),
    Addon("lithium", "Lithium", "Faster server ticks, same gameplay.", {"fabric": [["mr:lithium"]]}),
    Addon("ferritecore", "FerriteCore", "Uses less RAM.", {"fabric": [["mr:ferrite-core"]]}),
]
ADDON_BY_ID = {a.id: a for a in ADDONS}


def addons_for(provider: core.Provider) -> list[Addon]:
    if provider.bedrock:
        return []
    return [a for a in ADDONS if a.files_for(provider.kind)]


def extra_ports(cfg: core.ServerConfig) -> list[tuple[int, str, str]]:
    out = []
    for i in cfg.addons:
        a = ADDON_BY_ID.get(i)
        if a:
            out += a.ports
    return out


# --------------------------------------------------------------------------- finding files

class NotFound(Exception):
    pass


def _q(d: dict) -> str:
    return urllib.parse.urlencode({k: json.dumps(v) for k, v in d.items()})


def _modrinth(project: str, loaders: list[str], version: str, exact: bool):
    """(url, filename, required dependency project ids, note) for the newest fitting version."""
    vs = core.http_json(f"{MODRINTH}/project/{project}/version?{_q({'loaders': loaders, 'game_versions': [version]})}", 20)
    note = ""
    if not vs and not exact:
        # Plugins often keep working on newer versions without saying so.
        vs = core.http_json(f"{MODRINTH}/project/{project}/version?{_q({'loaders': loaders})}", 20)
        if vs:
            note = f" (! not made for {version} yet, it might not load)"
    if not vs:
        raise NotFound(f"no {'/'.join(loaders)} build for {version} on Modrinth")
    v = vs[0]
    f = next((f for f in v["files"] if f.get("primary")), v["files"][0])
    deps = [d["project_id"] for d in v.get("dependencies", [])
            if d.get("dependency_type") == "required" and d.get("project_id")]
    return f["url"], f["filename"], deps, note


def _hangar(slug: str, version: str):
    for q in ({"platform": "PAPER", "platformVersion": version}, {"platform": "PAPER"}):
        j = core.http_json(f"{HANGAR}/projects/{slug}/versions?limit=1&offset=0&" + urllib.parse.urlencode(q), 20)
        res = j.get("result", [])
        if res:
            dl = res[0].get("downloads", {}).get("PAPER", {})
            url = dl.get("downloadUrl")
            if not url:
                raise NotFound("Hangar only links to an outside download")
            name = (dl.get("fileInfo") or {}).get("name") or f"{slug}.jar"
            note = "" if "platformVersion" in q else f" (! not made for {version} yet, it might not load)"
            return url, name, [], note
    raise NotFound(f"no Paper build on Hangar")


def _geysermc(project: str, platform: str):
    url = f"https://download.geysermc.org/v2/projects/{project}/versions/latest/builds/latest/downloads/{platform}"
    j = core.http_json(f"https://download.geysermc.org/v2/projects/{project}/versions/latest/builds/latest", 20)
    name = j.get("downloads", {}).get(platform, {}).get("name") or f"{project}-{platform}.jar"
    return url, name, [], ""


def _jenkins(job: str, pattern: str):
    import re
    base = job.rstrip("/") + "/lastSuccessfulBuild/"
    j = core.http_json(base + "api/json", 20)
    for a in j.get("artifacts", []):
        if re.fullmatch(pattern, a.get("fileName", "")):
            return base + "artifact/" + a["relativePath"], a["fileName"], [], ""
    raise NotFound("no matching file in the newest build")


def resolve(addon: Addon, cfg: core.ServerConfig) -> list[tuple[str, str, list, str, str]]:
    """Every file this preset needs for this server: (url, filename, deps, note, source). Raises NotFound."""
    p = cfg.prov
    loaders = LOADERS[p.id]
    exact = p.kind == "fabric"  # mods break across versions, plugins usually don't
    out = []
    for candidates in addon.files_for(p.kind):
        errors = []
        for c in candidates:
            kind, _, slug = c.partition(":")
            try:
                if kind == "mr":
                    r = _modrinth(slug, loaders, cfg.version, exact)
                elif kind == "geysermc":
                    r = _geysermc(*slug.split(":", 1))
                elif kind == "jenkins":
                    job, _, pattern = slug.rpartition(":")
                    r = _jenkins(job, pattern)
                elif kind == "hangar" and p.kind == "bukkit":
                    r = _hangar(slug, cfg.version)
                else:
                    continue
                out.append(r + (c,))
                break
            except Exception as e:
                errors.append(f"{c}: {e}")
        else:
            raise NotFound("; ".join(errors) or "nothing to try")
    return out


# --------------------------------------------------------------------------- installing

def install(addon: Addon, cfg: core.ServerConfig, log=print) -> list[str]:
    folder = Path(cfg.folder) / cfg.prov.addon_folder
    folder.mkdir(parents=True, exist_ok=True)
    files = []
    pending = []
    for url, name, deps, note, source in resolve(addon, cfg):
        core.download(url, folder / name)
        files.append(name)
        log(f"  + {name}{note}")
        pending += deps
    seen = set()
    loaders = LOADERS[cfg.prov.id]
    while pending:  # required libraries, e.g. Fabric Language Kotlin
        dep = pending.pop()
        if dep in seen or dep == FABRIC_API_ID:
            continue
        seen.add(dep)
        try:
            url, name, deps, note = _modrinth(dep, loaders, cfg.version, cfg.prov.kind == "fabric")
            if not (folder / name).exists():
                core.download(url, folder / name)
            files.append(name)
            log(f"  + {name} (needed by {addon.name})")
            pending += deps
        except Exception as e:
            log(f"  ! could not get a library {addon.name} needs ({e})")
    return files


def sync(cfg: core.ServerConfig, wanted: list[str], log=print) -> list[str]:
    """Installs the ticked presets that are missing and removes the unticked ones. Returns failures."""
    folder = Path(cfg.folder) / cfg.prov.addon_folder
    failed = []
    for aid in [a for a in cfg.addons if a not in wanted]:
        others = {f for k, fs in cfg.addon_files.items() if k != aid and k in wanted for f in fs}
        for name in cfg.addon_files.get(aid, []):
            if name not in others:
                (folder / name).unlink(missing_ok=True)
        cfg.addon_files.pop(aid, None)
        cfg.addons.remove(aid)
        log(f"Removed {ADDON_BY_ID[aid].name if aid in ADDON_BY_ID else aid}")
    version_changed = cfg.addon_files.get("_version") != [cfg.version]
    for aid in wanted:
        a = ADDON_BY_ID.get(aid)
        if not a or not a.files_for(cfg.prov.kind):
            continue
        if aid in cfg.addons and not version_changed and all((folder / f).exists() for f in cfg.addon_files.get(aid, [])):
            continue
        log(f"Installing {a.name}...")
        try:
            old = cfg.addon_files.get(aid, [])
            new = install(a, cfg, log)
            for name in old:  # an older version of the same plugin
                if name not in new:
                    (folder / name).unlink(missing_ok=True)
            cfg.addon_files[aid] = new
            if aid not in cfg.addons:
                cfg.addons.append(aid)
        except Exception as e:
            failed.append(a.name)
            log(f"  ! {a.name} is not available for {cfg.prov.name} {cfg.version} ({e})")
    cfg.addon_files["_version"] = [cfg.version]
    return failed
