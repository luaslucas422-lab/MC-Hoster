# MC Hoster online service

Feedback, Server sharing, Hosted servers and Laura (the AI) all run here. It's one Flask app.

## Put it on Render
1. New **Web Service** from this repo, root directory `server`.
2. Build command: `pip install -r requirements.txt`
3. Start command: `gunicorn app:app --timeout 900 --workers 2 --threads 8`
   (Laura can take several minutes for a big plugin, so the timeout must be long.)
4. Add a **Disk** mounted at `/data` and set `DB_PATH=/data/data.db`, or everything is lost on every restart.
5. Environment variables:
   - `ANTHROPIC_API_KEY`: Laura's AI (console.anthropic.com). You pay Anthropic for what Laura uses.
   - `STRIPE_SECRET_KEY`: payments (dashboard.stripe.com > Developers > API keys). Use `sk_test_...` first.
   - `PUBLIC_URL`: this service's address, e.g. `https://mc-hoster-online.onrender.com`
   - optional `LAURA_MODEL` (default `claude-sonnet-5-5`) and `LAURA_PLAN_MODEL` (default `claude-opus-5-5`).
6. Put the service's address in `ONLINE_URL` in `mchoster/online.py` and release a new MC Hoster.

## Prices (server/laura.py)
`FREE_PER_MONTH = 15`, `PLAN_PER_MONTH = 150`, plan €10/month, plugins €0.25 / €0.50 / €1 taken from a balance
topped up with €1 / €5 / €10 (Stripe's smallest charge is €0.50).
