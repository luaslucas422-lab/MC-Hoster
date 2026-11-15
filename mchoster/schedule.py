"""Server scheduler: starts a server at a set time if the PC is on.

Uses Windows Task Scheduler (the task is "MC Hoster\\<server>"), which runs
`MC-Hoster.exe --start "<server folder>"`: the MC Hoster window opens and starts the server.

A schedule is a dict:
    {"enabled": True, "repeat": "Once|Daily|Weekly|Monthly|Yearly",
     "time": "18:30", "date": "2026-11-20", "days": ["Mon", "Fri"]}
"Once" runs on the date. "Monthly" runs on the date's day every month, "Yearly" on that date every year.
"""
from __future__ import annotations

import datetime as dt
import hashlib
import os
import subprocess
import sys
import tempfile
from pathlib import Path
from xml.sax.saxutils import escape

from . import core

REPEATS = ["Once", "Daily", "Weekly", "Monthly", "Yearly"]
DAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]
_DAY_XML = dict(zip(DAYS, ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]))
_MONTHS = ["January", "February", "March", "April", "May", "June", "July", "August", "September",
           "October", "November", "December"]


def supported() -> bool:
    return core.WINDOWS


def task_name(cfg: core.ServerConfig) -> str:
    h = hashlib.sha1(str(Path(cfg.folder).resolve()).lower().encode()).hexdigest()[:6]
    return f"MC Hoster\\{core.safe_folder_name(cfg.name)} {h}"


def validate(s: dict) -> str | None:
    """None if the schedule is fine, else what is wrong with it."""
    if not s.get("enabled"):
        return None
    if s.get("repeat") not in REPEATS:
        return "Pick how often."
    try:
        t = dt.datetime.strptime(s.get("time", ""), "%H:%M").time()
    except ValueError:
        return "The time has to look like 18:30."
    if s["repeat"] in ("Once", "Monthly", "Yearly"):
        try:
            d = dt.date.fromisoformat(s.get("date", ""))
        except ValueError:
            return "The date has to look like 2026-11-20."
        if s["repeat"] == "Once" and dt.datetime.combine(d, t) <= dt.datetime.now():
            return "That time has already passed."
    if s["repeat"] == "Weekly" and not s.get("days"):
        return "Tick at least one day."
    return None


def describe(s: dict) -> str:
    if not s or not s.get("enabled"):
        return "Off"
    t, r = s.get("time", "?"), s.get("repeat")
    try:
        d = dt.date.fromisoformat(s.get("date", ""))
    except ValueError:
        d = None
    if r == "Once":
        return f"Once, {d:%a %d %b %Y} at {t}" if d else f"Once at {t}"
    if r == "Daily":
        return f"Every day at {t}"
    if r == "Weekly":
        days = s.get("days") or []
        return f"Every {', '.join(days) if len(days) < 7 else 'day'} at {t}"
    if r == "Monthly":
        return f"Every month on day {d.day} at {t}" if d else f"Every month at {t}"
    if r == "Yearly":
        return f"Every year on {d:%d %B} at {t}" if d else f"Every year at {t}"
    return "Off"


def launch_command(folder: str) -> tuple[str, str]:
    if getattr(sys, "frozen", False):
        return sys.executable, f'--start "{folder}"'
    py = Path(sys.executable)
    pyw = py.with_name("pythonw.exe")
    script = Path(__file__).resolve().parent.parent / "MC-Hoster.py"
    return str(pyw if pyw.exists() else py), f'"{script}" --start "{folder}"'


def task_xml(cfg: core.ServerConfig, s: dict) -> str:
    t = dt.datetime.strptime(s["time"], "%H:%M").time()
    r = s["repeat"]
    if r in ("Once", "Monthly", "Yearly"):
        day = dt.date.fromisoformat(s["date"])
    else:
        day = dt.date.today()
    start = dt.datetime.combine(day, t)
    if r in ("Monthly", "Yearly"):  # first boundary must not be after the first run we want
        start = dt.datetime.combine(min(day, dt.date.today()), t)
    boundary = f"<StartBoundary>{start:%Y-%m-%dT%H:%M:%S}</StartBoundary><Enabled>true</Enabled>"
    if r == "Once":
        trigger = f"<TimeTrigger>{boundary}</TimeTrigger>"
    elif r == "Daily":
        trigger = f"<CalendarTrigger>{boundary}<ScheduleByDay><DaysInterval>1</DaysInterval></ScheduleByDay></CalendarTrigger>"
    elif r == "Weekly":
        days = "".join(f"<{_DAY_XML[x]} />" for x in DAYS if x in s.get("days", []))
        trigger = (f"<CalendarTrigger>{boundary}<ScheduleByWeek><DaysOfWeek>{days}</DaysOfWeek>"
                   f"<WeeksInterval>1</WeeksInterval></ScheduleByWeek></CalendarTrigger>")
    else:
        months = _MONTHS if r == "Monthly" else [_MONTHS[day.month - 1]]
        trigger = (f"<CalendarTrigger>{boundary}<ScheduleByMonth><DaysOfMonth><Day>{day.day}</Day></DaysOfMonth>"
                   f"<Months>{''.join(f'<{m} />' for m in months)}</Months></ScheduleByMonth></CalendarTrigger>")
    exe, args = launch_command(cfg.folder)
    return f"""<?xml version="1.0" encoding="UTF-16"?>
<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">
  <RegistrationInfo>
    <Author>MC Hoster</Author>
    <Description>{escape(f'Starts the Minecraft server "{cfg.name}" with MC Hoster ({describe(s)}).')}</Description>
  </RegistrationInfo>
  <Triggers>{trigger}</Triggers>
  <Principals>
    <Principal id="Author"><LogonType>InteractiveToken</LogonType><RunLevel>LeastPrivilege</RunLevel></Principal>
  </Principals>
  <Settings>
    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>
    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>
    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>
    <StartWhenAvailable>false</StartWhenAvailable>
    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>
    <AllowStartOnDemand>true</AllowStartOnDemand>
    <Enabled>true</Enabled>
    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>
  </Settings>
  <Actions Context="Author">
    <Exec><Command>{escape(exe)}</Command><Arguments>{escape(args)}</Arguments></Exec>
  </Actions>
</Task>
"""


def _schtasks(*args) -> subprocess.CompletedProcess:
    return subprocess.run(["schtasks", *args], capture_output=True, text=True, creationflags=core.NO_WINDOW)


def apply(cfg: core.ServerConfig, log=print) -> bool:
    """Creates, changes or removes the Windows task so it matches cfg.schedule."""
    s = cfg.schedule or {}
    if not supported():
        if s.get("enabled"):
            log("The server scheduler only works on Windows.")
        return False
    name = task_name(cfg)
    old = s.get("task")
    if old and old != name:  # the server was renamed
        _schtasks("/Delete", "/TN", old, "/F")
    if not s.get("enabled"):
        r = _schtasks("/Delete", "/TN", name, "/F")
        if r.returncode == 0:
            log("Auto-start turned off.")
        return True
    err = validate(s)
    if err:
        log(f"Auto-start not set: {err}")
        return False
    f = Path(tempfile.gettempdir()) / "mchoster-task.xml"
    f.write_text(task_xml(cfg, s), encoding="utf-16")
    r = _schtasks("/Create", "/TN", name, "/XML", str(f), "/F")
    f.unlink(missing_ok=True)
    if r.returncode != 0:
        log(f"Could not set auto-start: {(r.stderr or r.stdout).strip()}")
        return False
    s["task"] = name
    log(f"Auto-start: {describe(s)} (when this PC is on).")
    return True


def remove(cfg: core.ServerConfig) -> None:
    if supported():
        _schtasks("/Delete", "/TN", task_name(cfg), "/F")


def exists(cfg: core.ServerConfig) -> bool:
    return supported() and _schtasks("/Query", "/TN", task_name(cfg)).returncode == 0
