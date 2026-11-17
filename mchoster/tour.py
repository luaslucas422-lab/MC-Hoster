"""Opens the real window, clicks through every tab with sample data and saves a screenshot of each.

No network: version lists and the server are faked, so it only tests the window itself.
"""
from __future__ import annotations

import tempfile
import time
import tkinter as tk
from pathlib import Path

from . import core, online, paid, profile, recovery, updater
from .app import Wizard


def run(outdir: str) -> int:
    out = Path(outdir)
    out.mkdir(parents=True, exist_ok=True)
    core.list_versions = lambda p: (["1.21.124.2"] if p.bedrock else list(core.GH0ST_FABRIC_VERSIONS)
                                    if p.kind == "fabric" else core.FALLBACK_VERSIONS[:])
    from tkinter import messagebox
    messagebox.askyesno = lambda *a, **k: True  # the "more than 100 players" questions
    updater.releases = lambda count=30: [
        {"version": "3.0.0", "date": "2026-11-17", "notes": "## MC Hoster 3.0.0\n- **Server sharing** and **Hosted servers**\n- Send feedback\n- Update logs"},
        {"version": "2.1.1", "date": "2026-11-16", "notes": "- RAM on the players slider is honest now\n- Fixed the not enough memory crash"}]
    import base64
    online.inbox = lambda: [
        {"name": "Sam", "mc": "SamBuilds", "kind": "bug", "version": "3.0.0", "ts": time.time(),
         "text": "The server won't start on my laptop, here is a photo.", "images": [online.encode_photo(str(out / "_icon.png"))]},
        {"name": "Mia", "mc": "MiaMines", "kind": "idea", "version": "3.0.0", "ts": time.time() - 7200,
         "text": "Please add a plugin for pets!", "images": []}]
    paid.is_paid = lambda: True  # the tour shows everything (1200 players needs Paid)
    paid.max_players = lambda: core.MAX_PLAYERS
    root = tk.Tk()
    w = Wizard(root)
    root.update_idletasks()
    root.minsize(max(660, root.winfo_reqwidth()), max(560, root.winfo_reqheight()))
    root.geometry("+40+40")
    shots: list[str] = []

    def grab(name, win=None):
        win = win or root
        root.update()
        win.lift()
        win.attributes("-topmost", True)
        root.update()
        from PIL import ImageGrab
        x, y = win.winfo_rootx(), win.winfo_rooty()
        bw, bh = win.winfo_width(), win.winfo_height()
        try:
            kw = {"all_screens": True} if core.WINDOWS else {}
            ImageGrab.grab(bbox=(x, y, x + bw, y + bh), **kw).save(out / f"{name}.png")
            shots.append(name)
        except Exception as e:
            print("screenshot failed:", e)

    icon = out / "_icon.png"
    from PIL import Image, ImageDraw
    im = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    d.ellipse((4, 4, 60, 60), fill=(20, 20, 20, 255))
    d.text((24, 22), "G", fill=(255, 255, 255, 255))
    im.save(icon)
    dialogs = {}

    def pick(name):
        w.provider_var.set(name)
        w.provider_changed()

    def fill():
        w.name_var.set("Lucas's SMP")
        w.set_icon(str(icon))
        w.players_var.set(1200)
        w.players_changed(1200, from_spin=True)
        w.check_over_100()
        w.difficulty_var.set("Hard")
        w.eula_var.set(True)
        w.domain = {"enabled": True, "service": "Namecheap", "host": "play", "domain": "lucassmp.net", "password": "x"}
        w.domain_label.config(text="play.lucassmp.net (Namecheap)")
        w.sched = {"enabled": True, "repeat": "Weekly", "time": "18:00", "days": ["Fri", "Sat", "Sun"]}
        from . import schedule
        w.sched_label.config(text=schedule.describe(w.sched))
        w.update_buttons()

    def drawer():
        from . import icondraw
        d = icondraw.open_drawer(root, None, lambda p: None)
        d.set_color("#3A8EE8")
        d.gradient_background()
        d.put_stamp("Sword", 32, 32, 3)
        d.put_stamp("Heart", 52, 12, 1)
        d.redraw()
        dialogs["draw"] = d

    def respack():
        dialogs["draw"].top.destroy()
        dialogs["rp"] = w.respack_dialog()

    def config_tab():
        dialogs["rp"].destroy()
        w.cfg.tweaks = {"pvp": False, "keep_inventory": True, "motd": "&bLucas's &lSMP", "view-distance": 10}
        w.cfg.auto_restart = {"every_hours": 12, "on_crash": True}
        w.fill_config()
        w.show(w.CONFIG)

    def sched_dialog():
        dialogs["s"] = w.schedule_dialog()

    def domain_dialog():
        dialogs["s"].destroy()
        dialogs["d"] = w.domain_dialog()

    def play():
        dialogs["d"].destroy()
        w.collect()
        w.cfg.folder = tempfile.mkdtemp()
        w.cfg.installed_version = w.cfg.version
        w.cfg.addons = ["voicechat", "vanish", "geyser"]
        w.page = w.PLAY
        w.enter_edit_mode()
        w.play_title.config(text=f"{w.cfg.name} is online!")
        w._ready_at = time.time() - 74 * 60
        w.players = []
        # a world and two backups, for the Backups window
        wd = Path(w.cfg.folder) / "world"
        wd.mkdir()
        (wd / "level.dat").write_bytes(b"x" * 2000)
        core.make_backup(w.cfg, log=lambda l: None)
        w.cfg.save()
        recovery.save(w.cfg)
        w.progress.config(value=100)
        w.step_label.config(text="Open Minecraft → Multiplayer → Add Server, and paste the IP.")
        for l in ["[18:02:11 INFO]: Starting minecraft server version 1.21.11",
                  "[18:02:14 INFO]: [Gh0stAC] Gh0st AC 2.0.0 armed (Lite)",
                  "[18:02:15 INFO]: [voicechat] Voice chat server started at port 24454",
                  "[18:02:19 WARN]: Can't keep up! Is the server overloaded?",
                  '[18:02:21 INFO]: Done (9.812s)! For help, type "help"',
                  "[18:03:02 INFO]: Geo joined the game", "[18:04:40 INFO]: Sam joined the game"]:
            w.log(l)
            w._track_players(l)
        for k, v in {"domain": "play.lucassmp.net", "public": "86.45.120.17", "lan": "192.168.1.24", "local": "localhost"}.items():
            w.ip_vars[k].set(v)
        w.port_label.config(text="✔ Namecheap now points play.lucassmp.net at 86.45.120.17.\n"
                                 "✔ Opened on your router automatically: 25565 TCP (Minecraft), 24454 UDP (Voice chat), "
                                 "19132 UDP (Bedrock players (Geyser)). Friends anywhere can join.\n"
                                 "If Windows asks about the firewall, click Allow.", style="Ok.TLabel")
        w.show_ip_box()
        w.startstop_btn.config(state="normal")

    def last_top():
        return [c for c in root.winfo_children() if isinstance(c, tk.Toplevel)][-1]

    def hosted_tab():
        import base64
        w._fill_hosted([{"name": "Lucas's SMP", "owner": "Lucas", "mc": "DarkerDawnFR", "kind": "Paper", "version": "1.21.11",
                         "players": 20, "icon": base64.b64encode(icon.read_bytes()).decode()},
                        {"name": "Chill Survival", "owner": "Sam", "mc": "SamBuilds", "kind": "Fabric", "version": "1.21.11", "players": 8}])
        w.nb.select(w.HOSTED)

    def sharing_side():
        w.nb.select(w.page)
        w.side_var.set("sharing")
        w.show_side()
        w._set_posts_text("", [
            {"title": "Come join Lucas's SMP!", "name": "Lucas", "mc": "DarkerDawnFR", "server": "Lucas's SMP", "ts": time.time(),
             "body": "Survival with shops, ranks, crates and fishing. Whitelist is open."},
            {"title": "Best plugins for a small server?", "name": "Sam", "mc": "SamBuilds", "server": "", "ts": time.time() - 90000,
             "body": "I only have 4 friends online. What should I add first?"}])

    def hosting_side():
        w.side_var.set("hosting")
        w.show_side()

    steps = [
        lambda: grab("1-server-paper"),
        lambda: pick("Bedrock"),
        lambda: grab("1-server-bedrock"),
        lambda: w.show(w.SETTINGS),
        lambda: grab("3-settings-bedrock"),
        lambda: pick("Paper"),
        lambda: w.next() if w.page == w.SERVER else w.show(w.SERVER),
        lambda: w.show(w.MINECRAFT),
        lambda: grab("2-minecraft"),
        lambda: w.show(w.SETTINGS),
        fill,
        lambda: grab("3-settings-filled"),
        sched_dialog,
        lambda: grab("3-schedule", dialogs["s"]),
        domain_dialog,
        lambda: grab("3-custom-ip", dialogs["d"]),
        play,
        lambda: grab("4-play"),
        w.backups_dialog,
        lambda: grab("4-backups", last_top()),
        lambda: last_top().destroy(),
        lambda: w.show(w.ADDONS),
        lambda: grab("5-plugins"),
        lambda: w.show(w.SETTINGS),
        lambda: grab("3-settings-edit"),
        drawer,
        lambda: grab("3-icon-drawer", dialogs["draw"].top),
        respack,
        lambda: grab("3-resource-pack", dialogs["rp"]),
        config_tab,
        lambda: grab("6-config"),
        lambda: (w.exp_text.insert("1.0", "say Welcome to Lucas's SMP!\ntime set day\n# a note\n"), w.show(w.EXPERIMENTAL)),
        lambda: grab("7-experimental"),
        lambda: (w.st_on.set(True), w.st_gb.set("20"), w.nb.select(w.STORAGE), w.st_bar.config(value=82),
                 w.st_label.config(text="Using 16.4 GB of 20.00 GB (82%)")),
        lambda: grab("9-storage"),
        hosted_tab,
        lambda: grab("8-hosted"),
        sharing_side,
        lambda: grab("9-sharing"),
        hosting_side,
        w.feedback_dialog,
        lambda: grab("10-feedback", last_top()),
        lambda: last_top().destroy(),
        w.update_logs,
        lambda: grab("11-update-logs", last_top()),
        lambda: last_top().destroy(),
        w.inbox_dialog,
        lambda: grab("13-received-feedback", last_top()),
        lambda: last_top().destroy(),
        w.first_run,
        lambda: grab("12-welcome", w._first),
        lambda: w._first.destroy(),
        w.settings_dialog,
        lambda: grab("16-settings", last_top()),
        lambda: last_top().destroy(),
        lambda: (setattr(paid, "is_paid", lambda: False), w.paid_dialog()),
        lambda: grab("17-paid-version", last_top()),
        lambda: (last_top().destroy(), setattr(paid, "is_paid", lambda: True)),
        w.recovery_dialog,
        lambda: grab("18-server-recovery", last_top()),
        lambda: last_top().destroy(),
    ]

    def go(i=0):
        if i < len(steps):
            try:
                steps[i]()
            except Exception as e:
                import traceback
                traceback.print_exc()
                print("step", i, "failed:", e)
            root.after(700, go, i + 1)
        else:
            root.destroy()
    root.after(800, go)
    root.mainloop()

    # the other themes and a language, each in a fresh window (they apply when MC Hoster opens)
    before = profile.load()
    for theme, lang in (("modern", "en"), ("midnight", "fr")):
        profile.save(theme=theme, lang=lang)
        root = tk.Tk()
        w = Wizard(root)
        root.update_idletasks()
        root.minsize(max(660, root.winfo_reqwidth()), max(560, root.winfo_reqheight()))
        root.geometry("+40+40")
        root.after(1200, lambda t=theme: grab(f"19-theme-{t}"))
        root.after(1700, root.destroy)
        root.mainloop()
    profile.save(theme=before.get("theme", "classic"), lang=before.get("lang", "en"))
    print("screenshots:", ", ".join(shots))
    want = 27
    return 0 if len(shots) == want else 1
