"""MC Hoster online service: feedback inbox, Server sharing posts and the Hosted servers list.

Run: pip install -r requirements.txt && ADMIN_KEY=something-secret python app.py
Only someone with ADMIN_KEY can read feedback or delete posts. Data is a SQLite file (DB_PATH),
so put DB_PATH on a persistent disk or it is lost when the service restarts.
"""
import json
import os
import re
import sqlite3
import time

from flask import Flask, jsonify, request

DB = os.environ.get("DB_PATH", "data.db")
ADMIN = os.environ.get("ADMIN_KEY", "")
app = Flask(__name__)
app.config["MAX_CONTENT_LENGTH"] = 3 * 1024 * 1024
from laura import bp as laura_bp  # noqa: E402
app.register_blueprint(laura_bp)


def db():
    c = sqlite3.connect(DB)
    c.row_factory = sqlite3.Row
    c.executescript("""
    create table if not exists feedback(id integer primary key, ts real, name text, mc text, kind text, text text, version text, images text default '[]');
    create table if not exists posts(id integer primary key, ts real, name text, mc text, title text, body text, server text);
    create table if not exists hosted(name text, owner text, mc text, kind text, version text, players int, icon text, ts real,
                                      primary key(name, mc));
    """)
    try:
        c.execute("alter table feedback add column images text default '[]'")
    except sqlite3.OperationalError:
        pass
    return c


def who():
    return (request.headers.get("X-Name", "")[:24].strip(), re.sub(r"[^A-Za-z0-9_]", "", request.headers.get("X-Mc", ""))[:16])


def is_admin():
    return bool(ADMIN) and request.headers.get("X-Admin-Key") == ADMIN


def limited(c, table, name, mc, seconds, n):
    """At most n new rows from one person in `seconds`."""
    row = c.execute(f"select count(*) from {table} where ts > ? and name = ? and mc = ?", (time.time() - seconds, name, mc)).fetchone()
    return row[0] >= n


@app.get("/")
def root():
    return jsonify(ok=True, service="MC Hoster")


@app.post("/feedback")
def feedback_add():
    name, mc = who()
    d = request.get_json(silent=True) or {}
    text = str(d.get("text", "")).strip()[:6000]
    if len(text) < 3 or not name:
        return jsonify(error="Write a message first."), 400
    with db() as c:
        if limited(c, "feedback", name, mc, 3600, 10):
            return jsonify(error="That's a lot of feedback, try again later."), 429
        imgs = [i for i in (d.get("images") or [])[:3] if isinstance(i, str) and len(i) < 600000]
        c.execute("insert into feedback(ts, name, mc, kind, text, version, images) values(?,?,?,?,?,?,?)",
                  (time.time(), name, mc, str(d.get("kind", ""))[:20], text, request.headers.get("X-Version", ""), json.dumps(imgs)))
    return jsonify(ok=True)


@app.get("/feedback")
def feedback_list():
    # Anyone who finds the secret in the app can read this, on purpose.
    with db() as c:
        rows = c.execute("select * from feedback order by id desc limit 200").fetchall()
    items = []
    for r in rows:
        d = dict(r)
        d["images"] = json.loads(d.get("images") or "[]")
        items.append(d)
    return jsonify(items=items)


@app.get("/posts")
def posts_list():
    with db() as c:
        rows = c.execute("select * from posts order by id desc limit 200").fetchall()
    return jsonify(items=[dict(r) for r in rows])


@app.post("/posts")
def posts_add():
    name, mc = who()
    d = request.get_json(silent=True) or {}
    title, body = str(d.get("title", "")).strip()[:80], str(d.get("body", "")).strip()[:2000]
    if not name or len(title) < 2:
        return jsonify(error="A post needs a title."), 400
    with db() as c:
        if limited(c, "posts", name, mc, 600, 3):
            return jsonify(error="Slow down, try again in a few minutes."), 429
        c.execute("insert into posts values(null,?,?,?,?,?,?)", (time.time(), name, mc, title, body, str(d.get("server", ""))[:60]))
    return jsonify(ok=True)


@app.delete("/posts/<int:pid>")
def posts_del(pid):
    if not is_admin():
        return jsonify(error="No."), 403
    with db() as c:
        c.execute("delete from posts where id=?", (pid,))
    return jsonify(ok=True)


@app.get("/hosted")
def hosted_list():
    with db() as c:
        c.execute("delete from hosted where ts < ?", (time.time() - 3 * 86400,))  # not seen for 3 days
        rows = c.execute("select name, owner, mc, kind, version, players, icon from hosted order by ts desc limit 200").fetchall()
    return jsonify(items=[dict(r) for r in rows])


@app.post("/hosted")
def hosted_add():
    owner, mc = who()
    d = request.get_json(silent=True) or {}
    name = str(d.get("name", "")).strip()[:40]
    icon = str(d.get("icon", ""))[:60000]
    if not owner or not name:
        return jsonify(error="Missing name."), 400
    with db() as c:
        c.execute("insert or replace into hosted values(?,?,?,?,?,?,?,?)",
                  (name, owner, mc, str(d.get("kind", ""))[:30], str(d.get("version", ""))[:20], int(d.get("players", 0) or 0),
                   icon, time.time()))
    return jsonify(ok=True)


@app.delete("/hosted")
def hosted_del():
    owner, mc = who()
    d = request.get_json(silent=True) or {}
    with db() as c:
        c.execute("delete from hosted where name=? and mc=?", (str(d.get("name", ""))[:40], mc))
    return jsonify(ok=True)


if __name__ == "__main__":
    app.run(host="0.0.0.0", port=int(os.environ.get("PORT", "8080")))
