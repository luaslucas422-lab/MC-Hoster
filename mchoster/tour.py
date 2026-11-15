"""Opens the real window, clicks through every tab with sample data and saves a screenshot of each.

No network: version lists and the server are faked, so it only tests the window itself.
"""
from __future__ import annotations

import tempfile
import tkinter as tk
from pathlib import Path

from . import core
from .app import Wizard


def run(outdir: str) -> int:
    out = Path(outdir)
    out.mkdir(parents=True, exist_ok=True)
    core.list_versions = lambda p: (["1.21.124.2"] if p.bedrock else list(core.GH0ST_FABRIC_VERSIONS)
                                    if p.kind == "fabric" else core.FALLBACK_VERSIONS[:])
    from tkinter import messagebox
    messagebox.askyesno = lambda *a, **k: True  # the "more than 100 players" questions
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
        w.progress.config(value=100)
        w.step_label.config(text="Open Minecraft → Multiplayer → Add Server, and paste the IP.")
        for l in ["[18:02:11 INFO]: Starting minecraft server version 1.21.11",
                  "[18:02:14 INFO]: [Gh0stAC] Gh0st AC 2.0.0 armed (Lite)",
                  "[18:02:15 INFO]: [voicechat] Voice chat server started at port 24454",
                  "[18:02:19 WARN]: Can't keep up! Is the server overloaded?",
                  '[18:02:21 INFO]: Done (9.812s)! For help, type "help"']:
            w.log(l)
        for k, v in {"domain": "play.lucassmp.net", "public": "86.45.120.17", "lan": "192.168.1.24", "local": "localhost"}.items():
            w.ip_vars[k].set(v)
        w.port_label.config(text="✔ Namecheap now points play.lucassmp.net at 86.45.120.17.\n"
                                 "✔ Opened on your router automatically: 25565 TCP (Minecraft), 24454 UDP (Voice chat), "
                                 "19132 UDP (Bedrock players (Geyser)). Friends anywhere can join.\n"
                                 "If Windows asks about the firewall, click Allow.", style="Ok.TLabel")
        w.show_ip_box()
        w.startstop_btn.config(state="normal")

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
        lambda: w.show(w.ADDONS),
        lambda: grab("5-plugins"),
        lambda: w.show(w.SETTINGS),
        lambda: grab("3-settings-edit"),
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
    print("screenshots:", ", ".join(shots))
    want = 10
    return 0 if len(shots) == want else 1
