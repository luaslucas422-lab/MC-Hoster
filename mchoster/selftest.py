"""Headless end-to-end test: the same setup the window does, then a real server start.

Used by CI on Windows. Exit code 0 only if the server says Done and (when asked for)
Gh0st AC loaded.
"""
from __future__ import annotations

import json
import os
import re
import shutil
import sys
import tempfile
import threading
import time
from pathlib import Path

from . import core


DARK = ["darkshop", "darkah", "darkdiscord", "darkrtp", "darkscoreboard", "darkhomes", "darktpa", "darkhub", "darkcombat", "darkshards", "darkrifts", "darkessentials", "darkchat", "darkperformance", "darkbackup", "darkcrates", "darktips", "darkbossbar", "darkannounce", "darkspawners", "darksettings", "darkduels", "darkranks", "darkperks", "darkvcaddon", "darkteams", "darkhatesgriefers", "darkafkzone", "darknames", "darkfishing", "darknpcs", "noenchantlimits"]  # Dark RS Add-on is tested in --addons (RoseStacker)
DARK_TESTS = {"darkshop": ("darkshop test", "DARKSHOP"), "darkah": ("darkah test", "DARKAH"),
              "darkdiscord": ("dcedit test", "DARKDISCORD"), "darkrtp": ("darkrtp test", "DARKRTP"),
              "darkscoreboard": ("dsb test", "DARKSCOREBOARD"), "darkhomes": ("darkhomes test", "DARKHOMES"),
              "darktpa": ("darktpa test", "DARKTPA"), "darkhub": ("darkhub test", "DARKHUB"),
              "darkcombat": ("combat test", "DARKCOMBAT"), "darkshards": ("shards test", "DARKSHARDS"),
              "darkrifts": ("rift test", "DARKRIFTS"), "darkessentials": ("darkessentials test", "DARKESSENTIALS"),
              "darkchat": ("darkchat test", "DARKCHAT"), "darkperformance": ("darkperformance test", "DARKPERFORMANCE"),
              "darkbackup": ("backup test", "DARKBACKUP"), "darkcrates": ("crate test", "DARKCRATES"),
              "darktips": ("tips test", "DARKTIPS"), "darkbossbar": ("bar test", "DARKBOSSBAR"),
              "darkannounce": ("darkannounce test", "DARKANNOUNCE"), "darkspawners": ("spawners test", "DARKSPAWNERS"),
              "darksettings": ("settings test", "DARKSETTINGS"), "darkduels": ("duel test", "DARKDUELS"),
              "darkranks": ("rank test", "DARKRANKS"), "darkperks": ("perks test", "DARKPERKS"),
              "darkvcaddon": ("vc test", "DARKVCADDON"), "darkteams": ("darkteams test", "DARKTEAMS"), "darkhatesgriefers": ("dhg test", "DARKHATESGRIEFERS"), "darkafkzone": ("afkzone test", "DARKAFKZONE"), "darknames": ("dn test", "DARKNAMES"), "darkfishing": ("fish test", "DARKFISHING"), "darknpcs": ("npc test", "DARKNPCS"), "noenchantlimits": ("noenchantlimits test", "NOENCHANTLIMITS"),
              "darkrsaddon": ("stacking test", "DARKRSADDON")}


def run(args: list[str]) -> int:
    if len(args) < 2:
        print("usage: --selftest <paper|purpur|fabric|pufferfish|spigot> <version|latest> [--no-gh0st]")
        return 2
    pid, version = args[0], args[1]
    provider = core.PROVIDER_BY_ID[pid]
    gh0st = "--no-gh0st" not in args and not provider.bedrock
    with_addons = "--addons" in args
    dark = "--dark" in args  # only the Dark plugins (+ Vault with --vault), then their in-game tests
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
        # closing the Nether and the End (and opening them again before the real start)
        cfg.nether, cfg.end = False, False
        core.write_server_files(cfg)
        assert core.read_properties(d / "server.properties")["allow-nether"] == "false"
        if provider.kind == "bukkit":
            assert "allow-end: false" in (d / "bukkit.yml").read_text(), "bukkit.yml allow-end not written"
        assert core.valid_player_name("Geo_123") and not core.valid_player_name("ab") and not core.valid_player_name("bad name")
        cfg.owner = "Geo"
        cfg.save()
        assert core.ServerConfig.load(d).owner == "Geo", "owner name not saved"
        cfg.owner = ""
        # a name with ! makes a folder Java can't use: the folder is made without it, and fixed when renamed
        assert core.safe_folder_name("Geo's SMP!!") == "Geo's SMP" and core.safe_folder_name("!!!") == "My Server"
        tmp = Path(tempfile.mkdtemp())
        bad = tmp / "Test SMP!"
        bad.mkdir()
        t = core.ServerConfig(name="Test SMP!", eula=True)
        t.folder = str(bad)
        t.save()
        assert core.rename_server_folder(t, print) and Path(t.folder).name == "Test SMP" and Path(t.folder, "mchoster.json").exists()
        t.name = "Renamed Server"
        assert core.rename_server_folder(t, print) and Path(t.folder).name == "Renamed Server"
        assert not core.rename_server_folder(t, print), "renamed again for nothing"
        assert core.ServerConfig.load(Path(t.folder)).name == "Renamed Server"
        assert "port" in core.explain_exit(["[Server thread/WARN]: **** FAILED TO BIND TO PORT!"]).lower()
        assert "Java" in core.explain_exit(["java.lang.UnsupportedClassVersionError: has been compiled by a more recent version of the Java Runtime"])
        pe = core.plugin_errors(["[10:00:01 ERROR]: Error occurred while enabling DarkShop v1.0.0 (Is it up to date?)",
                                 "java.lang.NullPointerException: Cannot invoke \"String.length()\"", "\tat dev.x.Y.z(Y.java:1)"])
        assert "DarkShop" in pe and "NullPointerException" in pe["DarkShop"], pe
        assert core.plugin_errors(["[10:00:01 INFO]: Done (3.1s)! For help"]) == {}
        assert core.explain_exit(["Done (3.1s)! For help"]) is None and "!" in core.explain_exit([], "C:/x/My SMP!")
        cfg.nether, cfg.end = True, True
        core.write_server_files(cfg)
        assert core.read_properties(d / "server.properties")["allow-nether"] == "true"
        if provider.kind == "bukkit":
            assert "allow-end: true" in (d / "bukkit.yml").read_text()
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
    if dark:
        from . import addons
        wanted = DARK + (["vault"] if "--vault" in args else [])
        failed = addons.sync(cfg, wanted, log=lambda l: print("  " + l))
        assert not failed, f"addons failed: {failed}"
        print("  files:", sorted(p.name for p in (d / provider.addon_folder).iterdir()))
        # unticking Dark Shop must keep DarkShop.jar, Dark AH still needs it
        addons.sync(cfg, [w for w in wanted if w != "darkshop"], log=lambda l: print("  " + l))
        assert (d / provider.addon_folder / "DarkShop.jar").exists(), "DarkShop.jar removed although Dark AH needs it"
        addons.sync(cfg, wanted, log=lambda l: print("  " + l))

    # tab 6 Config: properties written now, gamerules / whitelist / ops sent when it's ready
    if not provider.bedrock:
        cfg.tweaks = {"pvp": False, "motd": "&bCI &lserver", "spawn-protection": 8, "allow-flight": True, "view-distance": 7,
                      "keep_inventory": True, "phantoms": False, "sleeping": 50, "whitelist_names": ["CiPlayer"], "aikar": True}
        cfg.resource_pack = {"url": "https://example.com/pack.zip", "sha1": "0" * 40, "required": True, "prompt": "Please"}
        cfg.experimental = {"enabled": True, "commands": ["/say MCHOSTER STARTUP OK", "# skipped"]}
        core.write_server_files(cfg)
        props = core.read_properties(d / "server.properties")
        for k, v in {"pvp": "false", "spawn-protection": "8", "allow-flight": "true", "view-distance": "7",
                     "resource-pack": "https\\://example.com/pack.zip", "require-resource-pack": "true"}.items():
            assert props.get(k, "").replace("\\", "") == v.replace("\\", ""), f"tab 6: {k}={props.get(k)} not {v}"
        assert "u00a7b" in props.get("motd", "").lower(), "motd colours: " + props.get("motd", "")
        assert "-Daikars.new.flags=true" in core.server_args(cfg), "Aikar's flags missing"
        cfg.resource_pack = {}
        core.write_server_files(cfg)
        assert core.read_properties(d / "server.properties").get("resource-pack", "") == "", "resource pack not removed"

    lines: list[str] = []
    ready = threading.Event()
    exited = threading.Event()
    srv = core.ServerProcess(cfg, on_line=lambda l: (lines.append(l), print("  | " + l[-200:])),
                             on_ready=ready.set, on_exit=lambda c: exited.set())
    srv.start()
    ok = ready.wait(600)
    tests = {}
    rules_ok: dict[str, bool] = {}
    if ok and not provider.bedrock:
        for c in core.ready_commands(cfg):
            srv.send(c)
        for _ in range(20):  # some servers print the answers a bit later
            time.sleep(1)
            joined = "\n".join(lines)
            for key, _label, default, names in core.GAMERULES:
                if key in cfg.tweaks:
                    rules_ok[key] = any(re.search(r"(?i)" + re.escape(n) + r".{0,30}(is now set to|now set|set to)", joined) for n in names)
            if rules_ok and all(rules_ok.values()):
                break
        print("gamerules set:", rules_ok)
    if ok:
        srv.send("list")
        time.sleep(3)
        for aid, (cmd, tag) in DARK_TESTS.items():
            if aid in cfg.addons:
                srv.send(cmd)
                tests[aid] = tag
        end = time.time() + 120
        while tests and time.time() < end and not all(re.search(t + r" TEST (OK|FAIL)", "\n".join(lines)) for t in tests.values()):
            time.sleep(1)
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
    if (with_addons or dark) and re.search(r"Could not load|Error occurred while enabling", text):
        print("FAILED: a plugin did not load")
        return 1
    for aid, tag in tests.items():
        m = re.search(tag + r" TEST (OK.*|FAIL.*)", text)
        print(f"{tag}: {m.group(0) if m else 'no answer'}")
        if not m or not m.group(1).startswith("OK"):
            print(f"FAILED: {tag} test")
            return 1
    if not provider.bedrock and "MCHOSTER STARTUP OK" not in text:
        print("FAILED: the Experimental tab's startup command didn't run")
        return 1
    bad_rules = [k for k, v in rules_ok.items() if not v]
    if bad_rules:
        print(f"FAILED: gamerules not set on {version}: {bad_rules}")
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


def respack_test() -> int:
    """Resource packs: search Modrinth, a CurseForge link, and the SHA-1 of the file."""
    from . import respack
    hits = respack.search_modrinth("faithful")
    print("Modrinth search:", [h["title"] for h in hits[:5]])
    assert hits, "no Modrinth results"
    mr = respack.modrinth_pack(hits[0]["id"], "1.21.1")
    print("Modrinth pack:", mr)
    assert mr["url"].startswith("https://") and len(mr["sha1"]) == 40
    sha = respack.sha1_of(mr["url"])
    assert sha == mr["sha1"], f"sha1 {sha} != {mr['sha1']}"
    cf = None
    for slug in ("bare-bones", "stay-true", "default-dark-mode"):  # some packs left CurseForge, one of these is there
        try:
            cf = respack.curseforge_pack(f"https://www.curseforge.com/minecraft/texture-packs/{slug}")
            break
        except LookupError as e:
            print(f"  {slug}: {e}")
    assert cf, "no CurseForge pack worked"
    print("CurseForge pack:", cf)
    assert cf["url"].startswith("https://")
    cf_sha = respack.sha1_of(cf["url"])
    print("CurseForge sha1:", cf_sha)
    assert len(cf_sha) == 40
    print("RESPACK TEST OK")
    return 0


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


def _quick_server(name: str) -> core.ServerConfig:
    mc = Path(tempfile.mkdtemp(prefix="mc-")) / ".minecraft"
    mc.mkdir(parents=True)
    (mc / "launcher_profiles.json").write_text("{}")
    cfg = core.ServerConfig(name=name, provider="paper", version="1.21.11", players=10, gh0stac=False,
                            minecraft_dir=str(mc), eula=True)
    cfg.folder = str(core.new_server_folder(name))  # in My Servers, like the window does
    core.setup_server(cfg, log=lambda l: print("  " + l))
    cfg.save()
    return cfg


def _wait_for(pred, seconds: float) -> bool:
    end = time.time() + seconds
    while time.time() < end:
        if pred():
            return True
        time.sleep(0.5)
    return pred()


def hold_server(folder: str) -> int:
    """Starts a server like the window does, waits for Done, then ends this process without stopping it."""
    cfg = core.ServerConfig.load(Path(folder))
    ready = threading.Event()
    srv = core.ServerProcess(cfg, on_ready=ready.set)
    srv.start()
    ok = ready.wait(300)
    print("HELD" if ok else "NOT READY", srv.proc.pid, flush=True)
    os._exit(0 if ok else 1)


def orphan_test() -> int:
    """The bug: a server kept running after MC Hoster closed, so every other server found its port taken."""
    import subprocess
    if not core.WINDOWS:
        print("skipped: not Windows")
        return 0
    a = _quick_server("Orphan A")
    b = _quick_server("Orphan B")
    fails = []

    # 1. MC Hoster ends without stopping the server: the server must end with it
    out = subprocess.run([sys.executable, "-u", sys.argv[0], "--hold-server", a.folder], capture_output=True, text=True, timeout=400)
    print("  hold:", out.stdout.strip(), out.stderr.strip()[-300:])
    if "HELD" not in out.stdout:
        fails.append("server A never started")
    pid = int(out.stdout.split()[-1]) if "HELD" in out.stdout else 0
    if not _wait_for(lambda: core.port_free(25565), 20):
        fails.append("server A kept running after MC Hoster ended")
        if pid:
            core.kill_tree(pid)
    else:
        print("  ok: server A stopped together with MC Hoster")

    # 2. A server left running by an old MC Hoster (no job): it's found and stopped
    a_cfg = core.ServerConfig.load(Path(a.folder))
    proc = subprocess.Popen(core.server_args(a_cfg), cwd=a.folder, stdin=subprocess.PIPE, stdout=subprocess.DEVNULL,
                            stderr=subprocess.DEVNULL, creationflags=core.NO_WINDOW)
    (Path(a.folder) / core.PID_FILE).write_text(json.dumps({"pid": proc.pid}))
    if not _wait_for(lambda: not core.port_free(25565), 240):
        fails.append("old-style server A didn't start")
    left = core.leftovers()
    print("  leftovers:", [(l.cfg.name, l.pid) for l in left])
    if not any(l.pid == proc.pid for l in left):
        fails.append("leftover server not found")
    pid_, prog, mine = core.who_has_port(b)
    print("  port 25565 owner:", pid_, prog, mine.name if mine else None)
    if mine is None or mine.name != "Orphan A" or pid_ != proc.pid:
        fails.append("didn't see that Orphan A has the port")
    for l in left:
        core.stop_leftover(l)
    if not _wait_for(lambda: core.port_free(25565), 20):
        fails.append("leftover not stopped")
    if core.leftovers():
        fails.append("leftover still listed")

    # 3. Something else has the port: another one is picked
    import socket as so
    blocker = so.socket()
    blocker.bind(("0.0.0.0", 25565))
    blocker.listen()
    if core.port_free(25565):
        fails.append("busy port looked free")
    new = core.next_free_port(25565)
    core.change_port(b, new)
    print("  port taken by this test, B moves to", new)
    if core.read_properties(Path(b.folder) / "server.properties").get("server-port") != str(new):
        fails.append("server.properties port not changed")

    # 4. Server B now starts and is reachable
    ready = threading.Event()
    srv = core.ServerProcess(b, on_ready=ready.set)
    srv.start()
    if not ready.wait(300):
        fails.append("server B didn't start")
    elif core.port_free(new):
        fails.append("server B isn't on its port")
    srv.stop(60)
    blocker.close()
    if (Path(b.folder) / core.PID_FILE).exists():
        fails.append("pid file not cleaned up")
    for c in (a, b):
        shutil.rmtree(c.folder, ignore_errors=True)
    print("FAILED: " + "; ".join(fails) if fails else "PASSED: orphan test")
    return 1 if fails else 0
