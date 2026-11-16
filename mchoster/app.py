"""MC Hoster window: a setup wizard in the style of the Fabric Installer.

Tabs: 1 Server, 2 Minecraft, 3 Settings, 4 Play, 5 Plugins & Mods.
While setting up, Back / Next walk through 1-3 and "Finish" on Settings makes the server.
After that every tab can be clicked: change anything in 1, 2, 3 or 5 and press "Apply & restart".
"""
from __future__ import annotations

import datetime as dt
import os
import queue
import subprocess
import sys
import threading
import time
import tkinter as tk
import webbrowser
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from . import addons, core, domain, icondraw, respack, schedule, updater
from .core import PROVIDERS, PROVIDER_BY_NAME, ServerConfig

GREEN, YELLOW, ORANGE, RED = "#2e9e44", "#c9a400", "#e07b00", "#cc2b2b"
LAG_COLORS = [GREEN, YELLOW, ORANGE, RED]
PAD = 10
MONO = "Consolas" if core.WINDOWS else "Courier"


def open_folder(path: str):
    try:
        if core.WINDOWS:
            os.startfile(path)  # noqa
        elif sys.platform == "darwin":
            subprocess.Popen(["open", path])
        else:
            subprocess.Popen(["xdg-open", path])
    except Exception:
        pass


class Wizard:
    TABS = ["Server", "Minecraft", "Settings", "Play", "Plugins & Mods", "Config"]
    SERVER, MINECRAFT, SETTINGS, PLAY, ADDONS, CONFIG = range(6)

    def __init__(self, root: tk.Tk):
        self.root = root
        self.q: queue.Queue = queue.Queue()
        self.cfg = ServerConfig()
        self.server: core.ServerProcess | None = None
        self.upnp: core.UPnP | None = None
        self.opened_ports: list = []
        self.icon_img = None
        self.page = 0
        self.edit = False          # True once the server exists: all tabs clickable, "Apply & restart"
        self.busy = False          # setting up / applying
        self.confirmed = 0   # how many times they said yes to a big player count
        self.versions_cache: dict[str, list[str]] = {}
        self.mc_checked_for = None
        self.addon_vars: dict[str, tk.BooleanVar] = {}
        self.sched: dict = {}
        self.domain: dict = {}

        root.title(f"{core.APP_NAME} {core.VERSION}")
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self._style()
        self._icon()

        self.outer = ttk.Frame(root, padding=(PAD, PAD, PAD, 0))
        self.outer.pack(fill="both", expand=True)
        self.nb = ttk.Notebook(self.outer)
        self.nb.pack(fill="both", expand=True)
        self.pages = []
        for i, name in enumerate(self.TABS):
            f = ttk.Frame(self.nb, padding=PAD + 4)
            self.nb.add(f, text=f"  {i + 1}. {name}  ")
            self.pages.append(f)
        self.nb.bind("<<NotebookTabChanged>>", self._tab_clicked)

        self.bar = ttk.Frame(root, padding=PAD)
        self.bar.pack(fill="x")
        ttk.Label(self.bar, text=f"MC Hoster {core.VERSION} · by {core.AUTHOR}", style="Hint.TLabel").pack(side="left")
        self.next_btn = ttk.Button(self.bar, text="Next", command=self.next, style="Accent.TButton", width=15)
        self.next_btn.pack(side="right")
        self.back_btn = ttk.Button(self.bar, text="Back", command=self.back, width=10)
        self.back_btn.pack(side="right", padx=(0, 6))

        self._page_server(self.pages[0])
        self._page_minecraft(self.pages[1])
        self._page_settings(self.pages[2])
        self._page_play(self.pages[3])
        self._page_addons(self.pages[4])
        self._page_config(self.pages[5])
        self.show(0)
        self.root.after(60, self._drain)
        self.root.after(1000, self._restart_tick)

    # ------------------------------------------------------------------ look
    def _style(self):
        s = ttk.Style(self.root)
        for theme in ("vista", "winnative", "aqua", "clam"):
            if theme in s.theme_names():
                s.theme_use(theme)
                break
        base = ("Segoe UI", 9) if core.WINDOWS else ("Helvetica", 10)
        self.root.option_add("*Font", base)
        from tkinter import font as tkfont
        for n in ("TkDefaultFont", "TkTextFont", "TkMenuFont"):
            try:
                tkfont.nametofont(n).configure(family=base[0], size=base[1])
            except tk.TclError:
                pass
        s.configure("Hint.TLabel", foreground="#6b6b6b")
        s.configure("Title.TLabel", font=(base[0], base[1] + 5, "bold"))
        s.configure("Bold.TCheckbutton", font=(base[0], base[1], "bold"))
        s.configure("Ok.TLabel", foreground=GREEN)
        s.configure("Bad.TLabel", foreground=RED)
        s.configure("Warn.TLabel", foreground=ORANGE)
        s.configure("Link.TLabel", foreground="#1a5fb4", font=(base[0], base[1], "underline"))
        s.configure("Accent.TButton", font=(base[0], base[1], "bold"))
        self.base_font = base

    def _icon(self):
        try:
            p = core.resource("assets", "icon.png")
            if p.exists():
                self._ico = tk.PhotoImage(file=str(p))
                self.root.iconphoto(True, self._ico)
        except Exception:
            pass

    def row(self, parent, r, label, widget, hint=None):
        lbl = ttk.Label(parent, text=label)
        lbl.grid(row=r, column=0, sticky="w", pady=4, padx=(0, 10))
        widget.grid(row=r, column=1, sticky="we", pady=4)
        h = None
        if hint:
            h = ttk.Label(parent, text=hint, style="Hint.TLabel")
            h.grid(row=r, column=2, sticky="w", padx=(8, 0))
        return lbl, h

    def dialog(self, title: str) -> tuple[tk.Toplevel, ttk.Frame]:
        top = tk.Toplevel(self.root)
        top.title(title)
        top.transient(self.root)
        top.resizable(False, False)
        f = ttk.Frame(top, padding=PAD + 4)
        f.pack(fill="both", expand=True)
        return top, f

    # ------------------------------------------------------------------ 1. Server
    def _page_server(self, f):
        f.columnconfigure(1, weight=1)
        self.server_title = ttk.Label(f, text="Host a Minecraft server from your PC", style="Title.TLabel")
        self.server_title.grid(row=0, column=0, columnspan=3, sticky="w")
        self.server_sub = ttk.Label(f, text="Pick what runs your server. Java ones come with Gh0st AC.", style="Hint.TLabel")
        self.server_sub.grid(row=1, column=0, columnspan=3, sticky="w", pady=(0, 10))

        self.provider_var = tk.StringVar(value=PROVIDERS[0].name)
        self.provider_cb = ttk.Combobox(f, textvariable=self.provider_var, values=[p.name for p in PROVIDERS], state="readonly", width=28)
        self.provider_cb.bind("<<ComboboxSelected>>", lambda e: self.provider_changed())
        self.row(f, 2, "Server type:", self.provider_cb)
        self.provider_blurb = ttk.Label(f, text="", style="Hint.TLabel", wraplength=460, justify="left")
        self.provider_blurb.grid(row=3, column=1, columnspan=2, sticky="w", pady=(0, 6))

        self.version_var = tk.StringVar()
        self.version_cb = ttk.Combobox(f, textvariable=self.version_var, state="readonly", width=28)
        self.version_cb.bind("<<ComboboxSelected>>", lambda e: self.version_changed())
        self.row(f, 4, "Minecraft version:", self.version_cb)
        self.version_status = ttk.Label(f, text="", wraplength=460, justify="left")
        self.version_status.grid(row=5, column=1, columnspan=2, sticky="w")

        ttk.Separator(f).grid(row=6, column=0, columnspan=3, sticky="we", pady=14)
        bottom = ttk.Frame(f)
        bottom.grid(row=7, column=0, columnspan=3, sticky="we")
        ttk.Button(bottom, text="My servers…", command=self.my_servers).pack(side="left")
        ttk.Button(bottom, text="Check for updates", command=lambda: self.check_updates(False)).pack(side="left", padx=(8, 0))
        ttk.Label(bottom, text=f"v{core.VERSION}", style="Hint.TLabel").pack(side="left", padx=(8, 0))
        self.new_btn = ttk.Button(bottom, text="New server…", command=self.new_server)
        self.load_versions()

    def provider(self) -> core.Provider:
        return PROVIDER_BY_NAME[self.provider_var.get()]

    def provider_changed(self):
        self.mc_var.set("")  # Java and Bedrock look in different places
        self.load_versions()
        self.settings_for_provider()

    def load_versions(self):
        p = self.provider()
        self.provider_blurb.config(text=p.blurb)
        if p.id in self.versions_cache:
            return self._set_versions(p.id, self.versions_cache[p.id])
        self.version_cb.config(values=[])
        if not self.edit:
            self.version_var.set("")
        self.version_status.config(text="Loading versions…", style="Hint.TLabel")
        self.update_buttons()

        def work():
            try:
                self.q.put(("versions", p.id, core.list_versions(p), None))
            except Exception as e:
                self.q.put(("versions", p.id, [], str(e)))
        threading.Thread(target=work, daemon=True).start()

    def _set_versions(self, pid, vs, err=None):
        if pid != self.provider().id:
            return
        if not vs:
            if self.edit:
                self.version_cb.config(values=[self.cfg.version])
                self.version_var.set(self.cfg.version)
            self.version_status.config(text="Couldn't load versions. Check your internet and pick the type again." +
                                       (f"\n({err})" if err else ""), style="Bad.TLabel")
            return self.update_buttons()
        self.versions_cache[pid] = vs
        if self.edit and core.is_release(self.cfg.installed_version or ""):
            cur = core.vtuple(self.cfg.installed_version)
            vs = [v for v in vs if core.vtuple(v) >= cur]
            if self.cfg.installed_version not in vs:
                vs.append(self.cfg.installed_version)
        self.version_cb.config(values=vs)
        if self.version_var.get() not in vs:
            self.version_var.set(self.cfg.version if self.edit and self.cfg.version in vs else vs[0])
        self.version_changed()

    def version_changed(self):
        p, v = self.provider(), self.version_var.get()
        if not v:
            return
        if self.edit:
            if v != self.cfg.installed_version:
                txt, st = (f"Update available: {self.cfg.installed_version} → {v}. Apply & restart installs it. "
                           "Your world is kept. You can't go back to an older version later."), "Warn.TLabel"
            elif p.bedrock:
                txt, st = f"Bedrock {v}, the newest. Phones and consoles need the same version to join.", "Ok.TLabel"
            else:
                txt, st = f"Running {p.name} {v}. Only newer versions are listed (older ones would break the world).", "Hint.TLabel"
            self.version_status.config(text=txt, style=st)
        elif p.bedrock:
            self.version_status.config(text=f"Bedrock {v}, the newest from Mojang. Players need the same version.", style="Ok.TLabel")
        elif core.gh0st_supported(p, v):
            self.version_status.config(text=f"✔ Gh0st AC works on {p.name} {v}   ·   needs Java {core.required_java(v)} "
                                            "(found or downloaded for you)", style="Ok.TLabel")
        else:
            self.version_status.config(text=f"Gh0st AC has no build for {p.name} {v}", style="Warn.TLabel")
        self.update_buttons()

    # ------------------------------------------------------------------ 2. Minecraft folder
    def _page_minecraft(self, f):
        f.columnconfigure(1, weight=1)
        self.mc_title = ttk.Label(f, text="Your Minecraft folder", style="Title.TLabel")
        self.mc_title.grid(row=0, column=0, columnspan=3, sticky="w")
        self.mc_sub = ttk.Label(f, text="", style="Hint.TLabel", justify="left", wraplength=480)
        self.mc_sub.grid(row=1, column=0, columnspan=3, sticky="w", pady=(0, 12))
        self.mc_var = tk.StringVar()
        e = ttk.Entry(f, textvariable=self.mc_var)
        e.bind("<KeyRelease>", lambda ev: self.check_mc())
        ttk.Label(f, text="Folder:").grid(row=2, column=0, sticky="w", padx=(0, 10))
        e.grid(row=2, column=1, sticky="we")
        ttk.Button(f, text="…", width=3, command=self.browse_mc).grid(row=2, column=2, padx=(6, 0))
        self.mc_status = ttk.Label(f, text="", wraplength=480, justify="left")
        self.mc_status.grid(row=3, column=0, columnspan=3, sticky="w", pady=(10, 0))
        self.add_list_var = tk.BooleanVar(value=True)
        self.add_list_cb = ttk.Checkbutton(f, text="Add the server to my server list", variable=self.add_list_var)
        self.add_list_cb.grid(row=4, column=0, columnspan=3, sticky="w", pady=(12, 0))

    def find_mc(self):
        p = self.provider()
        if p.bedrock:
            self.mc_title.config(text="Your Minecraft Bedrock folder")
            self.mc_sub.config(text="MC Hoster puts your new server in the Servers tab of Minecraft Bedrock on this PC.")
            found = core.find_bedrock_dir()
            self.mc_var.set(str(found) if found else str(core.default_bedrock_dir()))
        else:
            self.mc_title.config(text="Your Minecraft folder")
            self.mc_sub.config(text="MC Hoster puts your new server in the Multiplayer list, and can reuse the Java "
                                    "the Minecraft Launcher already downloaded, so there is less to download.")
            found = core.find_minecraft_dir()
            self.mc_var.set(str(found) if found else str(core.default_minecraft_dir()))
        self.mc_checked_for = p.bedrock
        self.check_mc(searched=True)

    def mc_ok(self, p: Path) -> bool:
        return core.looks_like_bedrock_dir(p) if self.provider().bedrock else core.looks_like_minecraft_dir(p)

    def check_mc(self, searched=False):
        p = Path(self.mc_var.get().strip().strip('"'))
        bed = self.provider().bedrock
        if self.mc_ok(p):
            self.mc_status.config(text="✔ Found it!" + ("" if self.edit else " Click Next."), style="Ok.TLabel")
            self.add_list_cb.state(["!disabled"])
        else:
            where = ("Bedrock keeps it in %APPDATA%\\Minecraft Bedrock\\Users\\…\\games\\com.mojang\\minecraftpe" if bed
                     else "it is usually %APPDATA%\\.minecraft")
            self.mc_status.config(text=("✖ Couldn't find it." if searched else "✖ That is not the right folder.") +
                                  f" Pick it with … ({where}), or click Next to skip. Then you add the server in Minecraft by hand.",
                                  style="Bad.TLabel")
            self.add_list_cb.state(["disabled"])

    def browse_mc(self):
        d = filedialog.askdirectory(title="Pick your Minecraft folder", initialdir=self.mc_var.get() or str(Path.home()))
        if d:
            self.mc_var.set(d)
            self.check_mc()

    # ------------------------------------------------------------------ 3. Settings
    def _page_settings(self, f):
        f.columnconfigure(1, weight=1)
        ttk.Label(f, text="Server settings", style="Title.TLabel").grid(row=0, column=0, columnspan=3, sticky="w", pady=(0, 6))

        self.name_var = tk.StringVar(value="My Server")
        self.name_var.trace_add("write", lambda *a: self.update_buttons())
        self.row(f, 1, "Name:", ttk.Entry(f, textvariable=self.name_var, width=30), "Shown in the server list")

        icon_row = ttk.Frame(f)
        self.icon_preview = tk.Label(icon_row, width=8, height=4, relief="groove", text="no icon", fg="#888", bg="#f3f3f3")
        self.icon_preview.pack(side="left")
        self.icon_btn = ttk.Button(icon_row, text="Choose PNG…", command=self.pick_icon)
        self.icon_btn.pack(side="left", padx=8)
        self.icon_draw = ttk.Button(icon_row, text="Draw…", command=self.draw_icon)
        self.icon_draw.pack(side="left", padx=(0, 8))
        self.icon_rm = ttk.Button(icon_row, text="Remove", command=self.clear_icon)
        self.icon_rm.pack(side="left")
        _, self.icon_hint = self.row(f, 2, "Icon:", icon_row, "PNG, 64×64")
        self.icon_status = ttk.Label(f, text="", style="Hint.TLabel")
        self.icon_status.grid(row=3, column=1, columnspan=2, sticky="w")

        pl = ttk.Frame(f)
        self.players_var = tk.IntVar(value=10)
        self.players_scale = ttk.Scale(pl, from_=1, to=core.MAX_PLAYERS, orient="horizontal", length=250,
                                       command=lambda v: self.players_changed(int(float(v))))
        self.players_scale.pack(side="left")
        self.players_scale.bind("<ButtonRelease-1>", lambda e: self.check_over_100())
        self.players_spin = ttk.Spinbox(pl, from_=1, to=core.MAX_PLAYERS, width=5, textvariable=self.players_var,
                                        command=self._spin_arrow)
        self.players_spin.bind("<Return>", lambda e: self._spin_typed(True))
        self.players_spin.bind("<FocusOut>", lambda e: self._spin_typed(True))
        self.players_spin.bind("<KeyRelease>", lambda e: self._spin_typed(False))
        self.players_spin.pack(side="left", padx=8)
        self.row(f, 4, "Max players:", pl, f"up to {core.MAX_PLAYERS}")
        lag = ttk.Frame(f)
        self.lag_canvas = tk.Canvas(lag, width=120, height=12, highlightthickness=0, bg="#e4e4e4")
        self.lag_canvas.pack(side="left")
        self.lag_label = ttk.Label(lag, text="")
        self.lag_label.pack(side="left", padx=8)
        lag.grid(row=5, column=1, columnspan=2, sticky="w", pady=(0, 4))
        self.players_scale.set(10)

        self.gamemode_var = tk.StringVar(value="Survival")
        self.gamemode_cb = ttk.Combobox(f, textvariable=self.gamemode_var, values=core.GAMEMODES, state="readonly", width=18)
        self.row(f, 6, "Game mode:", self.gamemode_cb)
        diff = ttk.Frame(f)
        self.difficulty_var = tk.StringVar(value="Normal")
        ttk.Combobox(diff, textvariable=self.difficulty_var, values=core.DIFFICULTIES, state="readonly", width=18).pack(side="left")
        self.hardcore_var = tk.BooleanVar(value=False)
        self.hardcore_cb = ttk.Checkbutton(diff, text="Hardcore (one life)", variable=self.hardcore_var, command=self._hardcore)
        self.hardcore_cb.pack(side="left", padx=10)
        self.row(f, 7, "Difficulty:", diff)
        dims = ttk.Frame(f)
        self.nether_var = tk.BooleanVar(value=True)
        self.end_var = tk.BooleanVar(value=True)
        self.nether_cb = ttk.Checkbutton(dims, text="Nether open", variable=self.nether_var)
        self.nether_cb.pack(side="left")
        self.end_cb = ttk.Checkbutton(dims, text="The End open", variable=self.end_var)
        self.end_cb.pack(side="left", padx=10)
        self.dims_hint = ttk.Label(dims, text="", style="Hint.TLabel")
        self.dims_hint.pack(side="left")
        self.row(f, 8, "Dimensions:", dims)
        self.owner_var = tk.StringVar(value="")
        _, self.owner_hint = self.row(f, 9, "Your name:", ttk.Entry(f, textvariable=self.owner_var, width=30),
                                      "Your Minecraft name: you get op and OWNER")

        sch = ttk.Frame(f)
        self.sched_label = ttk.Label(sch, text="Off")
        self.sched_label.pack(side="left")
        ttk.Button(sch, text="Schedule…", command=self.schedule_dialog, width=12).pack(side="right")
        self.row(f, 10, "Auto-start:", sch)
        dom = ttk.Frame(f)
        self.domain_label = ttk.Label(dom, text=domain.describe({}))
        self.domain_label.pack(side="left")
        ttk.Button(dom, text="Set up…", command=self.domain_dialog, width=12).pack(side="right")
        self.row(f, 11, "Custom IP:", dom)
        rp = ttk.Frame(f)
        self.rp = {}
        self.rp_label = ttk.Label(rp, text="None")
        self.rp_label.pack(side="left")
        self.rp_btn = ttk.Button(rp, text="Choose…", command=self.respack_dialog, width=12)
        self.rp_btn.pack(side="right")
        self.row(f, 12, "Resource pack:", rp)

        ttk.Separator(f).grid(row=13, column=0, columnspan=3, sticky="we", pady=8)
        self.gh0st_var = tk.BooleanVar(value=True)
        self.gh0st_cb = ttk.Checkbutton(f, text="Install Gh0st AC anticheat on the server (free version)", variable=self.gh0st_var)
        self.gh0st_cb.grid(row=14, column=0, columnspan=3, sticky="w")
        self.eula_row = ttk.Frame(f)
        self.eula_var = tk.BooleanVar(value=False)
        self.eula_cb = ttk.Checkbutton(self.eula_row, text="I agree to the", variable=self.eula_var, command=self.update_buttons)
        self.eula_cb.pack(side="left")
        link = ttk.Label(self.eula_row, text="Minecraft EULA", style="Link.TLabel", cursor="hand2")
        link.pack(side="left")
        link.bind("<Button-1>", lambda e: webbrowser.open("https://aka.ms/MinecraftEULA"))
        ttk.Label(self.eula_row, text="(needed to run any server)", style="Hint.TLabel").pack(side="left", padx=6)
        self.eula_row.grid(row=15, column=0, columnspan=3, sticky="w", pady=(4, 0))
        self.players_changed(10)

    def settings_for_provider(self):
        """Bedrock has no icon, hardcore, spectator or Gh0st AC."""
        p, v = self.provider(), self.version_var.get()
        bed = p.bedrock
        for w in (self.icon_btn, self.icon_rm, self.icon_draw, self.rp_btn):
            w.state(["disabled"] if bed else ["!disabled"])
        self.icon_hint.config(text="Bedrock has no server icons" if bed else "PNG, 64×64")
        self.hardcore_cb.state(["disabled"] if bed else ["!disabled"])
        if bed:
            self.hardcore_var.set(False)
        fabric = p.kind == "fabric"
        self.nether_cb.state(["disabled"] if bed else ["!disabled"])
        self.end_cb.state(["disabled"] if bed or fabric else ["!disabled"])
        if fabric:
            self.end_var.set(True)
        self.dims_hint.config(text="Bedrock can't close them" if bed else "Fabric can't close the End" if fabric else "")
        modes = core.BEDROCK_GAMEMODES if bed else core.GAMEMODES
        self.gamemode_cb.config(values=modes)
        if self.gamemode_var.get() not in modes:
            self.gamemode_var.set("Survival")
        if core.gh0st_supported(p, v):
            if self.gh0st_cb.instate(["disabled"]) and not self.edit:
                self.gh0st_var.set(True)  # back from a type without Gh0st AC
            self.gh0st_cb.state(["!disabled"])
            self.gh0st_cb.config(text="Install Gh0st AC anticheat on the server (free version)")
        else:
            self.gh0st_var.set(False)
            self.gh0st_cb.state(["disabled"])
            self.gh0st_cb.config(text="Gh0st AC is for Java servers only" if bed else f"Gh0st AC (no build for {p.name} {v})")
        self.players_changed(self.players_var.get())

    def _hardcore(self):
        if self.hardcore_var.get():
            self.difficulty_var.set("Hard")

    def _spin_arrow(self):
        self.players_changed(self.players_var.get(), from_spin=True)
        if self.players_var.get() > 100:
            self.check_over_100()

    def _spin_typed(self, commit: bool):
        try:
            n = int(self.players_spin.get())
        except ValueError:
            return
        self.players_changed(n, from_spin=True)
        if commit:
            if n > core.MAX_PLAYERS:
                self.players_var.set(core.MAX_PLAYERS)
            self.check_over_100()

    def players_changed(self, n, from_spin=False):
        n = max(1, min(core.MAX_PLAYERS, int(n)))
        if not hasattr(self, "lag_canvas"):
            return
        if not from_spin:
            self.players_var.set(n)
        else:
            self.players_scale.set(n)
        lvl, text = core.lag_level(n)
        ram = core.ram_for_players(n, self.provider().kind == "fabric")
        c = self.lag_canvas
        c.delete("all")
        c.create_rectangle(0, 0, 120 * (lvl + 1) / 4, 12, fill=LAG_COLORS[lvl], width=0)
        extra = "" if self.provider().bedrock else f"  ({ram / 1024:.1f} GB RAM)"
        self.lag_label.config(text=text + extra)

    def players_value(self) -> int:
        try:
            return max(1, min(core.MAX_PLAYERS, int(self.players_spin.get())))
        except ValueError:
            return self.players_var.get()

    def check_over_100(self) -> bool:
        """Over 100 players asks 2 times, over 500 asks 5 times, over 1000 asks 7 times.
        Saying no puts it back to the biggest number already confirmed."""
        n = self.players_value()
        need = core.confirmations_needed(n)
        if need <= self.confirmed:
            return True
        modded = self.provider().kind == "fabric"
        ram = core.ram_for_players(n, modded) / 1024
        ramtxt = "" if self.provider().bedrock else f" and gives the server {ram:.1f} GB of RAM"
        titles = ["More than 100 players?", "Are you sure?", "Really sure?", "Think about it...",
                  "Your PC may struggle", "Are you REALLY sure?", "Last chance"]
        texts = [
            f"Do you really want {n} players?\n\nMore than 100 players makes most PCs lag.",
            f"{n} players needs a strong PC and fast internet{ramtxt}.\n\nKeep {n} players?",
            f"Over 500 players is huge. Most home internet can't handle {n} players.\n\nStill want {n}?",
            f"Players will be kicked by lag and the PC may freeze while you host {n}.\n\nContinue with {n}?",
            f"Fifth check: {n} players. Everything else on this PC will get slow.\n\nKeep {n} players?",
            f"Over 1000 players is almost impossible from a home PC.\n\nDo you REALLY want {n}?",
            f"Final check: {n} players. This is your last chance to go back.\n\nKeep {n} players?",
        ]
        for i in range(need):
            if not messagebox.askyesno(f"{titles[i]} ({i + 1}/{need})", texts[i],
                                       icon="warning" if i else "question", parent=self.root):
                cap = core.confirmed_cap(i)
                cap = min(cap, 100) if i < 2 else cap
                self.players_var.set(cap)
                self.players_changed(cap, from_spin=True)
                return False
        self.confirmed = need
        return True

    def pick_icon(self):
        p = filedialog.askopenfilename(title="Server icon (64×64 PNG)", filetypes=[("PNG image", "*.png")])
        if not p:
            return
        ok, msg, size = core.check_icon(p)
        if not ok:
            if size and messagebox.askyesno("Icon has to be 64×64", msg + "\n\nShrink it to 64×64 for you?"):
                out = core.app_dir() / f"icon-64-{abs(hash(p)) % 10000}.png"
                core.resize_icon(p, out)
                p = str(out)
            else:
                if not size:
                    messagebox.showerror("Icon", msg)
                return
        self.set_icon(p)

    def set_icon(self, p: str):
        self.cfg.icon_path = p
        try:
            self.icon_img = tk.PhotoImage(file=p)
            self.icon_preview.config(image=self.icon_img, text="", width=64, height=64)
        except Exception:
            pass
        self.icon_status.config(text="✔ " + Path(p).name, style="Ok.TLabel")

    def draw_icon(self):
        icondraw.open_drawer(self.root, self.cfg.icon_path or None, self.set_icon)

    # ------------------------------------------------------------------ resource pack dialog
    def respack_dialog(self):
        rp = dict(self.rp or {})
        top, f = self.dialog("Resource pack")
        top.resizable(True, True)
        ttk.Label(f, text="Server resource pack", style="Title.TLabel").grid(row=0, column=0, columnspan=3, sticky="w")
        ttk.Label(f, text="Players get it when they join. Find one on Modrinth or CurseForge, or paste a direct download link.",
                  style="Hint.TLabel", wraplength=520).grid(row=1, column=0, columnspan=3, sticky="w", pady=(0, 8))
        nb = ttk.Notebook(f)
        nb.grid(row=2, column=0, columnspan=3, sticky="nsew")
        # Modrinth search
        mr = ttk.Frame(nb, padding=8)
        nb.add(mr, text="  Modrinth  ")
        q = tk.StringVar()
        ttk.Entry(mr, textvariable=q, width=40).grid(row=0, column=0, sticky="we")
        lb = tk.Listbox(mr, width=70, height=9, activestyle="none")
        lb.grid(row=1, column=0, columnspan=2, sticky="nsew", pady=6)
        hits: list = []

        def search(_e=None):
            lb.delete(0, "end")
            lb.insert("end", "Searching…")

            def work():
                try:
                    res = respack.search_modrinth(q.get().strip() or "faithful")
                except Exception as e:
                    res = e
                self.root.after(0, lambda: show(res))
            threading.Thread(target=work, daemon=True).start()

        def show(res):
            lb.delete(0, "end")
            hits.clear()
            if isinstance(res, Exception):
                lb.insert("end", f"Couldn't search Modrinth: {res}")
                return
            for h in res:
                hits.append(h)
                lb.insert("end", f"{h['title']}  ·  {h['downloads']:,} downloads  ·  {h['desc'][:70]}")
            if not res:
                lb.insert("end", "Nothing found.")
        ttk.Button(mr, text="Search", command=search).grid(row=0, column=1, padx=6)
        top.bind("<Return>", search)
        # CurseForge link
        cf = ttk.Frame(nb, padding=8)
        nb.add(cf, text="  CurseForge  ")
        ttk.Label(cf, text="Open the pack on curseforge.com and paste its page link here:").grid(row=0, column=0, sticky="w")
        cf_var = tk.StringVar()
        ttk.Entry(cf, textvariable=cf_var, width=70).grid(row=1, column=0, sticky="we", pady=6)
        # direct link
        dl = ttk.Frame(nb, padding=8)
        nb.add(dl, text="  Direct link  ")
        ttk.Label(dl, text="A link that downloads the .zip straight away (e.g. a Dropbox link ending in ?dl=1):").grid(row=0, column=0, sticky="w")
        url_var = tk.StringVar(value=rp.get("url", "") if rp.get("source") in (None, "", "Link") else "")
        ttk.Entry(dl, textvariable=url_var, width=70).grid(row=1, column=0, sticky="we", pady=6)

        opts = ttk.Frame(f)
        opts.grid(row=3, column=0, columnspan=3, sticky="we", pady=(8, 0))
        req = tk.BooleanVar(value=rp.get("required", False))
        ttk.Checkbutton(opts, text="Players must use it (they're kicked if they say no)", variable=req).grid(row=0, column=0, columnspan=2, sticky="w")
        ttk.Label(opts, text="Message:").grid(row=1, column=0, sticky="w", pady=4)
        prompt = tk.StringVar(value=rp.get("prompt", "This server uses a resource pack."))
        ttk.Entry(opts, textvariable=prompt, width=55).grid(row=1, column=1, sticky="we", pady=4)
        status = ttk.Label(f, text=("Now: " + respack.describe(rp)) if rp.get("url") else "", style="Hint.TLabel", wraplength=520)
        status.grid(row=4, column=0, columnspan=3, sticky="w", pady=(4, 0))
        btns = ttk.Frame(f)
        btns.grid(row=5, column=0, columnspan=3, sticky="we", pady=(10, 0))

        def done(info):
            info.update({"required": req.get(), "prompt": prompt.get().strip()})
            self.rp = info
            self.rp_label.config(text=respack.describe(info))
            top.destroy()

        def use():
            tab = nb.index("current")
            status.config(text="Getting the pack…", style="Hint.TLabel")
            sel = lb.curselection()
            mcv = self.version_var.get() or self.cfg.version

            def work():
                try:
                    if tab == 0:
                        if not sel or sel[0] >= len(hits):
                            raise LookupError("search, then click a pack in the list")
                        info = respack.modrinth_pack(hits[sel[0]]["id"], mcv)
                    elif tab == 1:
                        info = respack.curseforge_pack(cf_var.get().strip())
                    else:
                        u = url_var.get().strip()
                        if not u.lower().startswith(("http://", "https://")):
                            raise LookupError("paste a link starting with https://")
                        info = {"url": u, "sha1": "", "name": Path(u.split("?")[0]).name or "Custom pack", "source": "Link"}
                    if not info.get("sha1"):
                        info["sha1"] = respack.sha1_of(info["url"])
                    self.root.after(0, lambda: done(info))
                except Exception as e:
                    self.root.after(0, lambda: status.config(text=f"Couldn't use that pack: {e}", style="Bad.TLabel"))
            threading.Thread(target=work, daemon=True).start()

        def remove():
            self.rp = {}
            self.rp_label.config(text="None")
            top.destroy()
        ttk.Button(btns, text="Use this pack", style="Accent.TButton", command=use).pack(side="right")
        ttk.Button(btns, text="Cancel", command=top.destroy).pack(side="right", padx=6)
        ttk.Button(btns, text="No resource pack", command=remove).pack(side="left")
        search()
        return top

    def clear_icon(self):
        self.cfg.icon_path = ""
        self.icon_img = None
        self.icon_preview.config(image="", text="no icon", width=8, height=4)
        self.icon_status.config(text="")

    # ------------------------------------------------------------------ scheduler dialog
    def schedule_dialog(self):
        s = dict(self.sched or {})
        top, f = self.dialog("Server scheduler")
        ttk.Label(f, text="Start the server automatically", style="Title.TLabel").grid(row=0, column=0, columnspan=4, sticky="w")
        ttk.Label(f, text="At that time MC Hoster opens and starts this server.\n"
                          "Your PC has to be on (not asleep) and signed in.", style="Hint.TLabel",
                  justify="left").grid(row=1, column=0, columnspan=4, sticky="w", pady=(0, 10))
        on = tk.BooleanVar(value=bool(s.get("enabled", True)))
        ttk.Checkbutton(f, text="Turn on auto-start", variable=on, style="Bold.TCheckbutton").grid(row=2, column=0, columnspan=4, sticky="w")

        rep = tk.StringVar(value=s.get("repeat", "Daily"))
        ttk.Label(f, text="How often:").grid(row=3, column=0, sticky="w", pady=6)
        rep_cb = ttk.Combobox(f, textvariable=rep, state="readonly", width=24, values=schedule.REPEATS)
        rep_cb.grid(row=3, column=1, columnspan=3, sticky="w")

        now = dt.datetime.now() + dt.timedelta(hours=1)
        hh, mm = (s.get("time") or f"{now:%H}:00").split(":")
        hour, minute = tk.StringVar(value=hh), tk.StringVar(value=mm)
        tf = ttk.Frame(f)
        ttk.Spinbox(tf, from_=0, to=23, width=3, textvariable=hour, format="%02.0f", wrap=True).pack(side="left")
        ttk.Label(tf, text=" : ").pack(side="left")
        ttk.Spinbox(tf, from_=0, to=59, width=3, textvariable=minute, format="%02.0f", wrap=True, increment=5).pack(side="left")
        ttk.Label(tf, text="  (24-hour clock)", style="Hint.TLabel").pack(side="left")
        ttk.Label(f, text="Time:").grid(row=4, column=0, sticky="w", pady=6)
        tf.grid(row=4, column=1, columnspan=3, sticky="w")

        try:
            d0 = dt.date.fromisoformat(s.get("date", ""))
        except ValueError:
            d0 = dt.date.today()
        day, month, year = tk.StringVar(value=str(d0.day)), tk.StringVar(value=str(d0.month)), tk.StringVar(value=str(d0.year))
        df = ttk.Frame(f)
        ttk.Label(df, text="Day").pack(side="left")
        ttk.Spinbox(df, from_=1, to=31, width=3, textvariable=day, wrap=True).pack(side="left", padx=(4, 10))
        ttk.Label(df, text="Month").pack(side="left")
        ttk.Spinbox(df, from_=1, to=12, width=3, textvariable=month, wrap=True).pack(side="left", padx=(4, 10))
        ttk.Label(df, text="Year").pack(side="left")
        ttk.Spinbox(df, from_=d0.year, to=d0.year + 10, width=5, textvariable=year).pack(side="left", padx=4)
        date_lbl = ttk.Label(f, text="Date:")
        days_vars = {d: tk.BooleanVar(value=d in s.get("days", ["Sat", "Sun"])) for d in schedule.DAYS}
        wf = ttk.Frame(f)
        for d in schedule.DAYS:
            ttk.Checkbutton(wf, text=d, variable=days_vars[d]).pack(side="left", padx=(0, 4))
        days_lbl = ttk.Label(f, text="Days:")
        what = ttk.Label(f, text="", style="Ok.TLabel")
        what.grid(row=6, column=0, columnspan=4, sticky="w", pady=(4, 0))
        err = ttk.Label(f, text="", style="Bad.TLabel")
        err.grid(row=7, column=0, columnspan=4, sticky="w")

        def gather() -> dict:
            try:
                date = dt.date(int(year.get()), int(month.get()), int(day.get())).isoformat()
            except ValueError:
                date = "bad"
            try:
                t = f"{int(hour.get()):02d}:{int(minute.get()):02d}"
            except ValueError:
                t = "bad"
            g = {"enabled": on.get(), "repeat": rep.get(), "time": t, "date": date,
                 "days": [d for d in schedule.DAYS if days_vars[d].get()]}
            if s.get("task"):
                g["task"] = s["task"]
            return g

        def refresh(*_):
            r = rep.get()
            for w in (date_lbl, df, days_lbl, wf):
                w.grid_remove()
            if r in ("Once", "Monthly", "Yearly"):
                date_lbl.grid(row=5, column=0, sticky="w", pady=6)
                df.grid(row=5, column=1, columnspan=3, sticky="w")
            elif r == "Weekly":
                days_lbl.grid(row=5, column=0, sticky="w", pady=6)
                wf.grid(row=5, column=1, columnspan=3, sticky="w")
            g = dict(gather(), enabled=True)
            what.config(text=("→ " + schedule.describe(g)) if schedule.validate(g) is None else "")
            err.config(text="" if schedule.supported() else "The scheduler only works on Windows.")
        rep_cb.bind("<<ComboboxSelected>>", refresh)
        for v in (hour, minute, day, month, year, *days_vars.values()):
            v.trace_add("write", refresh)
        refresh()

        btns = ttk.Frame(f)
        btns.grid(row=8, column=0, columnspan=4, sticky="e", pady=(10, 0))

        def ok():
            g = gather()
            e = schedule.validate(g)
            if e:
                err.config(text=e)
                return
            self.sched = g
            self.sched_label.config(text=schedule.describe(g))
            self.update_buttons()
            top.destroy()
        ttk.Button(btns, text="OK", style="Accent.TButton", command=ok).pack(side="right")
        ttk.Button(btns, text="Cancel", command=top.destroy).pack(side="right", padx=6)
        top.grab_set()
        return top

    # ------------------------------------------------------------------ custom IP dialog
    def domain_dialog(self):
        d = dict(self.domain or {})
        top, f = self.dialog("Custom IP")
        f.columnconfigure(1, weight=1)
        ttk.Label(f, text="Your own server address", style="Title.TLabel").grid(row=0, column=0, columnspan=3, sticky="w")
        ttk.Label(f, text="Friends type play.yourdomain.com instead of numbers. Every time the server starts,\n"
                          "MC Hoster tells your domain your PC's current IP, so it keeps working when it changes.",
                  style="Hint.TLabel", justify="left").grid(row=1, column=0, columnspan=3, sticky="w", pady=(0, 10))
        on = tk.BooleanVar(value=bool(d.get("enabled", True)))
        ttk.Checkbutton(f, text="Use a custom IP", variable=on, style="Bold.TCheckbutton").grid(row=2, column=0, columnspan=3, sticky="w")
        svc = tk.StringVar(value=d.get("service", "Namecheap"))
        ttk.Label(f, text="Domain from:").grid(row=3, column=0, sticky="w", pady=5)
        svc_cb = ttk.Combobox(f, textvariable=svc, values=domain.SERVICES, state="readonly", width=20)
        svc_cb.grid(row=3, column=1, sticky="w")
        host, dom, pw = tk.StringVar(value=d.get("host", "play")), tk.StringVar(value=d.get("domain", "")), tk.StringVar(value=d.get("password", ""))
        host_lbl, host_e = ttk.Label(f, text="Host:"), ttk.Entry(f, textvariable=host, width=16)
        host_hint = ttk.Label(f, text="play → play.yourdomain.com, @ for just yourdomain.com", style="Hint.TLabel")
        dom_lbl, dom_e = ttk.Label(f, text="Domain:"), ttk.Entry(f, textvariable=dom, width=30)
        pw_lbl, pw_e = ttk.Label(f, text="Password:"), ttk.Entry(f, textvariable=pw, width=36, show="•")
        show_pw = tk.BooleanVar(value=False)
        pw_show = ttk.Checkbutton(f, text="Show", variable=show_pw, command=lambda: pw_e.config(show="" if show_pw.get() else "•"))
        help_lbl = ttk.Label(f, text="", style="Hint.TLabel", justify="left", wraplength=470)
        result = ttk.Label(f, text="", style="Ok.TLabel", wraplength=470, justify="left")

        def place(*_):
            s = svc.get()
            for w in (host_lbl, host_e, host_hint, dom_lbl, dom_e, pw_lbl, pw_e, pw_show):
                w.grid_remove()
            if s == "Namecheap":
                host_lbl.config(text="Host:")
                host_hint.config(text="play → play.yourdomain.com, @ for just yourdomain.com")
                pw_lbl.config(text="Dynamic DNS password:")
                for w, r, c in ((host_lbl, 4, 0), (host_e, 4, 1), (host_hint, 5, 1), (dom_lbl, 6, 0), (dom_e, 6, 1), (pw_lbl, 7, 0), (pw_e, 7, 1), (pw_show, 7, 2)):
                    w.grid(row=r, column=c, sticky="w", pady=3)
                help_lbl.config(text="On namecheap.com: Domain List → Manage → Advanced DNS. Turn on Dynamic DNS and copy its "
                                     "password here. In Host Records, add an \"A + Dynamic DNS Record\" with the same host.")
            elif s == "DuckDNS":
                host_lbl.config(text="Name:")
                host_hint.config(text="yourname → yourname.duckdns.org (free)")
                pw_lbl.config(text="Token:")
                for w, r, c in ((host_lbl, 4, 0), (host_e, 4, 1), (host_hint, 5, 1), (pw_lbl, 7, 0), (pw_e, 7, 1), (pw_show, 7, 2)):
                    w.grid(row=r, column=c, sticky="w", pady=3)
                help_lbl.config(text="Sign in on duckdns.org, add a sub domain, and copy the token from the top of the page.")
            else:
                dom_lbl.config(text="Address:")
                dom_lbl.grid(row=6, column=0, sticky="w", pady=3)
                dom_e.grid(row=6, column=1, sticky="w", pady=3)
                help_lbl.config(text="At your domain provider, make an A record for this address that points to your public IP "
                                     "(shown in the Play tab). Update it yourself when your IP changes.")
            help_lbl.grid(row=8, column=0, columnspan=3, sticky="w", pady=(6, 0))
            result.config(text="")
        svc_cb.bind("<<ComboboxSelected>>", place)
        place()
        result.grid(row=9, column=0, columnspan=3, sticky="w", pady=(6, 0))

        def gather():
            return {"enabled": on.get(), "service": svc.get(), "host": host.get().strip(), "domain": dom.get().strip(),
                    "password": pw.get().strip()}

        def test():
            g = dict(gather(), enabled=True)
            e = domain.validate(g)
            if e:
                result.config(text=e, style="Bad.TLabel")
                return
            result.config(text="Testing…", style="Hint.TLabel")

            def work():
                ip = core.public_ip()
                ok, msg = domain.update(g, ip) if ip else (False, "Couldn't find your public IP.")
                self.root.after(0, lambda: result.config(text=("✔ " if ok else "✖ ") + msg +
                                                         (" It can take a few minutes to work everywhere." if ok else ""),
                                                         style="Ok.TLabel" if ok else "Bad.TLabel"))
            threading.Thread(target=work, daemon=True).start()

        btns = ttk.Frame(f)
        btns.grid(row=10, column=0, columnspan=3, sticky="we", pady=(10, 0))
        ttk.Button(btns, text="Test now", command=test).pack(side="left")

        def ok():
            g = gather()
            e = domain.validate(g)
            if e:
                result.config(text=e, style="Bad.TLabel")
                return
            self.domain = g
            self.domain_label.config(text=domain.describe(g))
            top.destroy()
        ttk.Button(btns, text="OK", style="Accent.TButton", command=ok).pack(side="right")
        ttk.Button(btns, text="Cancel", command=top.destroy).pack(side="right", padx=6)
        top.grab_set()
        return top

    # ------------------------------------------------------------------ 4. Play
    def _page_play(self, f):
        f.columnconfigure(0, weight=1)
        f.rowconfigure(4, weight=1)
        top = ttk.Frame(f)
        top.grid(row=0, column=0, sticky="we")
        self.play_title = ttk.Label(top, text="Not set up yet", style="Title.TLabel")
        self.play_title.pack(side="left")
        self.progress = ttk.Progressbar(f, mode="determinate", maximum=100)
        self.progress.grid(row=1, column=0, sticky="we", pady=(6, 2))
        self.step_label = ttk.Label(f, text="", style="Hint.TLabel")
        self.step_label.grid(row=2, column=0, sticky="w")

        self.ip_box = ttk.LabelFrame(f, text=" Join with this IP ", padding=8)
        self.ip_box.columnconfigure(1, weight=1)
        self.ip_vars = {}
        self.ip_rows = {}
        rows = [("domain", "Your address:"), ("public", "Friends:"), ("lan", "Same Wi-Fi:"), ("local", "This PC:"), ("port", "Port:")]
        for r, (key, label) in enumerate(rows):
            lbl = ttk.Label(self.ip_box, text=label)
            lbl.grid(row=r, column=0, sticky="w", padx=(0, 8))
            v = tk.StringVar(value="…")
            self.ip_vars[key] = v
            big = key in ("public", "domain")
            e = ttk.Entry(self.ip_box, textvariable=v, state="readonly", font=(MONO, self.base_font[1] + (5 if big else 1), "bold"))
            e.grid(row=r, column=1, sticky="we", pady=2)
            b = ttk.Button(self.ip_box, text="Copy", width=6, command=lambda k=key: self.copy(self.ip_vars[k].get()))
            b.grid(row=r, column=2, padx=(6, 0))
            self.ip_rows[key] = (lbl, e, b)
        self.port_label = ttk.Label(self.ip_box, text="", wraplength=500, justify="left", style="Hint.TLabel")
        self.port_label.grid(row=len(rows), column=0, columnspan=3, sticky="w", pady=(6, 0))

        con = ttk.Frame(f)
        con.grid(row=4, column=0, sticky="nsew", pady=(8, 0))
        con.columnconfigure(0, weight=1)
        con.rowconfigure(0, weight=1)
        self.console = tk.Text(con, height=10, wrap="none", bg="#1e1e1e", fg="#d4d4d4", insertbackground="#fff",
                               font=(MONO, 9), relief="flat", state="disabled")
        sb = ttk.Scrollbar(con, command=self.console.yview)
        self.console.config(yscrollcommand=sb.set)
        self.console.grid(row=0, column=0, sticky="nsew")
        sb.grid(row=0, column=1, sticky="ns")
        self.console.tag_config("warn", foreground="#e5c07b")
        self.console.tag_config("err", foreground="#ff6b6b")
        self.console.tag_config("me", foreground="#7fd1ff")

        cmd = ttk.Frame(f)
        cmd.grid(row=5, column=0, sticky="we", pady=(6, 0))
        cmd.columnconfigure(0, weight=1)
        self.cmd_var = tk.StringVar()
        ce = ttk.Entry(cmd, textvariable=self.cmd_var)
        ce.grid(row=0, column=0, sticky="we")
        ce.bind("<Return>", lambda e: self.send_cmd())
        ttk.Button(cmd, text="Send", command=self.send_cmd).grid(row=0, column=1, padx=(6, 0))
        self.startstop_btn = ttk.Button(cmd, text="Stop server", command=self.start_stop, state="disabled")
        self.startstop_btn.grid(row=0, column=2, padx=(6, 0))
        ttk.Button(cmd, text="Open folder", command=lambda: self.cfg.folder and open_folder(self.cfg.folder)).grid(row=0, column=3, padx=(6, 0))
        ttk.Button(cmd, text="Plugins & Mods →", command=lambda: self.edit and self.show(self.ADDONS)).grid(row=0, column=4, padx=(6, 0))

    def log(self, line: str):
        tag = "err" if ("ERROR" in line or "Exception" in line) else "warn" if ("WARN" in line or line.lstrip().startswith("!")) \
            else "me" if line.startswith("»") else ""
        self.console.config(state="normal")
        self.console.insert("end", line + "\n", tag)
        if int(self.console.index("end-1c").split(".")[0]) > 3000:
            self.console.delete("1.0", "500.0")
        self.console.see("end")
        self.console.config(state="disabled")

    def copy(self, text):
        self.root.clipboard_clear()
        self.root.clipboard_append(text)
        self.step_label.config(text=f"Copied {text}")

    def send_cmd(self):
        c = self.cmd_var.get().strip().lstrip("/")
        if c and self.server and self.server.running:
            self.log("» " + c)
            self.server.send(c)
        self.cmd_var.set("")

    # ------------------------------------------------------------------ 5. Plugins & Mods
    def _page_addons(self, f):
        f.columnconfigure(0, weight=1)
        f.rowconfigure(2, weight=1)
        ttk.Label(f, text="Plugins & Mods", style="Title.TLabel").grid(row=0, column=0, sticky="w")
        self.addons_sub = ttk.Label(f, text="", style="Hint.TLabel", wraplength=520, justify="left")
        self.addons_sub.grid(row=1, column=0, sticky="w", pady=(0, 8))
        box = ttk.Frame(f, relief="sunken", borderwidth=1)
        box.grid(row=2, column=0, sticky="nsew")
        box.columnconfigure(0, weight=1)
        box.rowconfigure(0, weight=1)
        self.addons_canvas = tk.Canvas(box, highlightthickness=0, height=260)
        sb = ttk.Scrollbar(box, command=self.addons_canvas.yview)
        self.addons_canvas.config(yscrollcommand=sb.set)
        self.addons_canvas.grid(row=0, column=0, sticky="nsew")
        sb.grid(row=0, column=1, sticky="ns")
        self.addons_list = ttk.Frame(self.addons_canvas, padding=8)
        win = self.addons_canvas.create_window(0, 0, window=self.addons_list, anchor="nw")
        self.addons_list.bind("<Configure>", lambda e: self.addons_canvas.config(scrollregion=self.addons_canvas.bbox("all")))
        self.addons_canvas.bind("<Configure>", lambda e: self.addons_canvas.itemconfigure(win, width=e.width))
        self.root.bind_all("<MouseWheel>", self._wheel)
        bottom = ttk.Frame(f)
        bottom.grid(row=3, column=0, sticky="we", pady=(8, 0))
        self.addons_status = ttk.Label(bottom, text="", style="Hint.TLabel")
        self.addons_status.pack(side="left")
        self.addons_folder_btn = ttk.Button(bottom, text="Open folder", command=self._open_addon_folder)
        self.addons_folder_btn.pack(side="right")

    def _wheel(self, e):
        if self.page == self.ADDONS:
            self.addons_canvas.yview_scroll(-1 if e.delta > 0 else 1, "units")
        elif self.page == self.CONFIG:
            self.config_canvas.yview_scroll(-1 if e.delta > 0 else 1, "units")

    # ------------------------------------------------------------------ 6. Config
    def _page_config(self, f):
        f.columnconfigure(0, weight=1)
        f.rowconfigure(2, weight=1)
        ttk.Label(f, text="Config", style="Title.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Label(f, text="Everything else about your server. Change anything, then click Apply & restart. "
                          "Things you don't change stay the Minecraft default.", style="Hint.TLabel", wraplength=540,
                  justify="left").grid(row=1, column=0, sticky="w", pady=(0, 8))
        box = ttk.Frame(f, relief="sunken", borderwidth=1)
        box.grid(row=2, column=0, sticky="nsew")
        box.columnconfigure(0, weight=1)
        box.rowconfigure(0, weight=1)
        self.config_canvas = tk.Canvas(box, highlightthickness=0, height=300)
        sb = ttk.Scrollbar(box, command=self.config_canvas.yview)
        self.config_canvas.config(yscrollcommand=sb.set)
        self.config_canvas.grid(row=0, column=0, sticky="nsew")
        sb.grid(row=0, column=1, sticky="ns")
        inner = ttk.Frame(self.config_canvas, padding=8)
        win = self.config_canvas.create_window(0, 0, window=inner, anchor="nw")
        inner.bind("<Configure>", lambda e: self.config_canvas.config(scrollregion=self.config_canvas.bbox("all")))
        self.config_canvas.bind("<Configure>", lambda e: self.config_canvas.itemconfigure(win, width=e.width))
        inner.columnconfigure(0, weight=1)
        self.tweak_vars: dict[str, tk.Variable] = {}
        r = 0

        def section(title):
            nonlocal r
            lf = ttk.LabelFrame(inner, text=f" {title} ", padding=8)
            lf.grid(row=r, column=0, sticky="we", pady=(0, 8))
            lf.columnconfigure(1, weight=1)
            r += 1
            return lf

        def add(parent, row, key, label, kind, default, hint=""):
            if kind == "bool":
                v = tk.BooleanVar(value=bool(default))
                ttk.Checkbutton(parent, text=label, variable=v).grid(row=row, column=0, columnspan=2, sticky="w", pady=2)
            elif kind.startswith("int"):
                _, lo, hi = kind.split(":")
                v = tk.IntVar(value=int(default))
                ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", pady=2)
                ttk.Spinbox(parent, from_=int(lo), to=int(hi), textvariable=v, width=10).grid(row=row, column=1, sticky="w", padx=8)
            elif kind.startswith("choice"):
                v = tk.StringVar(value=str(default))
                ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", pady=2)
                ttk.Combobox(parent, textvariable=v, values=kind.split(":", 1)[1].split("|"), state="readonly", width=16).grid(row=row, column=1, sticky="w", padx=8)
            else:
                v = tk.StringVar(value=str(default))
                ttk.Label(parent, text=label).grid(row=row, column=0, sticky="w", pady=2)
                ttk.Entry(parent, textvariable=v, width=34).grid(row=row, column=1, sticky="we", padx=8)
            if hint:
                ttk.Label(parent, text=hint, style="Hint.TLabel", wraplength=230, justify="left").grid(row=row, column=2, sticky="w")
            self.tweak_vars[key] = v

        world_keys = ["motd", "level-seed", "level-type", "generate-structures", "view-distance", "simulation-distance",
                      "spawn-protection", "max-world-size"]
        lf = section("World")
        i = 0
        for key, label, kind, default, hint in core.PROP_TWEAKS:
            if key in world_keys:
                add(lf, i, key, label, kind, default, hint)
                i += 1
        lf = section("Players")
        i = 0
        for key, label, kind, default, hint in core.PROP_TWEAKS:
            if key not in world_keys:
                add(lf, i, key, label, kind, default, hint)
                i += 1
        add(lf, i, "whitelist_names", "Whitelisted players", "text", "", "Names with commas: Geo, Steve")
        add(lf, i + 1, "ops", "Extra ops (admins)", "text", "", "Names with commas. You're op already (tab 3)")
        lf = section("Game rules")
        for i, (key, label, default, _names) in enumerate(core.GAMERULES):
            add(lf, i, key, label, "int:0:100" if isinstance(default, int) and not isinstance(default, bool) else "bool", default)
        lf = section("Performance")
        add(lf, 0, "ram_mb", "Memory for the server (MB)", "int:0:65536", 0, f"0 = automatic. This PC has {core.total_ram_mb():,} MB")
        add(lf, 1, "aikar", "Smoother memory clean-up (Aikar's flags, best with 4 GB+)", "bool", False)
        lf = section("Auto restart")
        self.ar_hours = tk.IntVar(value=0)
        ttk.Label(lf, text="Restart every (hours)").grid(row=0, column=0, sticky="w", pady=2)
        ttk.Spinbox(lf, from_=0, to=168, textvariable=self.ar_hours, width=10).grid(row=0, column=1, sticky="w", padx=8)
        ttk.Label(lf, text="0 = never. Players get a warning 5 min, 1 min and 10 s before.", style="Hint.TLabel",
                  wraplength=230).grid(row=0, column=2, sticky="w")
        self.ar_crash = tk.BooleanVar(value=True)
        ttk.Checkbutton(lf, text="Start it again if it crashes (up to 3 times in 10 minutes)", variable=self.ar_crash).grid(
            row=1, column=0, columnspan=3, sticky="w", pady=2)
        ttk.Button(inner, text="Reset everything to the defaults", command=self._config_defaults).grid(row=r, column=0, sticky="w")

    def _config_defaults(self):
        if not messagebox.askyesno("Config", "Put every setting on this page back to the default?"):
            return
        self.cfg.tweaks = {}
        self.cfg.auto_restart = {}
        self.fill_config()

    def _tweak_defaults(self) -> dict:
        d = {k: dflt for k, _l, _k, dflt, _h in core.PROP_TWEAKS}
        d.update({k: dflt for k, _l, dflt, _n in core.GAMERULES})
        d.update({"whitelist_names": "", "ops": "", "ram_mb": 0, "aikar": False})
        return d

    def fill_config(self):
        t = self.cfg.tweaks or {}
        for k, dflt in self._tweak_defaults().items():
            v = t.get(k, dflt)
            if isinstance(v, list):
                v = ", ".join(v)
            try:
                self.tweak_vars[k].set(v)
            except Exception:
                pass
        ar = self.cfg.auto_restart or {}
        self.ar_hours.set(int(ar.get("every_hours", 0) or 0))
        self.ar_crash.set(bool(ar.get("on_crash", True)))

    def collect_config(self):
        out = {}
        for k, dflt in self._tweak_defaults().items():
            try:
                v = self.tweak_vars[k].get()
            except Exception:
                continue
            if k in ("whitelist_names", "ops"):
                names = [n.strip() for n in str(v).replace(";", ",").split(",") if n.strip()]
                if names:
                    out[k] = names
                continue
            if isinstance(dflt, bool):
                v = bool(v)
            elif isinstance(dflt, int):
                try:
                    v = int(v)
                except (TypeError, ValueError):
                    v = dflt
            if v != dflt:
                out[k] = v
        self.cfg.tweaks = out
        try:
            hours = max(0, int(self.ar_hours.get()))
        except (TypeError, ValueError, tk.TclError):
            hours = 0
        self.cfg.auto_restart = {"every_hours": hours, "on_crash": bool(self.ar_crash.get())}

    def _open_addon_folder(self):
        if self.cfg.folder and self.cfg.prov.addon_folder:
            p = Path(self.cfg.folder) / self.cfg.prov.addon_folder
            p.mkdir(parents=True, exist_ok=True)
            open_folder(str(p))

    def build_addons(self):
        for w in self.addons_list.winfo_children():
            w.destroy()
        self.addon_vars = {}
        p = self.cfg.prov if self.edit else self.provider()
        what = "mods" if p.kind == "fabric" else "plugins"
        if p.bedrock:
            self.addons_sub.config(text="Bedrock Dedicated Server can't use plugins or mods.\n"
                                        "Want Java and Bedrock players together? Make a Paper server and tick Geyser + Floodgate here.")
            self.addons_status.config(text="")
            self.addons_folder_btn.state(["disabled"])
            return
        self.addons_folder_btn.state(["!disabled"])
        self.addons_sub.config(text=f"Tick the {what} you want, then click Apply & restart. They are downloaded for "
                                    f"{p.name} {self.version_var.get() or self.cfg.version} from Modrinth. "
                                    f"Gh0st AC is in tab 3. Your own {what} go in the {p.addon_folder} folder." +
                                    ("\nOn the very newest version some plugins may not be updated yet: check the console after a restart."
                                     if p.kind == "bukkit" else ""))
        # folders by what they do; folders with something ticked start open
        groups: dict[str, list] = {}
        for a in addons.addons_for(p):
            groups.setdefault(addons.category(a), []).append(a)
        if not hasattr(self, "_open_folders"):
            self._open_folders = {addons.category(a) for a in addons.addons_for(p) if a.id in self.cfg.addons} or {addons.CATEGORIES[0]}
        self._folder_heads = {}
        r = 0
        for cat in addons.CATEGORIES:
            items = groups.get(cat)
            if not items:
                continue
            head = ttk.Button(self.addons_list, style="Toolbutton")
            head.grid(row=r, column=0, sticky="we", pady=(6, 2))
            body = ttk.Frame(self.addons_list, padding=(18, 0, 0, 0))
            body.grid(row=r + 1, column=0, sticky="we")
            r += 2
            for i, a in enumerate(items):
                v = tk.BooleanVar(value=a.id in self.cfg.addons)
                self.addon_vars[a.id] = v
                row = ttk.Frame(body)
                row.grid(row=i, column=0, sticky="we", pady=3)
                ttk.Checkbutton(row, text=a.name, variable=v, style="Bold.TCheckbutton", width=20,
                                command=self._addons_count).pack(side="left", anchor="n")
                txt = ttk.Frame(row)
                txt.pack(side="left", fill="x", expand=True)
                ttk.Label(txt, text=a.desc, wraplength=320, justify="left").pack(anchor="w")
                if a.note:
                    ttk.Label(txt, text=a.note, style="Warn.TLabel").pack(anchor="w")
            self._folder_heads[cat] = (head, body, [a.id for a in items])
            head.config(command=lambda c=cat: self._toggle_folder(c))
            if cat not in self._open_folders:
                body.grid_remove()
        self._addons_count()

    def _toggle_folder(self, cat):
        head, body, _ids = self._folder_heads[cat]
        if cat in self._open_folders:
            self._open_folders.discard(cat)
            body.grid_remove()
        else:
            self._open_folders.add(cat)
            body.grid()
        self._addons_count()

    def _addons_count(self):
        for cat, (head, _body, ids) in getattr(self, "_folder_heads", {}).items():
            ticked = sum(self.addon_vars[i].get() for i in ids if i in self.addon_vars)
            head.config(text=f"{'▼' if cat in self._open_folders else '▶'}  📁 {cat}   ({ticked}/{len(ids)} ticked)")
        n = sum(v.get() for v in self.addon_vars.values())
        installed = len([a for a in self.cfg.addons if a in self.addon_vars])
        self.addons_status.config(text=f"{installed} installed · {n} ticked")

    # ------------------------------------------------------------------ navigation
    def _tab_clicked(self, _e=None):
        try:
            i = self.nb.index("current")
        except tk.TclError:
            return
        if i == self.page:
            return
        if not self.edit or self.busy:
            self.nb.select(self.page)
            return
        self.show(i)

    def show(self, i):
        self.page = i
        for j in range(len(self.pages)):
            enabled = (self.edit and not self.busy) or j == i
            self.nb.tab(j, state="normal" if enabled else "disabled")
        try:
            cur = self.nb.index("current")
        except tk.TclError:
            cur = -1
        if cur != i:
            self.nb.select(i)
        if i == self.MINECRAFT and (not self.mc_var.get() or self.mc_checked_for != self.provider().bedrock):
            self.find_mc()
        if i == self.SETTINGS:
            self.settings_for_provider()
        if i == self.ADDONS:
            self.build_addons()
        self.update_buttons()

    def update_buttons(self):
        i = self.page
        if self.edit:
            self.back_btn.pack_forget()
            if i == self.PLAY:
                self.next_btn.config(text="Close", state="normal")
            else:
                running = self.server and self.server.running
                self.next_btn.config(text="Apply & restart" if running else "Apply & start",
                                     state="disabled" if self.busy or not self.name_var.get().strip() else "normal")
            return
        if not self.back_btn.winfo_ismapped():
            self.back_btn.pack(side="right", padx=(0, 6), after=self.next_btn)
        self.back_btn.config(state="normal" if 0 < i < 3 else "disabled")
        if i == self.SERVER:
            self.next_btn.config(text="Next", state="normal" if self.version_var.get() else "disabled")
        elif i == self.MINECRAFT:
            self.next_btn.config(text="Next", state="normal")
        elif i == self.SETTINGS:
            ok = self.eula_var.get() and self.name_var.get().strip()
            self.next_btn.config(text="Finish", state="normal" if ok else "disabled")
        else:
            self.next_btn.config(text="Close", state="disabled" if self.busy else "normal")

    def back(self):
        if not self.edit and 0 < self.page < 3:
            self.show(self.page - 1)

    def next(self):
        if self.edit:
            return self.on_close() if self.page == self.PLAY else self.apply()
        if self.page in (self.SERVER, self.MINECRAFT):
            self.show(self.page + 1)
        elif self.page == self.SETTINGS:
            self.finish()
        else:
            self.on_close()

    def collect(self):
        c = self.cfg
        c.name = self.name_var.get().strip() or "My Server"
        if not self.edit:
            c.provider = self.provider().id
        c.version = self.version_var.get() or c.version
        c.players = self.players_value()
        c.gamemode = self.gamemode_var.get()
        c.difficulty = self.difficulty_var.get()
        c.hardcore = self.hardcore_var.get()
        c.nether = self.nether_var.get() or c.prov.bedrock
        c.end = self.end_var.get() or c.prov.bedrock or c.prov.kind == "fabric"
        c.gh0stac = self.gh0st_var.get()
        c.owner = self.owner_var.get().strip()
        c.resource_pack = dict(self.rp or {})
        self.collect_config()
        mc = Path(self.mc_var.get().strip().strip('"'))
        c.minecraft_dir = str(mc) if self.mc_var.get().strip() and self.mc_ok(mc) else ""
        c.add_to_list = self.add_list_var.get() and bool(c.minecraft_dir)
        c.eula = self.eula_var.get()
        c.schedule = dict(self.sched or {})
        c.domain = dict(self.domain or {})

    def ready_to_go(self) -> bool:
        if not self.check_over_100():
            return False
        name = self.owner_var.get().strip()
        if name and not core.valid_player_name(name):
            messagebox.showerror("Your name", "That isn't a Minecraft name (3-16 letters, numbers or _).\nChange it in tab 3, or leave it empty.")
            return False
        for what, err in (("Auto-start", self.sched.get("enabled") and schedule.validate(self.sched)),
                          ("Custom IP", self.domain.get("enabled") and domain.validate(self.domain))):
            if err:
                messagebox.showerror(what, f"{err}\nChange it in tab 3.")
                return False
        return True

    def finish(self):
        if not self.ready_to_go():
            return
        self.collect()
        self.busy = True
        self.show(self.PLAY)
        self.play_title.config(text=f"Setting up {self.cfg.name}…")
        p = self.cfg.prov
        self.log(f"» {p.name} {self.cfg.version}, {self.cfg.players} players, {self.cfg.gamemode}, {self.cfg.difficulty}"
                 f"{', hardcore' if self.cfg.hardcore else ''}{', Gh0st AC' if self.cfg.gh0stac else ''}")
        self._work(lambda: None)

    def apply(self):
        if not self.ready_to_go():
            return
        old_name = self.cfg.name
        self.collect()
        wanted = [a for a, v in self.addon_vars.items() if v.get()] if self.addon_vars else list(self.cfg.addons)
        self.busy = True
        self.show(self.PLAY)
        self.ip_box.grid_remove()
        self.play_title.config(text=f"Applying changes to {self.cfg.name}…")
        self.log("» Applying changes")
        running = self.server and self.server.running

        def before():
            if running:
                self.q.put(("log", "» Stopping the server to apply the changes (the world is saved)."))
                self.server.stop(60)
            return wanted
        self._work(before)

    def _work(self, before):
        def work():
            try:
                wanted = before()
                log = lambda l: self.q.put(("log", l))
                if self.edit and self.cfg.folder:
                    core.rename_server_folder(self.cfg, log)  # renamed server, or a folder Java can't use
                core.setup_server(self.cfg, log=log, progress=lambda d, t: self.q.put(("bytes", d, t)),
                                  step=lambda i, n, t: self.q.put(("step", i, n, t)))
                if wanted is not None:
                    failed = addons.sync(self.cfg, wanted, log)
                    if failed:
                        log("! Not installed: " + ", ".join(failed))
                if self.cfg.schedule.get("enabled") or self.cfg.schedule.get("task"):
                    schedule.apply(self.cfg, log)
                self.cfg.save()
                self.q.put(("setup_done",))
            except Exception as e:
                self.q.put(("setup_failed", f"{e.__class__.__name__}: {e}"))
        threading.Thread(target=work, daemon=True).start()

    # ------------------------------------------------------------------ edit mode
    def enter_edit_mode(self):
        """The server exists: fill every tab from it and unlock the tabs."""
        c = self.cfg
        self.edit = True
        self.busy = False
        p = c.prov
        self.provider_var.set(p.name)
        self.provider_cb.state(["disabled"])
        self.server_title.config(text=c.name)
        self.server_sub.config(text=f"{p.name} server. The type can't change: click New server… to make another one.")
        if not self.new_btn.winfo_ismapped():
            self.new_btn.pack(side="left", padx=8)
        self.version_var.set(c.version)
        self.load_versions()
        self.mc_var.set(c.minecraft_dir)
        self.mc_checked_for = p.bedrock
        if c.minecraft_dir:
            self.check_mc()
        self.add_list_var.set(c.add_to_list)
        self.name_var.set(c.name)
        icon = Path(c.folder) / "server-icon.png"
        if icon.exists():
            self.set_icon(str(icon))
        else:
            self.clear_icon()
        self.confirmed = core.confirmations_needed(c.players)
        self.players_var.set(c.players)
        self.players_changed(c.players, from_spin=True)
        self.gamemode_var.set(c.gamemode)
        self.difficulty_var.set(c.difficulty)
        self.hardcore_var.set(c.hardcore)
        self.nether_var.set(c.nether)
        self.end_var.set(c.end)
        self.gh0st_var.set(c.gh0stac)
        self.owner_var.set(c.owner or "")
        self.rp = dict(c.resource_pack or {})
        self.rp_label.config(text=respack.describe(self.rp))
        self.fill_config()
        self.sched = dict(c.schedule or {})
        self.sched_label.config(text=schedule.describe(self.sched))
        self.domain = dict(c.domain or {})
        self.domain_label.config(text=domain.describe(self.domain))
        self.eula_var.set(True)
        self.eula_cb.state(["disabled"])
        self.settings_for_provider()
        self.show(self.page)

    def new_server(self):
        if self.server and self.server.running:
            if not messagebox.askyesno("New server", f"{self.cfg.name} is running. Stop it and make a new server?"):
                return
            self.server.stop(60)
        self._close_ports()
        for w in self.root.winfo_children():
            w.destroy()
        Wizard(self.root)

    def open_existing(self, cfg: ServerConfig):
        self.cfg = cfg
        self.page = self.PLAY
        self.enter_edit_mode()
        self.progress.config(value=100)
        if core.bad_path(Path(cfg.folder).name):
            core.rename_server_folder(cfg, self.log)
            if cfg.schedule.get("enabled") or cfg.schedule.get("task"):
                schedule.apply(cfg, self.log)
        self.log(f"» Opening {cfg.name} ({cfg.prov.name} {cfg.version}) from {cfg.folder}")
        if not cfg.prov.bedrock and (not cfg.java or not Path(cfg.java).exists()
                                     or (core.java_major(cfg.java) or 99) < core.required_java(cfg.version)):
            found = core.find_java(core.required_java(cfg.version), Path(cfg.minecraft_dir) if cfg.minecraft_dir else None,
                                   cfg.provider == "spigot")
            if not found:
                self.log("! The Java this server used is gone. Click Apply & start in tab 3 to get it again.")
                return
            cfg.java = str(found[0])
            cfg.save()
        self.run_server()

    def my_servers(self):
        servers = core.my_servers()
        if not servers:
            messagebox.showinfo("My servers", f"No servers yet. They are saved in\n{core.servers_dir()}")
            return
        top, frm = self.dialog("My servers")
        lb = tk.Listbox(frm, width=70, height=min(10, len(servers)), activestyle="none")
        for s in servers:
            auto = "  ·  auto-start: " + schedule.describe(s.schedule) if s.schedule.get("enabled") else ""
            lb.insert("end", f"{s.name}   ·   {s.prov.name} {s.version}   ·   {s.players} players{auto}")
        lb.pack(fill="both", expand=True)
        lb.selection_set(0)
        btns = ttk.Frame(frm)
        btns.pack(fill="x", pady=(8, 0))

        def go():
            sel = lb.curselection()
            if not sel:
                return
            top.destroy()
            if self.server and self.server.running:
                if not messagebox.askyesno("My servers", f"{self.cfg.name} is running. Stop it first?"):
                    return
                self.server.stop(60)
                self._close_ports()
            self.open_existing(servers[sel[0]])
        ttk.Button(btns, text="Open & start", style="Accent.TButton", command=go).pack(side="right")
        ttk.Button(btns, text="Open folder", command=lambda: lb.curselection() and open_folder(servers[lb.curselection()[0]].folder)).pack(side="right", padx=6)
        lb.bind("<Double-Button-1>", lambda e: go())

    # ------------------------------------------------------------------ running the server
    def free_port_or_ask(self) -> bool:
        """Before starting: is something already using the port? Usually an old server left running. False = don't start."""
        cfg = self.cfg
        pid, prog, mine = core.who_has_port(cfg)
        if not pid and not mine and core.port_free(cfg.port, cfg.prov.bedrock):
            return True
        if mine is not None:
            same = Path(mine.folder) == Path(cfg.folder)
            what = "This server is" if same else f"Your server \"{mine.name}\" is"
            if not messagebox.askyesno("A server is still running",
                                       f"{what} still running in the background (MC Hoster was closed without stopping it), "
                                       f"so it's using port {cfg.port}.\n\nStop it now and start {cfg.name}?", parent=self.root):
                return False
            self.log(f"» Stopping {mine.name}, which was still running in the background.")
            for lo in core.leftovers():
                if lo.pid == pid or Path(lo.cfg.folder) == Path(mine.folder):
                    core.stop_leftover(lo)
            time.sleep(1)
            if core.port_free(cfg.port, cfg.prov.bedrock):
                return True
        if pid and prog and prog.lower() in core.SERVER_EXES:
            # a Minecraft server we don't know, most likely one an older MC Hoster left running
            if messagebox.askyesno("A server is still running",
                                   f"A Minecraft server ({prog}, process {pid}) is still running and using port {cfg.port}. "
                                   "It's probably one an older MC Hoster left running after it was closed.\n\n"
                                   f"Stop it now and start {cfg.name}?", parent=self.root):
                self.log(f"» Stopping the old server (process {pid}).")
                core.kill_tree(pid)
                for _ in range(20):
                    if core.port_free(cfg.port, cfg.prov.bedrock):
                        return True
                    time.sleep(0.5)
        new = core.next_free_port(cfg.port, cfg.prov.bedrock)
        who = f"{prog} (process {pid})" if prog else "another program"
        if not messagebox.askyesno("Port in use", f"Port {cfg.port} is being used by {who}.\n\n"
                                   f"Use port {new} for {cfg.name} instead? The address becomes "
                                   f"{core.server_address(cfg, 'localhost').split(':')[0]}:{new}.", parent=self.root):
            return False
        core.change_port(cfg, new)
        self.log(f"» Port {new} is used from now on, because something else has the old one.")
        threading.Thread(target=lambda: core.add_to_list(cfg, log=lambda l: None), daemon=True).start()
        return True

    # ------------------------------------------------------------------ auto restart
    def _restart_tick(self):
        """Every second: a timed restart (tab 6) with warnings in chat."""
        try:
            hours = int((self.cfg.auto_restart or {}).get("every_hours", 0) or 0)
            at = getattr(self, "_ready_at", None)
            if hours > 0 and at and self.server and self.server.running and not getattr(self, "_restarting", False):
                left = at + hours * 3600 - time.time()
                for secs, text in ((300, "5 minutes"), (60, "1 minute"), (10, "10 seconds")):
                    if left <= secs and secs not in self._warned:
                        self._warned.add(secs)
                        self.server.send(f"say The server restarts in {text}.")
                if left <= 0:
                    self.restart_now("Auto restart")
        finally:
            self.root.after(1000, self._restart_tick)

    def restart_now(self, why: str):
        self._restarting = True
        self._ready_at = None
        self.log(f"» {why}: restarting the server (the world is saved).")
        srv = self.server

        def work():
            try:
                srv.stop(90)
            finally:
                self.q.put(("restart",))
        threading.Thread(target=work, daemon=True).start()

    def _crash_restart(self, why) -> bool:
        """After a crash: start again (tab 6), at most 3 times in 10 minutes. True if it will."""
        if not (self.cfg.auto_restart or {}).get("on_crash", True):
            return False
        if why and "crashed" not in why and "memory" not in why.lower():
            return False  # a setup problem (port, Java, missing jar...): starting again won't help
        now = time.time()
        self._crashes = [t for t in getattr(self, "_crashes", []) if now - t < 600] + [now]
        if len(self._crashes) > 3:
            self.log("! It crashed 3 times in 10 minutes, so it isn't started again by itself.")
            return False
        self.log(f"! The server crashed{': ' + why if why else ''}. Starting it again in 10 seconds…")
        self.step_label.config(text="It crashed: starting again in 10 seconds…")
        self.root.after(10_000, lambda: (not (self.server and self.server.running)) and self.run_server())
        return True

    def run_server(self):
        self.busy = False
        self._restarting = False
        if not self.free_port_or_ask():
            self.show(self.PLAY)
            self.play_title.config(text=f"{self.cfg.name} is offline")
            self.startstop_btn.config(text="Start server", state="normal")
            self.update_buttons()
            return
        # every server uses "localhost", so the Multiplayer list shows whichever one ran last: keep it this one
        cfg = self.cfg
        threading.Thread(target=lambda: core.add_to_list(cfg, log=lambda l: None), daemon=True).start()
        self.show(self.PLAY)
        self.play_title.config(text=f"Starting {self.cfg.name}…")
        self.step_label.config(text="The first start makes the world, give it a minute.")
        self.progress.config(mode="indeterminate")
        self.progress.start(12)
        try:
            addons.refresh_bundled(self.cfg, self.log)
        except Exception as e:
            self.log(f"! Couldn't check the Dark plugins: {e}")
        sp = core.ServerProcess(self.cfg)
        sp.on_line = lambda l: self.q.put(("log", l, sp))
        sp.on_ready = lambda: self.q.put(("ready", sp))
        sp.on_exit = lambda code: self.q.put(("exited", code, sp))
        self.server = sp
        try:
            self.server.start()
        except Exception as e:
            self.q.put(("setup_failed", f"Could not start the server: {e}"))
            return
        self.startstop_btn.config(text="Stop server", state="normal")
        self.update_buttons()
        threading.Thread(target=self._network, daemon=True).start()

    def _network(self):
        cfg = self.cfg
        lan = core.lan_ip()
        bed = cfg.prov.bedrock
        self.q.put(("ip", "lan", lan if bed else core.server_address(cfg, lan)))
        self.q.put(("ip", "local", "127.0.0.1" if bed else core.server_address(cfg)))
        self.q.put(("ip", "port", str(cfg.port)))
        up = core.UPnP()
        ports = core.ports_for(cfg, addons.extra_ports(cfg))
        opened, failed = [], []
        for port, proto, what in ports:
            (opened if up.open(port, lan, f"MC Hoster {cfg.name} {what}", proto) else failed).append((port, proto, what))
        pub = (up.external_ip() if opened else None) or core.public_ip()
        self.upnp, self.opened_ports = (up, opened) if opened else (None, [])
        self.q.put(("ip", "public", ((pub if bed else core.server_address(cfg, pub)) if pub else "couldn't find it")))
        lines = []
        if "darkranks" in cfg.addons and not bed:
            try:
                f = Path(cfg.folder) / cfg.prov.addon_folder / "DarkRanks" / "owner.txt"
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text(cfg.owner if core.valid_player_name(cfg.owner) else "", encoding="utf-8")
            except OSError:
                pass
        if "darkbossbar" in cfg.addons and not bed:
            # Dark Bossbar shows {ip}: the custom address, or the public one
            addr = domain.join_address(cfg.domain, cfg) if cfg.domain.get("enabled") else (core.server_address(cfg, pub) if pub else "")
            try:
                f = Path(cfg.folder) / cfg.prov.addon_folder / "DarkBossbar" / "address.txt"
                f.parent.mkdir(parents=True, exist_ok=True)
                f.write_text(addr, encoding="utf-8")
            except OSError:
                pass
        if cfg.domain.get("enabled"):
            self.q.put(("ip", "domain", domain.join_address(cfg.domain, cfg)))
            if pub:
                ok, msg = domain.update(cfg.domain, pub)
                lines.append(("✔ " if ok else "✖ ") + msg)
        if opened and not failed:
            lines.append("✔ Opened on your router automatically: " + ", ".join(f"{p} {pr} ({w})" for p, pr, w in opened)
                         + ". Friends anywhere can join.")
        else:
            if opened:
                lines.append("✔ Opened: " + ", ".join(f"{p} {pr}" for p, pr, w in opened) + ".")
            lines.append("Your router didn't let MC Hoster open " + ", ".join(f"{p} {pr} ({w})" for p, pr, w in failed) +
                         f" by itself. People on your Wi-Fi can join now. For friends somewhere else, forward "
                         f"{'it' if len(failed) == 1 else 'them'} to {lan} in your router settings.")
        if bed:
            lines.append("In Minecraft: Play → Servers → Add Server, type the address and port.")
        lines.append("If Windows asks about the firewall, click Allow.")
        self.q.put(("port", "\n".join(lines), bool(opened) and not failed))

    def _close_ports(self):
        if self.upnp:
            up, ports = self.upnp, list(self.opened_ports)
            threading.Thread(target=lambda: [up.close(p, pr) for p, pr, _ in ports], daemon=True).start()
        self.upnp, self.opened_ports = None, []

    def start_stop(self):
        if self.server and self.server.running:
            self.startstop_btn.config(state="disabled", text="Stopping…")
            threading.Thread(target=self.server.stop, daemon=True).start()
        else:
            self.run_server()

    def show_ip_box(self):
        shown = {"port": self.cfg.prov.bedrock, "domain": bool(self.cfg.domain.get("enabled"))}
        for key, on in shown.items():
            for w in self.ip_rows[key]:
                (w.grid if on else w.grid_remove)()
        self.ip_box.grid(row=3, column=0, sticky="we", pady=(8, 0))

    # ------------------------------------------------------------------ updates
    def check_updates(self, quiet: bool):
        """quiet = the check when MC Hoster opens: only says something if there is an update."""
        threading.Thread(target=lambda: self.q.put(("update", updater.latest(), quiet)), daemon=True).start()

    def update_dialog(self, info, quiet: bool):
        if info is None:
            if not quiet:
                messagebox.showinfo("Check for updates", "Couldn't reach GitHub. Check your internet and try again.")
            return
        if not updater.newer(info["version"]):
            if not quiet:
                messagebox.showinfo("Check for updates", f"You have the newest MC Hoster (v{core.VERSION}).")
            return
        if quiet and updater.skipped() == info["version"]:
            return
        top, f = self.dialog("Update available")
        ttk.Label(f, text=f"MC Hoster {info['version']} is out!", style="Title.TLabel").pack(anchor="w")
        ttk.Label(f, text=f"You have {core.VERSION}. Here's what's new:", style="Hint.TLabel").pack(anchor="w", pady=(0, 6))
        box = ttk.Frame(f)
        box.pack(fill="both", expand=True)
        txt = tk.Text(box, width=78, height=18, wrap="word", relief="flat", padx=8, pady=6)
        sb = ttk.Scrollbar(box, command=txt.yview)
        txt.config(yscrollcommand=sb.set)
        txt.insert("1.0", updater.plain(info["notes"]) or "(no notes)")
        txt.config(state="disabled")
        txt.pack(side="left", fill="both", expand=True)
        sb.pack(side="right", fill="y")
        bar = ttk.Progressbar(f, mode="determinate", maximum=100)
        status = ttk.Label(f, text="", style="Hint.TLabel")
        status.pack(anchor="w", pady=(6, 0))
        btns = ttk.Frame(f)
        btns.pack(fill="x", pady=(8, 0))
        can = updater.can_install() and info.get("url")
        install = ttk.Button(btns, text="Install update" if can else "Open download page")
        install.pack(side="right")
        ttk.Button(btns, text="Later", command=top.destroy).pack(side="right", padx=6)

        def skip():
            updater.skip(info["version"])
            top.destroy()
        ttk.Button(btns, text="Skip this version", command=skip).pack(side="left")
        self._upd = {"top": top, "status": status, "bar": bar, "install": install}

        def go():
            if not can:
                webbrowser.open(info["page"])
                top.destroy()
                return
            running = self.server and self.server.running
            if running and not messagebox.askyesno("Update", "Your server will stop (the world is saved) and MC Hoster "
                                                   "restarts with the update. Go ahead?", parent=top):
                return
            install.state(["disabled"])
            bar.pack(fill="x", pady=(6, 0), before=status)
            status.config(text="Downloading…")

            def work():
                try:
                    if running:
                        self.server.stop(60)
                    new = updater.download(info, lambda d, t: self.q.put(("upd_progress", d, t)))
                    updater.install(new)
                    self.q.put(("upd_ready",))
                except Exception as e:
                    self.q.put(("upd_failed", str(e)))
            threading.Thread(target=work, daemon=True).start()
        install.config(command=go)

    # ------------------------------------------------------------------ events from worker threads
    def _drain(self):
        try:
            while True:
                ev = self.q.get_nowait()
                k = ev[0]
                if k == "versions":
                    self._set_versions(ev[1], ev[2], ev[3])
                elif k == "restart":
                    self._restarting = False
                    self.run_server()
                elif k == "update":
                    self.update_dialog(ev[1], ev[2])
                elif k == "upd_progress":
                    u = getattr(self, "_upd", None)
                    if u and u["top"].winfo_exists():
                        done, total = ev[1], ev[2]
                        u["status"].config(text=f"Downloading… {done / 1048576:.1f} / {total / 1048576:.1f} MB" if total else
                                           f"Downloading… {done / 1048576:.1f} MB")
                        if total:
                            u["bar"].config(value=done * 100 / total)
                elif k == "upd_ready":
                    self._close_ports()
                    self.root.destroy()
                    return
                elif k == "upd_failed":
                    u = getattr(self, "_upd", None)
                    if u and u["top"].winfo_exists():
                        u["status"].config(text="")
                        u["install"].state(["!disabled"])
                    messagebox.showerror("Update", f"The update didn't work: {ev[1]}\nYou can download it from GitHub instead.")
                elif k == "log":
                    self.log(ev[1])
                    if len(ev) > 2 and ev[2] is self.server and ("FAILED TO BIND TO PORT" in ev[1] or "Address already in use" in ev[1]):
                        messagebox.showwarning("Port in use", f"Port {self.cfg.port} is already taken, so the server can't start. "
                                               "Another server is probably still running: close it, or start again and "
                                               "MC Hoster will offer to stop it or use another port.")
                elif k == "step":
                    i, n, t = ev[1], ev[2], ev[3]
                    self.progress.stop()
                    self.progress.config(mode="determinate", value=100 * i / n)
                    self.step_label.config(text=f"Step {i + 1} of {n}: {t}")
                    self._step_base, self._step_size = 100 * i / n, 100 / n
                elif k == "bytes":
                    d, t = ev[1], ev[2]
                    if t:
                        self.progress.config(value=getattr(self, "_step_base", 0) + getattr(self, "_step_size", 25) * d / t)
                elif k == "setup_done":
                    self.progress.config(value=100)
                    self.log("» Done. Starting the server.")
                    self.page = self.PLAY
                    self.enter_edit_mode()
                    self.run_server()
                elif k == "setup_failed":
                    self.busy = False
                    self.progress.stop()
                    self.play_title.config(text="Something went wrong")
                    self.log("ERROR " + ev[1])
                    if self.cfg.folder and (Path(self.cfg.folder) / "mchoster.json").exists():
                        self.enter_edit_mode()
                    else:
                        self.show(self.PLAY)
                    messagebox.showerror("MC Hoster", ev[1])
                elif k == "ready":
                    if ev[-1] is not self.server:
                        continue  # an older server that was replaced
                    self.progress.stop()
                    self.progress.config(mode="determinate", value=100)
                    self.play_title.config(text=f"{self.cfg.name} is online!")
                    self._ready_at = time.time()
                    self._warned = set()
                    for c in core.ready_commands(self.cfg):  # gamerules, whitelist and ops from tab 6, you as op
                        self.server.send(c)
                    self.step_label.config(text=("Open Minecraft → Play → Servers → Add Server." if self.cfg.prov.bedrock
                                                 else "Open Minecraft → Multiplayer → Add Server, and paste the IP."))
                    self.show_ip_box()
                    broken = core.plugin_errors(self.server.tail)
                    if broken:
                        messagebox.showwarning("Plugins didn't start",
                                               "These plugins failed to start, so their commands won't work:\n\n"
                                               + "\n".join(f"• {n}: {why}" for n, why in broken.items())
                                               + "\n\nThe console has the details. Send me (or the plugin's maker) the red lines.")
                elif k == "ip":
                    self.ip_vars[ev[1]].set(ev[2])
                elif k == "port":
                    self.port_label.config(text=ev[1], style="Ok.TLabel" if ev[2] else "Hint.TLabel")
                elif k == "exited":
                    if len(ev) > 2 and ev[2] is not self.server:
                        continue  # the old server finished stopping after another one started
                    self.progress.stop()
                    self._close_ports()
                    self.play_title.config(text=f"{self.cfg.name} is offline")
                    self.step_label.config(text=f"The server stopped (exit code {ev[1]}).")
                    srv = ev[2] if len(ev) > 2 else None
                    by_itself = srv is not None and not srv.stopping and not getattr(self, "_closing", False)
                    self._ready_at = None
                    if by_itself and not any("FAILED TO BIND" in l or "Address already in use" in l for l in srv.tail):
                        # it stopped by itself: say why, if we can tell (a plain "stop" typed in the console ends with 0)
                        why = core.explain_exit(srv.tail, self.cfg.folder)
                        crashed = bool(why) or ev[1] not in (0, None)
                        if crashed and self._crash_restart(why):
                            pass
                        elif crashed:
                            last = "\n".join(l[-160:] for l in srv.tail[-8:])
                            self.step_label.config(text="The server stopped by itself. " + (why or "See the console above."))
                            messagebox.showwarning("The server stopped", (why or "It stopped by itself.") +
                                                   (f"\n\nLast lines from the server:\n{last}" if last else ""))
                    self.startstop_btn.config(text="Start server", state="normal")
                    self.ip_box.grid_remove()
                    self.update_buttons()
                    if getattr(self, "_closing", False):
                        self.root.destroy()
                        return
        except queue.Empty:
            pass
        except tk.TclError:
            return  # this window was replaced by New server
        self.root.after(60, self._drain)

    def on_close(self):
        if self.server and self.server.running:
            if not messagebox.askyesno("MC Hoster", "Your server is still running. Stop it and close MC Hoster?"):
                return
            self._closing = True
            self.play_title.config(text="Saving the world and stopping…")
            threading.Thread(target=self.server.stop, daemon=True).start()
            return
        if self.busy and not messagebox.askyesno("MC Hoster", "MC Hoster is still working. Close anyway?"):
            return
        self._close_ports()
        self.root.destroy()


def main(start_folder: str | None = None):
    if core.WINDOWS:
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("DarkerDawnFR.MCHoster")
        except Exception:
            pass
    root = tk.Tk()
    w = Wizard(root)
    root.update_idletasks()
    root.minsize(max(660, root.winfo_reqwidth()), max(560, root.winfo_reqheight()))
    def check_leftovers():
        try:
            left = core.leftovers()
        except Exception:
            return
        for lo in left:
            if start_folder and Path(lo.cfg.folder) == Path(start_folder):
                continue
            if messagebox.askyesno("A server is still running",
                                   f"\"{lo.cfg.name}\" is still running in the background, from before MC Hoster was closed.\n\n"
                                   "Stop it now? (Yes is best: a server without MC Hoster can't be stopped or seen.)", parent=root):
                core.stop_leftover(lo)
        if start_folder or left:
            return
        # a server an older MC Hoster left running (it didn't keep track of them)
        for port, udp in ((core.PORT, False), (core.BEDROCK_PORT, True)):
            if core.port_free(port, udp):
                continue
            pid = core.port_owner(port, udp)
            prog = core.process_name(pid) if pid else None
            if prog and prog.lower() in core.SERVER_EXES and messagebox.askyesno(
                    "A server is still running",
                    f"A Minecraft server ({prog}, process {pid}) is still running on port {port}, probably left running "
                    "by an older MC Hoster after it was closed.\n\nStop it now?", parent=root):
                core.kill_tree(pid)
    root.after(300, check_leftovers)
    if not start_folder:
        root.after(2500, lambda: w.check_updates(True))
    if start_folder:  # started by the scheduler
        try:
            w.open_existing(ServerConfig.load(Path(start_folder)))
        except Exception as e:
            messagebox.showerror("MC Hoster", f"Could not open the scheduled server:\n{e}")
    root.mainloop()
