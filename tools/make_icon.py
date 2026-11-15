"""Draws the MC Hoster icon: a pixel grass block with a signal wave above it. Writes assets/icon.png and icon.ico."""
import random
from pathlib import Path
from PIL import Image

random.seed(7)
S = 16
im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
px = im.load()
greens = [(95, 159, 53), (106, 176, 60), (84, 140, 46), (121, 192, 72)]
dirts = [(134, 96, 67), (121, 85, 58), (150, 108, 74), (108, 76, 52)]
for y in range(5, 16):
    for x in range(1, 15):
        if y < 8 or (y == 8 and random.random() < .5):
            px[x, y] = random.choice(greens)
        else:
            px[x, y] = random.choice(dirts)
# signal arcs (white) above the block
for x, y in [(7, 3), (8, 3), (5, 2), (6, 1), (7, 1), (8, 1), (9, 1), (10, 2), (3, 1), (12, 1), (4, 0), (11, 0), (5, 0), (10, 0)]:
    px[x, y] = (61, 174, 233, 255)
for x in range(1, 15):  # outline
    px[x, 15] = (60, 42, 28, 255)
big = im.resize((256, 256), Image.NEAREST)
out = Path(__file__).resolve().parent.parent / "assets"
out.mkdir(exist_ok=True)
big.resize((64, 64), Image.NEAREST).save(out / "icon.png")
big.save(out / "icon-256.png")
big.save(out / "icon.ico", sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
print("ok")
