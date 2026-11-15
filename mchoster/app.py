"""MC Hoster window: a setup wizard in the style of the Fabric Installer.

Tabs across the top (1 Server, 2 Minecraft, 3 Settings, 4 Play), Back / Next at the bottom,
"Finish" on the settings tab, then the server starts and the IP is shown.
"""
from __future__ import annotations

import os
import queue
import subprocess
import sys
import threading
import tkinter as tk
import webbrowser
from pathlib import Path
from tkinter import filedialog, messagebox, ttk

from . import core
from .core import PROVIDERS, PROVIDER_BY_NAME, PROVIDER_BY_ID, ServerConfig

GREEN, YELLOW, ORANGE, RED = "#2e9e44", "#c9a400", "#e07b00", "#cc2b2b"
LAG_COLORS = [GREEN, YELLOW, ORANGE, RED]
PAD = 10


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
    TABS = ["Server", "Minecraft", "Settings", "Play"]

    def __init__(self, root: tk.Tk):
        self.root = root
        self.q: queue.Queue = queue.Queue()
        self.cfg = ServerConfig()
        self.server: core.ServerProcess | None = None
        self.upnp: core.UPnP | None = None
        self.icon_img = None
        self.page = 0
        self.versions_cache: dict[str, list[str]] = {}

        root.title(f"{core.APP_NAME} {core.VERSION}")
        root.protocol("WM_DELETE_WINDOW", self.on_close)
        self._style()
        self._icon()

        outer = ttk.Frame(root, padding=(PAD, PAD, PAD, 0))
        outer.pack(fill="both", expand=True)
        self.nb = ttk.Notebook(outer)
        self.nb.pack(fill="both", expand=True)
        self.pages = []
        for i, name in enumerate(self.TABS):
            f = ttk.Frame(self.nb, padding=PAD + 4)
            self.nb.add(f, text=f"  {i + 1}. {name}  ")
            self.pages.append(f)
        self.nb.bind("<<NotebookTabChanged>>", lambda e: self.nb.select(self.page) if self.nb.index("current") != self.page else None)

        bar = ttk.Frame(root, padding=PAD)
        bar.pack(fill="x")
        ttk.Label(bar, text=f"MC Hoster {core.VERSION} · by {core.AUTHOR}", style="Hint.TLabel").pack(side="left")
        self.next_btn = ttk.Button(bar, text="Next", command=self.next, style="Accent.TButton", width=12)
        self.next_btn.pack(side="right")
        self.back_btn = ttk.Button(bar, text="Back", command=self.back, width=10)
        self.back_btn.pack(side="right", padx=(0, 6))

        self._page_server(self.pages[0])
        self._page_minecraft(self.pages[1])
        self._page_settings(self.pages[2])
        self._page_play(self.pages[3])
        self.show(0)
        self.root.after(60, self._drain)

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
        s.configure("Big.TLabel", font=(base[0], base[1] + 3, "bold"))
        s.configure("Ok.TLabel", foreground=GREEN)
        s.configure("Bad.TLabel", foreground=RED)
        s.configure("Warn.TLabel", foreground=ORANGE)
        s.configure("Link.TLabel", foreground="#1a5fb4", font=(base[0], base[1], "underline"))
        s.configure("Accent.TButton", font=(base[0], base[1], "bold"))
        s.configure("IP.TEntry", font=("Consolas" if core.WINDOWS else "Courier", base[1] + 5, "bold"))
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
        ttk.Label(parent, text=label).grid(row=r, column=0, sticky="w", pady=4, padx=(0, 10))
        widget.grid(row=r, column=1, sticky="we", pady=4)
        if hint:
            ttk.Label(parent, text=hint, style="Hint.TLabel").grid(row=r, column=2, sticky="w", padx=(8, 0))

    # ------------------------------------------------------------------ page 1: server type and version
    def _page_server(self, f):
        f.columnconfigure(1, weight=1)
        ttk.Label(f, text="Host a Minecraft server from your PC", style="Title.TLabel").grid(row=0, column=0, columnspan=3, sticky="w")
        ttk.Label(f, text="Pick what runs your server. Every choice here works with Gh0st AC.",
                  style="Hint.TLabel").grid(row=1, column=0, columnspan=3, sticky="w", pady=(0, 10))

        self.provider_var = tk.StringVar(value=PROVIDERS[0].name)
        cb = ttk.Combobox(f, textvariable=self.provider_var, values=[p.name for p in PROVIDERS], state="readonly", width=28)
        cb.bind("<<ComboboxSelected>>", lambda e: self.load_versions())
        self.row(f, 2, "Server type:", cb)
        self.provider_blurb = ttk.Label(f, text="", style="Hint.TLabel", wraplength=420, justify="left")
        self.provider_blurb.grid(row=3, column=1, columnspan=2, sticky="w", pady=(0, 6))

        self.version_var = tk.StringVar()
        self.version_cb = ttk.Combobox(f, textvariable=self.version_var, state="readonly", width=28)
        self.version_cb.bind("<<ComboboxSelected>>", lambda e: self.version_changed())
        self.row(f, 4, "Minecraft version:", self.version_cb)
        self.version_status = ttk.Label(f, text="")
        self.version_status.grid(row=5, column=1, columnspan=2, sticky="w")

        ttk.Separator(f).grid(row=6, column=0, columnspan=3, sticky="we", pady=14)
        bottom = ttk.Frame(f)
        bottom.grid(row=7, column=0, columnspan=3, sticky="we")
        ttk.Label(bottom, text="Already made one?").pack(side="left")
        ttk.Button(bottom, text="My servers…", command=self.my_servers).pack(side="left", padx=8)
        self.load_versions()

    def provider(self) -> core.Provider:
        return PROVIDER_BY_NAME[self.provider_var.get()]

    def load_versions(self):
        p = self.provider()
        self.provider_blurb.config(text=p.blurb)
        if p.id in self.versions_cache:
            return self._set_versions(p.id, self.versions_cache[p.id])
        self.version_cb.config(values=[])
        self.version_var.set("")
        self.version_status.config(text="Loading versions…", style="Hint.TLabel")
        self.update_buttons()

        def work():
            try:
                vs = core.list_versions(p)
                self.q.put(("versions", p.id, vs, None))
            except Exception as e:
                self.q.put(("versions", p.id, [], str(e)))
        threading.Thread(target=work, daemon=True).start()

    def _set_versions(self, pid, vs, err=None):
        if pid != self.provider().id:
            return
        if not vs:
            self.version_status.config(text="Couldn't load versions. Check your internet and pick the type again." +
                                       (f"\n({err})" if err else ""), style="Bad.TLabel")
            return self.update_buttons()
        self.versions_cache[pid] = vs
        self.version_cb.config(values=vs)
        if self.version_var.get() not in vs:
            self.version_var.set(vs[0])
        self.version_changed()

    def version_changed(self):
        p, v = self.provider(), self.version_var.get()
        if not v:
            return
        java = core.required_java(v)
        if core.gh0st_supported(p, v):
            self.version_status.config(text=f"✔ Gh0st AC works on {p.name} {v}   ·   needs Java {java} (found or downloaded for you)", style="Ok.TLabel")
        else:
            self.version_status.config(text=f"Gh0st AC has no build for {p.name} {v}", style="Warn.TLabel")
        self.update_buttons()

    # ------------------------------------------------------------------ page 2: Minecraft folder
    def _page_minecraft(self, f):
        f.columnconfigure(1, weight=1)
        ttk.Label(f, text="Your Minecraft folder", style="Title.TLabel").grid(row=0, column=0, columnspan=3, sticky="w")
        ttk.Label(f, text="MC Hoster puts your new server in the Multiplayer list, and can reuse the Java\n"
                          "the Minecraft Launcher already downloaded, so there is less to download.",
                  style="Hint.TLabel", justify="left").grid(row=1, column=0, columnspan=3, sticky="w", pady=(0, 12))
        self.mc_var = tk.StringVar()
        e = ttk.Entry(f, textvariable=self.mc_var)
        e.bind("<KeyRelease>", lambda ev: self.check_mc())
        ttk.Label(f, text="Minecraft folder:").grid(row=2, column=0, sticky="w", padx=(0, 10))
        e.grid(row=2, column=1, sticky="we")
        ttk.Button(f, text="…", width=3, command=self.browse_mc).grid(row=2, column=2, padx=(6, 0))
        self.mc_status = ttk.Label(f, text="", wraplength=440, justify="left")
        self.mc_status.grid(row=3, column=0, columnspan=3, sticky="w", pady=(10, 0))
        self.add_list_var = tk.BooleanVar(value=True)
        self.add_list_cb = ttk.Checkbutton(f, text="Add the server to my Multiplayer list", variable=self.add_list_var)
        self.add_list_cb.grid(row=4, column=0, columnspan=3, sticky="w", pady=(12, 0))

    def find_mc(self):
        self.mc_status.config(text="Looking for your Minecraft folder…", style="Hint.TLabel")
        found = core.find_minecraft_dir()
        self.mc_var.set(str(found) if found else str(core.default_minecraft_dir()))
        self.check_mc(searched=True)

    def check_mc(self, searched=False):
        p = Path(self.mc_var.get().strip().strip('"'))
        if core.looks_like_minecraft_dir(p):
            self.mc_status.config(text="✔ Found it! Click Next.", style="Ok.TLabel")
            self.add_list_cb.state(["!disabled"])
        else:
            self.mc_status.config(text=("✖ Couldn't find your Minecraft folder." if searched else "✖ That is not a Minecraft folder.") +
                                  " Pick it with … (it is usually %APPDATA%\\.minecraft), or click Next to skip."
                                  " Then you add the server in Multiplayer by hand.", style="Bad.TLabel")
            self.add_list_cb.state(["disabled"])

    def browse_mc(self):
        d = filedialog.askdirectory(title="Pick your .minecraft folder", initialdir=self.mc_var.get() or str(Path.home()))
        if d:
            self.mc_var.set(d)
            self.check_mc()

    # ------------------------------------------------------------------ page 3: settings
    def _page_settings(self, f):
        f.columnconfigure(1, weight=1)
        ttk.Label(f, text="Server settings", style="Title.TLabel").grid(row=0, column=0, columnspan=3, sticky="w", pady=(0, 8))

        self.name_var = tk.StringVar(value="My Server")
        self.name_var.trace_add("write", lambda *a: self.update_buttons())
        self.row(f, 1, "Name:", ttk.Entry(f, textvariable=self.name_var, width=30), "Shown in the server list")

        icon_row = ttk.Frame(f)
        self.icon_preview = tk.Label(icon_row, width=8, height=4, relief="groove", text="no icon", fg="#888", bg="#f3f3f3")
        self.icon_preview.pack(side="left")
        ttk.Button(icon_row, text="Choose PNG…", command=self.pick_icon).pack(side="left", padx=8)
        ttk.Button(icon_row, text="Remove", command=self.clear_icon).pack(side="left")
        self.row(f, 2, "Icon:", icon_row, "PNG, 64×64")
        self.icon_status = ttk.Label(f, text="", style="Hint.TLabel")
        self.icon_status.grid(row=3, column=1, columnspan=2, sticky="w")

        pl = ttk.Frame(f)
        self.players_var = tk.IntVar(value=10)
        self.players_scale = ttk.Scale(pl, from_=1, to=100, orient="horizontal", length=230,
                                       command=lambda v: self.players_changed(int(float(v))))
        self.players_scale.set(10)
        self.players_scale.pack(side="left")
        self.players_spin = ttk.Spinbox(pl, from_=1, to=500, width=5, textvariable=self.players_var,
                                        command=lambda: self.players_changed(self.players_var.get(), from_spin=True))
        self.players_spin.bind("<KeyRelease>", lambda e: self._spin_typed())
        self.players_spin.pack(side="left", padx=8)
        self.row(f, 4, "Max players:", pl)
        lag = ttk.Frame(f)
        self.lag_canvas = tk.Canvas(lag, width=120, height=12, highlightthickness=0, bg="#e4e4e4")
        self.lag_canvas.pack(side="left")
        self.lag_label = ttk.Label(lag, text="")
        self.lag_label.pack(side="left", padx=8)
        lag.grid(row=5, column=1, columnspan=2, sticky="w", pady=(0, 4))

        self.gamemode_var = tk.StringVar(value="Survival")
        self.row(f, 6, "Game mode:", ttk.Combobox(f, textvariable=self.gamemode_var, values=core.GAMEMODES, state="readonly", width=18))
        diff = ttk.Frame(f)
        self.difficulty_var = tk.StringVar(value="Normal")
        ttk.Combobox(diff, textvariable=self.difficulty_var, values=core.DIFFICULTIES, state="readonly", width=18).pack(side="left")
        self.hardcore_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(diff, text="Hardcore (one life)", variable=self.hardcore_var,
                        command=self._hardcore).pack(side="left", padx=10)
        self.row(f, 7, "Difficulty:", diff)

        ttk.Separator(f).grid(row=8, column=0, columnspan=3, sticky="we", pady=8)
        self.gh0st_var = tk.BooleanVar(value=True)
        self.gh0st_cb = ttk.Checkbutton(f, text="Install Gh0st AC anticheat on the server (free version)", variable=self.gh0st_var)
        self.gh0st_cb.grid(row=9, column=0, columnspan=3, sticky="w")
        eula = ttk.Frame(f)
        self.eula_var = tk.BooleanVar(value=False)
        ttk.Checkbutton(eula, text="I agree to the", variable=self.eula_var, command=self.update_buttons).pack(side="left")
        link = ttk.Label(eula, text="Minecraft EULA", style="Link.TLabel", cursor="hand2")
        link.pack(side="left")
        link.bind("<Button-1>", lambda e: webbrowser.open("https://aka.ms/MinecraftEULA"))
        ttk.Label(eula, text="(needed to run any server)", style="Hint.TLabel").pack(side="left", padx=6)
        eula.grid(row=10, column=0, columnspan=3, sticky="w", pady=(4, 0))
        self.players_changed(10)

    def _hardcore(self):
        if self.hardcore_var.get():
            self.difficulty_var.set("Hard")

    def _spin_typed(self):
        try:
            self.players_changed(int(self.players_spin.get()), from_spin=True)
        except ValueError:
            pass

    def players_changed(self, n, from_spin=False):
        n = max(1, min(500, int(n)))
        if not hasattr(self, "lag_canvas"):
            return
        if not from_spin:
            self.players_var.set(n)
        elif n <= 100:
            self.players_scale.set(n)
        lvl, text = core.lag_level(n)
        ram = core.ram_for_players(n, self.provider().kind == "fabric")
        c = self.lag_canvas
        c.delete("all")
        w = 120 * (lvl + 1) / 4
        c.create_rectangle(0, 0, w, 12, fill=LAG_COLORS[lvl], width=0)
        self.lag_label.config(text=f"{text}  ({ram / 1024:.1f} GB RAM)")

    def pick_icon(self):
        p = filedialog.askopenfilename(title="Server icon (64×64 PNG)", filetypes=[("PNG image", "*.png")])
        if not p:
            return
        ok, msg, size = core.check_icon(p)
        if not ok:
            if size and messagebox.askyesno("Icon has to be 64×64", msg + "\n\nShrink it to 64×64 for you?"):
                out = core.app_dir() / "icon-64.png"
                core.resize_icon(p, out)
                p = str(out)
            else:
                if not size:
                    messagebox.showerror("Icon", msg)
                return
        self.cfg.icon_path = p
        try:
            self.icon_img = tk.PhotoImage(file=p)
            self.icon_preview.config(image=self.icon_img, text="", width=64, height=64)
            self.icon_status.config(text="✔ " + Path(p).name, style="Ok.TLabel")
        except Exception:
            self.icon_status.config(text="✔ " + Path(p).name, style="Ok.TLabel")

    def clear_icon(self):
        self.cfg.icon_path = ""
        self.icon_img = None
        self.icon_preview.config(image="", text="no icon", width=8, height=4)
        self.icon_status.config(text="")

    # ------------------------------------------------------------------ page 4: setup, IP and console
    def _page_play(self, f):
        f.columnconfigure(0, weight=1)
        f.rowconfigure(4, weight=1)
        top = ttk.Frame(f)
        top.grid(row=0, column=0, sticky="we")
        self.play_title = ttk.Label(top, text="Setting up your server…", style="Title.TLabel")
        self.play_title.pack(side="left")
        self.progress = ttk.Progressbar(f, mode="determinate", maximum=100)
        self.progress.grid(row=1, column=0, sticky="we", pady=(6, 2))
        self.step_label = ttk.Label(f, text="", style="Hint.TLabel")
        self.step_label.grid(row=2, column=0, sticky="w")

        self.ip_box = ttk.LabelFrame(f, text=" Join with this IP ", padding=8)
        self.ip_box.columnconfigure(1, weight=1)
        self.ip_vars = {}
        for r, (key, label) in enumerate([("public", "Friends:"), ("lan", "Same Wi-Fi:"), ("local", "This PC:")]):
            ttk.Label(self.ip_box, text=label).grid(row=r, column=0, sticky="w", padx=(0, 8))
            v = tk.StringVar(value="…")
            self.ip_vars[key] = v
            e = ttk.Entry(self.ip_box, textvariable=v, state="readonly", style="IP.TEntry" if key == "public" else "TEntry",
                          font=(("Consolas" if core.WINDOWS else "Courier"), self.base_font[1] + (5 if key == "public" else 1), "bold"))
            e.grid(row=r, column=1, sticky="we", pady=2)
            ttk.Button(self.ip_box, text="Copy", width=6, command=lambda k=key: self.copy(self.ip_vars[k].get())).grid(row=r, column=2, padx=(6, 0))
        self.port_label = ttk.Label(self.ip_box, text="", wraplength=470, justify="left", style="Hint.TLabel")
        self.port_label.grid(row=3, column=0, columnspan=3, sticky="w", pady=(6, 0))

        con = ttk.Frame(f)
        con.grid(row=4, column=0, sticky="nsew", pady=(8, 0))
        con.columnconfigure(0, weight=1)
        con.rowconfigure(0, weight=1)
        self.console = tk.Text(con, height=10, wrap="none", bg="#1e1e1e", fg="#d4d4d4", insertbackground="#fff",
                               font=(("Consolas" if core.WINDOWS else "Courier"), 9), relief="flat", state="disabled")
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

    def log(self, line: str):
        tag = "err" if ("ERROR" in line or "Exception" in line) else "warn" if "WARN" in line else "me" if line.startswith("»") else ""
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

    # ------------------------------------------------------------------ navigation
    def show(self, i):
        self.page = i
        for j in range(len(self.pages)):
            self.nb.tab(j, state="normal" if j == i else "disabled")
        self.nb.select(i)
        if i == 1 and not self.mc_var.get():
            self.find_mc()
        if i == 2:
            p, v = self.provider(), self.version_var.get()
            if core.gh0st_supported(p, v):
                self.gh0st_cb.state(["!disabled"])
                self.gh0st_cb.config(text="Install Gh0st AC anticheat on the server (free version)")
            else:
                self.gh0st_var.set(False)
                self.gh0st_cb.state(["disabled"])
                self.gh0st_cb.config(text=f"Gh0st AC (no build for {p.name} {v})")
            self.players_changed(self.players_var.get())
        self.update_buttons()

    def update_buttons(self):
        i = self.page
        self.back_btn.config(state="normal" if 0 < i < 3 else "disabled")
        if i == 0:
            self.next_btn.config(text="Next", state="normal" if self.version_var.get() else "disabled")
        elif i == 1:
            self.next_btn.config(text="Next", state="normal")
        elif i == 2:
            ok = self.eula_var.get() and self.name_var.get().strip()
            self.next_btn.config(text="Finish", state="normal" if ok else "disabled")
        else:
            self.next_btn.config(text="Close", state="normal")

    def back(self):
        if 0 < self.page < 3:
            self.show(self.page - 1)

    def next(self):
        if self.page == 0:
            self.show(1)
        elif self.page == 1:
            self.show(2)
        elif self.page == 2:
            self.finish()
        else:
            self.on_close()

    def collect(self):
        c = self.cfg
        c.name = self.name_var.get().strip() or "My Server"
        c.provider = self.provider().id
        c.version = self.version_var.get()
        try:
            c.players = max(1, min(500, int(self.players_spin.get())))
        except ValueError:
            c.players = self.players_var.get()
        c.gamemode = self.gamemode_var.get()
        c.difficulty = self.difficulty_var.get()
        c.hardcore = self.hardcore_var.get()
        c.gh0stac = self.gh0st_var.get()
        mc = Path(self.mc_var.get().strip().strip('"'))
        c.minecraft_dir = str(mc) if core.looks_like_minecraft_dir(mc) else ""
        c.add_to_list = self.add_list_var.get() and bool(c.minecraft_dir)
        c.eula = self.eula_var.get()

    def finish(self):
        self.collect()
        self.show(3)
        self.next_btn.config(state="disabled")
        self.play_title.config(text=f"Setting up {self.cfg.name}…")
        self.log(f"» {PROVIDER_BY_ID[self.cfg.provider].name} {self.cfg.version}, {self.cfg.players} players, "
                 f"{self.cfg.gamemode}, {self.cfg.difficulty}{', hardcore' if self.cfg.hardcore else ''}"
                 f"{', Gh0st AC' if self.cfg.gh0stac else ''}")

        def work():
            try:
                core.setup_server(self.cfg, log=lambda l: self.q.put(("log", l)),
                                  progress=lambda d, t: self.q.put(("bytes", d, t)),
                                  step=lambda i, n, t: self.q.put(("step", i, n, t)))
                self.q.put(("setup_done",))
            except Exception as e:
                self.q.put(("setup_failed", f"{e.__class__.__name__}: {e}"))
        threading.Thread(target=work, daemon=True).start()

    # ------------------------------------------------------------------ running the server
    def run_server(self):
        self.play_title.config(text=f"Starting {self.cfg.name}…")
        self.step_label.config(text="The first start makes the world, give it a minute.")
        self.progress.config(mode="indeterminate")
        self.progress.start(12)
        self.server = core.ServerProcess(self.cfg, on_line=lambda l: self.q.put(("log", l)),
                                         on_ready=lambda: self.q.put(("ready",)),
                                         on_exit=lambda code: self.q.put(("exited", code)))
        try:
            self.server.start()
        except Exception as e:
            self.q.put(("setup_failed", f"Could not start Java: {e}"))
            return
        self.startstop_btn.config(text="Stop server", state="normal")
        self.next_btn.config(state="normal")
        threading.Thread(target=self._network, daemon=True).start()

    def _network(self):
        lan = core.lan_ip()
        port = self.cfg.port
        suffix = "" if port == core.PORT else f":{port}"
        self.q.put(("ip", "lan", lan + suffix))
        self.q.put(("ip", "local", "localhost" + suffix))
        up = core.UPnP()
        opened = up.open(port, lan, f"MC Hoster {self.cfg.name}")
        pub = (up.external_ip() if opened else None) or core.public_ip()
        if opened:
            self.upnp = up
        self.q.put(("ip", "public", (pub + suffix) if pub else "couldn't find it"))
        if opened:
            msg = f"✔ Port {port} was opened on your router automatically, so friends anywhere can join with the Friends IP."
        else:
            msg = (f"Your router didn't let MC Hoster open port {port} by itself. People on your Wi-Fi can join now. "
                   f"For friends somewhere else, forward TCP port {port} to {lan} in your router settings.")
        msg += "\nIf Windows asks about Java and the firewall, click Allow."
        self.q.put(("port", msg, opened))

    def start_stop(self):
        if self.server and self.server.running:
            self.startstop_btn.config(state="disabled", text="Stopping…")
            threading.Thread(target=self.server.stop, daemon=True).start()
        else:
            self.run_server()

    def open_existing(self, cfg: ServerConfig):
        self.cfg = cfg
        self.show(3)
        self.progress.config(value=100)
        self.log(f"» Opening {cfg.name} ({PROVIDER_BY_ID[cfg.provider].name} {cfg.version}) from {cfg.folder}")
        if not cfg.java or not Path(cfg.java).exists():
            found = core.find_java(core.required_java(cfg.version), Path(cfg.minecraft_dir) if cfg.minecraft_dir else None)
            if not found:
                messagebox.showerror("Java", "The Java this server used is gone. Make the server again to download Java.")
                return
            cfg.java = str(found[0])
            cfg.save()
        self.run_server()

    def my_servers(self):
        servers = core.my_servers()
        if not servers:
            messagebox.showinfo("My servers", f"No servers yet. They are saved in\n{core.servers_dir()}")
            return
        top = tk.Toplevel(self.root)
        top.title("My servers")
        top.transient(self.root)
        frm = ttk.Frame(top, padding=PAD)
        frm.pack(fill="both", expand=True)
        lb = tk.Listbox(frm, width=60, height=min(10, len(servers)), activestyle="none")
        for s in servers:
            lb.insert("end", f"{s.name}   ·   {PROVIDER_BY_ID.get(s.provider, PROVIDERS[0]).name} {s.version}   ·   {s.players} players")
        lb.pack(fill="both", expand=True)
        lb.selection_set(0)
        btns = ttk.Frame(frm)
        btns.pack(fill="x", pady=(8, 0))

        def go():
            sel = lb.curselection()
            if sel:
                top.destroy()
                self.open_existing(servers[sel[0]])
        ttk.Button(btns, text="Start", style="Accent.TButton", command=go).pack(side="right")
        ttk.Button(btns, text="Open folder", command=lambda: lb.curselection() and open_folder(servers[lb.curselection()[0]].folder)).pack(side="right", padx=6)
        lb.bind("<Double-Button-1>", lambda e: go())

    # ------------------------------------------------------------------ events from worker threads
    def _drain(self):
        try:
            while True:
                ev = self.q.get_nowait()
                k = ev[0]
                if k == "versions":
                    self._set_versions(ev[1], ev[2], ev[3])
                elif k == "log":
                    self.log(ev[1])
                elif k == "step":
                    i, n, t = ev[1], ev[2], ev[3]
                    self.progress.config(mode="determinate", value=100 * i / n)
                    self.step_label.config(text=f"Step {i + 1} of {n}: {t}")
                    self._step_base, self._step_size = 100 * i / n, 100 / n
                elif k == "bytes":
                    d, t = ev[1], ev[2]
                    if t:
                        self.progress.config(value=getattr(self, "_step_base", 0) + getattr(self, "_step_size", 25) * d / t)
                elif k == "setup_done":
                    self.progress.config(value=100)
                    self.log("» Setup done. Starting the server.")
                    self.run_server()
                elif k == "setup_failed":
                    self.progress.stop()
                    self.play_title.config(text="Setup failed")
                    self.log("ERROR " + ev[1])
                    self.next_btn.config(state="normal")
                    messagebox.showerror("MC Hoster", ev[1])
                elif k == "ready":
                    self.progress.stop()
                    self.progress.config(mode="determinate", value=100)
                    self.play_title.config(text=f"{self.cfg.name} is online!")
                    self.step_label.config(text="Open Minecraft → Multiplayer → Add Server, and paste the IP.")
                    self.ip_box.grid(row=3, column=0, sticky="we", pady=(8, 0))
                elif k == "ip":
                    self.ip_vars[ev[1]].set(ev[2])
                elif k == "port":
                    self.port_label.config(text=ev[1], style="Ok.TLabel" if ev[2] else "Hint.TLabel")
                elif k == "exited":
                    self.progress.stop()
                    if self.upnp:
                        threading.Thread(target=self.upnp.close, args=(self.cfg.port,), daemon=True).start()
                        self.upnp = None
                    self.play_title.config(text=f"{self.cfg.name} is offline")
                    self.step_label.config(text=f"The server stopped (exit code {ev[1]}).")
                    self.startstop_btn.config(text="Start server", state="normal")
                    self.ip_box.grid_remove()
                    if getattr(self, "_closing", False):
                        self.root.destroy()
                        return
        except queue.Empty:
            pass
        self.root.after(60, self._drain)

    def on_close(self):
        if self.server and self.server.running:
            if not messagebox.askyesno("MC Hoster", "Your server is still running. Stop it and close MC Hoster?"):
                return
            self._closing = True
            self.play_title.config(text="Saving the world and stopping…")
            threading.Thread(target=self.server.stop, daemon=True).start()
            return
        if self.upnp:
            self.upnp.close(self.cfg.port)
        self.root.destroy()


def main():
    if core.WINDOWS:
        try:
            import ctypes
            ctypes.windll.shcore.SetProcessDpiAwareness(1)
            ctypes.windll.shell32.SetCurrentProcessExplicitAppUserModelID("DarkerDawnFR.MCHoster")
        except Exception:
            pass
    root = tk.Tk()
    Wizard(root)
    root.update_idletasks()
    root.minsize(max(600, root.winfo_reqwidth()), max(470, root.winfo_reqheight()))
    root.mainloop()
