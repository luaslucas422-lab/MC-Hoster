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
    Addon("tab", "TAB", "A better tab list and name tags: headers, footers, sorting by rank, animations.",
          {"bukkit": [["mr:tab-was-taken", "hangar:TAB"]]}, note="Don't run another plugin that changes the tab list next to it"),
    Addon("decentholograms", "DecentHolograms", "Floating text and items: /dh create <name> <text>.",
          {"bukkit": [["mr:decentholograms", "hangar:DecentHolograms"]]}),
    Addon("multiverse", "Multiverse-Core", "More worlds: /mv create <name> normal, /mv tp <world>.",
          {"bukkit": [["mr:multiverse-core", "hangar:Multiverse-Core"]]}),
    Addon("placeholderapi", "PlaceholderAPI", "%placeholders% for other plugins .",
          {"bukkit": [["hangar:PlaceholderAPI", "mr:placeholderapi"]]}),
    Addon("grimac", "GrimAC", "A strong free anticheat (movement, combat, blocks).",
          {"bukkit": [["mr:grimac", "hangar:GrimAC"]]}, note="Works next to Gh0st AC"),
    Addon("squaremap", "squaremap", "A light web map of your world (like BlueMap, but flat and fast). Port 8080.",
          {"bukkit": [["mr:squaremap", "hangar:squaremap"]]}, ports=[(8080, "TCP", "squaremap")]),
    Addon("skinsrestorer", "SkinsRestorer", "/skin <name> to change your skin, and skins for cracked players.",
          {"bukkit": [["mr:skinsrestorer", "hangar:SkinsRestorer"]]}),
    Addon("quickshop", "QuickShop-Hikari", "Player shops: put a chest down, hit it with an item, set a price.",
          {"bukkit": [["mr:quickshop-hikari", "hangar:QuickShop-Hikari"]]}, note="Needs Vault and a money plugin "),
    Addon("fawe", "FastAsyncWorldEdit", "WorldEdit, but much faster for big edits (//set on millions of blocks).",
          {"bukkit": [["mr:fastasyncworldedit", "hangar:FastAsyncWorldEdit"]]}, note="Don't tick WorldEdit with it"),
    Addon("viabackwards", "ViaBackwards", "Older Minecraft versions can join (with ViaVersion).",
          {"bukkit": [["mr:viabackwards", "hangar:ViaBackwards"], ["mr:viaversion", "hangar:ViaVersion"]]}, note="Installs ViaVersion too"),
    Addon("worldguard", "WorldGuard", "Protected regions with flags (pvp, mob spawning, entry...).",
          {"bukkit": [["mr:worldguard"], ["mr:worldedit", "hangar:WorldEdit"]]}, note="Installs WorldEdit too"),
    Addon("chunkyborder", "ChunkyBorder", "A round or square world border with particles (/chunky border).",
          {"bukkit": [["mr:chunkyborder", "hangar:ChunkyBorder"], ["mr:chunky", "hangar:Chunky"]]}, note="Installs Chunky too"),
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


# The folders in tab 5, in this order.
CATEGORIES = ["Admin & permissions", "Protection & anticheat", "Chat, voice & looks",
              "World & building", "Maps", "Performance", "Cross-play & versions", "Economy", "Other"]
_CATEGORY = {
    "luckperms": 0, "vanish": 0, "essentials": 0, "placeholderapi": 0,
    "coreprotect": 1, "ledger": 1, "grimac": 1, "worldguard": 1,
    "voicechat": 2, "tab": 2, "decentholograms": 2, "skinsrestorer": 2,
    "worldedit": 3, "fawe": 3, "multiverse": 3, "chunky": 3, "chunkyborder": 3,
    "bluemap": 4, "squaremap": 4,
    "spark": 5, "lithium": 5, "ferritecore": 5, "rosestacker": 5,
    "geyser": 6, "viaversion": 6, "viabackwards": 6,
    "vault": 7, "quickshop": 7,
}


def category(a: Addon) -> str:
    return CATEGORIES[_CATEGORY.get(a.id, len(CATEGORIES) - 1)]


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


# --------------------------------------------------------------------------- your own plugins (tab 5)
# Plugins and mods you keep in folders on this PC. Tick them in tab 5 and MC Hoster copies them to the server,
# and copies them again before every start if the file in your folder changed. That is how updates arrive:
# replace the file in your folder.

def default_plugin_folder() -> Path:
    docs = Path.home() / "Documents"
    d = (docs if docs.is_dir() else Path.home()) / "MC Hoster Plugins"
    try:
        d.mkdir(parents=True, exist_ok=True)
    except OSError:
        pass
    return d


def _folders_file() -> Path:
    return core.app_dir() / "plugin-folders.json"


def plugin_folders() -> list[Path]:
    """The default folder, then the folders added with "Add folder…"."""
    out = [default_plugin_folder()]
    try:
        for f in json.loads(_folders_file().read_text(encoding="utf-8")):
            p = Path(f)
            if p not in out:
                out.append(p)
    except Exception:
        pass
    return out


def add_plugin_folder(path: str) -> None:
    try:
        cur = json.loads(_folders_file().read_text(encoding="utf-8"))
    except Exception:
        cur = []
    if path not in cur and Path(path) != default_plugin_folder():
        cur.append(path)
        _folders_file().write_text(json.dumps(cur), encoding="utf-8")


def remove_plugin_folder(path: str) -> None:
    try:
        cur = [f for f in json.loads(_folders_file().read_text(encoding="utf-8")) if f != path]
        _folders_file().write_text(json.dumps(cur), encoding="utf-8")
    except Exception:
        pass


def jar_kind(jar: Path) -> str:
    """"bukkit" (plugin.yml), "fabric" (fabric.mod.json) or "" if it can't be told."""
    import zipfile
    try:
        with zipfile.ZipFile(jar) as z:
            names = set(z.namelist())
    except Exception:
        return ""
    if "fabric.mod.json" in names:
        return "fabric"
    if "plugin.yml" in names or "paper-plugin.yml" in names:
        return "bukkit"
    return ""


def scan_plugins(kind: str) -> list[Path]:
    """Jars in your folders (two levels deep) that fit this server type, newest names first by folder order."""
    seen, out = set(), []
    for folder in plugin_folders():
        try:
            jars = sorted(list(folder.glob("*.jar")) + list(folder.glob("*/*.jar")), key=lambda p: p.name.lower())
        except OSError:
            continue
        for j in jars:
            k = jar_kind(j)
            if k and k != kind:
                continue  # a Paper plugin on a Fabric server (or the other way round) can't work
            if str(j) not in seen:
                seen.add(str(j))
                out.append(j)
    return out


def sync_custom(cfg: core.ServerConfig, log=print) -> list[str]:
    """Copies the ticked jars (cfg.custom_jars) to the server if they are new or changed, and removes the ones that
    were unticked. Runs on Apply and before every start. Returns the file names that changed."""
    import shutil
    folder = Path(cfg.folder) / cfg.prov.addon_folder
    installed = list(cfg.addon_files.get("_custom", []))
    changed, now = [], []
    for path in cfg.custom_jars or []:
        src = Path(path)
        if not src.is_file():
            if (folder / src.name).exists():
                now.append(src.name)  # the file left your folder: the copy on the server stays as it is
            continue
        dst = folder / src.name
        now.append(src.name)
        try:
            if dst.exists() and dst.stat().st_size == src.stat().st_size and dst.read_bytes() == src.read_bytes():
                continue
            folder.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(src, dst)
            changed.append(src.name)
        except OSError as e:
            log(f"! Couldn't copy {src.name}: {e}")
    for name in installed:
        if name not in now:
            (folder / name).unlink(missing_ok=True)
            log(f"Removed {name}")
    cfg.addon_files["_custom"] = now
    if changed:
        log("Updated your plugins: " + ", ".join(n.removesuffix(".jar") for n in changed))
    return changed


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
    skip = {c for a in (getattr(cfg, "_wanted", None) or cfg.addons) for c in PROVIDED.get(a, [])}
    for url, name, deps, note, source in resolve(addon, cfg):
        if source in skip:
            log(f"  = {name} not needed (already there)")
            continue
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
            if any(name.lower().startswith(p.split(":", 1)[1].lower()) for p in skip):
                log(f"  = {name} not needed (already there)")
                continue
            if not (folder / name).exists():
                core.download(url, folder / name)
            files.append(name)
            log(f"  + {name} (needed by {addon.name})")
            pending += deps
        except Exception as e:
            log(f"  ! could not get a library {addon.name} needs ({e})")
    return files


# Presets that can't be on the same server: ticking the first unticks the others.
CONFLICTS = {"fawe": ["worldedit"], "worldedit": ["fawe"]}
# A file another preset brings along, that a ticked preset already gives (FAWE is a WorldEdit too).
PROVIDED = {"fawe": ["mr:worldedit", "hangar:WorldEdit"]}


def fix_conflicts(wanted: list[str]) -> list[str]:
    out = list(wanted)
    for a in wanted:
        if a in out:
            for c in CONFLICTS.get(a, []):
                if c in out and out.index(c) > out.index(a):
                    out.remove(c)
    return out


def sync(cfg: core.ServerConfig, wanted: list[str], log=print) -> list[str]:
    """Installs the ticked presets that are missing and removes the unticked ones. Returns failures."""
    fixed = fix_conflicts(wanted)
    for a in wanted:
        if a not in fixed:
            log(f"Not installing {ADDON_BY_ID[a].name if a in ADDON_BY_ID else a}: it can't be on the same server as "
                + ", ".join(ADDON_BY_ID[x].name for x in CONFLICTS.get(a, []) if x in fixed and x in ADDON_BY_ID))
    wanted = fixed
    cfg._wanted = wanted  # install() skips files a ticked preset already gives
    folder = Path(cfg.folder) / cfg.prov.addon_folder
    failed = []
    # (ids from older versions, like the Dark plugins that used to come inside MC Hoster, are left alone)
    for aid in [a for a in cfg.addons if a not in wanted and a in ADDON_BY_ID]:
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
    sync_custom(cfg, log)
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
