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
VERSION = "1.0.0"
AUTHOR = "DarkerDawnFR"
UA = f"MC-Hoster/{VERSION} (github.com/luaslucas422-lab/MC-Hoster)"
PORT = 25565
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


def download(url: str, dest: Path, progress=None, timeout: float = 60) -> Path:
    """Download to dest (through a .part file). progress(done_bytes, total_bytes_or_0)."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_name(dest.name + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": UA})
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
    return re.fullmatch(r"\d+(\.\d+){1,2}", v) is not None


def sort_versions(vs) -> list[str]:
    out = sorted({v for v in vs if is_release(v)}, key=vtuple, reverse=True)
    return out


@dataclass
class Provider:
    id: str
    name: str
    kind: str          # "fabric" (mods/) or "bukkit" (plugins/)
    blurb: str
    slow: bool = False

    @property
    def addon_folder(self) -> str:
        return "mods" if self.kind == "fabric" else "plugins"


PROVIDERS = [
    Provider("paper", "Paper", "bukkit", "Fast and the most popular. Plugins go in the plugins folder. Best pick for most people."),
    Provider("fabric", "Fabric", "fabric", "Mod loader. Server-side Fabric mods go in the mods folder. Fabric API is installed for you."),
    Provider("purpur", "Purpur", "bukkit", "Paper with lots of extra options. Runs every Paper plugin."),
    Provider("pufferfish", "Pufferfish", "bukkit", "Paper fork tuned for big servers. Only its newest 1.21 build is offered."),
    Provider("spigot", "Spigot", "bukkit", "The original plugin server. It has to be compiled on your PC first, which takes 5 to 10 minutes.", slow=True),
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


def list_versions(provider: Provider) -> list[str]:
    """Versions shown for a provider: the ones Gh0st AC has a build for, newest first."""
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
    if not is_release(version):
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


def ram_for_players(players: int, modded: bool = False) -> int:
    """MB of RAM to give the server. 2 GB base, about 64 MB per player, capped at 60 % of the PC."""
    mb = 2048 + players * 64 + (512 if modded else 0)
    cap = max(1024, int(total_ram_mb() * 0.6))
    mb = min(mb, cap)
    return max(1024, (mb // 256) * 256)


def lag_level(players: int) -> tuple[int, str]:
    """0..3 and a short description of how hard the PC will work."""
    ram = total_ram_mb()
    need = 2048 + players * 64
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

    def open(self, port: int, ip: str, desc: str = "MC Hoster") -> bool:
        if not self.control_url and not self.discover():
            return False
        try:
            self._soap("AddPortMapping", {"NewRemoteHost": "", "NewExternalPort": port, "NewProtocol": "TCP",
                                          "NewInternalPort": port, "NewInternalClient": ip, "NewEnabled": 1,
                                          "NewPortMappingDescription": desc, "NewLeaseDuration": 0})
            return True
        except Exception:
            return False

    def close(self, port: int) -> None:
        if self.control_url:
            try:
                self._soap("DeletePortMapping", {"NewRemoteHost": "", "NewExternalPort": port, "NewProtocol": "TCP"})
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
    gh0stac: bool = True
    icon_path: str = ""
    minecraft_dir: str = ""
    add_to_list: bool = True
    eula: bool = False
    # filled in by setup
    folder: str = ""
    java: str = ""
    ram_mb: int = 2048
    port: int = PORT

    def save(self):
        Path(self.folder, "mchoster.json").write_text(json.dumps(asdict(self), indent=2), encoding="utf-8")

    @staticmethod
    def load(folder: Path) -> "ServerConfig":
        d = json.loads((folder / "mchoster.json").read_text(encoding="utf-8"))
        c = ServerConfig(**{k: v for k, v in d.items() if k in ServerConfig.__dataclass_fields__})
        c.folder = str(folder)
        return c


def my_servers() -> list[ServerConfig]:
    out = []
    for f in sorted(servers_dir().glob("*/mchoster.json")):
        try:
            out.append(ServerConfig.load(f.parent))
        except Exception:
            continue
    return out


def safe_folder_name(name: str) -> str:
    s = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "", name).strip().rstrip(".") or "My Server"
    return s[:40]


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


def write_server_files(cfg: ServerConfig) -> None:
    d = Path(cfg.folder)
    provider = PROVIDER_BY_ID[cfg.provider]
    (d / "eula.txt").write_text(
        "# Accepted in MC Hoster by the person who set up this server: https://aka.ms/MinecraftEULA\n"
        f"# {time.strftime('%Y-%m-%d %H:%M')}\neula=true\n", encoding="utf-8")
    props = {
        "motd": _prop_escape(cfg.name),
        "server-port": str(cfg.port),
        "max-players": str(cfg.players),
        "gamemode": cfg.gamemode.lower(),
        "force-gamemode": "false",
        "difficulty": cfg.difficulty.lower(),
        "hardcore": str(cfg.hardcore).lower(),
        "online-mode": "true",
        "pvp": "true",
        "view-distance": str(view_distance(cfg.players)),
        "simulation-distance": str(min(10, view_distance(cfg.players))),
        "spawn-protection": "0",
        "enable-status": "true",
    }
    f = d / "server.properties"
    existing = {}
    if f.exists():  # keep anything the owner changed by hand
        for line in f.read_text(encoding="utf-8", errors="replace").splitlines():
            if "=" in line and not line.startswith("#"):
                k, v = line.split("=", 1)
                existing[k] = v
    existing.update(props)
    f.write_text("#Minecraft server properties (written by MC Hoster)\n" +
                 "".join(f"{k}={v}\n" for k, v in existing.items()), encoding="utf-8")
    if cfg.icon_path:
        ok, _, _ = check_icon(cfg.icon_path)
        if ok:
            shutil.copy(cfg.icon_path, d / "server-icon.png")
        else:
            resize_icon(cfg.icon_path, d / "server-icon.png")
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


def java_args(cfg: ServerConfig) -> list[str]:
    args = [cfg.java, f"-Xms{min(1024, cfg.ram_mb)}M", f"-Xmx{cfg.ram_mb}M"]
    if cfg.ram_mb >= 4096:
        args += ["-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200"]
    args += ["-jar", "server.jar", "nogui"]
    return args


def write_start_script(cfg: ServerConfig) -> None:
    d = Path(cfg.folder)
    args = java_args(cfg)
    if WINDOWS:
        line = " ".join(f'"{a}"' if " " in a else a for a in args)
        (d / "start.bat").write_text(f"@echo off\r\ntitle {cfg.name}\r\ncd /d \"%~dp0\"\r\n{line}\r\npause\r\n", encoding="utf-8")
    else:
        line = " ".join(f"'{a}'" for a in args)
        p = d / "start.sh"
        p.write_text(f"#!/bin/sh\ncd \"$(dirname \"$0\")\"\nexec {line}\n", encoding="utf-8")
        p.chmod(0o755)


def setup_server(cfg: ServerConfig, log=print, progress=None, step=None) -> ServerConfig:
    """Makes a ready-to-run server folder. step(index, total, text) reports the big steps."""
    provider = PROVIDER_BY_ID[cfg.provider]
    if not cfg.eula:
        raise ValueError("The Minecraft EULA has to be accepted first")
    steps = ["Finding Java", "Downloading the server", "Writing settings", "Adding to your server list"]
    st = step or (lambda i, n, t: log(f"[{i + 1}/{n}] {t}"))
    mc_dir = Path(cfg.minecraft_dir) if cfg.minecraft_dir else None

    st(0, len(steps), steps[0])
    need = required_java(cfg.version)
    jdk = provider.id == "spigot"  # BuildTools compiles, so it needs a JDK, not just a JRE
    found = find_java(need, mc_dir, jdk)
    if found:
        log(f"Using Java {found[1]}: {found[0]}")
        cfg.java = str(found[0])
    else:
        log(f"No Java {need}{' JDK' if jdk else ''} on this PC, getting one (only the first time).")
        cfg.java = str(download_java(need, progress, log, jdk))

    if not cfg.folder:
        cfg.folder = str(new_server_folder(cfg.name))
    d = Path(cfg.folder)
    log(f"Server folder: {d}")
    cfg.ram_mb = ram_for_players(cfg.players, provider.kind == "fabric")

    st(1, len(steps), steps[1] + (" (compiling Spigot, 5-10 min)" if provider.slow else ""))
    get_server_jar(provider, cfg.version, d, Path(cfg.java), log, progress)

    st(2, len(steps), steps[2])
    write_server_files(cfg)
    if cfg.gh0stac and gh0st_supported(provider, cfg.version):
        log(f"Gh0st AC installed in {provider.addon_folder}/")
    cfg.save()

    st(3, len(steps), steps[3])
    if mc_dir and cfg.add_to_list:
        try:
            icon = (d / "server-icon.png").read_bytes() if (d / "server-icon.png").exists() else None
            add_to_server_list(mc_dir, cfg.name, "localhost" if cfg.port == PORT else f"localhost:{cfg.port}", icon)
            log("Added to your Multiplayer list as \"" + cfg.name + "\" (restart Minecraft if it is open).")
        except Exception as e:
            log(f"Could not add it to the Multiplayer list ({e}). Add localhost by hand.")
    return cfg


# --------------------------------------------------------------------------- running

class ServerProcess:
    DONE = re.compile(r"Done \(\d")

    def __init__(self, cfg: ServerConfig, on_line=None, on_ready=None, on_exit=None):
        self.cfg = cfg
        self.on_line = on_line or (lambda l: None)
        self.on_ready = on_ready or (lambda: None)
        self.on_exit = on_exit or (lambda code: None)
        self.proc: subprocess.Popen | None = None
        self.ready = False

    @property
    def running(self) -> bool:
        return self.proc is not None and self.proc.poll() is None

    def start(self):
        write_start_script(self.cfg)
        self.ready = False
        self.proc = subprocess.Popen(java_args(self.cfg), cwd=self.cfg.folder, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
                                     encoding="utf-8", errors="replace", bufsize=1, creationflags=NO_WINDOW)
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        p = self.proc
        for line in p.stdout:
            line = line.rstrip("\r\n")
            self.on_line(line)
            if not self.ready and self.DONE.search(line):
                self.ready = True
                self.on_ready()
        self.on_exit(p.wait())

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
        self.send("stop")
        try:
            self.proc.wait(wait)
        except subprocess.TimeoutExpired:
            self.proc.kill()
