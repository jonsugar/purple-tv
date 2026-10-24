# Renders app/src/main/res/drawable-nodpi/banner.png, the 640x360 launcher banner.
# Usage: python3 tools/make-banner.py app/src/main/res/font/inter_bold.ttf app/src/main/res/drawable-nodpi/banner.png
import sys
from PIL import Image, ImageDraw, ImageFont
W, H, K = 640, 360, 4                     # final size, supersampling factor
PURPLE, WHITE = (0x91, 0x46, 0xFF), (255, 255, 255)
font = ImageFont.truetype(sys.argv[1], 80 * K)
img = Image.new("RGB", (W * K, H * K), PURPLE)
d = ImageDraw.Draw(img)
text = "Purple TV"
tb = d.textbbox((0, 0), text, font=font)
tw, th = tb[2] - tb[0], tb[3] - tb[1]
icon, gap = 104 * K, 30 * K
total = icon + gap + tw
x0 = (W * K - total) // 2
iy = (H * K - icon) // 2
# A white rounded screen with a purple play glyph: the app's "watch" mark.
d.rounded_rectangle((x0, iy, x0 + icon, iy + icon), radius=26 * K, fill=WHITE)
cx, cy, r = x0 + icon * 0.54, iy + icon / 2, icon * 0.24
d.polygon([(cx - r * 0.8, cy - r), (cx - r * 0.8, cy + r), (cx + r, cy)], fill=PURPLE)
tx = x0 + icon + gap - tb[0]
ty = (H * K - th) // 2 - tb[1]
d.text((tx, ty), text, font=font, fill=WHITE)
img.resize((W, H), Image.LANCZOS).save(sys.argv[2])
print("banner", W, "x", H)
