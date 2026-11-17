"""Upgrade test: a server made by an older MC Hoster must keep working with the new one.

  python upgrade_test.py old <old repo> <folder> <type>   make + run a server with the old MC Hoster code
  python upgrade_test.py new <new repo> <folder>          open it like the window does, run it, then Apply & restart it
"""
import re
import sys
import threading
import time
from pathlib import Path

phase, repo, folder = sys.argv[1], sys.argv[2], Path(sys.argv[3])
sys.path.insert(0, repo)
from mchoster import core, addons  # noqa: E402

BAD = re.compile(r"Error occurred while enabling|Could not load '|Exception in server tick|Encountered an unexpected exception|"
                 r"FAILED TO BIND|Failed to start|java\.lang\.\w+Error|Server thread/ERROR")


def run(cfg, seconds, label):
    lines, ready, exited = [], threading.Event(), threading.Event()
    srv = core.ServerProcess(cfg, on_line=lambda l: (lines.append(l), print(f"  {label} | " + l[-220:])),
                             on_ready=ready.set, on_exit=lambda c: exited.set())
    srv.start()
    ok = ready.wait(600)
    if ok:
        srv.send("plugins")
        time.sleep(seconds)
    alive = srv.running and not exited.is_set()
    srv.stop(90)
    exited.wait(60)
    bad = [l for l in lines if BAD.search(l)]
    print(f"{label}: ready={ok} still running after {seconds}s={alive}, problem lines={len(bad)}")
    for l in bad:
        print("  PROBLEM " + l[-300:])
    return ok and alive, bad


if phase == "old":
    ptype = sys.argv[4]
    prov = core.PROVIDER_BY_ID[ptype]
    version = core.list_versions(prov)[0]
    folder.mkdir(parents=True, exist_ok=True)
    cfg = core.ServerConfig(name="Upgrade Test", provider=ptype, version=version, players=20, eula=True, add_to_list=False)
    cfg.folder = str(folder)
    core.setup_server(cfg, log=lambda l: print("  old setup: " + l))
    wanted = [a for a in ("vault", "essentials") if a in addons.ADDON_BY_ID]
    failed = addons.sync(cfg, wanted, log=lambda l: print("  old addons: " + l))
    print("old version:", getattr(core, "VERSION", "?"), "addons:", sorted(cfg.addons), "failed:", failed)
    cfg.save()
    ok, _ = run(cfg, 20, "old")
    sys.exit(0 if ok else 1)

# ---- new MC Hoster opens the old server
print("new version:", core.VERSION)
cfg = core.ServerConfig.load(folder)
# exactly what the window does in open_existing()
if not cfg.prov.bedrock and (not cfg.java or not Path(cfg.java).exists()):
    found = core.find_java(core.required_java(cfg.version), None, cfg.provider == "spigot")
    assert found, "the Java this server used is gone and no other was found"
    cfg.java = str(found[0])
    cfg.save()
changed = addons.sync_custom(cfg) if hasattr(addons, "sync_custom") else []  # what the window does before every start
print("your plugins updated on start:", changed)
ok1, bad1 = run(cfg, 60, "opened")
# then Apply & restart (setup again + the new bundled plugins)
core.setup_server(cfg, log=lambda l: print("  new setup: " + l))
failed = addons.sync(cfg, list(cfg.addons), log=lambda l: print("  new addons: " + l))
print("addons after update:", sorted(cfg.addons), "failed:", failed)
ok2, bad2 = run(cfg, 60, "after apply")
if not (ok1 and ok2) or failed:
    print("UPGRADE TEST FAIL")
    sys.exit(1)
print("UPGRADE TEST OK" + (f" ({len(bad1) + len(bad2)} problem lines, see above)" if bad1 or bad2 else ""))
