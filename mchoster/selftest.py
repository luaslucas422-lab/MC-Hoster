"""Headless end-to-end test: the same setup the window does, then a real server start.

Used by CI on Windows. Exit code 0 only if the server says Done and (when asked for)
Gh0st AC loaded.
"""
from __future__ import annotations

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
    gh0st = "--no-gh0st" not in args
    provider = core.PROVIDER_BY_ID[pid]
    versions = core.list_versions(provider)
    print(f"{provider.name} versions offered: {', '.join(versions)}")
    if version == "latest":
        version = versions[0]
    assert version in versions, f"{version} is not offered for {provider.name}"

    mc = Path(tempfile.mkdtemp(prefix="mc-")) / ".minecraft"
    mc.mkdir()
    (mc / "launcher_profiles.json").write_text("{}")
    icon = Path(tempfile.mkdtemp()) / "icon.png"
    from PIL import Image
    Image.new("RGBA", (200, 120), (40, 160, 70, 255)).save(icon)
    resized = core.resize_icon(str(icon), icon.with_name("icon64.png"))
    assert core.check_icon(str(resized))[0], "resize did not make a 64x64 png"
    assert not core.check_icon(str(icon))[0], "a 200x120 icon should be refused"

    cfg = core.ServerConfig(name=f"CI {provider.name} {version} ✓", provider=pid, version=version, players=12,
                            gamemode="Creative", difficulty="Hard", gh0stac=gh0st, icon_path=str(resized),
                            minecraft_dir=str(mc), eula=True)
    cfg.folder = str(Path(tempfile.mkdtemp(prefix="srv-")))
    t0 = time.time()
    core.setup_server(cfg, log=lambda l: print("  " + l))
    print(f"setup took {time.time() - t0:.0f}s, RAM {cfg.ram_mb} MB, java {cfg.java}")

    d = Path(cfg.folder)
    props = (d / "server.properties").read_text()
    for want in ("gamemode=creative", "difficulty=hard", "max-players=12", "motd=CI "):
        assert want in props, f"server.properties is missing {want}"
    assert (d / "server-icon.png").exists()
    assert (d / ("start.bat" if core.WINDOWS else "start.sh")).exists()
    jar = d / provider.addon_folder / "Gh0stAC.jar"
    assert jar.exists() == gh0st, "Gh0st AC jar placement is wrong"
    _, (_, root) = core.NBT.read((mc / "servers.dat").read_bytes())
    entries = root["servers"][1][1]
    assert entries and entries[0]["ip"][1] == "localhost" and "icon" in entries[0], "servers.dat entry is wrong"
    # adding twice must not duplicate it
    core.add_to_server_list(mc, cfg.name, "localhost")
    assert len(core.NBT.read((mc / "servers.dat").read_bytes())[1][1]["servers"][1][1]) == 1

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
    if gh0st and not re.search(r"Gh0st AC.*armed", text):
        print("FAILED: Gh0st AC did not load")
        return 1
    print(f"PASSED: {provider.name} {version} started{' with Gh0st AC' if gh0st else ''}")
    shutil.rmtree(d, ignore_errors=True)
    return 0
