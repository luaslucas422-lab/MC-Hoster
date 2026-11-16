"""Windows: the update script waits for MC Hoster to close, swaps in the new exe and starts it."""
import filecmp
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
from mchoster import updater  # noqa: E402

d = Path(tempfile.mkdtemp(prefix="upd "))  # a space in the path, like "Program Files"
old, new = d / "MC-Hoster.exe", d / "MC-Hoster.new.exe"
shutil.copy(r"C:\Windows\System32\hostname.exe", old)
shutil.copy(r"C:\Windows\System32\whoami.exe", new)
# stands in for MC Hoster: closes after 4 seconds
fake = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(4)"])
bat = d / "swap.bat"
bat.write_text(updater.swap_script(new, old, [fake.pid]), encoding="mbcs")
t0 = time.time()
subprocess.Popen(["cmd", "/c", str(bat)], creationflags=0x08000000 | 0x00000200)
time.sleep(2)
assert filecmp.cmp(old, r"C:\Windows\System32\hostname.exe", shallow=False), "swapped before MC Hoster closed"
fake.wait()
for _ in range(30):
    if not new.exists():
        break
    time.sleep(1)
assert not new.exists() and filecmp.cmp(old, r"C:\Windows\System32\whoami.exe", shallow=False), "the new exe wasn't swapped in"
print(f"UPDATE TEST OK (swapped {time.time() - t0:.1f}s after start)")
latest = updater.latest()
print("latest release on GitHub:", latest and latest["version"], "exe:", bool(latest and latest["url"]))
assert latest and latest["url"], "couldn't read the latest release"
