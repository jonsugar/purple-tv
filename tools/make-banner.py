# Renders app/src/main/res/drawable-nodpi/banner.png, the 640x360 launcher banner.
# Usage: python3 tools/make-banner.py app/src/main/res/font/inter_bold.ttf app/src/main/res/drawable-nodpi/banner.png
import sys
from pathlib import Path
from xml.etree import ElementTree

from PIL import Image, ImageDraw, ImageFont, ImageOps

W, H, K = 640, 360, 4                     # final size, supersampling factor
values = Path(__file__).resolve().parents[1] / "app/src/main/res/values"
colors = {item.attrib["name"]: item.text for item in ElementTree.parse(values / "themes.xml").findall("color")}
text = ElementTree.parse(values / "strings.xml").findtext("string[@name='app_name']")

left_to_right = Image.linear_gradient("L").transpose(Image.Transpose.ROTATE_90)
img = ImageOps.colorize(left_to_right, colors["accent_yellow"], colors["accent_gold"]).resize((W * K, H * K))
font = ImageFont.truetype(sys.argv[1], 80 * K)
d = ImageDraw.Draw(img)
tb = d.textbbox((0, 0), text, font=font)
tw, th = tb[2] - tb[0], tb[3] - tb[1]
icon, gap = 104 * K, 30 * K
total = icon + gap + tw
x0 = (W * K - total) // 2
iy = (H * K - icon) // 2
# The existing rounded screen and play glyph.
d.rounded_rectangle((x0, iy, x0 + icon, iy + icon), radius=26 * K, fill=colors["background"])
cx, cy, r = x0 + icon * 0.54, iy + icon / 2, icon * 0.24
d.polygon([(cx - r * 0.8, cy - r), (cx - r * 0.8, cy + r), (cx + r, cy)], fill=colors["accent_gold_light"])
tx = x0 + icon + gap - tb[0]
ty = (H * K - th) // 2 - tb[1]
d.text((tx, ty), text, font=font, fill=colors["background"])
img.resize((W, H), Image.LANCZOS).save(sys.argv[2])
print("banner", W, "x", H)
