"""Headless end-to-end test: the same setup the window does, then a real server start.

Used by CI on Windows. Exit code 0 only if the server says Done and (when asked for)
Gh0st AC loaded.
"""
from __future__ import annotations

import os
import re
import shutil
import sys
import tempfile
import threading
import time
from pathlib import Path

from . import core


def run(args: list[str]) -> int:
    if len(args) < 2:
        print("usage: --selftest <paper|purpur|fabric|pufferfish|spigot> <version|latest> [--no-gh0st]")
        return 2
    pid, version = args[0], args[1]
    provider = core.PROVIDER_BY_ID[pid]
    gh0st = "--no-gh0st" not in args and not provider.bedrock
    with_addons = "--addons" in args
    versions = core.list_versions(provider)
    print(f"{provider.name} versions offered: {', '.join(versions)}")
    if version == "latest":
        version = versions[0]
    assert version in versions, f"{version} is not offered for {provider.name}"

    if provider.bedrock:
        mc = Path(tempfile.mkdtemp(prefix="mc-")) / "minecraftpe"
        mc.mkdir()
        (mc / "external_servers.txt").write_text("1:Hive:geo.hivebedrock.network:19132:1700000000\n")
    else:
        mc = Path(tempfile.mkdtemp(prefix="mc-")) / ".minecraft"
        mc.mkdir()
        (mc / "launcher_profiles.json").write_text("{}")
    icon = Path(tempfile.mkdtemp()) / "icon.png"
    from PIL import Image
    Image.new("RGBA", (200, 120), (40, 160, 70, 255)).save(icon)
    resized = core.resize_icon(str(icon), icon.with_name("icon64.png"))
    assert core.check_icon(str(resized))[0], "resize did not make a 64x64 png"
    assert not core.check_icon(str(icon))[0], "a 200x120 icon should be refused"

    cfg = core.ServerConfig(name=f"CI {provider.name} {version} ✓", provider=pid, version=version, players=250,
                            gamemode="Creative", difficulty="Hard", gh0stac=gh0st, icon_path=str(resized),
                            minecraft_dir=str(mc), eula=True)
    cfg.folder = str(Path(tempfile.mkdtemp(prefix="srv-")))
    t0 = time.time()
    core.setup_server(cfg, log=lambda l: print("  " + l))
    print(f"setup took {time.time() - t0:.0f}s, RAM {cfg.ram_mb} MB, java {cfg.java}")

    d = Path(cfg.folder)
    props = core.read_properties(d / "server.properties")
    want = {"gamemode": "creative", "difficulty": "hard", "max-players": "250"}
    want["server-name" if provider.bedrock else "motd"] = props.get("server-name" if provider.bedrock else "motd")
    for k, v in want.items():
        assert props.get(k) == v, f"server.properties {k}={props.get(k)}, wanted {v}"
    assert (d / ("start.bat" if core.WINDOWS else "start.sh")).exists()
    if provider.bedrock:
        assert core.bedrock_exe(d).exists(), "bedrock_server is missing"
        assert props.get("server-port") == "19132"
        lines = (mc / "external_servers.txt").read_text().splitlines()
        assert len(lines) == 2 and lines[1].startswith("2:CI Bedrock") and ":127.0.0.1:19132:" in lines[1], lines
        core.add_bedrock_server(mc, cfg.name, "127.0.0.1", 19132)
        assert len((mc / "external_servers.txt").read_text().splitlines()) == 2, "Bedrock entry was duplicated"
    else:
        assert (d / "server-icon.png").exists()
        jar = d / provider.addon_folder / "Gh0stAC.jar"
        assert jar.exists() == gh0st, "Gh0st AC jar placement is wrong"
        _, (_, root) = core.NBT.read((mc / "servers.dat").read_bytes())
        entries = root["servers"][1][1]
        assert entries and entries[0]["ip"][1] == "localhost" and "icon" in entries[0], "servers.dat entry is wrong"
        core.add_to_server_list(mc, cfg.name, "localhost")  # adding twice must not duplicate it
        assert len(core.NBT.read((mc / "servers.dat").read_bytes())[1][1]["servers"][1][1]) == 1
        # running setup again (Apply & restart) must not download again and must keep hand-made settings
        core.update_properties(d / "server.properties", {"motd": "hand edited", "pvp": "false"})
        cfg.players = 20
        core.setup_server(cfg, log=lambda l: print("  again: " + l))
        props = core.read_properties(d / "server.properties")
        assert props["pvp"] == "false" and props["max-players"] == "20" and props["motd"] != "hand edited", props
    if with_addons:
        from . import addons
        wanted = [a.id for a in addons.addons_for(provider)]
        failed = addons.sync(cfg, wanted, log=lambda l: print("  " + l))
        print("addons installed:", sorted(cfg.addons), "failed:", failed)
        assert not failed, f"addons failed: {failed}"
        files = sorted(p.name for p in (d / provider.addon_folder).iterdir())
        print("  files:", files)
        addons.sync(cfg, [w for w in wanted if w != "spark"], log=lambda l: print("  " + l))
        assert "spark" not in cfg.addons and not any("spark" in f.lower() for f in os.listdir(d / provider.addon_folder)), "spark not removed"

    lines: list[str] = []
    ready = threading.Event()
    exited = threading.Event()
    srv = core.ServerProcess(cfg, on_line=lambda l: (lines.append(l), print("  | " + l[-200:])),
                             on_ready=ready.set, on_exit=lambda c: exited.set())
    srv.start()
    ok = ready.wait(600)
    if ok:
        srv.send("list")
        time.sleep(3)
    srv.stop(60)
    exited.wait(30)
    text = "\n".join(lines)
    print()
    print("LAN IP:", core.lan_ip(), " public IP:", core.public_ip())
    if not ok:
        print("FAILED: server never said Done")
        return 1
    if with_addons and re.search(r"Could not load|Error occurred while enabling|Incompatible mods", text):
        print("FAILED: a plugin or mod did not load")
        return 1
    if gh0st and not re.search(r"Gh0st AC.*armed", text):
        print("FAILED: Gh0st AC did not load")
        return 1
    print(f"PASSED: {provider.name} {version} started{' with Gh0st AC' if gh0st else ''}")
    shutil.rmtree(d, ignore_errors=True)
    return 0


def addons_test(args: list[str]) -> int:
    """Every preset for this server type has to be downloadable for this version."""
    from . import addons
    provider = core.PROVIDER_BY_ID[args[0]]
    version = args[1] if args[1] != "latest" else core.list_versions(provider)[0]
    cfg = core.ServerConfig(provider=provider.id, version=version)
    bad = []
    for a in addons.addons_for(provider):
        try:
            files = addons.resolve(a, cfg)
            print(f"OK   {a.name:20} " + ", ".join(f"{name} [{src}]{note}" for url, name, deps, note, src in files))
        except Exception as e:
            bad.append(a.name)
            print(f"FAIL {a.name:20} {e}")
    print(f"{provider.name} {version}: {len(bad)} missing" + (": " + ", ".join(bad) if bad else ""))
    return 1 if bad else 0


def schedule_test() -> int:
    """Makes a real Windows scheduled task for each repeat type, checks it exists, deletes it."""
    import datetime as dt
    import subprocess
    from . import schedule
    if not schedule.supported():
        print("skipped: not Windows")
        return 0
    d = Path(tempfile.mkdtemp(prefix="srv-"))
    cfg = core.ServerConfig(name="CI schedule", folder=str(d))
    when = (dt.datetime.now() + dt.timedelta(days=2))
    cases = [
        {"enabled": True, "repeat": "Once", "time": "18:30", "date": when.date().isoformat()},
        {"enabled": True, "repeat": "Daily", "time": "07:05"},
        {"enabled": True, "repeat": "Weekly", "time": "20:00", "days": ["Sat", "Sun"]},
        {"enabled": True, "repeat": "Monthly", "time": "12:00", "date": "2026-12-15"},
        {"enabled": True, "repeat": "Yearly", "time": "09:00", "date": "2027-06-01"},
    ]
    for c in cases:
        cfg.schedule = dict(c)
        assert schedule.validate(c) is None, schedule.validate(c)
        assert schedule.apply(cfg, log=print), f"could not create {c['repeat']}"
        q = subprocess.run(["schtasks", "/Query", "/TN", schedule.task_name(cfg), "/V", "/FO", "LIST"], capture_output=True, text=True)
        assert q.returncode == 0, q.stderr
        info = [l for l in q.stdout.splitlines() if l.split(":")[0].strip() in ("Next Run Time", "Schedule Type", "Task To Run", "Days", "Months")]
        print(f"  {c['repeat']:8} -> " + " | ".join(l.strip() for l in info))
    cfg.name = "CI schedule renamed"  # renaming deletes the old task
    assert schedule.apply(cfg, log=print)
    assert subprocess.run(["schtasks", "/Query", "/TN", "MC Hoster\\CI schedule " + schedule.task_name(cfg).split()[-1]],
                          capture_output=True).returncode != 0, "old task was not removed after rename"
    cfg.schedule["enabled"] = False
    schedule.apply(cfg, log=print)
    assert not schedule.exists(cfg), "task still there after turning it off"
    assert schedule.validate({"enabled": True, "repeat": "Once", "time": "10:00", "date": "2020-01-01"}), "past date accepted"
    print("PASSED: scheduler")
    return 0
