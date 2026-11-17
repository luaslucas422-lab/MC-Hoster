"""Laura: the AI in MC Hoster that writes Minecraft plugins. Runs inside the MC Hoster online service (app.py).

Settings (Render > Environment):
  ANTHROPIC_API_KEY      your Claude API key (console.anthropic.com)
  STRIPE_SECRET_KEY      sk_live_... (or sk_test_... to try it with test cards)
  PUBLIC_URL             this service's address, e.g. https://mc-hoster-online.onrender.com
  LAURA_MODEL            optional, default claude-sonnet-5-5 (free and pay-per-plugin)
  LAURA_PLAN_MODEL       optional, default claude-opus-5-5 (the EUR 10 plan)

Money: Stripe can't charge less than EUR 0.50, so people top up a balance (EUR 1 / 5 / 10) and each plugin takes
EUR 0.25 / 0.50 / 1 from it. The plan is EUR 10 a month.
"""
import json
import os
import re
import sqlite3
import time
import urllib.error
import urllib.parse
import urllib.request

from flask import Blueprint, jsonify, request

bp = Blueprint("laura", __name__)
DB = os.environ.get("DB_PATH", "data.db")

FREE_PER_MONTH = 15
PLAN_PER_MONTH = 150
PLAN_EUR_CENTS = 1000
SIZES = {"small": 25, "normal": 50, "big": 100}          # euro cents per plugin
TOPUPS = {"eur1": 100, "eur5": 500, "eur10": 1000}
FIXES_PER_JOB = 3                                         # free tries to fix compile errors
MAX_TOKENS = {"small": 12000, "normal": 24000, "big": 48000}
THINK = {"normal": 4000, "high": 16000, "max": 32000}


def db():
    c = sqlite3.connect(DB)
    c.row_factory = sqlite3.Row
    c.executescript("""
    create table if not exists laura_users(id text primary key, credits int default 0, plan_until real default 0,
                                           month text default '', free_used int default 0, plan_used int default 0,
                                           name text default '', mc text default '');
    create table if not exists laura_jobs(id integer primary key, user text, ts real, size text, fixes int default 0,
                                          paid_with text, prompt text, files text);
    create table if not exists laura_payments(session text primary key, user text, what text, ts real, done int default 0);
    """)
    return c


def _user(c, uid):
    month = time.strftime("%Y-%m")
    row = c.execute("select * from laura_users where id=?", (uid,)).fetchone()
    if not row:
        c.execute("insert into laura_users(id, month) values(?,?)", (uid, month))
        row = c.execute("select * from laura_users where id=?", (uid,)).fetchone()
    if row["month"] != month:  # a new month: free and plan uses start again
        c.execute("update laura_users set month=?, free_used=0, plan_used=0 where id=?", (month, uid))
        row = c.execute("select * from laura_users where id=?", (uid,)).fetchone()
    return row


def _uid():
    uid = re.sub(r"[^A-Za-z0-9-]", "", request.headers.get("X-Install", ""))[:64]
    return uid if len(uid) >= 16 else ""


def _status(row):
    plan = row["plan_until"] > time.time()
    return {"free_left": max(0, FREE_PER_MONTH - row["free_used"]), "free_per_month": FREE_PER_MONTH,
            "credits_cents": row["credits"], "plan": plan, "plan_until": row["plan_until"],
            "plan_left": max(0, PLAN_PER_MONTH - row["plan_used"]) if plan else 0, "plan_per_month": PLAN_PER_MONTH,
            "sizes": SIZES, "topups": TOPUPS, "plan_price_cents": PLAN_EUR_CENTS,
            "switched_on": bool(os.environ.get("ANTHROPIC_API_KEY")), "payments_on": bool(os.environ.get("STRIPE_SECRET_KEY"))}


@bp.get("/laura/me")
def me():
    uid = _uid()
    if not uid:
        return jsonify(error="MC Hoster didn't say who you are."), 400
    with db() as c:
        return jsonify(_status(_user(c, uid)))


# --------------------------------------------------------------------------- the AI

SYSTEM = """You are Laura, the AI inside MC Hoster. You write complete, working Minecraft server plugins for people who
host their own server. They are often young players, not programmers: be friendly and clear, never condescending.

Target: a {server} {version} server running Java {java}. Write a Bukkit/Paper plugin that uses ONLY the Paper API
(org.bukkit.*, io.papermc.paper.*, net.kyori.adventure.*) and the Java standard library. No other libraries, no NMS,
no Vault, no PlaceholderAPI, unless the person explicitly asks AND you load it by reflection so the plugin still
works without it. If the server is Spigot, use only org.bukkit.* and org.spigotmc.* (no Paper or Adventure classes).

Size asked for: {size}. {size_hint}

Rules:
- Write every file needed: plugin.yml (name, version, main, api-version: '1.20', author: Laura (MC Hoster),
  commands with usage and permission, permissions with sensible defaults), a config.yml when there are settings,
  and the Java sources. Package: dev.mchoster.laura.<lowercase plugin name>.
- It must compile with plain javac against the server jar. Double-check imports, generics, method names and
  that every method you call exists in this API version. Prefer stable, long-lived API.
- No placeholder code, no "TODO", no pseudo-code. Handle null players, console senders and offline players.
- Colour messages with ChatColor or '&' codes translated with ChatColor.translateAlternateColorCodes.
- Never write anything harmful: no backdoors, no hidden op, no remote code, no stealing data, no lag machines.
  If asked for that, refuse kindly and offer something safe instead.

Answer format, exactly:
1. A short friendly message (2-6 sentences) saying what the plugin does and listing its commands.
2. Every file as:
<file path="plugin.yml">
...contents...
</file>
<file path="src/dev/mchoster/laura/example/ExamplePlugin.java">
...contents...
</file>
Paths of Java files start with src/. Resources (plugin.yml, config.yml) have no folder.
"""

SIZE_HINT = {"small": "Keep it to one or two classes and a few commands: a focused feature.",
             "normal": "A normal plugin: several features, a config, maybe a simple menu (inventory GUI).",
             "big": "A big plugin: many features, menus, data saved to files, admin commands. Split it into clear classes."}


def _anthropic(model, system, messages, effort, max_tokens):
    key = os.environ.get("ANTHROPIC_API_KEY", "")
    budget = THINK.get(effort, THINK["normal"])
    variants = [
        {"thinking": {"type": "enabled", "budget_tokens": budget}, "max_tokens": max_tokens + budget},
        {"thinking": {"type": "adaptive"}, "output_config": {"effort": "high" if effort != "normal" else "medium"},
         "max_tokens": max_tokens + budget},
        {"max_tokens": max_tokens},
    ]
    last = ""
    for extra in variants:  # newer models changed how thinking is asked for: try the ways one after the other
        body = {"model": model, "system": system, "messages": messages, **extra}
        req = urllib.request.Request("https://api.anthropic.com/v1/messages", data=json.dumps(body).encode(), method="POST",
                                     headers={"x-api-key": key, "anthropic-version": "2023-06-01",
                                              "content-type": "application/json"})
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                d = json.loads(r.read().decode())
            return "".join(b.get("text", "") for b in d.get("content", []) if b.get("type") == "text")
        except urllib.error.HTTPError as e:
            last = e.read().decode(errors="replace")[:500]
            if e.code == 400 and ("thinking" in last or "effort" in last or "output_config" in last):
                continue
            raise RuntimeError(f"AI error {e.code}: {last}")
    raise RuntimeError(f"AI error: {last}")


def _files(text):
    files = {}
    for m in re.finditer(r'<file path="([^"]+)">\n?(.*?)</file>', text, re.S):
        path = m.group(1).strip().lstrip("/").replace("\\", "/")
        if ".." in path:
            continue
        files[path] = m.group(2)
    message = re.split(r"<file path=", text, 1)[0].strip()
    return message, files


def _charge(c, row, size):
    """What pays for this plugin: plan, free, credits; or None if nothing can."""
    if row["plan_until"] > time.time() and row["plan_used"] < PLAN_PER_MONTH:
        return "plan"
    if row["free_used"] < FREE_PER_MONTH:
        return "free"
    if row["credits"] >= SIZES[size]:
        return "credits"
    return None


@bp.post("/laura/make")
def make():
    uid = _uid()
    d = request.get_json(silent=True) or {}
    size = d.get("size") if d.get("size") in SIZES else "normal"
    effort = d.get("effort") if d.get("effort") in THINK else "normal"
    prompt = str(d.get("prompt", "")).strip()[:6000]
    if not uid or len(prompt) < 5:
        return jsonify(error="Tell Laura what the plugin should do."), 400
    if not os.environ.get("ANTHROPIC_API_KEY"):
        return jsonify(error="Laura isn't switched on yet."), 503
    with db() as c:
        row = _user(c, uid)
        how = _charge(c, row, size)
        if not how:
            return jsonify(error="You've used your free plugins for this month. Top up or get the plan to make more.",
                           need_payment=True), 402
        plan = how == "plan"
        if effort == "max" and not plan:
            effort = "high"  # Max thinking is for the plan
        recent = c.execute("select count(*) from laura_jobs where user=? and ts>?", (uid, time.time() - 600)).fetchone()[0]
        if recent >= 10:
            return jsonify(error="That's a lot of plugins at once. Wait a few minutes."), 429
    model = os.environ.get("LAURA_PLAN_MODEL" if plan else "LAURA_MODEL", "claude-opus-5-5" if plan else "claude-sonnet-5-5")
    system = SYSTEM.format(server=str(d.get("server", "Paper"))[:20], version=str(d.get("version", "1.21"))[:12],
                           java=str(d.get("java", "21"))[:3], size=size, size_hint=SIZE_HINT[size])
    content = prompt
    prev = d.get("previous") or {}
    if isinstance(prev, dict) and prev:  # "change the last plugin": send its files along
        content = "Here is the plugin you made before:\n" + "".join(
            f'<file path="{k}">\n{str(v)[:60000]}</file>\n' for k, v in list(prev.items())[:40]) + "\nChange it like this:\n" + prompt
    try:
        text = _anthropic(model, system, [{"role": "user", "content": content}], effort, MAX_TOKENS[size])
    except Exception as e:
        return jsonify(error=f"Laura couldn't answer: {e}"), 502
    message, files = _files(text)
    if not files:
        return jsonify(message=message or text[:3000], files={}, charged=False)  # a question or a refusal: free
    with db() as c:
        row = _user(c, uid)
        how = _charge(c, row, size) or how
        if how == "plan":
            c.execute("update laura_users set plan_used=plan_used+1 where id=?", (uid,))
        elif how == "free":
            c.execute("update laura_users set free_used=free_used+1 where id=?", (uid,))
        else:
            c.execute("update laura_users set credits=max(0, credits-?) where id=?", (SIZES[size], uid))
        c.execute("update laura_users set name=?, mc=? where id=?", (request.headers.get("X-Name", "")[:24],
                                                                      request.headers.get("X-Mc", "")[:16], uid))
        cur = c.execute("insert into laura_jobs(user, ts, size, paid_with, prompt, files) values(?,?,?,?,?,?)",
                        (uid, time.time(), size, how, prompt, json.dumps(files)))
        job = cur.lastrowid
        status = _status(_user(c, uid))
    return jsonify(message=message, files=files, job=job, charged=True, paid_with=how, model=model, status=status)


@bp.post("/laura/fix")
def fix():
    """The plugin didn't compile on the person's PC: Laura gets the errors and fixes it (free, a few times)."""
    uid = _uid()
    d = request.get_json(silent=True) or {}
    with db() as c:
        job = c.execute("select * from laura_jobs where id=? and user=?", (int(d.get("job", 0) or 0), uid)).fetchone()
        if not job:
            return jsonify(error="Unknown plugin."), 404
        if job["fixes"] >= FIXES_PER_JOB:
            return jsonify(error="Laura already tried to fix this one 3 times."), 429
        c.execute("update laura_jobs set fixes=fixes+1 where id=?", (job["id"],))
        files = json.loads(job["files"])
        plan = job["paid_with"] == "plan"
    model = os.environ.get("LAURA_PLAN_MODEL" if plan else "LAURA_MODEL", "claude-opus-5-5" if plan else "claude-sonnet-5-5")
    system = SYSTEM.format(server=str(d.get("server", "Paper"))[:20], version=str(d.get("version", "1.21"))[:12],
                           java=str(d.get("java", "21"))[:3], size=job["size"], size_hint=SIZE_HINT[job["size"]])
    content = ("You wrote this plugin:\n" + "".join(f'<file path="{k}">\n{v}</file>\n' for k, v in files.items())
               + "\nIt does not compile. javac said:\n" + str(d.get("errors", ""))[:12000]
               + "\nFix it. Send back EVERY file (also the unchanged ones) in the same format, with a one-line message.")
    try:
        text = _anthropic(model, system, [{"role": "user", "content": content}], "high", MAX_TOKENS[job["size"]])
    except Exception as e:
        return jsonify(error=f"Laura couldn't answer: {e}"), 502
    message, new = _files(text)
    if new:
        with db() as c:
            c.execute("update laura_jobs set files=? where id=?", (json.dumps(new), job["id"]))
    return jsonify(message=message, files=new or files)


# --------------------------------------------------------------------------- paying (Stripe Checkout)

def _stripe(method, path, data=None):
    key = os.environ.get("STRIPE_SECRET_KEY", "")
    body = urllib.parse.urlencode(data, doseq=True).encode() if data else None
    req = urllib.request.Request("https://api.stripe.com/v1" + path, data=body, method=method,
                                 headers={"Authorization": f"Bearer {key}",
                                          "Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        raise RuntimeError(json.loads(e.read().decode()).get("error", {}).get("message", f"Stripe said {e.code}"))


@bp.post("/laura/buy")
def buy():
    uid = _uid()
    what = (request.get_json(silent=True) or {}).get("what", "")
    if not uid or (what not in TOPUPS and what != "plan"):
        return jsonify(error="Pick something to buy."), 400
    if not os.environ.get("STRIPE_SECRET_KEY"):
        return jsonify(error="Paying isn't switched on yet."), 503
    base = os.environ.get("PUBLIC_URL", request.host_url).rstrip("/")
    data = {"client_reference_id": uid, "metadata[uid]": uid, "metadata[what]": what,
            "success_url": base + "/laura/paid?session_id={CHECKOUT_SESSION_ID}", "cancel_url": base + "/laura/cancelled",
            "line_items[0][quantity]": 1, "line_items[0][price_data][currency]": "eur"}
    if what == "plan":
        data.update({"mode": "subscription", "line_items[0][price_data][unit_amount]": PLAN_EUR_CENTS,
                     "line_items[0][price_data][recurring][interval]": "month",
                     "line_items[0][price_data][product_data][name]": "Laura Plan (MC Hoster)",
                     "subscription_data[metadata][uid]": uid})
    else:
        data.update({"mode": "payment", "line_items[0][price_data][unit_amount]": TOPUPS[what],
                     "line_items[0][price_data][product_data][name]": f"Laura balance EUR {TOPUPS[what] // 100}"})
    try:
        s = _stripe("POST", "/checkout/sessions", data)
    except Exception as e:
        return jsonify(error=str(e)), 502
    with db() as c:
        c.execute("insert or replace into laura_payments(session, user, what, ts) values(?,?,?,?)", (s["id"], uid, what, time.time()))
    return jsonify(url=s["url"], session=s["id"])


def _apply_paid(c, session_id):
    p = c.execute("select * from laura_payments where session=?", (session_id,)).fetchone()
    if not p or p["done"]:
        return bool(p and p["done"])
    s = _stripe("GET", f"/checkout/sessions/{urllib.parse.quote(session_id)}")
    if s.get("payment_status") not in ("paid", "no_payment_required") or s.get("status") != "complete":
        return False
    _user(c, p["user"])
    if p["what"] == "plan":
        c.execute("update laura_users set plan_until=max(plan_until, ?) + 31*86400 where id=?", (time.time(), p["user"]))
        if s.get("subscription"):
            c.execute("insert or ignore into laura_payments(session, user, what, ts, done) values(?,?,?,?,1)",
                      ("sub:" + s["subscription"], p["user"], "sub", time.time()))
    else:
        c.execute("update laura_users set credits=credits+? where id=?", (TOPUPS[p["what"]], p["user"]))
    c.execute("update laura_payments set done=1 where session=?", (session_id,))
    return True


@bp.get("/laura/check")
def check():
    """MC Hoster asks this every few seconds after opening the payment window."""
    uid = _uid()
    sid = request.args.get("session", "")
    with db() as c:
        p = c.execute("select user from laura_payments where session=?", (sid,)).fetchone()
        if not p or p["user"] != uid:
            return jsonify(error="Unknown payment."), 404
        try:
            done = _apply_paid(c, sid)
        except Exception as e:
            return jsonify(error=str(e)), 502
        return jsonify(paid=done, status=_status(_user(c, uid)))


@bp.post("/laura/renew")
def renew():
    """Monthly plan: MC Hoster asks this when the plan ran out; it checks the subscription is still paid."""
    uid = _uid()
    with db() as c:
        subs = c.execute("select session from laura_payments where user=? and what='sub'", (uid,)).fetchall()
        for s in subs:
            try:
                sub = _stripe("GET", "/subscriptions/" + s["session"][4:])
            except Exception:
                continue
            if sub.get("status") in ("active", "trialing"):
                end = float(sub.get("current_period_end") or (sub.get("items", {}).get("data") or [{}])[0].get("current_period_end") or 0)
                c.execute("update laura_users set plan_until=max(plan_until, ?) where id=?", (end, uid))
        return jsonify(_status(_user(c, uid)))


@bp.get("/laura/paid")
def paid_page():
    return ("<html><body style='font-family:sans-serif;text-align:center;padding:60px;background:#111;color:#eee'>"
            "<h1>Thanks!</h1><p>Your payment went through. You can close this window: MC Hoster updates by itself.</p></body></html>")


@bp.get("/laura/cancelled")
def cancelled_page():
    return ("<html><body style='font-family:sans-serif;text-align:center;padding:60px;background:#111;color:#eee'>"
            "<h1>Cancelled</h1><p>Nothing was charged. You can close this window.</p></body></html>")
