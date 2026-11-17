"""Laura, MC Hoster's AI that makes plugins: talks to the online service, then builds the plugin on this PC.

The AI writes the source; javac (a JDK MC Hoster downloads) compiles it against the server's own jar, so the plugin
fits that exact server. If it doesn't compile, Laura gets the errors and fixes it (free, up to 3 times).
"""
from __future__ import annotations

import io
import re
import shutil
import subprocess
import tempfile
import uuid
import zipfile
from pathlib import Path

from . import addons, core, online, profile

SIZES = [("small", "Small", "€0.25", "one feature, a few commands"),
         ("normal", "Normal", "€0.50", "several features, a config, maybe a menu"),
         ("big", "Big", "€1", "lots of features, menus, saved data")]
EFFORTS = [("normal", "Normal", "Thinks a bit. Fast."), ("high", "High", "Thinks longer. Better for tricky plugins."),
           ("max", "Max", "Thinks the most. Plan only.")]


class LauraError(Exception):
    pass


def install_id() -> str:
    p = profile.load()
    if not p.get("install_id"):
        p = profile.save(install_id=str(uuid.uuid4()))
    return p["install_id"]


def _call(method, path, body=None, timeout=60):
    try:
        return online._call(method, path, body, timeout=timeout, extra={"X-Install": install_id()})
    except online.Offline as e:
        raise LauraError(str(e))


def status() -> dict:
    return _call("GET", "/laura/me", timeout=20)


def make(prompt: str, size: str, effort: str, cfg: core.ServerConfig, previous: dict | None = None) -> dict:
    return _call("POST", "/laura/make", {"prompt": prompt, "size": size, "effort": effort, "server": cfg.prov.name,
                                         "version": cfg.version, "java": core.required_java(cfg.version),
                                         "previous": previous or {}}, timeout=900)


def fix(job: int, errors: str, cfg: core.ServerConfig) -> dict:
    return _call("POST", "/laura/fix", {"job": job, "errors": errors, "server": cfg.prov.name, "version": cfg.version,
                                        "java": core.required_java(cfg.version)}, timeout=900)


def buy(what: str) -> dict:
    return _call("POST", "/laura/buy", {"what": what}, timeout=40)


def check(session: str) -> dict:
    return _call("GET", "/laura/check?session=" + session, timeout=30)


def renew() -> dict:
    return _call("POST", "/laura/renew", {}, timeout=30)


# --------------------------------------------------------------------------- building on this PC

def classpath(cfg: core.ServerConfig) -> list[Path]:
    """The server's own jars: what the plugin is compiled against. Paper and Spigot unpack them on the first start."""
    d = Path(cfg.folder)
    jars = []
    for sub in ("versions", "libraries", "cache", "bundler"):
        if (d / sub).is_dir():
            jars += sorted((d / sub).rglob("*.jar"))
    if (d / "server.jar").exists():
        jars.append(d / "server.jar")
    return jars


def can_build(cfg: core.ServerConfig) -> str:
    """'' if Laura can build for this server, otherwise why not."""
    if cfg.prov.bedrock:
        return "Bedrock servers can't use plugins."
    if cfg.prov.kind == "fabric":
        return "Laura makes plugins for Paper, Purpur, Pufferfish and Spigot servers. Fabric mods are coming later."
    if len(classpath(cfg)) < 2:  # only server.jar: the API jars appear after the first start
        return "Start this server once first, so Laura can build plugins that fit it."
    return ""


def jdk(cfg: core.ServerConfig, log=print) -> Path:
    need = core.required_java(cfg.version)
    found = core.find_java(need, None, jdk=True)
    java = Path(found[0]) if found else Path(core.download_java(need, None, log, jdk=True))
    javac = java.with_name("javac.exe" if core.WINDOWS else "javac")
    if not javac.exists():
        raise LauraError("The Java that was found can't compile (no javac).")
    return javac


def plugin_name(files: dict) -> str:
    m = re.search(r"(?m)^name:\s*['\"]?([A-Za-z0-9_\- ]+)", files.get("plugin.yml", ""))
    return re.sub(r"[^A-Za-z0-9_-]", "", (m.group(1) if m else "LauraPlugin").replace(" ", "")) or "LauraPlugin"


def compile_plugin(files: dict, cfg: core.ServerConfig, log=print) -> tuple[Path | None, str]:
    """(jar, '') when it built, or (None, javac's errors)."""
    if "plugin.yml" not in files:
        return None, "plugin.yml is missing. Write a plugin.yml file."
    work = Path(tempfile.mkdtemp(prefix="laura-"))
    try:
        src, out = work / "src", work / "classes"
        out.mkdir()
        sources = []
        for path, text in files.items():
            p = work / path
            if not p.resolve().is_relative_to(work.resolve()):
                continue
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(text, encoding="utf-8")
            if path.endswith(".java"):
                sources.append(p)
        if not sources:
            return None, "There are no .java files. Write the Java sources under src/."
        javac = jdk(cfg, log)
        cp = ";" if core.WINDOWS else ":"
        argfile = work / "args.txt"
        argfile.write_text("\n".join(['-encoding', 'UTF-8', '-nowarn', '-proc:none', '-d', f'"{out.as_posix()}"',
                                      '-cp', '"' + cp.join(p.as_posix() for p in classpath(cfg)) + '"']
                                     + [f'"{s.as_posix()}"' for s in sources]), encoding="utf-8")
        log("Laura: building the plugin…")
        r = subprocess.run([str(javac), "@" + str(argfile)], capture_output=True, text=True, timeout=300,
                           creationflags=core.NO_WINDOW)
        if r.returncode != 0:
            errs = (r.stdout + r.stderr).replace(str(work), "")
            return None, errs[-12000:]
        name = plugin_name(files)
        jar = work / f"{name}.jar"
        with zipfile.ZipFile(jar, "w", zipfile.ZIP_DEFLATED) as z:
            for f in out.rglob("*"):
                if f.is_file():
                    z.write(f, f.relative_to(out).as_posix())
            for path, text in files.items():
                if not path.endswith(".java") and not path.startswith("src/"):
                    z.writestr(path, text)
        dest_dir = addons.default_plugin_folder() / "Laura"
        dest_dir.mkdir(parents=True, exist_ok=True)
        dest = dest_dir / jar.name
        shutil.copyfile(jar, dest)
        src_zip = dest_dir / f"{name}-source.zip"  # the code, so they (or Laura) can change it later
        buf = io.BytesIO()
        with zipfile.ZipFile(buf, "w", zipfile.ZIP_DEFLATED) as z:
            for path, text in files.items():
                z.writestr(path, text)
        src_zip.write_bytes(buf.getvalue())
        return dest, ""
    finally:
        shutil.rmtree(work, ignore_errors=True)


def build(result: dict, cfg: core.ServerConfig, log=print) -> tuple[Path | None, dict, str]:
    """Compile, letting Laura fix errors up to 3 times. Returns (jar or None, final files, last errors)."""
    files = result.get("files") or {}
    errors = ""
    for attempt in range(4):
        jar, errors = compile_plugin(files, cfg, log)
        if jar:
            return jar, files, ""
        if attempt == 3 or not result.get("job"):
            break
        log(f"Laura: it didn't compile, fixing it (try {attempt + 1} of 3)…")
        try:
            files = fix(result["job"], errors, cfg).get("files") or files
        except LauraError as e:
            errors += f"\n{e}"
            break
    return None, files, errors


def add_to_server(jar: Path, cfg: core.ServerConfig, log=print) -> None:
    """Ticks the new plugin for this server (tab 5, My plugins) and copies it there."""
    path = str(jar)
    cfg.custom_jars = [p for p in (cfg.custom_jars or []) if Path(p).name != jar.name] + [path]
    addons.sync_custom(cfg, log)
    cfg.save()


def open_payment(url: str) -> None:
    """The Stripe checkout in its own MC Hoster window (falls back to the browser)."""
    import sys
    args = [sys.executable] + ([] if getattr(sys, "frozen", False) else [str(Path(sys.argv[0]).resolve())]) + ["--pay", url]
    try:
        subprocess.Popen(args, close_fds=True)
    except Exception:
        import webbrowser
        webbrowser.open(url)


def payment_window(url: str) -> int:
    """Runs in its own process (MC-Hoster.exe --pay <url>): a small window with the Stripe page in it."""
    try:
        import webview
        webview.create_window("MC Hoster · Payment (Stripe)", url, width=520, height=760)
        webview.start()
    except Exception:
        import webbrowser
        webbrowser.open(url)
    return 0
