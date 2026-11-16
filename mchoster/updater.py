"""Updates: checks GitHub for a newer MC Hoster release, shows what's new, and installs it.

The new MC-Hoster.exe is downloaded next to the running one, then a small script waits for MC Hoster to close,
swaps the files and opens the new one. The exe keeps its place, so auto-start tasks keep working.
"""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tempfile
from pathlib import Path

from . import core

REPO = "luaslucas422-lab/MC-Hoster"
API = f"https://api.github.com/repos/{REPO}/releases/latest"
PAGE = f"https://github.com/{REPO}/releases/latest"


def parse(v: str) -> tuple[int, ...]:
    nums = re.findall(r"\d+", v or "")
    return tuple(int(n) for n in nums[:4]) or (0,)


def newer(latest: str, current: str = core.VERSION) -> bool:
    return parse(latest) > parse(current)


def latest() -> dict | None:
    """{"version", "notes", "url" (the exe), "page"} of the newest release, or None if GitHub can't be reached."""
    try:
        d = json.loads(core.http_get(API, timeout=15, headers={"Accept": "application/vnd.github+json"}))
    except Exception:
        return None
    exe = next((a for a in d.get("assets", []) if a.get("name", "").lower().endswith(".exe")), None)
    return {"version": d.get("tag_name", "").lstrip("v"), "notes": d.get("body") or "", "page": d.get("html_url") or PAGE,
            "url": exe.get("browser_download_url") if exe else None, "size": exe.get("size", 0) if exe else 0}


def releases(count: int = 30) -> list[dict] | None:
    """[{"version", "date", "notes"}] newest first, or None if GitHub can't be reached."""
    try:
        d = json.loads(core.http_get(f"https://api.github.com/repos/{REPO}/releases?per_page={count}", timeout=15,
                                     headers={"Accept": "application/vnd.github+json"}))
    except Exception:
        return None
    return [{"version": r.get("tag_name", "").lstrip("v"), "date": (r.get("published_at") or "")[:10],
             "notes": r.get("body") or ""} for r in d if not r.get("draft")]


def plain(md: str) -> str:
    """Release notes (Markdown) as plain text for the window."""
    out = []
    for line in md.replace("\r", "").splitlines():
        line = re.sub(r"^#+\s*", "", line)
        line = re.sub(r"^\s*[-*]\s+", "  • ", line)
        line = line.replace("**", "").replace("`", "")
        line = re.sub(r"\[([^\]]+)\]\([^)]+\)", r"\1", line)
        out.append(line)
    return re.sub(r"\n{3,}", "\n\n", "\n".join(out)).strip()


# ---------------------------------------------------------------- "skip this version"

def _state_file() -> Path:
    return core.app_dir() / "update.json"


def skipped() -> str:
    try:
        return json.loads(_state_file().read_text()).get("skip", "")
    except Exception:
        return ""


def skip(version: str) -> None:
    try:
        _state_file().write_text(json.dumps({"skip": version}))
    except OSError:
        pass


# ---------------------------------------------------------------- installing

def can_install() -> bool:
    """Only the .exe can replace itself (running from the source code: download it from GitHub)."""
    return core.WINDOWS and getattr(sys, "frozen", False)


def download(info: dict, progress=None) -> Path:
    """Downloads the new exe next to the running one (or to temp if that folder can't be written)."""
    exe = Path(sys.executable)
    target = exe.with_name(exe.stem + ".new.exe")
    try:
        target.touch()
    except OSError:
        target = Path(tempfile.gettempdir()) / "MC-Hoster.new.exe"
    core.download(info["url"], target, progress)
    if info.get("size") and target.stat().st_size != info["size"]:
        target.unlink(missing_ok=True)
        raise IOError("the download was incomplete, try again")
    return target


def swap_script(new: Path, exe: Path, pids: list[int]) -> str:
    sleep = "ping -n 2 127.0.0.1 >nul"  # "timeout" needs a console, this script has none
    waits = "".join(f':wait{p}\r\ntasklist /FI "PID eq {p}" 2>nul | find " {p} " >nul && ({sleep} & goto wait{p})\r\n' for p in pids)
    return (
        "@echo off\r\n"
        + waits +
        f'for /l %%i in (1,1,30) do (move /y "{new}" "{exe}" >nul 2>&1 && goto moved || {sleep})\r\n'
        ":moved\r\n"
        f'{sleep} & {sleep} & {sleep}\r\n'  # let the old copy's temp files go before the new one opens
        f'start "" /d "{exe.parent}" "{exe}"\r\n'
        '(goto) 2>nul & del "%~f0"\r\n'
    )


def install(new: Path) -> None:
    """Starts the swap script. MC Hoster must close right after this."""
    exe = Path(sys.executable)
    bat = Path(tempfile.gettempdir()) / "mchoster-update.bat"
    # the .exe is a small launcher (parent) around this Python process: wait for both to close
    bat.write_text(swap_script(new, exe, [os.getpid(), os.getppid()]), encoding="mbcs" if core.WINDOWS else "utf-8", errors="replace")
    # The old copy's PyInstaller settings must not reach the new one, or it opens its files in the folder that is
    # being deleted and crashes (the "updating just crashes" bug).
    env = {k: v for k, v in os.environ.items() if not k.startswith(("_PYI", "_MEI")) and k != "_MEIPASS2"}
    env["PYINSTALLER_RESET_ENVIRONMENT"] = "1"
    subprocess.Popen(["cmd", "/c", str(bat)], creationflags=0x08000000 | 0x00000200,  # no window, own process group
                     close_fds=True, cwd=str(exe.parent), env=env)
