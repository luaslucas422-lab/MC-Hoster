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
    # "jenkins:<job url>:<file regex>" (newest build on a Jenkins server)
    # or "bundled:<file>" (one of the Dark plugins that come inside MC Hoster, in darkplugins/)
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
    Addon("vault", "Vault", "Economy and permissions link that shop, money and rank plugins need (VaultUnlocked).",
          {"bukkit": [["mr:vaultunlocked", "hangar:VaultUnlocked", "hangar:Vault"]]}),
    Addon("darkshop", "Dark Shop", "The old DonutSMP /shop (End, Nether, Gear, Food), /sell, /bal, /pay, /baltop menu, /bounty.",
          {"bukkit": [["bundled:DarkShop.jar"]]}, note="Made by DarkerDawnFR, comes with MC Hoster"),
    Addon("darkah", "Dark AH", "DonutSMP-style auction house: /ah, /ah sell <price>, sort, filter, search.",
          {"bukkit": [["bundled:DarkAH.jar"], ["bundled:DarkShop.jar"]]},
          note="Comes with Dark Shop for the money"),
    Addon("darkdiscord", "Dark Discord", "/discord and /dc send your Discord link; /dcedit (ops) sets it, Text or GUI mode.",
          {"bukkit": [["bundled:DarkDiscord.jar"]]}, note="Set your link in game: /dcedit link https://discord.gg/..."),
    Addon("darkrtp", "Dark RTP", "/rtp: random teleport to the Overworld, Nether or End. /rtpq: go with another player.",
          {"bukkit": [["bundled:DarkRTP.jar"]]}, note="Made by DarkerDawnFR, comes with MC Hoster"),
    Addon("darkscoreboard", "Dark Scoreboard", "The old DonutSMP scoreboard + tab list: money, kills, deaths, bounty, playtime. Edit with /dsb edit.",
          {"bukkit": [["bundled:DarkScoreboard.jar"]]}, note="Shows money and bounty from Dark Shop; titled Test SMP until you change it"),
    Addon("darkhomes", "Dark Homes", "The modern DonutSMP /home menu: a bed per home, delete buttons, /sethome, /delhome.",
          {"bukkit": [["bundled:DarkHomes.jar"]]}, note="Made by DarkerDawnFR, comes with MC Hoster"),
    Addon("darktpa", "Dark TPA", "/tpa, /tpahere, /tpaccept, /tpdeny, /tpauto, and /tp, /tphere for ops.",
          {"bukkit": [["bundled:DarkTPA.jar"]]}, note="Made by DarkerDawnFR, comes with MC Hoster"),
    Addon("darkhub", "Dark Hub", "/hub: a bed per hub (100 players each) and a Random Hub button. Ops: /hubset <number>.",
          {"bukkit": [["bundled:DarkHub.jar"]]}, note="Set your hubs in game: /hubset 1 [name]"),
    Addon("darkcombat", "Dark Combat", "DonutSMP combat tag: 20 seconds, no teleports, logging out kills you. /combat time <s>.",
          {"bukkit": [["bundled:DarkCombat.jar"]]}, note="Made by DarkerDawnFR, comes with MC Hoster"),
    Addon("darkshards", "Dark Shards", "Shards from playing and kills; /shards shop with 24-hour shard tools (amethyst Shard Pickaxe).",
          {"bukkit": [["bundled:DarkShards.jar"]]}, note="Made by DarkerDawnFR, comes with MC Hoster"),
    Addon("darkrifts", "Dark Rifts", "Original: rifts open around the world. Hold one alone through mob waves for money, shards and loot.",
          {"bukkit": [["bundled:DarkRifts.jar"]]}, note="Made by DarkerDawnFR, not a DonutSMP copy. Best with Dark Shop + Dark Shards"),
    Addon("darkessentials", "Dark Essentials", "/spawn, /setspawn, /back, kits (starter + daily), welcome messages, AFK, /rules.",
          {"bukkit": [["bundled:DarkEssentials.jar"]]}, note="Set spawn in game: /setspawn"),
    Addon("darkchat", "Dark Chat", "Rank prefixes, @mentions with a ping, [item] in chat, anti-spam and caps, /msg /r /ignore /mute.",
          {"bukkit": [["bundled:DarkChat.jar"]]}, note="Made by DarkerDawnFR, comes with MC Hoster"),
    Addon("darkperformance", "Dark Performance", "Less lag on a home PC: /tps, clears ground items, mob limits, view distance drops when lagging.",
          {"bukkit": [["bundled:DarkPerformance.jar"]]}, note="Made by DarkerDawnFR, comes with MC Hoster"),
    Addon("darkbackup", "Dark Backup", "Backs up the world every 6 hours while it runs, keeps the newest 10. /backup now.",
          {"bukkit": [["bundled:DarkBackup.jar"]]}, note="Backups go in the server's backups folder"),
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


def bundled_jar(name: str) -> Path:
    """A Dark plugin that ships inside MC Hoster."""
    import sys
    for p in (core.resource("darkplugins", name), Path(sys.argv[0]).resolve().parent / "darkplugins" / name):
        if p.exists():
            return p
    raise NotFound(f"{name} is missing from MC Hoster")


def _bundled_outdated(addon: Addon, cfg: core.ServerConfig) -> bool:
    """True if this MC Hoster has a different copy of a bundled jar than the server (MC Hoster was updated)."""
    folder = Path(cfg.folder) / cfg.prov.addon_folder
    for candidates in addon.files_for(cfg.prov.kind):
        for c in candidates:
            if c.startswith("bundled:"):
                name = c.split(":", 1)[1]
                try:
                    if bundled_jar(name).read_bytes() != (folder / name).read_bytes():
                        return True
                except Exception:
                    return True
    return False


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
                if kind == "bundled":
                    r = (str(bundled_jar(slug)), slug, [], "")
                elif kind == "mr":
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
        if source.startswith("bundled:"):
            import shutil
            shutil.copyfile(url, folder / name)
        else:
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
        if aid in cfg.addons and not version_changed and all((folder / f).exists() for f in cfg.addon_files.get(aid, [])) \
                and not _bundled_outdated(a, cfg):
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
    ensure_java_for_addons(cfg, log)
    return failed


def jar_java(jar: Path) -> int:
    """The Java version a jar was built for (from its .class files), 0 if unknown."""
    import zipfile
    best = 0
    try:
        with zipfile.ZipFile(jar) as z:
            n = 0
            for info in z.infolist():
                if not info.filename.endswith(".class") or info.filename.startswith("META-INF/"):
                    continue
                with z.open(info) as f:
                    head = f.read(8)
                if head[:4] == b"\xca\xfe\xba\xbe":
                    best = max(best, int.from_bytes(head[6:8], "big") - 44)
                n += 1
                if n >= 40:
                    break
    except Exception:
        return 0
    return best


def ensure_java_for_addons(cfg: core.ServerConfig, log=print) -> None:
    """Some plugins need a newer Java than the server would (WorldEdit 7.4 needs Java 25). Switch Java if so."""
    if cfg.prov.bedrock or not cfg.java:
        return
    folder = Path(cfg.folder) / cfg.prov.addon_folder
    need, who = 0, ""
    for aid, files in cfg.addon_files.items():
        for name in files:
            j = jar_java(folder / name)
            if j > need:
                need, who = j, ADDON_BY_ID[aid].name if aid in ADDON_BY_ID else name
    have = core.java_major(cfg.java) or 0
    if need <= have:
        return
    found = core.find_java(need, Path(cfg.minecraft_dir) if cfg.minecraft_dir else None, cfg.provider == "spigot")
    if found:
        cfg.java = str(found[0])
    else:
        log(f"{who} needs Java {need}, getting it (only the first time).")
        cfg.java = str(core.download_java(need, log=log, jdk=cfg.provider == "spigot"))
    log(f"{who} needs Java {need}, so the server now runs on Java {need}.")
    core.write_start_script(cfg)
