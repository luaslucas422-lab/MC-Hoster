"""Draw a 64×64 server icon: pencil, eraser, fill, colour picker, undo, background, and ready-made pictures (stamps)."""
from __future__ import annotations

import time
import tkinter as tk
from pathlib import Path
from tkinter import colorchooser, messagebox, ttk

from . import core, stamps

N = 64          # icon size
CELL = 7        # screen pixels per icon pixel
PALETTE = ["#000000", "#3F3F3F", "#7F7F7F", "#BFBFBF", "#FFFFFF", "#7A4E2A", "#B5763A", "#E8C39E",
           "#E83030", "#A01818", "#FF7A00", "#FFD000", "#F5C830", "#5DA83A", "#2E7D32", "#7DC854",
           "#4FE3E0", "#22A5B5", "#3A8EE8", "#2C55B8", "#8A5CF5", "#C040E0", "#FF69B4", "#F2FBFF"]


class IconDrawer:
    def __init__(self, root: tk.Misc, start: str | None, on_save):
        self.on_save = on_save
        self.px: list[str | None] = [None] * (N * N)
        self.undo: list[list] = []
        self.color = "#E83030"
        self.tool = "pencil"
        self.size = 1
        self.stamp = None
        self.stamp_scale = 3
        self.last = None
        self.top = tk.Toplevel(root)
        self.top.title("Draw your server icon")
        self.top.transient(root)
        self.top.resizable(False, False)
        f = ttk.Frame(self.top, padding=10)
        f.pack(fill="both", expand=True)

        left = ttk.Frame(f)
        left.grid(row=0, column=0, sticky="n")
        self.canvas = tk.Canvas(left, width=N * CELL, height=N * CELL, highlightthickness=1, highlightbackground="#888", cursor="crosshair")
        self.canvas.pack()
        self.cells = []
        for y in range(N):
            for x in range(N):
                self.cells.append(self.canvas.create_rectangle(x * CELL, y * CELL, (x + 1) * CELL, (y + 1) * CELL,
                                                               width=0, fill=self._bg(x, y)))
        self.canvas.bind("<Button-1>", self._down)
        self.canvas.bind("<B1-Motion>", self._drag)
        self.canvas.bind("<ButtonRelease-1>", lambda e: setattr(self, "last", None))
        self.canvas.bind("<Button-3>", lambda e: self._down(e, erase=True))
        self.canvas.bind("<B3-Motion>", lambda e: self._drag(e, erase=True))
        self.canvas.bind("<ButtonRelease-3>", lambda e: setattr(self, "last", None))
        self.top.bind("<Control-z>", lambda e: self.do_undo())
        prev = ttk.Frame(left)
        prev.pack(fill="x", pady=(6, 0))
        ttk.Label(prev, text="Real size:").pack(side="left")
        self.preview = tk.Canvas(prev, width=N, height=N, highlightthickness=1, highlightbackground="#888", bg="#ddd")
        self.preview.pack(side="left", padx=6)
        self.preview_img = tk.PhotoImage(width=N, height=N)
        self.preview.create_image(0, 0, image=self.preview_img, anchor="nw")
        ttk.Label(prev, text="Left: draw · Right: erase · Ctrl+Z: undo", foreground="#6b6b6b").pack(side="left", padx=8)

        side = ttk.Frame(f, padding=(12, 0, 0, 0))
        side.grid(row=0, column=1, sticky="n")
        ttk.Label(side, text="Tools", font=("Segoe UI", 10, "bold")).pack(anchor="w")
        tools = ttk.Frame(side)
        tools.pack(anchor="w", pady=(2, 6))
        self.tool_btns = {}
        for t, label in (("pencil", "✏ Pencil"), ("eraser", "⌫ Eraser"), ("fill", "🪣 Fill"), ("picker", "💧 Picker")):
            b = ttk.Button(tools, text=label, width=12, command=lambda t=t: self.set_tool(t))
            b.pack(side="left", padx=(0, 3))
            self.tool_btns[t] = b
        sz = ttk.Frame(side)
        sz.pack(anchor="w", pady=(0, 6))
        ttk.Label(sz, text="Brush:").pack(side="left")
        self.size_var = tk.IntVar(value=1)
        for n in (1, 2, 3, 4):
            ttk.Radiobutton(sz, text=f"{n}px", value=n, variable=self.size_var, command=lambda: setattr(self, "size", self.size_var.get())).pack(side="left")

        ttk.Label(side, text="Colours", font=("Segoe UI", 10, "bold")).pack(anchor="w", pady=(4, 0))
        pal = ttk.Frame(side)
        pal.pack(anchor="w")
        for i, c in enumerate(PALETTE):
            sw = tk.Label(pal, bg=c, width=3, height=1, relief="raised", borderwidth=1)
            sw.grid(row=i // 8, column=i % 8, padx=1, pady=1)
            sw.bind("<Button-1>", lambda e, c=c: self.set_color(c))
        cur = ttk.Frame(side)
        cur.pack(anchor="w", pady=(4, 6))
        self.cur = tk.Label(cur, bg=self.color, width=6, height=1, relief="sunken")
        self.cur.pack(side="left")
        ttk.Button(cur, text="More colours…", command=self.more_colors).pack(side="left", padx=6)

        ttk.Label(side, text="Pictures (click one, then click the icon)", font=("Segoe UI", 10, "bold")).pack(anchor="w", pady=(4, 0))
        st = ttk.Frame(side)
        st.pack(anchor="w")
        self.stamp_imgs = []
        for i, name in enumerate(stamps.STAMPS):
            img = tk.PhotoImage(width=32, height=32)
            p = stamps.pixels(name)
            for y in range(16):
                for x in range(16):
                    if p[y][x]:
                        img.put(p[y][x], to=(x * 2, y * 2, x * 2 + 2, y * 2 + 2))
            self.stamp_imgs.append(img)
            b = tk.Button(st, image=img, width=36, height=36, relief="raised", command=lambda n=name: self.set_stamp(n))
            b.grid(row=i // 6, column=i % 6, padx=1, pady=1)
            _Tip(b, name)
        ss = ttk.Frame(side)
        ss.pack(anchor="w", pady=(2, 6))
        ttk.Label(ss, text="Picture size:").pack(side="left")
        self.scale_var = tk.IntVar(value=3)
        for n, label in ((1, "16"), (2, "32"), (3, "48"), (4, "64")):
            ttk.Radiobutton(ss, text=label, value=n, variable=self.scale_var, command=lambda: setattr(self, "stamp_scale", self.scale_var.get())).pack(side="left")

        ttk.Label(side, text="Background", font=("Segoe UI", 10, "bold")).pack(anchor="w", pady=(4, 0))
        bg = ttk.Frame(side)
        bg.pack(anchor="w", pady=(2, 6))
        ttk.Button(bg, text="Fill with colour", command=self.fill_background).pack(side="left")
        ttk.Button(bg, text="Gradient", command=self.gradient_background).pack(side="left", padx=3)
        ttk.Button(bg, text="Clear all", command=self.clear).pack(side="left")

        btns = ttk.Frame(f)
        btns.grid(row=1, column=0, columnspan=2, sticky="we", pady=(10, 0))
        ttk.Button(btns, text="Undo", command=self.do_undo).pack(side="left")
        ttk.Button(btns, text="Use this icon", style="Accent.TButton", command=self.save).pack(side="right")
        ttk.Button(btns, text="Cancel", command=self.top.destroy).pack(side="right", padx=6)

        if start and Path(start).exists():
            self.load(start)
        self.set_tool("pencil")
        self.redraw()

    # ---------------------------------------------------------------- drawing
    @staticmethod
    def _bg(x, y):
        return "#FFFFFF" if ((x // 4) + (y // 4)) % 2 == 0 else "#E2E2E2"

    def set_tool(self, t):
        self.tool = t
        self.stamp = None
        for k, b in self.tool_btns.items():
            b.state(["pressed"] if k == t else ["!pressed"])

    def set_color(self, c):
        self.color = c
        self.cur.config(bg=c)
        if self.tool in ("eraser", "picker") or self.stamp:
            self.set_tool("pencil")

    def more_colors(self):
        c = colorchooser.askcolor(self.color, parent=self.top, title="Pick a colour")[1]
        if c:
            self.set_color(c.upper())

    def set_stamp(self, name):
        self.set_tool("stamp")
        self.stamp = name

    def _xy(self, e):
        return max(0, min(N - 1, e.x // CELL)), max(0, min(N - 1, e.y // CELL))

    def _snap(self):
        self.undo.append(list(self.px))
        del self.undo[:-60]

    def _down(self, e, erase=False):
        x, y = self._xy(e)
        if self.tool == "picker" and not erase:
            c = self.px[y * N + x]
            if c:
                self.set_color(c)
            return
        self._snap()
        if self.tool == "fill" and not erase:
            self.flood(x, y, self.color)
        elif self.tool == "stamp" and self.stamp and not erase:
            self.put_stamp(self.stamp, x, y, self.stamp_scale)
        else:
            self.brush(x, y, None if erase or self.tool == "eraser" else self.color)
        self.last = (x, y)
        self.redraw()

    def _drag(self, e, erase=False):
        if self.tool in ("fill", "picker", "stamp") and not erase:
            return
        x, y = self._xy(e)
        c = None if erase or self.tool == "eraser" else self.color
        lx, ly = self.last or (x, y)
        steps = max(abs(x - lx), abs(y - ly), 1)
        for i in range(steps + 1):  # a line, so fast strokes have no gaps
            self.brush(round(lx + (x - lx) * i / steps), round(ly + (y - ly) * i / steps), c)
        self.last = (x, y)
        self.redraw()

    def brush(self, x, y, c):
        for dy in range(self.size):
            for dx in range(self.size):
                X, Y = x + dx - self.size // 2, y + dy - self.size // 2
                if 0 <= X < N and 0 <= Y < N:
                    self.px[Y * N + X] = c

    def flood(self, x, y, c):
        target = self.px[y * N + x]
        if target == c:
            return
        stack = [(x, y)]
        while stack:
            X, Y = stack.pop()
            if 0 <= X < N and 0 <= Y < N and self.px[Y * N + X] == target:
                self.px[Y * N + X] = c
                stack += [(X + 1, Y), (X - 1, Y), (X, Y + 1), (X, Y - 1)]

    def put_stamp(self, name, cx, cy, scale):
        p = stamps.pixels(name)
        size = 16 * scale
        ox, oy = cx - size // 2, cy - size // 2
        ox, oy = max(0, min(N - size, ox)), max(0, min(N - size, oy))
        for y in range(16):
            for x in range(16):
                if p[y][x]:
                    for dy in range(scale):
                        for dx in range(scale):
                            X, Y = ox + x * scale + dx, oy + y * scale + dy
                            if 0 <= X < N and 0 <= Y < N:
                                self.px[Y * N + X] = p[y][x]

    def fill_background(self):
        self._snap()
        self.px = [c if c else self.color for c in self.px]
        self.redraw()

    def gradient_background(self):
        self._snap()
        r, g, b = (int(self.color[i:i + 2], 16) for i in (1, 3, 5))
        for y in range(N):
            k = 1 - 0.55 * y / (N - 1)
            row = "#%02X%02X%02X" % (int(r * k), int(g * k), int(b * k))
            for x in range(N):
                if not self.px[y * N + x]:
                    self.px[y * N + x] = row
        self.redraw()

    def clear(self):
        self._snap()
        self.px = [None] * (N * N)
        self.redraw()

    def do_undo(self):
        if self.undo:
            self.px = self.undo.pop()
            self.redraw()

    def redraw(self):
        shown = getattr(self, "_shown", None) or [0] * (N * N)
        for i, c in enumerate(self.px):
            if shown[i] == c:  # only the pixels that changed
                continue
            x, y = i % N, i // N
            self.canvas.itemconfigure(self.cells[i], fill=c or self._bg(x, y))
        self._shown = list(self.px)
        rows = []
        for y in range(N):
            rows.append("{" + " ".join((self.px[y * N + x] or ("#FFFFFF" if ((x // 4) + (y // 4)) % 2 == 0 else "#E2E2E2")) for x in range(N)) + "}")
        self.preview_img.put(" ".join(rows), to=(0, 0))

    # ---------------------------------------------------------------- files
    def load(self, path):
        try:
            from PIL import Image
            im = Image.open(path).convert("RGBA").resize((N, N))
            for y in range(N):
                for x in range(N):
                    r, g, b, a = im.getpixel((x, y))
                    self.px[y * N + x] = "#%02X%02X%02X" % (r, g, b) if a >= 128 else None
        except Exception:
            pass

    def image(self):
        from PIL import Image
        im = Image.new("RGBA", (N, N), (0, 0, 0, 0))
        for i, c in enumerate(self.px):
            if c:
                im.putpixel((i % N, i // N), tuple(int(c[k:k + 2], 16) for k in (1, 3, 5)) + (255,))
        return im

    def save(self):
        if not any(self.px):
            messagebox.showinfo("Icon", "Draw something first.", parent=self.top)
            return
        d = core.app_dir() / "icons"
        d.mkdir(parents=True, exist_ok=True)
        out = d / f"drawn-{time.strftime('%Y%m%d-%H%M%S')}.png"
        self.image().save(out)
        self.top.destroy()
        self.on_save(str(out))


class _Tip:
    """A little name popup when hovering a picture."""

    def __init__(self, w, text):
        self.w, self.text, self.tip = w, text, None
        w.bind("<Enter>", self.show)
        w.bind("<Leave>", self.hide)

    def show(self, _e):
        self.tip = tk.Toplevel(self.w)
        self.tip.wm_overrideredirect(True)
        self.tip.geometry(f"+{self.w.winfo_rootx() + 20}+{self.w.winfo_rooty() + 40}")
        tk.Label(self.tip, text=self.text, bg="#ffffe0", relief="solid", borderwidth=1, padx=4).pack()

    def hide(self, _e):
        if self.tip:
            self.tip.destroy()
            self.tip = None


def open_drawer(root, start, on_save) -> IconDrawer:
    return IconDrawer(root, start, on_save)
