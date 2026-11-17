# MC Hoster online service

Feedback, Server sharing and Hosted servers run here. It's one Flask app.

## Put it on Render
1. New **Web Service** from this repo, root directory `server`.
2. Build command: `pip install -r requirements.txt`
3. Start command: `gunicorn app:app --timeout 900 --workers 2 --threads 8`
4. Add a **Disk** mounted at `/data` and set `DB_PATH=/data/data.db`, or everything is lost on every restart.

5. Put the service's address in `ONLINE_URL` in `mchoster/online.py` and release a new MC Hoster.

