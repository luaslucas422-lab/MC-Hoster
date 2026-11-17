"""MC Hoster core: everything that is not the window.

Downloads server jars and Java, writes the server files, adds the server to the
Minecraft multiplayer list, opens the port with UPnP and runs the server.
No third-party imports here except Pillow (only for the server icon).
"""
from __future__ import annotations

import base64
import glob
import io
import json
import os
import re
import shutil
import socket
import struct
import subprocess
import sys
import tarfile
import threading
import time
import urllib.parse
import urllib.request
import zipfile
from dataclasses import dataclass, field, asdict
from pathlib import Path
from xml.etree import ElementTree

APP_NAME = "MC Hoster"
VERSION = "3.5.1"
AUTHOR = "DarkerDawnFR"
UA = f"MC-Hoster/{VERSION} (github.com/luaslucas422-lab/MC-Hoster)"
PORT = 25565
BEDROCK_PORT = 19132
BROWSER_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36"
MAX_PLAYERS = 1500
# players above each limit need this many "yes" answers
CONFIRM_TIERS = [(1000, 7), (500, 5), (100, 2)]


def confirmations_needed(players: int) -> int:
    for limit, times in CONFIRM_TIERS:
        if players > limit:
            return times
    return 0


def confirmed_cap(times: int) -> int:
    """Most players that `times` confirmations allow."""
    for limit, need in reversed(CONFIRM_TIERS):
        if times < need:
            return limit
    return MAX_PLAYERS
WINDOWS = os.name == "nt"
NO_WINDOW = 0x08000000 if WINDOWS else 0  # CREATE_NO_WINDOW, so java never pops a console

# Oldest version the Bukkit build of Gh0st AC runs on, and the only one the Fabric build is for.
GH0ST_BUKKIT_MIN = (1, 20, 6)
GH0ST_FABRIC_VERSIONS = ["1.21.1"]
FABRIC_API_PINNED = {"1.21.1": "0.115.0+1.21.1"}  # same one Gh0st AC's CI tests with
# Only used when the version APIs cannot be reached.
FALLBACK_VERSIONS = ["26.3", "26.1.2", "26.1.1", "26.1", "1.21.11", "1.21.10", "1.21.9", "1.21.8", "1.21.7",
                     "1.21.6", "1.21.5", "1.21.4", "1.21.3", "1.21.1", "1.21", "1.20.6"]


# --------------------------------------------------------------------------- paths

def resource(*parts: str) -> Path:
    """A file shipped with the app (inside the .exe when frozen)."""
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parent.parent))
    return base.joinpath(*parts)


def app_dir() -> Path:
    if WINDOWS:
        base = Path(os.environ.get("LOCALAPPDATA") or Path.home() / "AppData" / "Local")
    else:
        base = Path.home() / ".local" / "share"
    d = base / "MC-Hoster"
    d.mkdir(parents=True, exist_ok=True)
    return d


def servers_dir() -> Path:
    docs = Path.home() / "Documents"
    d = (docs if docs.is_dir() else Path.home()) / "MC Hoster Servers"
    d.mkdir(parents=True, exist_ok=True)
    return d


# --------------------------------------------------------------------------- http

def http_get(url: str, timeout: float = 30, headers: dict | None = None, data: bytes | None = None) -> bytes:
    h = {"User-Agent": UA}
    h.update(headers or {})
    req = urllib.request.Request(url, headers=h, data=data)
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read()


def http_json(url: str, timeout: float = 30):
    return json.loads(http_get(url, timeout).decode("utf-8"))


def download(url: str, dest: Path, progress=None, timeout: float = 60, headers: dict | None = None) -> Path:
    """Download to dest (through a .part file). progress(done_bytes, total_bytes_or_0)."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    h = {"User-Agent": UA}
    h.update(headers or {})
    req = urllib.request.Request(url, headers=h)
    with urllib.request.urlopen(req, timeout=timeout) as r, open(part, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done = 0
        while True:
            chunk = r.read(256 * 1024)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if progress:
                progress(done, total)
    if part.stat().st_size == 0:
        part.unlink()
        raise IOError(f"Empty download: {url}")
    os.replace(part, dest)
    return dest


# --------------------------------------------------------------------------- versions

def vtuple(v: str) -> tuple:
    return tuple(int(x) for x in v.split("."))


def is_release(v: str) -> bool:
    return re.fullmatch(r"\d+(\.\d+){1,3}", v) is not None


def sort_versions(vs) -> list[str]:
    out = sorted({v for v in vs if is_release(v)}, key=vtuple, reverse=True)
    return out


@dataclass
class Provider:
    id: str
    name: str
    kind: str          # "fabric" (mods/), "bukkit" (plugins/) or "bedrock"
    blurb: str
    slow: bool = False

    @property
    def addon_folder(self) -> str:
        return {"fabric": "mods", "bukkit": "plugins"}.get(self.kind, "")

    @property
    def bedrock(self) -> bool:
        return self.kind == "bedrock"


PROVIDERS = [
    Provider("paper", "Paper", "bukkit", "Fast and the most popular. Plugins go in the plugins folder. Best pick for most people."),
    Provider("fabric", "Fabric", "fabric", "Mod loader. Server-side Fabric mods go in the mods folder. Fabric API is installed for you."),
    Provider("purpur", "Purpur", "bukkit", "Paper with lots of extra options. Runs every Paper plugin."),
    Provider("pufferfish", "Pufferfish", "bukkit", "Paper fork tuned for big servers. Only its newest 1.21 build is offered."),
    Provider("spigot", "Spigot", "bukkit", "The original plugin server. It has to be compiled on your PC first, which takes 5 to 10 minutes.", slow=True),
    Provider("bedrock", "Bedrock", "bedrock", "Mojang's official Bedrock Dedicated Server, for phone, console and Windows 10/11 players. "
             "No plugins, mods or Gh0st AC (those are Java only). Want both? Pick Paper and add Geyser in tab 5."),
]
PROVIDER_BY_NAME = {p.name: p for p in PROVIDERS}
PROVIDER_BY_ID = {p.id: p for p in PROVIDERS}


def _paper_versions(project: str = "paper") -> list[str]:
    try:
        j = http_json(f"https://fill.papermc.io/v3/projects/{project}", 15)
        vs = j.get("versions", [])
        if isinstance(vs, dict):  # v3 groups them: {"1.21": ["1.21.11", ...], ...}
            vs = [v for group in vs.values() for v in group]
        if vs:
            return list(vs)
    except Exception:
        pass
    j = http_json(f"https://api.papermc.io/v2/projects/{project}", 15)
    return list(j.get("versions", []))


def _pufferfish_latest() -> tuple[str, str]:
    """(minecraft version, jar url) of the newest Pufferfish 1.21 build."""
    base = "https://ci.pufferfish.host/job/Pufferfish-1.21/lastSuccessfulBuild/"
    j = http_json(base + "api/json", 15)
    arts = [a for a in j.get("artifacts", []) if a.get("fileName", "").endswith(".jar")]
    if not arts:
        raise IOError("Pufferfish has no build to download right now")
    arts.sort(key=lambda a: ("mojmap" not in a["fileName"], "paperclip" not in a["fileName"]))
    a = arts[0]
    m = re.search(r"(\d+\.\d+(?:\.\d+)?)", a["fileName"])
    return (m.group(1) if m else "1.21"), base + "artifact/" + a["relativePath"]


BEDROCK_LINKS = "https://net-secondary.web.minecraft-services.net/api/v1.0/download/links"


def _bedrock_latest() -> tuple[str, str]:
    """(version, zip url) of the newest Bedrock Dedicated Server for this OS."""
    j = json.loads(http_get(BEDROCK_LINKS, 20, {"User-Agent": BROWSER_UA}).decode("utf-8"))
    want = "serverBedrockWindows" if WINDOWS else "serverBedrockLinux"
    for link in j["result"]["links"]:
        if link.get("downloadType") == want:
            url = link["downloadUrl"]
            m = re.search(r"bedrock-server-([\d.]+)\.zip", url)
            return (m.group(1) if m else "latest"), url
    raise IOError("Mojang's download list has no Bedrock server for this PC")


def list_versions(provider: Provider) -> list[str]:
    """Versions shown for a provider: the ones Gh0st AC has a build for, newest first."""
    if provider.bedrock:
        return [_bedrock_latest()[0]]
    if provider.id == "fabric":
        return list(GH0ST_FABRIC_VERSIONS)
    if provider.id == "pufferfish":
        return [_pufferfish_latest()[0]]
    try:
        if provider.id == "purpur":
            vs = http_json("https://api.purpurmc.org/v2/purpur", 15).get("versions", [])
        else:  # paper, and spigot (Spigot ships the same release versions as Paper)
            vs = _paper_versions()
    except Exception:
        vs = FALLBACK_VERSIONS
    return [v for v in sort_versions(vs) if vtuple(v) >= GH0ST_BUKKIT_MIN]


def gh0st_supported(provider: Provider, version: str) -> bool:
    if not is_release(version) or provider.bedrock:
        return False
    if provider.kind == "fabric":
        return version in GH0ST_FABRIC_VERSIONS
    return vtuple(version) >= GH0ST_BUKKIT_MIN


def gh0st_jar(provider: Provider) -> Path | None:
    name = "gh0st-ac-fabric.jar" if provider.kind == "fabric" else "gh0st-ac-bukkit.jar"
    for p in (resource("gh0stac", name), Path(sys.argv[0]).resolve().parent / "gh0stac" / name):
        if p.is_file():
            return p
    return None


def required_java(version: str) -> int:
    v = vtuple(version) if is_release(version) else (1, 21)
    if v[0] >= 26:
        return 25
    if v >= (1, 20, 5):
        return 21
    return 17


# --------------------------------------------------------------------------- Minecraft folder

def default_minecraft_dir() -> Path:
    if WINDOWS:
        return Path(os.environ.get("APPDATA") or Path.home() / "AppData" / "Roaming") / ".minecraft"
    if sys.platform == "darwin":
        return Path.home() / "Library" / "Application Support" / "minecraft"
    return Path.home() / ".minecraft"


def looks_like_minecraft_dir(p: Path) -> bool:
    return p.is_dir() and any((p / x).exists() for x in ("launcher_profiles.json", "versions", "options.txt", "servers.dat"))


def find_minecraft_dir() -> Path | None:
    cands = [default_minecraft_dir()]
    if WINDOWS:  # other launchers that keep their own .minecraft-style folder
        appdata = Path(os.environ.get("APPDATA", ""))
        cands += [appdata / ".tlauncher" / "legacy" / "Minecraft" / "game", appdata / ".minecraft"]
    for c in cands:
        if looks_like_minecraft_dir(c):
            return c
    return None


# --------------------------------------------------------------------------- Java

def java_major(exe: str | Path) -> int | None:
    try:
        r = subprocess.run([str(exe), "-version"], capture_output=True, text=True, timeout=20, creationflags=NO_WINDOW)
    except Exception:
        return None
    m = re.search(r'version "(\d+)(?:\.(\d+))?', r.stderr + r.stdout)
    if not m:
        return None
    major = int(m.group(1))
    if major == 1 and m.group(2):  # "1.8.0_391"
        major = int(m.group(2))
    return major


def java_is_32bit(exe: str | Path) -> bool:
    """32-bit Java can't use more than about 1.5 GB, so a server with more RAM dies right away."""
    try:
        r = subprocess.run([str(exe), "-version"], capture_output=True, text=True, timeout=20, creationflags=NO_WINDOW)
    except Exception:
        return False
    out = r.stderr + r.stdout
    return bool(re.search(r"\b32-Bit\b", out, re.I)) or ("64-Bit" not in out and "Client VM" in out)


def _java_candidates(mc_dir: Path | None) -> list[Path]:
    exe = "java.exe" if WINDOWS else "java"
    c: list[Path] = []
    own = app_dir() / "java"
    c += [Path(p) for p in glob.glob(str(own / "*" / "**" / "bin" / exe), recursive=True)]
    if os.environ.get("JAVA_HOME"):
        c.append(Path(os.environ["JAVA_HOME"]) / "bin" / exe)
    w = shutil.which("java")
    if w:
        c.append(Path(w))
    if mc_dir:  # the Java the Minecraft Launcher downloaded
        c += [Path(p) for p in glob.glob(str(mc_dir / "runtime" / "*" / "**" / "bin" / exe), recursive=True)]
    if WINDOWS:
        local = os.environ.get("LOCALAPPDATA", "")
        c += [Path(p) for p in glob.glob(local + r"\Packages\Microsoft.4297127D64EC6_8wekyb3d8bbwe\LocalCache\Local\runtime\*\**\bin\java.exe", recursive=True)]
        for pf in {os.environ.get("ProgramFiles", r"C:\Program Files"), os.environ.get("ProgramW6432", r"C:\Program Files")}:
            for vendor in ("Eclipse Adoptium", "Java", "Microsoft", "Zulu", "Amazon Corretto", "BellSoft"):
                c += [Path(p) for p in glob.glob(os.path.join(pf, vendor, "*", "bin", "java.exe"))]
    seen, out = set(), []
    for p in c:
        k = str(p).lower()
        if k not in seen and p.is_file():
            seen.add(k)
            out.append(p)
    return out


def is_jdk(java: Path) -> bool:
    return (java.parent / ("javac.exe" if WINDOWS else "javac")).is_file()


def find_java(min_major: int, mc_dir: Path | None = None, jdk: bool = False) -> tuple[Path, int] | None:
    """jdk=True only accepts a full JDK (Spigot's BuildTools has to compile)."""
    best = None
    for p in _java_candidates(mc_dir):
        if jdk and not is_jdk(p):
            continue
        m = java_major(p)
        if m and java_is_32bit(p):
            continue
        if m and m >= min_major and (best is None or m < best[1]):  # the closest version is the safest
            best = (p, m)
    return best


def download_java(major: int, progress=None, log=print, jdk: bool = False) -> Path:
    os_name = "windows" if WINDOWS else ("mac" if sys.platform == "darwin" else "linux")
    import platform
    arch = {"amd64": "x64", "x86_64": "x64", "arm64": "aarch64", "aarch64": "aarch64"}.get(platform.machine().lower(), "x64")
    url = f"https://api.adoptium.net/v3/binary/latest/{major}/ga/{os_name}/{arch}/{'jdk' if jdk else 'jre'}/hotspot/normal/eclipse"
    target = app_dir() / "java" / (f"{major}-jdk" if jdk else str(major))
    archive = app_dir() / "java" / (f"java{major}.zip" if WINDOWS else f"java{major}.tar.gz")
    log(f"Downloading Java {major}{' JDK' if jdk else ''} (Eclipse Temurin)...")
    download(url, archive, progress, timeout=120)
    shutil.rmtree(target, ignore_errors=True)
    target.mkdir(parents=True)
    if WINDOWS:
        with zipfile.ZipFile(archive) as z:
            z.extractall(target)
    else:
        with tarfile.open(archive) as t:
            t.extractall(target)
    archive.unlink(missing_ok=True)
    exe = "java.exe" if WINDOWS else "java"
    found = glob.glob(str(target / "**" / "bin" / exe), recursive=True)
    if not found:
        raise IOError("Java was downloaded but java" + (".exe" if WINDOWS else "") + " is missing from it")
    if not WINDOWS:
        os.chmod(found[0], 0o755)
    return Path(found[0])


# --------------------------------------------------------------------------- server jars

def _stable_first(items: list) -> str:
    for it in items:
        if it.get("stable", True):
            return it["version"]
    return items[0]["version"]


def get_server_jar(provider: Provider, version: str, d: Path, java: Path, log=print, progress=None) -> str:
    """Puts the server jar in d and returns its file name."""
    if provider.id == "paper":
        url = None
        try:
            b = http_json(f"https://fill.papermc.io/v3/projects/paper/versions/{version}/builds/latest")
            url = b["downloads"]["server:default"]["url"]
        except Exception:
            builds = http_json(f"https://api.papermc.io/v2/projects/paper/versions/{version}/builds")["builds"]
            last = builds[-1]
            url = (f"https://api.papermc.io/v2/projects/paper/versions/{version}/builds/{last['build']}"
                   f"/downloads/{last['downloads']['application']['name']}")
        log(f"Downloading Paper {version}...")
        download(url, d / "server.jar", progress)
    elif provider.id == "purpur":
        log(f"Downloading Purpur {version}...")
        download(f"https://api.purpurmc.org/v2/purpur/{version}/latest/download", d / "server.jar", progress)
    elif provider.id == "pufferfish":
        _, url = _pufferfish_latest()
        log("Downloading Pufferfish...")
        download(url, d / "server.jar", progress)
    elif provider.id == "fabric":
        loaders = http_json(f"https://meta.fabricmc.net/v2/versions/loader/{version}")
        loader = next((l["loader"]["version"] for l in loaders if l["loader"].get("stable", True)), loaders[0]["loader"]["version"])
        installer = _stable_first(http_json("https://meta.fabricmc.net/v2/versions/installer"))
        log(f"Downloading Fabric {version} (loader {loader})...")
        download(f"https://meta.fabricmc.net/v2/versions/loader/{version}/{loader}/{installer}/server/jar", d / "server.jar", progress)
        log("Downloading Fabric API...")
        get_fabric_api(version, d / "mods", progress)
    elif provider.id == "spigot":
        bt = app_dir() / "buildtools"
        bt.mkdir(parents=True, exist_ok=True)
        log("Downloading BuildTools (Spigot has to be compiled on your PC)...")
        download("https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar", bt / "BuildTools.jar", progress)
        log(f"Compiling Spigot {version}. This takes 5 to 10 minutes, the log below shows it working.")
        p = subprocess.Popen([str(java), "-jar", "BuildTools.jar", "--rev", version, "--output-dir", str(d)], cwd=bt,
                             stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, errors="replace", creationflags=NO_WINDOW)
        for line in p.stdout:
            log("  " + line.rstrip())
        if p.wait() != 0:
            raise IOError("BuildTools failed to compile Spigot " + version)
        jars = sorted(d.glob("spigot-*.jar"))
        if not jars:
            raise IOError("BuildTools finished but no spigot jar was made")
        os.replace(jars[-1], d / "server.jar")
    else:
        raise ValueError(provider.id)
    return "server.jar"


def get_fabric_api(version: str, mods: Path, progress=None) -> None:
    mods.mkdir(parents=True, exist_ok=True)
    try:
        q = urllib.parse.urlencode({"game_versions": json.dumps([version]), "loaders": json.dumps(["fabric"])})
        vs = http_json(f"https://api.modrinth.com/v2/project/fabric-api/version?{q}")
        f = next(f for f in vs[0]["files"] if f.get("primary")) if any(f.get("primary") for f in vs[0]["files"]) else vs[0]["files"][0]
        download(f["url"], mods / "fabric-api.jar", progress)
        return
    except Exception:
        pass
    v = FABRIC_API_PINNED.get(version)
    if not v:
        raise IOError("Could not find Fabric API for " + version)
    download(f"https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{v}/fabric-api-{v}.jar", mods / "fabric-api.jar", progress)


# --------------------------------------------------------------------------- PC info

def free_ram_mb() -> int | None:
    """RAM that is free right now (other programs, like Minecraft itself, are using the rest)."""
    try:
        if WINDOWS:
            import ctypes

            class MEMSTAT(ctypes.Structure):
                _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                            ("sullAvailExtendedVirtual", ctypes.c_ulonglong)]
            s = MEMSTAT()
            s.dwLength = ctypes.sizeof(MEMSTAT)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(s))
            return int(min(s.ullAvailPhys, s.ullAvailPageFile) // (1024 * 1024))
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemAvailable"):
                    return int(line.split()[1]) // 1024
    except Exception:
        pass
    return None


def heap_for_start(cfg: "ServerConfig") -> int:
    """The -Xmx to start with: what was chosen, but never more than the PC has free right now (an automatic
    amount only; a number typed in the Config tab is used as is)."""
    mb = cfg.ram_mb
    if tweak(cfg, "ram_mb", 0):
        return mb
    free = free_ram_mb()
    if free:
        mb = min(mb, max(768, int(free * 0.75)))
    return max(768, (mb // 128) * 128)


def folder_size(path: str | Path) -> int:
    """Bytes used by everything inside a folder (what the Max storage usage tab watches)."""
    total = 0
    stack = [str(path)]
    while stack:
        try:
            with os.scandir(stack.pop()) as it:
                for e in it:
                    try:
                        if e.is_dir(follow_symlinks=False):
                            stack.append(e.path)
                        elif e.is_file(follow_symlinks=False):
                            total += e.stat(follow_symlinks=False).st_size
                    except OSError:
                        pass
        except OSError:
            pass
    return total


def storage_limit_bytes(cfg: "ServerConfig") -> int:
    """0 when there is no limit."""
    st = cfg.storage or {}
    if not st.get("enabled"):
        return 0
    try:
        return int(float(st.get("max_gb", 0)) * 1024 ** 3)
    except (TypeError, ValueError):
        return 0


def fmt_bytes(n: float) -> str:
    return f"{n / 1024 ** 3:.2f} GB" if n >= 1024 ** 3 else f"{n / 1024 ** 2:.0f} MB"


def total_ram_mb() -> int:
    try:
        if WINDOWS:
            import ctypes

            class MEMSTAT(ctypes.Structure):
                _fields_ = [("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                            ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                            ("ullTotalPageFile", ctypes.c_ulonglong), ("ullAvailPageFile", ctypes.c_ulonglong),
                            ("ullTotalVirtual", ctypes.c_ulonglong), ("ullAvailVirtual", ctypes.c_ulonglong),
                            ("sullAvailExtendedVirtual", ctypes.c_ulonglong)]
            s = MEMSTAT()
            s.dwLength = ctypes.sizeof(MEMSTAT)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(s))
            return int(s.ullTotalPhys // (1024 * 1024))
        with open("/proc/meminfo") as f:
            for line in f:
                if line.startswith("MemTotal"):
                    return int(line.split()[1]) // 1024
    except Exception:
        pass
    return 8192


def ram_needed_mb(players: int, modded: bool = False) -> int:
    """What the server really wants, with no PC limit. 2 GB base, 64 MB for each of the
    first 20 players, 128 MB up to 100, 192 MB up to 500 and 256 MB for every player after that."""
    p = max(1, players)
    mb = 2048 + (512 if modded else 0)
    mb += min(p, 20) * 64
    mb += max(0, min(p, 100) - 20) * 128
    mb += max(0, min(p, 500) - 100) * 192
    mb += max(0, p - 500) * 256
    return mb


def ram_for_players(players: int, modded: bool = False) -> int:
    """MB of RAM to give the server: what it needs, but never more than 60 % of the PC."""
    cap = max(1024, int(total_ram_mb() * 0.6))
    mb = min(ram_needed_mb(players, modded), cap)
    return max(1024, (mb // 256) * 256)


def lag_level(players: int) -> tuple[int, str]:
    """0..3 and a short description of how hard the PC will work."""
    ram = total_ram_mb()
    need = ram_needed_mb(players)
    score = players / 25 + need / max(ram, 1) * 2
    if score < 1.0:
        return 0, "Smooth, your PC will barely notice"
    if score < 2.0:
        return 1, "Fine on most PCs"
    if score < 3.2:
        return 2, "Might lag, more if you play on this PC too"
    return 3, "Heavy, expect lag unless your PC is strong"


def view_distance(players: int) -> int:
    return 10 if players <= 10 else 8 if players <= 30 else 6


# --------------------------------------------------------------------------- network

def lan_ip() -> str:
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("8.8.8.8", 80))
        ip = s.getsockname()[0]
        s.close()
        return ip
    except Exception:
        return "127.0.0.1"


def public_ip() -> str | None:
    for url in ("https://api.ipify.org", "https://ifconfig.me/ip", "https://icanhazip.com"):
        try:
            ip = http_get(url, 8).decode().strip()
            if re.fullmatch(r"[0-9a-fA-F:.]+", ip):
                return ip
        except Exception:
            continue
    return None


class UPnP:
    """Just enough UPnP IGD to open and close one TCP port on a home router."""

    def __init__(self):
        self.control_url = None
        self.service = None

    def discover(self, timeout: float = 3.0) -> bool:
        msg = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n"
               "MX: 2\r\nST: urn:schemas-upnp-org:device:InternetGatewayDevice:1\r\n\r\n").encode()
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM, socket.IPPROTO_UDP)
        s.settimeout(timeout)
        locations = []
        try:
            s.setsockopt(socket.IPPROTO_IP, socket.IP_MULTICAST_TTL, 2)
            for _ in range(2):
                s.sendto(msg, ("239.255.255.250", 1900))
            end = time.time() + timeout
            while time.time() < end:
                try:
                    data, _ = s.recvfrom(4096)
                except socket.timeout:
                    break
                m = re.search(rb"(?im)^location:\s*(\S+)", data)
                if m and m.group(1).decode() not in locations:
                    locations.append(m.group(1).decode())
        except OSError:
            pass
        finally:
            s.close()
        for loc in locations:
            try:
                xml = http_get(loc, 5).decode("utf-8", "replace")
                root = ElementTree.fromstring(xml)
                for svc in root.iter():
                    if not svc.tag.endswith("service"):
                        continue
                    kids = {c.tag.split("}")[-1]: (c.text or "").strip() for c in svc}
                    st = kids.get("serviceType", "")
                    if "WANIPConnection" in st or "WANPPPConnection" in st:
                        self.service = st
                        self.control_url = urllib.parse.urljoin(loc, kids.get("controlURL", ""))
                        return True
            except Exception:
                continue
        return False

    def _soap(self, action: str, args: dict) -> str:
        body = "".join(f"<{k}>{v}</{k}>" for k, v in args.items())
        env = ('<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" '
               's:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/"><s:Body>'
               f'<u:{action} xmlns:u="{self.service}">{body}</u:{action}></s:Body></s:Envelope>')
        return http_get(self.control_url, 6, {"Content-Type": 'text/xml; charset="utf-8"',
                                               "SOAPAction": f'"{self.service}#{action}"'}, env.encode()).decode("utf-8", "replace")

    def open(self, port: int, ip: str, desc: str = "MC Hoster", proto: str = "TCP") -> bool:
        if not self.control_url and not self.discover():
            return False
        try:
            self._soap("AddPortMapping", {"NewRemoteHost": "", "NewExternalPort": port, "NewProtocol": proto,
                                          "NewInternalPort": port, "NewInternalClient": ip, "NewEnabled": 1,
                                          "NewPortMappingDescription": desc, "NewLeaseDuration": 0})
            return True
        except Exception:
            return False

    def close(self, port: int, proto: str = "TCP") -> None:
        if self.control_url:
            try:
                self._soap("DeletePortMapping", {"NewRemoteHost": "", "NewExternalPort": port, "NewProtocol": proto})
            except Exception:
                pass

    def external_ip(self) -> str | None:
        try:
            m = re.search(r"<NewExternalIPAddress>([^<]+)<", self._soap("GetExternalIPAddress", {}))
            return m.group(1) if m else None
        except Exception:
            return None


# --------------------------------------------------------------------------- servers.dat (NBT)

class NBT:
    """Uncompressed NBT, which is what servers.dat is. Keeps every tag type so nothing is lost."""

    @staticmethod
    def read(data: bytes):
        buf = io.BytesIO(data)
        t = buf.read(1)[0]
        name = NBT._str(buf)
        return name, (t, NBT._payload(t, buf))

    @staticmethod
    def _str(buf):
        n = struct.unpack(">H", buf.read(2))[0]
        return buf.read(n).decode("utf-8", "replace")

    @staticmethod
    def _payload(t, buf):
        fmt = {1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}
        if t in fmt:
            size = struct.calcsize(fmt[t])
            return struct.unpack(fmt[t], buf.read(size))[0]
        if t == 7:
            n = struct.unpack(">i", buf.read(4))[0]
            return buf.read(n)
        if t == 8:
            return NBT._str(buf)
        if t == 9:
            et = buf.read(1)[0]
            n = struct.unpack(">i", buf.read(4))[0]
            return (et, [NBT._payload(et, buf) for _ in range(n)])
        if t == 10:
            out = {}
            while True:
                ct = buf.read(1)
                if not ct or ct[0] == 0:
                    return out
                k = NBT._str(buf)
                out[k] = (ct[0], NBT._payload(ct[0], buf))
        if t == 11:
            n = struct.unpack(">i", buf.read(4))[0]
            return list(struct.unpack(f">{n}i", buf.read(4 * n)))
        if t == 12:
            n = struct.unpack(">i", buf.read(4))[0]
            return list(struct.unpack(f">{n}q", buf.read(8 * n)))
        raise ValueError(f"bad NBT tag {t}")

    @staticmethod
    def write(name: str, tag) -> bytes:
        out = io.BytesIO()
        t, v = tag
        out.write(bytes([t]))
        NBT._wstr(out, name)
        NBT._wpayload(out, t, v)
        return out.getvalue()

    @staticmethod
    def _wstr(out, s):
        b = s.encode("utf-8")
        out.write(struct.pack(">H", len(b)) + b)

    @staticmethod
    def _wpayload(out, t, v):
        fmt = {1: ">b", 2: ">h", 3: ">i", 4: ">q", 5: ">f", 6: ">d"}
        if t in fmt:
            out.write(struct.pack(fmt[t], v))
        elif t == 7:
            out.write(struct.pack(">i", len(v)) + bytes(v))
        elif t == 8:
            NBT._wstr(out, v)
        elif t == 9:
            et, items = v
            out.write(bytes([et if items else (et or 0)]) + struct.pack(">i", len(items)))
            for it in items:
                NBT._wpayload(out, et, it)
        elif t == 10:
            for k, (ct, cv) in v.items():
                out.write(bytes([ct]))
                NBT._wstr(out, k)
                NBT._wpayload(out, ct, cv)
            out.write(b"\x00")
        elif t == 11:
            out.write(struct.pack(">i", len(v)) + struct.pack(f">{len(v)}i", *v))
        elif t == 12:
            out.write(struct.pack(">i", len(v)) + struct.pack(f">{len(v)}q", *v))


def add_to_server_list(mc_dir: Path, name: str, address: str, icon_png: bytes | None = None) -> bool:
    """Adds (or updates) the server in the Multiplayer list. Returns False if it was already there unchanged."""
    f = mc_dir / "servers.dat"
    root = {}
    if f.exists() and f.stat().st_size > 0:
        _, (t, root) = NBT.read(f.read_bytes())  # raises on a broken file, so we never overwrite one
        if t != 10:
            raise ValueError("servers.dat is not a compound")
        shutil.copy2(f, f.with_name("servers.dat.mchoster-backup"))
    lst = root.get("servers", (9, (10, [])))[1]
    et, items = lst
    entry = {"name": (8, name), "ip": (8, address), "acceptTextures": (1, 1)}
    if icon_png:
        entry["icon"] = (8, base64.b64encode(icon_png).decode())
    for it in items:
        if it.get("ip", (8, ""))[1] == address:
            it.update(entry)
            break
    else:
        items.insert(0, entry)
    root["servers"] = (9, (10, items))
    tmp = f.with_name("servers.dat.tmp")
    tmp.write_bytes(NBT.write("", (10, root)))
    os.replace(tmp, f)
    return True


# --------------------------------------------------------------------------- server folder


GAMEMODES = ["Survival", "Creative", "Adventure", "Spectator"]
BEDROCK_GAMEMODES = ["Survival", "Creative", "Adventure"]
DIFFICULTIES = ["Peaceful", "Easy", "Normal", "Hard"]


@dataclass
class ServerConfig:
    name: str = "My Server"
    provider: str = "paper"
    version: str = "1.21.11"
    players: int = 10
    gamemode: str = "Survival"
    difficulty: str = "Normal"
    hardcore: bool = False
    nether: bool = True   # the Nether can be closed (allow-nether)
    end: bool = True      # the End can be closed (Paper/Spigot: bukkit.yml allow-end)
    gh0stac: bool = True
    owner: str = ""       # the owner's Minecraft name: made op, and gets Dark Ranks' owner rank
    icon_path: str = ""
    minecraft_dir: str = ""      # .minecraft for Java, the minecraftpe folder for Bedrock
    add_to_list: bool = True
    eula: bool = False
    addons: list = field(default_factory=list)        # ids of preset plugins/mods (tab 5)
    addon_files: dict = field(default_factory=dict)   # id -> file names in plugins/ or mods/
    schedule: dict = field(default_factory=dict)      # auto-start, see schedule.py
    domain: dict = field(default_factory=dict)        # custom IP (play.yourdomain.com), see domain.py
    tweaks: dict = field(default_factory=dict)        # tab 6 Config: server.properties, gamerules, RAM... (only what was changed)
    resource_pack: dict = field(default_factory=dict) # url, sha1, required, prompt, name, source
    auto_restart: dict = field(default_factory=dict)  # every_hours (0 = off), on_crash
    experimental: dict = field(default_factory=dict)  # tab 7: startup commands {"enabled": bool, "commands": [...]}
    storage: dict = field(default_factory=dict)       # tab 9: {"enabled": bool, "max_gb": number}
    custom_jars: list = field(default_factory=list)   # tab 5 "My plugins": full paths of the jars that are ticked
    # filled in by setup
    folder: str = ""
    java: str = ""
    ram_mb: int = 2048
    port: int = PORT
    installed_version: str = ""

    @property
    def prov(self) -> Provider:
        return PROVIDER_BY_ID[self.provider]

    def save(self):
        Path(self.folder, "mchoster.json").write_text(json.dumps(asdict(self), indent=2), encoding="utf-8")

    @staticmethod
    def load(folder: Path) -> "ServerConfig":
        d = json.loads((Path(folder) / "mchoster.json").read_text(encoding="utf-8"))
        c = ServerConfig(**{k: v for k, v in d.items() if k in ServerConfig.__dataclass_fields__})
        c.folder = str(folder)
        if not c.installed_version:
            c.installed_version = c.version
        return c


def my_servers() -> list[ServerConfig]:
    out = []
    for f in sorted(servers_dir().glob("*/mchoster.json")):
        try:
            out.append(ServerConfig.load(f.parent))
        except Exception:
            continue
    return out


# Windows can't have <>:"/\|?* in names, and Java can't start a server from a folder with ! in its path
# (jar paths use "!/"); # % ^ & ; ` also break Java or start.bat.
BAD_PATH_CHARS = '!#%^&;`'


def safe_folder_name(name: str) -> str:
    s = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "", name)
    s = "".join(ch for ch in s if ch not in BAD_PATH_CHARS)
    s = re.sub(r"\s+", " ", s).strip().rstrip(".").strip() or "My Server"
    return s[:40].strip()


def bad_path(folder: str | Path) -> bool:
    """True if Java can't run a server from this folder."""
    return any(ch in str(folder) for ch in BAD_PATH_CHARS)


def rename_server_folder(cfg: "ServerConfig", log=print) -> bool:
    """Gives the server folder the server's (safe) name: after renaming the server, or when the folder has ! in it.
    The server must be stopped. True if the folder moved (cfg.folder is updated and saved)."""
    d = Path(cfg.folder)
    want = safe_folder_name(cfg.name)
    if not d.is_dir():
        return False
    if not bad_path(d.name) and (d.name == want or re.fullmatch(re.escape(want) + r" \(\d+\)", d.name)):
        return False
    target, i = d.with_name(want), 2
    while target.exists():
        target = d.with_name(f"{want} ({i})")
        i += 1
    for attempt in range(10):  # the server can take a moment to let go of its files
        try:
            d.rename(target)
            break
        except OSError as e:
            if attempt == 9:
                log(f"! Couldn't rename the server folder to \"{target.name}\": {e}")
                return False
            time.sleep(1)
    cfg.folder = str(target)
    cfg.save()
    log(f"Server folder renamed: {d.name} → {target.name}")
    if bad_path(target.parent):
        log(f"! The folder {target.parent} has one of {BAD_PATH_CHARS} in its path, so Java may not start servers there.")
    return True


def new_server_folder(name: str) -> Path:
    base = servers_dir() / safe_folder_name(name)
    p, i = base, 2
    while p.exists():
        p = base.with_name(f"{base.name} ({i})")
        i += 1
    p.mkdir(parents=True)
    return p


def _prop_escape(s: str) -> str:
    out = []
    for ch in s:
        if ch in "\\":
            out.append("\\\\")
        elif ord(ch) > 126 or ord(ch) < 32:
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    return "".join(out)


def update_properties(f: Path, props: dict, header: str = "") -> None:
    """Sets these keys in a .properties file and keeps every other line (and comment) as it was."""
    lines = f.read_text(encoding="utf-8", errors="replace").splitlines() if f.exists() else ([header] if header else [])
    out, seen = [], set()
    for line in lines:
        t = line.strip()
        if t and not t.startswith("#") and "=" in t:
            k = t.split("=", 1)[0].strip()
            if k in props:
                if k not in seen:
                    out.append(f"{k}={props[k]}")
                    seen.add(k)
                continue
        out.append(line)
    out += [f"{k}={v}" for k, v in props.items() if k not in seen]
    f.write_text("\n".join(out) + "\n", encoding="utf-8")


def read_properties(f: Path) -> dict:
    d = {}
    if f.exists():
        for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
            t = line.strip()
            if t and not t.startswith("#") and "=" in t:
                k, v = t.split("=", 1)
                d[k.strip()] = v.strip()
    return d


def check_icon(path: str) -> tuple[bool, str, tuple[int, int] | None]:
    """(ok, message, size). The icon has to be a 64x64 PNG."""
    try:
        from PIL import Image
        with Image.open(path) as im:
            if im.format != "PNG":
                return False, "That file is not a PNG.", im.size
            if im.size != (64, 64):
                return False, f"That image is {im.size[0]}×{im.size[1]}. It has to be 64×64.", im.size
            return True, "64×64 PNG", im.size
    except Exception as e:
        return False, f"Can't open that image ({e.__class__.__name__}).", None


def resize_icon(src: str, dest: Path) -> Path:
    from PIL import Image
    with Image.open(src) as im:
        im = im.convert("RGBA")
        w, h = im.size
        side = min(w, h)  # crop to a square from the middle, then shrink
        im = im.crop(((w - side) // 2, (h - side) // 2, (w - side) // 2 + side, (h - side) // 2 + side))
        im = im.resize((64, 64), Image.LANCZOS if side > 64 else Image.NEAREST)
        im.save(dest, "PNG")
    return dest


# --------------------------------------------------------------------------- Bedrock

BEDROCK_KEEP = {"server.properties", "allowlist.json", "permissions.json", "whitelist.json"}


def bedrock_exe(d: Path) -> Path:
    return d / ("bedrock_server.exe" if WINDOWS else "bedrock_server")


def install_bedrock(d: Path, log=print, progress=None) -> str:
    """Downloads the newest Bedrock Dedicated Server into d. On an update, worlds and settings are kept."""
    version, url = _bedrock_latest()
    z = app_dir() / "bedrock-server.zip"
    log(f"Downloading the Bedrock server {version} from Mojang...")
    download(url, z, progress, timeout=180, headers={"User-Agent": BROWSER_UA})
    updating = bedrock_exe(d).exists()
    with zipfile.ZipFile(z) as zf:
        for info in zf.infolist():
            name = info.filename.replace("\\", "/")
            if name.startswith("worlds/") or ".." in name:
                continue
            target = d / name
            if updating and name in BEDROCK_KEEP and target.exists():
                continue
            if info.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            target.parent.mkdir(parents=True, exist_ok=True)
            with zf.open(info) as src, open(target, "wb") as out:
                shutil.copyfileobj(src, out)
    z.unlink(missing_ok=True)
    if not WINDOWS:
        os.chmod(bedrock_exe(d), 0o755)
    return version


def bedrock_dirs() -> list[Path]:
    """minecraftpe folders of Minecraft Bedrock on this PC (new launcher first, then the old Store one)."""
    if not WINDOWS:
        return [p for p in [Path.home() / ".local/share/mcpelauncher/games/com.mojang/minecraftpe"] if p.is_dir()]
    out = []
    appdata = Path(os.environ.get("APPDATA", ""))
    users = sorted(glob.glob(str(appdata / "Minecraft Bedrock" / "Users" / "*" / "games" / "com.mojang" / "minecraftpe")),
                   key=lambda p: "Shared" in p)
    out += [Path(p) for p in users]
    local = os.environ.get("LOCALAPPDATA", "")
    out.append(Path(local) / "Packages" / "Microsoft.MinecraftUWP_8wekyb3d8bbwe" / "LocalState" / "games" / "com.mojang" / "minecraftpe")
    return [p for p in out if p.is_dir()]


def default_bedrock_dir() -> Path:
    if WINDOWS:
        return Path(os.environ.get("APPDATA", "")) / "Minecraft Bedrock"
    return Path.home() / ".local/share/mcpelauncher"


def looks_like_bedrock_dir(p: Path) -> bool:
    return p.is_dir() and (p.name == "minecraftpe" or any((p / x).exists() for x in ("options.txt", "external_servers.txt")))


def find_bedrock_dir() -> Path | None:
    for p in bedrock_dirs():
        if (p / "external_servers.txt").exists() or (p / "options.txt").exists():
            return p
    ds = bedrock_dirs()
    return ds[0] if ds else None


def add_bedrock_server(pe_dir: Path, name: str, address: str, port: int) -> bool:
    """Adds the server to Bedrock's Servers tab (external_servers.txt: index:name:address:port:timestamp)."""
    f = pe_dir / "external_servers.txt"
    lines = [l for l in f.read_text(encoding="utf-8", errors="replace").splitlines() if l.strip()] if f.exists() else []
    if f.exists():
        shutil.copy2(f, f.with_name("external_servers.txt.mchoster-backup"))
    name = name.replace(":", " ")
    keep, idx = [], 0
    for l in lines:
        parts = l.split(":")
        if len(parts) >= 4 and parts[2] == address and parts[3] == str(port):
            continue  # replaced below
        keep.append(l)
        if parts[0].isdigit():
            idx = max(idx, int(parts[0]))
    keep.append(f"{idx + 1}:{name}:{address}:{port}:{int(time.time())}")
    f.write_text("\n".join(keep) + "\n", encoding="utf-8")
    return True


# --------------------------------------------------------------------------- server folder

def set_allow_end(folder: Path, on: bool) -> None:
    """Paper, Purpur, Pufferfish and Spigot close the End with allow-end in bukkit.yml."""
    f = Path(folder) / "bukkit.yml"
    if not f.exists():
        if not on:
            f.write_text("settings:\n  allow-end: false\n", encoding="utf-8")
        return
    text = f.read_text(encoding="utf-8", errors="replace")
    value = "true" if on else "false"
    if re.search(r"(?m)^\s+allow-end:", text):
        text = re.sub(r"(?m)^(\s+allow-end:)\s*\S+", rf"\1 {value}", text)
    elif re.search(r"(?m)^settings:\s*$", text):
        text = re.sub(r"(?m)^settings:\s*$", f"settings:\n  allow-end: {value}", text, count=1)
    else:
        text = f"settings:\n  allow-end: {value}\n" + text
    f.write_text(text, encoding="utf-8")


# --------------------------------------------------------------------------- tab 6: Config

# (key, label, kind, default, help). kind: text, int:min:max, bool, choice:a|b|c. Keys are server.properties names.
PROP_TWEAKS = [
    ("motd", "Server list message (MOTD)", "text", "", "Under the name in the server list. & colours work. Empty = the server's name"),
    ("level-seed", "World seed", "text", "", "Only for a new world"),
    ("level-type", "World type", "choice:Normal|Flat|Large Biomes|Amplified", "Normal", "Only for a new world"),
    ("generate-structures", "Villages, temples and other structures", "bool", True, ""),
    ("view-distance", "View distance (chunks)", "int:0:32", 0, "0 = automatic (by max players)"),
    ("simulation-distance", "Simulation distance (chunks)", "int:0:32", 0, "How far mobs and farms keep working. 0 = automatic"),
    ("spawn-protection", "Spawn protection (blocks)", "int:0:128", 0, "Only ops can build this close to spawn"),
    ("max-world-size", "World border (blocks from the middle)", "int:1000:29999984", 29999984, ""),
    ("pvp", "PvP (players can hurt each other)", "bool", True, ""),
    ("allow-flight", "Allow flying (no kick for flying mods)", "bool", False, "Needed for some plugins and mods"),
    ("force-gamemode", "Everyone gets the game mode when they join", "bool", False, ""),
    ("white-list", "Whitelist (only listed players can join)", "bool", False, "Add names below"),
    ("player-idle-timeout", "Kick AFK players after (minutes)", "int:0:1440", 0, "0 = never"),
    ("hide-online-players", "Hide who's online in the server list", "bool", False, ""),
    ("enable-command-block", "Command blocks", "bool", False, ""),
]
LEVEL_TYPES = {"Normal": "minecraft:normal", "Flat": "minecraft:flat", "Large Biomes": "minecraft:large_biomes", "Amplified": "minecraft:amplified"}

# (key, label, default, [names to try: old camelCase, new snake_case]). Sent as commands when the server is ready.
GAMERULES = [
    ("keep_inventory", "Keep inventory when you die", False, ["keepInventory", "keep_inventory"]),
    ("mob_griefing", "Mobs break blocks (creepers, endermen)", True, ["mobGriefing", "mob_griefing"]),
    ("fire_tick", "Fire spreads", True, ["doFireTick", "fire_spread_radius_around_player"]),
    ("daylight", "Day and night", True, ["doDaylightCycle", "advance_time"]),
    ("weather", "Weather changes", True, ["doWeatherCycle", "advance_weather"]),
    ("phantoms", "Phantoms", True, ["doInsomnia", "spawn_phantoms"]),
    ("mob_spawning", "Mobs spawn", True, ["doMobSpawning", "spawn_mobs"]),
    ("regeneration", "Health comes back by itself", True, ["naturalRegeneration", "natural_health_regeneration"]),
    ("fall_damage", "Fall damage", True, ["fallDamage", "fall_damage"]),
    ("immediate_respawn", "Respawn without the death screen", False, ["doImmediateRespawn", "immediate_respawn"]),
    ("advancements", "Advancements in chat", True, ["announceAdvancements", "show_advancement_messages"]),
    ("sleeping", "Players who must sleep to skip the night (%)", 100, ["playersSleepingPercentage", "players_sleeping_percentage"]),
]


def tweak(cfg: "ServerConfig", key: str, default=None):
    return (cfg.tweaks or {}).get(key, default)


def tweak_props(cfg: "ServerConfig") -> dict[str, str]:
    """server.properties lines from tab 6 and the resource pack (only what was changed from the default)."""
    out: dict[str, str] = {}
    t = cfg.tweaks or {}
    for key, _label, kind, default, _h in PROP_TWEAKS:
        if key not in t or t[key] == default:
            continue
        v = t[key]
        if key == "motd":
            out["motd"] = _prop_escape(re.sub(r"&([0-9a-fk-orA-FK-OR])", "\u00a7\\1", str(v))) if str(v).strip() else _prop_escape(cfg.name)
        elif key == "level-type":
            out["level-type"] = LEVEL_TYPES.get(str(v), "minecraft:normal")
        elif kind == "bool":
            out[key] = str(bool(v)).lower()
            if key == "white-list":
                out["enforce-whitelist"] = str(bool(v)).lower()
        elif kind.startswith("int"):
            if key in ("view-distance", "simulation-distance") and int(v) <= 0:
                continue
            out[key] = str(int(v))
        else:
            out[key] = _prop_escape(str(v))
    rp = cfg.resource_pack or {}
    if rp.get("url"):
        out["resource-pack"] = _prop_escape(rp["url"])
        out["resource-pack-sha1"] = rp.get("sha1", "")
        out["require-resource-pack"] = str(bool(rp.get("required"))).lower()
        out["resource-pack-prompt"] = _prop_escape(json.dumps({"text": rp.get("prompt") or "This server uses a resource pack."}))
    else:
        out.update({"resource-pack": "", "resource-pack-sha1": "", "require-resource-pack": "false"})
    return out


def ready_commands(cfg: "ServerConfig") -> list[str]:
    """Console commands for when the server is ready: gamerules, whitelist and ops from tab 6, the owner."""
    cmds = []
    ex = cfg.experimental or {}
    if ex.get("enabled", True):  # tab 7: your own commands, first
        for c in ex.get("commands", []) or []:
            c = str(c).strip()
            if c and not c.startswith("#"):
                cmds.append(c[1:] if c.startswith("/") else c)
    if cfg.prov.bedrock:
        return cmds
    t = cfg.tweaks or {}
    for key, _label, default, names in GAMERULES:
        if key not in t or t[key] == default:
            continue
        v = t[key]
        val = str(v).lower() if isinstance(v, bool) else str(int(v))
        for n in names:  # one of the names works, depending on the Minecraft version
            cmds.append(f"gamerule {n} {val}")
    for n in t.get("whitelist_names", []) or []:
        if valid_player_name(n):
            cmds.append(f"whitelist add {n}")
    names = list(t.get("ops", []) or [])
    if cfg.owner:
        names.insert(0, cfg.owner)
    for n in names:
        if valid_player_name(n):
            cmds.append(f"op {n}")
    return cmds


def write_server_files(cfg: ServerConfig) -> None:
    d = Path(cfg.folder)
    provider = cfg.prov
    view = view_distance(cfg.players)
    if provider.bedrock:
        update_properties(d / "server.properties", {
            "server-name": cfg.name.replace(";", " ")[:60],
            "gamemode": cfg.gamemode.lower() if cfg.gamemode in BEDROCK_GAMEMODES else "survival",
            "difficulty": cfg.difficulty.lower(),
            "max-players": str(cfg.players),
            "server-port": str(cfg.port),
            "server-portv6": str(cfg.port + 1),
            "online-mode": "true",
            "view-distance": str(view * 3),
        })
        write_start_script(cfg)
        return
    (d / "eula.txt").write_text(
        "# Accepted in MC Hoster by the person who set up this server: https://aka.ms/MinecraftEULA\n"
        f"# {time.strftime('%Y-%m-%d %H:%M')}\neula=true\n", encoding="utf-8")
    update_properties(d / "server.properties", {
        "motd": _prop_escape(cfg.name),
        "server-port": str(cfg.port),
        "max-players": str(cfg.players),
        "gamemode": cfg.gamemode.lower(),
        "difficulty": cfg.difficulty.lower(),
        "hardcore": str(cfg.hardcore).lower(),
        "allow-nether": str(cfg.nether).lower(),
        "online-mode": "true",
        "view-distance": str(view),
        "simulation-distance": str(min(10, view)),
        "spawn-protection": "0",
        "enable-status": "true",
        **tweak_props(cfg),
    }, header="#Minecraft server properties (written by MC Hoster)")
    if provider.kind == "bukkit":
        set_allow_end(d, cfg.end)
    icon = d / "server-icon.png"
    if cfg.icon_path and Path(cfg.icon_path).exists():
        if Path(cfg.icon_path).resolve() != icon.resolve():
            if check_icon(cfg.icon_path)[0]:
                shutil.copy(cfg.icon_path, icon)
            else:
                resize_icon(cfg.icon_path, icon)
    elif not cfg.icon_path:
        icon.unlink(missing_ok=True)
    addons = d / provider.addon_folder
    addons.mkdir(exist_ok=True)
    jar_name = "Gh0stAC.jar"
    if cfg.gh0stac and gh0st_supported(provider, cfg.version):
        src = gh0st_jar(provider)
        if not src:
            raise IOError("The Gh0st AC jar is missing from this copy of MC Hoster")
        shutil.copy(src, addons / jar_name)
    else:
        (addons / jar_name).unlink(missing_ok=True)
    write_start_script(cfg)


def server_args(cfg: ServerConfig) -> list[str]:
    if cfg.prov.bedrock:
        return [str(bedrock_exe(Path(cfg.folder)))]
    heap = heap_for_start(cfg)
    args = [cfg.java, f"-Xms{min(512, heap)}M", f"-Xmx{heap}M"]
    if tweak(cfg, "aikar", False):  # Aikar's flags: smoother garbage collection
        args += ["-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200", "-XX:+UnlockExperimentalVMOptions",
                 "-XX:+DisableExplicitGC", "-XX:+AlwaysPreTouch", "-XX:G1NewSizePercent=30", "-XX:G1MaxNewSizePercent=40",
                 "-XX:G1HeapRegionSize=8M", "-XX:G1ReservePercent=20", "-XX:G1HeapWastePercent=5", "-XX:G1MixedGCCountTarget=4",
                 "-XX:InitiatingHeapOccupancyPercent=15", "-XX:G1MixedGCLiveThresholdPercent=90", "-XX:G1RSetUpdatingPauseTimePercent=5",
                 "-XX:SurvivorRatio=32", "-XX:+PerfDisableSharedMem", "-XX:MaxTenuringThreshold=1", "-Daikars.new.flags=true"]
    elif heap >= 4096:
        args += ["-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200"]
    args += ["-jar", "server.jar", "nogui"]
    return args


java_args = server_args  # old name


def write_start_script(cfg: ServerConfig) -> None:
    d = Path(cfg.folder)
    args = server_args(cfg)
    if WINDOWS:
        line = " ".join(f'"{a}"' if " " in a else a for a in args)
        (d / "start.bat").write_text(f"@echo off\r\ntitle {cfg.name}\r\ncd /d \"%~dp0\"\r\n{line}\r\npause\r\n", encoding="utf-8")
    else:
        line = " ".join(f"'{a}'" for a in args)
        env = "LD_LIBRARY_PATH=. " if cfg.prov.bedrock else ""
        p = d / "start.sh"
        p.write_text(f"#!/bin/sh\ncd \"$(dirname \"$0\")\"\n{env}exec {line}\n", encoding="utf-8")
        p.chmod(0o755)


def server_address(cfg: ServerConfig, host: str = "localhost") -> str:
    default = BEDROCK_PORT if cfg.prov.bedrock else PORT
    return host if cfg.port == default else f"{host}:{cfg.port}"


def add_to_list(cfg: ServerConfig, log=print) -> None:
    if not (cfg.minecraft_dir and cfg.add_to_list):
        return
    d, mc = Path(cfg.folder), Path(cfg.minecraft_dir)
    try:
        if cfg.prov.bedrock:
            add_bedrock_server(mc, cfg.name, "127.0.0.1", cfg.port)
            log(f"Added to your Bedrock Servers tab as \"{cfg.name}\" (restart Minecraft if it is open).")
        else:
            icon = (d / "server-icon.png").read_bytes() if (d / "server-icon.png").exists() else None
            add_to_server_list(mc, cfg.name, server_address(cfg), icon)
            log(f"Added to your Multiplayer list as \"{cfg.name}\" (restart Minecraft if it is open).")
    except Exception as e:
        log(f"Could not add it to your server list ({e}). Add it by hand.")


def setup_server(cfg: ServerConfig, log=print, progress=None, step=None) -> ServerConfig:
    """Makes (or updates) a ready-to-run server folder. step(index, total, text) reports the big steps.

    Safe to run again on an existing server: it only downloads when the version changed or files are missing,
    and server.properties keeps everything MC Hoster doesn't manage.
    """
    provider = cfg.prov
    if not cfg.eula:
        raise ValueError("The Minecraft EULA has to be accepted first")
    st = step or (lambda i, n, t: log(f"[{i + 1}/{n}] {t}"))
    mc_dir = Path(cfg.minecraft_dir) if cfg.minecraft_dir else None
    if not cfg.folder:
        cfg.folder = str(new_server_folder(cfg.name))
    d = Path(cfg.folder)
    log(f"Server folder: {d}")

    if provider.bedrock:
        steps = ["Downloading the Bedrock server", "Writing settings", "Adding to your server list"]
        cfg.port = BEDROCK_PORT if cfg.port == PORT else cfg.port
        st(0, len(steps), steps[0])
        if not bedrock_exe(d).exists() or cfg.installed_version != cfg.version:
            cfg.version = install_bedrock(d, log, progress)
        else:
            log(f"Bedrock server {cfg.version} is already here.")
        cfg.installed_version = cfg.version
        st(1, len(steps), steps[1])
        write_server_files(cfg)
        cfg.save()
        st(2, len(steps), steps[2])
        add_to_list(cfg, log)
        return cfg

    steps = ["Finding Java", "Downloading the server", "Writing settings", "Adding to your server list"]
    st(0, len(steps), steps[0])
    need = required_java(cfg.version)
    jdk = provider.id == "spigot"  # BuildTools compiles, so it needs a JDK, not just a JRE
    current = java_major(cfg.java) if cfg.java and Path(cfg.java).exists() else None
    if current and current >= need and (not jdk or is_jdk(Path(cfg.java))) and not java_is_32bit(cfg.java):
        log(f"Using Java {current}: {cfg.java}")
    else:
        found = find_java(need, mc_dir if not provider.bedrock else None, jdk)
        if found:
            log(f"Using Java {found[1]}: {found[0]}")
            cfg.java = str(found[0])
        else:
            log(f"No Java {need}{' JDK' if jdk else ''} on this PC, getting one (only the first time).")
            cfg.java = str(download_java(need, progress, log, jdk))
    cfg.ram_mb = int(tweak(cfg, "ram_mb", 0) or 0) or ram_for_players(cfg.players, provider.kind == "fabric")

    st(1, len(steps), steps[1] + (" (compiling Spigot, 5-10 min)" if provider.slow else ""))
    if not (d / "server.jar").exists() or cfg.installed_version != cfg.version:
        get_server_jar(provider, cfg.version, d, Path(cfg.java), log, progress)
        if cfg.installed_version and cfg.installed_version != cfg.version:
            log(f"Updated from {cfg.installed_version} to {cfg.version}.")
    else:
        log(f"{provider.name} {cfg.version} is already here.")
    cfg.installed_version = cfg.version

    st(2, len(steps), steps[2])
    write_server_files(cfg)
    if cfg.gh0stac and gh0st_supported(provider, cfg.version):
        log(f"Gh0st AC installed in {provider.addon_folder}/")
    cfg.save()

    st(3, len(steps), steps[3])
    add_to_list(cfg, log)
    return cfg


# --------------------------------------------------------------------------- running

def ports_for(cfg: ServerConfig, extra: list | None = None) -> list[tuple[int, str, str]]:
    """(port, TCP/UDP, what for) for everything the router has to let in."""
    ports = [(cfg.port, "UDP" if cfg.prov.bedrock else "TCP", "Minecraft")]
    for p in extra or []:
        if p not in ports:
            ports.append(p)
    return ports


# --------------------------------------------------------------------------- servers left running

_JOB = None


def _kill_with_us(proc: subprocess.Popen) -> None:
    """Windows: put the server in a job that closes with MC Hoster, so it can never keep running
    after MC Hoster is closed, crashes, or is ended in Task Manager."""
    global _JOB
    if not WINDOWS:
        return
    try:
        import ctypes
        from ctypes import wintypes
        k32 = ctypes.WinDLL("kernel32", use_last_error=True)
        k32.CreateJobObjectW.restype = wintypes.HANDLE
        k32.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
        k32.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
        k32.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
        if _JOB is None:
            class Basic(ctypes.Structure):
                _fields_ = [("PerProcessUserTimeLimit", ctypes.c_int64), ("PerJobUserTimeLimit", ctypes.c_int64),
                            ("LimitFlags", wintypes.DWORD), ("MinimumWorkingSetSize", ctypes.c_size_t),
                            ("MaximumWorkingSetSize", ctypes.c_size_t), ("ActiveProcessLimit", wintypes.DWORD),
                            ("Affinity", ctypes.c_size_t), ("PriorityClass", wintypes.DWORD), ("SchedulingClass", wintypes.DWORD)]

            class IoCounters(ctypes.Structure):
                _fields_ = [(n, ctypes.c_uint64) for n in ("r", "w", "o", "rb", "wb", "ob")]

            class Extended(ctypes.Structure):
                _fields_ = [("Basic", Basic), ("Io", IoCounters), ("ProcessMemoryLimit", ctypes.c_size_t),
                            ("JobMemoryLimit", ctypes.c_size_t), ("PeakProcessMemoryUsed", ctypes.c_size_t),
                            ("PeakJobMemoryUsed", ctypes.c_size_t)]
            job = k32.CreateJobObjectW(None, None)
            info = Extended()
            info.Basic.LimitFlags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
            if not k32.SetInformationJobObject(job, 9, ctypes.byref(info), ctypes.sizeof(info)):
                return
            _JOB = job
        k32.AssignProcessToJobObject(_JOB, int(proc._handle))
    except Exception:
        pass


PID_FILE = "mchoster.pid"
SERVER_EXES = ("java.exe", "javaw.exe", "bedrock_server.exe", "java", "bedrock_server")


def _run(args: list[str]) -> str:
    try:
        return subprocess.run(args, capture_output=True, text=True, timeout=15, creationflags=NO_WINDOW,
                              errors="replace").stdout
    except Exception:
        return ""


def process_name(pid: int) -> str | None:
    """The program name of a running process, None if it isn't running."""
    if WINDOWS:
        out = _run(["tasklist", "/FI", f"PID eq {pid}", "/FO", "CSV", "/NH"]).strip()
        m = re.match(r'"([^"]+)","(\d+)"', out)
        return m.group(1) if m and int(m.group(2)) == pid else None
    try:
        return Path(f"/proc/{pid}/comm").read_text().strip()
    except OSError:
        return None


def port_free(port: int, udp: bool = False) -> bool:
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM if udp else socket.SOCK_STREAM)
    try:
        if not udp and WINDOWS:
            s.setsockopt(socket.SOL_SOCKET, getattr(socket, "SO_EXCLUSIVEADDRUSE", 0xFFFFFFFB), 1)
        s.bind(("0.0.0.0", port))
        return True
    except OSError:
        return False
    finally:
        s.close()


def next_free_port(port: int, udp: bool = False) -> int:
    for p in range(port + 1, port + 200):
        if port_free(p, udp):
            return p
    return port


def valid_player_name(n: str) -> bool:
    return bool(re.fullmatch(r"[A-Za-z0-9_]{3,16}", n or ""))


def port_owner(port: int, udp: bool = False) -> int | None:
    """PID of the program using a port (Windows), None if unknown."""
    if not WINDOWS:
        return None
    for line in _run(["netstat", "-ano", "-p", "UDP" if udp else "TCP"]).splitlines():
        parts = line.split()
        if len(parts) < 4 or not parts[0].upper().startswith("UDP" if udp else "TCP"):
            continue
        if parts[1].rsplit(":", 1)[-1] == str(port) and (udp or parts[3].upper() == "LISTENING"):
            try:
                return int(parts[-1])
            except ValueError:
                pass
    return None


def kill_tree(pid: int) -> None:
    if WINDOWS:
        _run(["taskkill", "/PID", str(pid), "/T", "/F"])
    else:
        try:
            os.kill(pid, 9)
        except OSError:
            pass


@dataclass
class Leftover:
    cfg: "ServerConfig"
    pid: int


def leftovers() -> list[Leftover]:
    """MC Hoster servers that are still running without a window (MC Hoster was closed or crashed)."""
    out = []
    for cfg in my_servers():
        f = Path(cfg.folder) / PID_FILE
        if not f.exists():
            continue
        try:
            pid = int(json.loads(f.read_text())["pid"])
        except Exception:
            f.unlink(missing_ok=True)
            continue
        name = process_name(pid)
        if name and name.lower() in SERVER_EXES and (pid != os.getpid()):
            out.append(Leftover(cfg, pid))
        else:
            f.unlink(missing_ok=True)
    return out


def stop_leftover(lo: Leftover, wait: float = 20) -> bool:
    kill_tree(lo.pid)
    end = time.time() + wait
    while time.time() < end and process_name(lo.pid):
        time.sleep(0.5)
    (Path(lo.cfg.folder) / PID_FILE).unlink(missing_ok=True)
    return not process_name(lo.pid)


def who_has_port(cfg: "ServerConfig") -> tuple[int | None, str | None, "ServerConfig | None"]:
    """If the server's port is taken: (pid, program, the MC Hoster server using it or None)."""
    udp = cfg.prov.bedrock
    if port_free(cfg.port, udp):
        return None, None, None
    pid = port_owner(cfg.port, udp)
    mine = None
    for lo in leftovers():
        if lo.pid == pid or pid is None:
            mine = lo.cfg
            pid = lo.pid
            break
    return pid, (process_name(pid) if pid else None), mine


def change_port(cfg: "ServerConfig", port: int) -> None:
    cfg.port = port
    props = {"server-port": str(port)}
    if cfg.prov.bedrock:
        props["server-portv6"] = str(port + 1)
    else:
        props["query.port"] = str(port)
    update_properties(Path(cfg.folder) / "server.properties", props)
    cfg.save()


# Why a server stopped by itself: (pattern in its output, what to tell the user)
EXIT_REASONS = [
    (r"FAILED TO BIND TO PORT|Address already in use",
     "Another program (often a server from an older or second MC Hoster) is using the port. Close the other MC Hoster, "
     "or let MC Hoster pick another port when it asks."),
    (r"UnsupportedClassVersionError|compiled by a more recent version of the Java Runtime|requires running the server with Java",
     "This Minecraft version needs a newer Java. Click Apply & start in tab 3 and MC Hoster gets the right one."),
    (r"Unable to access jarfile|Invalid or corrupt jarfile|Error opening zip file|ZipException",
     "server.jar is missing or broken. Click Apply & start in tab 3 to download it again."),
    (r"session\.lock|already in use by another|The world is already being used|DirectoryLock",
     "The world is still open in another server (an older MC Hoster may still be running it). "
     "Close the other MC Hoster, or restart your PC, then start again."),
    (r"OutOfMemoryError|Could not reserve enough space|Could not create the Java Virtual Machine",
     "Not enough memory for the server. Close other programs, lower Max players in tab 3, or set "
     "'Memory for the server' in the Config tab. (If this keeps happening, click Apply & start in tab 3 "
     "so MC Hoster picks a 64-bit Java.)"),
    (r"agree to the EULA|eula\.txt",
     "The Minecraft EULA isn't accepted. Tick it in tab 3 and click Apply & start."),
    (r"Failed to load properties|Failed to load level|Exception reading .*level\.dat|Failed to read level",
     "The world or server.properties couldn't be read (maybe from a crash). A backup is in the world folder "
     "(level.dat_old), or Dark Backup's backups folder."),
    (r"Encountered an unexpected exception|Exception in server tick loop|This crash report has been saved",
     "The server crashed. The crash report is in the crash-reports folder of the server."),
]


def plugin_errors(lines: list[str]) -> dict[str, str]:
    """Plugins that failed to load or enable: {name: first line of the error}."""
    out: dict[str, str] = {}
    pats = [r"Error occurred while enabling (\S+)", r"Could not load '(?:plugins[/\\])?([^']+?)(?:\.jar)?'",
            r"Could not load plugin '?([^' ]+)", r"Ambiguous plugin name `?([^`' ]+)"]
    for i, line in enumerate(lines):
        for pat in pats:
            m = re.search(pat, line)
            if m:
                name = m.group(1).split(" v")[0]
                why = line.split(")", 1)[-1].strip(" :") if "(" in line else ""
                for nxt in lines[i + 1:i + 4]:  # the exception's message is usually on the next lines
                    t = nxt.strip()
                    if t and not t.startswith("at ") and ("Exception" in t or "Error" in t):
                        why = t[-160:]
                        break
                out.setdefault(name, why or "see the console")
    return out


def explain_exit(lines: list[str], folder: str = "") -> str | None:
    """What went wrong when a server stopped by itself, from its last lines (None if nothing known)."""
    text = "\n".join(lines[-300:])
    if folder and bad_path(folder):
        return f"The server folder has one of {BAD_PATH_CHARS} in its path, and Java can't start a server there. " \
               "Open the server again (My servers…) and MC Hoster renames the folder."
    for pat, why in EXIT_REASONS:
        hits = [l.strip() for l in lines[-300:] if re.search(pat, l, re.I)]
        if hits:  # show the real line too, so it's clear what Java actually said
            return f"{why}\n\nJava said: {hits[-1][:200]}"
    return None


class ServerProcess:
    DONE = re.compile(r"INFO\]: Done \(\d+[.,]\d+s\)! For help|Server started\.")  # not a plugin's own "Done (...)"

    def __init__(self, cfg: ServerConfig, on_line=None, on_ready=None, on_exit=None):
        self.cfg = cfg
        self.on_line = on_line or (lambda l: None)
        self.on_ready = on_ready or (lambda: None)
        self.on_exit = on_exit or (lambda code: None)
        self.proc: subprocess.Popen | None = None
        self.ready = False
        self.stopping = False  # True when MC Hoster (or the user) asked it to stop
        self.tail: list[str] = []  # the last lines, to explain why it stopped

    @property
    def running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def start(self):
        write_start_script(self.cfg)
        self.ready = False
        self.stopping = False
        self.tail = []
        env = dict(os.environ)
        if self.cfg.prov.bedrock and not WINDOWS:
            env["LD_LIBRARY_PATH"] = "."
        self.proc = subprocess.Popen(server_args(self.cfg), cwd=self.cfg.folder, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, env=env,
                                     encoding="utf-8", errors="replace", bufsize=1, creationflags=NO_WINDOW)
        _kill_with_us(self.proc)
        try:
            (Path(self.cfg.folder) / PID_FILE).write_text(json.dumps({"pid": self.proc.pid, "port": self.cfg.port,
                                                                       "started": time.time()}))
        except OSError:
            pass
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        p = self.proc
        for line in p.stdout:
            line = line.rstrip("\r\n")
            self.tail.append(line)
            if len(self.tail) > 300:
                del self.tail[:100]
            self.on_line(line)
            if not self.ready and self.DONE.search(line):
                self.ready = True
                self.on_ready()
        code = p.wait()
        self._clear_pid(p.pid)
        self.on_exit(code)

    def _clear_pid(self, pid: int):
        try:
            f = Path(self.cfg.folder) / PID_FILE
            if f.exists() and json.loads(f.read_text()).get("pid") == pid:
                f.unlink()
        except Exception:
            pass

    def send(self, cmd: str):
        if self.running:
            try:
                self.proc.stdin.write(cmd.strip() + "\n")
                self.proc.stdin.flush()
            except Exception:
                pass

    def stop(self, wait: float = 30):
        if not self.running:
            return
        self.stopping = True
        self.send("stop")
        try:
            self.proc.wait(wait)
        except subprocess.TimeoutExpired:
            kill_tree(self.proc.pid)
            try:
                self.proc.wait(10)
            except subprocess.TimeoutExpired:
                self.proc.kill()
        if self.proc.poll() is not None:
            self._clear_pid(self.proc.pid)
