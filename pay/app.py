"""MC Hoster payments: the paid version (EUR 5, lifetime) and donations, through Stripe Checkout.

No database: Stripe keeps the payments, and a paid version key is a signature of the Stripe payment, which MC Hoster
checks offline with the public key built into it.

Settings (Render > Environment):
  STRIPE_SECRET_KEY    sk_live_... (sk_test_... to try it with Stripe's test cards)
  LICENSE_PRIVATE_KEY  signs paid version keys (set already; never share it)
  PUBLIC_URL           this service's address
  WEBSITE_URL          the MC Hoster website (default https://mc-hoster.onrender.com)
"""
import base64
import html
import json
import os
import urllib.error
import urllib.parse
import urllib.request

from flask import Flask, jsonify, redirect, request

app = Flask(__name__)
PAID_CENTS = 500
DONATIONS = {"2": 200, "5": 500, "10": 1000, "20": 2000}
SITE = os.environ.get("WEBSITE_URL", "https://mc-hoster.onrender.com").rstrip("/")


def _b64(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def _stripe(method, path, data=None):
    key = os.environ.get("STRIPE_SECRET_KEY", "")
    if not key:
        raise RuntimeError("Payments aren't switched on yet.")
    body = urllib.parse.urlencode(data).encode() if data else None
    req = urllib.request.Request("https://api.stripe.com/v1" + path, data=body, method=method,
                                 headers={"Authorization": f"Bearer {key}", "Content-Type": "application/x-www-form-urlencoded"})
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            msg = json.loads(e.read().decode()).get("error", {}).get("message")
        except Exception:
            msg = None
        raise RuntimeError(msg or f"Stripe said {e.code}")


def _base():
    return os.environ.get("PUBLIC_URL", request.host_url).rstrip("/")


def _session(kind, cents, name):
    return _stripe("POST", "/checkout/sessions", {
        "mode": "payment",
        "line_items[0][quantity]": 1,
        "line_items[0][price_data][currency]": "eur",
        "line_items[0][price_data][unit_amount]": cents,
        "line_items[0][price_data][product_data][name]": name,
        "metadata[kind]": kind,
        "success_url": _base() + ("/thanks" if kind == "paid" else "/donated") + "?session_id={CHECKOUT_SESSION_ID}",
        "cancel_url": SITE + "/thanks.html",
        "allow_promotion_codes": "true",
    })


def license_key(session_id: str) -> str:
    from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
    priv = Ed25519PrivateKey.from_private_bytes(base64.b64decode(os.environ["LICENSE_PRIVATE_KEY"]))
    payload = json.dumps({"s": session_id[-24:], "v": 1}, separators=(",", ":")).encode()
    return "MCH1-" + _b64(payload) + "." + _b64(priv.sign(payload))


def _paid_session(session_id):
    s = _stripe("GET", "/checkout/sessions/" + urllib.parse.quote(session_id))
    ok = s.get("payment_status") == "paid" and s.get("metadata", {}).get("kind") == "paid"
    return ok


PAGE = """<!DOCTYPE html><html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>{title}</title><link rel="stylesheet" href="{site}/style.css"></head><body><div class="wrap">
<header><a class="logo" href="{site}/"><i></i>MC Hoster</a></header><main class="doc">{body}</main></div></body></html>"""


def page(title, body):
    return PAGE.format(title=html.escape(title), site=SITE, body=body)


@app.get("/")
def root():
    return jsonify(ok=True, service="MC Hoster payments", payments=bool(os.environ.get("STRIPE_SECRET_KEY")))


@app.get("/buy/paid")
def buy_paid():
    try:
        return redirect(_session("paid", PAID_CENTS, "MC Hoster Paid (lifetime)")["url"], 303)
    except Exception as e:
        return page("Not yet", f"<h1>Not yet</h1><p>{html.escape(str(e))}</p><p><a href='{SITE}'>Back</a></p>"), 503


@app.get("/buy/donate")
def buy_donate():
    cents = DONATIONS.get(request.args.get("amount", "5"), 500)
    try:
        return redirect(_session("donate", cents, "Donation to MC Hoster")["url"], 303)
    except Exception as e:
        return page("Not yet", f"<h1>Not yet</h1><p>{html.escape(str(e))}</p><p><a href='{SITE}'>Back</a></p>"), 503


@app.post("/api/checkout")
def api_checkout():
    """MC Hoster's Paid version window: start a payment, then poll /api/license."""
    try:
        s = _session("paid", PAID_CENTS, "MC Hoster Paid (lifetime)")
        return jsonify(url=s["url"], session=s["id"])
    except Exception as e:
        return jsonify(error=str(e)), 503


@app.get("/api/license")
def api_license():
    sid = request.args.get("session", "")
    try:
        if sid and _paid_session(sid):
            return jsonify(key=license_key(sid))
        return jsonify(paid=False)
    except Exception as e:
        return jsonify(error=str(e)), 502


@app.get("/thanks")
def thanks():
    sid = request.args.get("session_id", "")
    try:
        ok = bool(sid) and _paid_session(sid)
    except Exception as e:
        return page("Something went wrong", f"<h1>Something went wrong</h1><p>{html.escape(str(e))}</p>"), 502
    if not ok:
        return page("Not paid", "<h1>That payment didn't go through</h1><p>Nothing was charged.</p>"), 402
    key = license_key(sid)
    return page("Thank you!", f"""<h1>Thank you!</h1>
<p>You have MC Hoster Paid, for life. Here is your key:</p>
<p class="note" style="word-break:break-all;font-family:monospace;color:var(--ink)">{html.escape(key)}</p>
<p>In MC Hoster, click <b>Paid version</b> on the Server tab, then <b>I have a key</b>, and paste it.
Copy it and keep it somewhere safe: it works on your other PCs too. If you lose it, use Send feedback in
MC Hoster with the email on your Stripe receipt.</p>""")


@app.get("/donated")
def donated():
    return page("Thank you!", "<h1>Thank you so much!</h1><p>Your donation helps MC Hoster keep growing.</p>"
                              f"<p><a href='{SITE}'>Back to MC Hoster</a></p>")


if __name__ == "__main__":
    app.run(port=int(os.environ.get("PORT", "8080")))
