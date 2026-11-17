"""Server Recovery: a small .mh file with everything needed to rebuild a server's setup.

It holds the MC Hoster settings, the server's own settings files (server.properties, ops, whitelist, bans…), the
list of plugins/mods, the server icon and each world's level.dat. It does NOT hold the worlds themselves (that's what
backups are for); recovery puts the world back from the newest backup when the world folder is gone.

Two copies are kept: <server folder>/<name>.mh and MC Hoster's own recovery folder, so a broken or deleted server
folder can still be rebuilt. The format is plain JSON, so the website can read it too.
"""
from __future__ import annotations

import base64
import gzip
import hashlib
import json
import os
import shutil
import time
from pathlib import Path

from . import core

FORMAT = "mchoster-recovery"
TEXT_FILES = ["server.properties", "ops.json", "whitelist.json", "banned-players.json", "banned-ips.json", "eula.txt",
              "permissions.json", "allowlist.json", "bukkit.yml", "spigot.yml", "commands.yml", "help.yml",
              "config/paper-global.yml", "config/paper-world-defaults.yml", "purpur.yml", "pufferfish.yml"]
JSON_FILES = {"ops.json", "whitelist.json", "banned-players.json", "banned-ips.json", "permissions.json", "allowlist.json"}


def recovery_dir() -> Path:
    d = core.app_dir() / "recovery"
    d.mkdir(parents=True, exist_ok=True)
    return d


def file_name(cfg: core.ServerConfig) -> str:
    return core.safe_folder_name(cfg.name) + ".mh"


def build(cfg: core.ServerConfig) -> dict:
    d = Path(cfg.folder)
    files = {}
    for name in TEXT_FILES:
        p = d / name
        if p.is_file() and p.stat().st_size < 512 * 1024:
            try:
                files[name] = p.read_text(encoding="utf-8")
            except (OSError, UnicodeDecodeError):
                pass
    plugins = []
    pf = d / cfg.prov.addon_folder if cfg.prov.addon_folder else None
    if pf and pf.is_dir():
        for j in sorted(pf.glob("*.jar")):
            try:
                plugins.append({"file": j.name, "size": j.stat().st_size,
                                "sha1": hashlib.sha1(j.read_bytes()).hexdigest()})
            except OSError:
                pass
    worlds = []
    for w in core.world_dirs(cfg):
        entry = {"name": w.relative_to(d).as_posix()}
        ld = w / "level.dat"
        if not cfg.prov.bedrock and ld.is_file() and ld.stat().st_size < 2 * 1024 * 1024 and level_dat_ok(ld):
            entry["level_dat"] = base64.b64encode(ld.read_bytes()).decode()
        worlds.append(entry)
    icon = d / "server-icon.png"
    conf = json.loads((d / "mchoster.json").read_text(encoding="utf-8")) if (d / "mchoster.json").exists() else {}
    return {"format": FORMAT, "version": 1, "saved": int(time.time()), "mchoster": core.VERSION,
            "name": cfg.name, "type": cfg.prov.name, "minecraft": cfg.version, "folder": str(d),
            "config": conf, "files": files, "plugins": plugins, "worlds": worlds,
            "icon": base64.b64encode(icon.read_bytes()).decode() if icon.is_file() else "",
            "backups": [b.name for b in core.list_backups(cfg)[:5]]}


def save(cfg: core.ServerConfig, log=None) -> Path | None:
    """Writes both copies. Quietly does nothing for servers with Server Recovery turned off."""
    if not getattr(cfg, "recovery", True) or not cfg.folder or not Path(cfg.folder).is_dir():
        return None
    try:
        data = json.dumps(build(cfg), separators=(",", ":"))
        out = None
        for target in (Path(cfg.folder) / file_name(cfg), recovery_dir() / file_name(cfg)):
            tmp = target.with_suffix(".mh.part")
            tmp.write_text(data, encoding="utf-8")
            os.replace(tmp, target)
            out = out or target
        return out
    except Exception as e:
        if log:
            log(f"! Couldn't save the Server Recovery file: {e}")
        return None


def load(path: str | Path) -> dict:
    d = json.loads(Path(path).read_text(encoding="utf-8"))
    if d.get("format") != FORMAT:
        raise ValueError("This isn't an MC Hoster Server Recovery (.mh) file.")
    return d


def saved_files() -> list[Path]:
    return sorted(recovery_dir().glob("*.mh"), key=lambda p: p.stat().st_mtime, reverse=True)


def level_dat_ok(p: Path) -> bool:
    try:
        with gzip.open(p) as f:
            return len(f.read(64)) > 3
    except Exception:
        return False


def _json_ok(p: Path) -> bool:
    try:
        json.loads(p.read_text(encoding="utf-8"))
        return True
    except Exception:
        return False


def _props_ok(p: Path) -> bool:
    try:
        t = p.read_text(encoding="utf-8")
    except Exception:
        return False
    return "\x00" not in t and ("server-port" in t or "level-name" in t)


def target_folder(data: dict) -> Path:
    """Where the server lives: its old folder if it still exists, otherwise a new one in MC Hoster's servers folder."""
    old = Path(data.get("folder", ""))
    if data.get("folder") and old.is_dir():
        return old
    return core.new_server_folder(data.get("name") or "Recovered server")


def diagnose(data: dict, folder: Path) -> list[str]:
    """Problems found, in plain words (empty = looks healthy)."""
    probs = []
    if not folder.is_dir():
        return ["The server folder is gone. It will be rebuilt" +
                (", and the world put back from the newest backup." if data.get("backups") else ".")]
    if not (folder / "mchoster.json").exists() or not _json_ok(folder / "mchoster.json"):
        probs.append("MC Hoster's settings for this server are missing or broken.")
    for name in data.get("files", {}):
        p = folder / name
        if not p.exists():
            probs.append(f"{name} is missing.")
        elif name in JSON_FILES and not _json_ok(p):
            probs.append(f"{name} is broken.")
        elif name == "server.properties" and not _props_ok(p):
            probs.append("server.properties is broken.")
    bedrock = data.get("type") == "Bedrock"
    for w in data.get("worlds", []):
        wd = folder / w["name"]
        if not wd.is_dir():
            probs.append(f"The world folder {w['name']} is gone" + (" (a backup can bring it back)." if data.get("backups") else "."))
        elif not bedrock and not level_dat_ok(wd / "level.dat"):
            probs.append(f"{w['name']}/level.dat is broken (the world won't load).")
    pf = folder / ("mods" if data.get("config", {}).get("provider") == "fabric" else "plugins")
    missing = [p["file"] for p in data.get("plugins", []) if not (pf / p["file"]).exists()]
    if missing and not bedrock:
        probs.append(f"{len(missing)} plugin/mod file(s) are missing: " + ", ".join(missing[:5]) + (" …" if len(missing) > 5 else ""))
    return probs


def repair(data: dict, folder: Path, log=print) -> core.ServerConfig:
    """Puts back everything the .mh file knows. MC Hoster then runs its normal setup (server jar, Java, plugins)."""
    folder.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y-%m-%d_%H-%M-%S")
    broken = folder / f"broken-files-{stamp}"

    def keep_old(p: Path):  # never delete anything: broken files go into a folder next to the server
        broken.mkdir(exist_ok=True)
        shutil.move(str(p), str(broken / p.name))

    conf = dict(data.get("config") or {})
    cfg = core.ServerConfig(**{k: v for k, v in conf.items() if k in core.ServerConfig.__dataclass_fields__})
    cfg.folder = str(folder)
    cfg.eula = True
    if not cfg.installed_version:
        cfg.installed_version = cfg.version
    jar = folder / "mchoster.json"
    if jar.exists() and not _json_ok(jar):
        keep_old(jar)
    cfg.save()
    log("» Settings put back.")
    for name, text in (data.get("files") or {}).items():
        p = folder / name
        bad = not p.exists() or (name in JSON_FILES and not _json_ok(p)) or (name == "server.properties" and not _props_ok(p))
        if bad:
            if p.exists():
                keep_old(p)
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, encoding="utf-8")
            log(f"» {name} put back.")
    if data.get("icon") and not (folder / "server-icon.png").exists():
        (folder / "server-icon.png").write_bytes(base64.b64decode(data["icon"]))
    # worlds
    missing_world = [w for w in data.get("worlds", []) if not (folder / w["name"]).is_dir()]
    if missing_world:
        backups = core.list_backups(cfg)
        if backups:
            log(f"» A world folder is gone: putting it back from the newest backup ({backups[0].name}).")
            core.restore_backup(cfg, backups[0], log)
        else:
            log("! A world folder is gone and there is no backup: the server will make a new world.")
    if data.get("type") != "Bedrock":
        for w in data.get("worlds", []):
            ld = folder / w["name"] / "level.dat"
            if (folder / w["name"]).is_dir() and not level_dat_ok(ld):
                old = ld.with_name("level.dat_old")
                if ld.exists():
                    keep_old(ld)
                if level_dat_ok(old):
                    shutil.copyfile(old, ld)
                    log(f"» {w['name']}/level.dat was broken: used the server's own spare copy.")
                elif w.get("level_dat"):
                    ld.write_bytes(base64.b64decode(w["level_dat"]))
                    log(f"» {w['name']}/level.dat was broken: put back the copy from the recovery file.")
    if broken.exists():
        log(f"» The broken files were kept in {broken.name}, just in case.")
    return cfg
