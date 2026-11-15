"""Opens the real window, clicks through every tab with sample data and saves a screenshot of each.

No network: version lists and the server are faked, so it only tests the window itself.
"""
from __future__ import annotations

import tkinter as tk
from pathlib import Path

from . import core
from .app import Wizard


def run(outdir: str) -> int:
    out = Path(outdir)
    out.mkdir(parents=True, exist_ok=True)
    core.list_versions = lambda p: list(core.GH0ST_FABRIC_VERSIONS) if p.kind == "fabric" else core.FALLBACK_VERSIONS[:]
    root = tk.Tk()
    w = Wizard(root)
    root.update_idletasks()
    root.minsize(max(600, root.winfo_reqwidth()), max(470, root.winfo_reqheight()))
    root.geometry("+40+40")
    shots: list[str] = []

    def grab(name):
        root.update()
        root.lift()
        root.attributes("-topmost", True)
        root.update()
        from PIL import ImageGrab
        x, y = root.winfo_rootx(), root.winfo_rooty()
        bw, bh = root.winfo_width(), root.winfo_height()
        try:
            img = ImageGrab.grab(bbox=(x, y, x + bw, y + bh), all_screens=True) if core.WINDOWS else ImageGrab.grab(bbox=(x, y, x + bw, y + bh))
            img.save(out / f"{name}.png")
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

    steps = []
    steps.append(lambda: grab("1-server-paper"))
    steps.append(lambda: (w.provider_var.set("Fabric"), w.load_versions()))
    steps.append(lambda: grab("1-server-fabric"))
    steps.append(lambda: (w.provider_var.set("Paper"), w.load_versions()))
    steps.append(lambda: w.next())
    steps.append(lambda: grab("2-minecraft"))
    steps.append(lambda: w.next())
    steps.append(lambda: grab("3-settings-empty"))

    def fill():
        w.name_var.set("Lucas's SMP")
        w.cfg.icon_path = str(icon)
        w.icon_img = tk.PhotoImage(file=str(icon))
        w.icon_preview.config(image=w.icon_img, text="", width=64, height=64)
        w.icon_status.config(text="✔ server-icon.png", style="Ok.TLabel")
        w.players_scale.set(45)
        w.gamemode_var.set("Survival")
        w.difficulty_var.set("Hard")
        w.eula_var.set(True)
        w.update_buttons()
    steps.append(fill)
    steps.append(lambda: grab("3-settings-filled"))

    def play():
        w.collect()
        w.show(3)
        w.play_title.config(text=f"{w.cfg.name} is online!")
        w.progress.config(value=100)
        w.step_label.config(text="Open Minecraft → Multiplayer → Add Server, and paste the IP.")
        for l in ["[18:02:11 INFO]: Starting minecraft server version 1.21.11",
                  "[18:02:14 INFO]: [Gh0stAC] Gh0st AC 2.0.0 armed (Lite)",
                  "[18:02:19 WARN]: Can't keep up! Is the server overloaded?",
                  '[18:02:21 INFO]: Done (9.812s)! For help, type "help"']:
            w.log(l)
        w.ip_vars["public"].set("86.45.120.17")
        w.ip_vars["lan"].set("192.168.1.24")
        w.ip_vars["local"].set("localhost")
        w.port_label.config(text="✔ Port 25565 was opened on your router automatically, so friends anywhere can join "
                                 "with the Friends IP.\nIf Windows asks about Java and the firewall, click Allow.", style="Ok.TLabel")
        w.ip_box.grid(row=3, column=0, sticky="we", pady=(8, 0))
        w.startstop_btn.config(state="normal")
    steps.append(play)
    steps.append(lambda: grab("4-play"))

    def go(i=0):
        if i < len(steps):
            steps[i]()
            root.after(700, go, i + 1)
        else:
            root.destroy()
    root.after(800, go)
    root.mainloop()
    print("screenshots:", ", ".join(shots))
    return 0 if len(shots) == 6 else 1
