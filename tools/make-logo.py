from pathlib import Path

from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent.parent
SRC = Path(r"C:\Users\11589\AppData\Local\Temp\codex-clipboard-461293c8-cb1f-47a3-a5d6-c487beba6bcf.jpg")
OUT = ROOT / "frontend" / "public" / "brand-logo.png"
ICO = ROOT / "build" / "brand-logo.ico"

size = 512
scale = 4

image = Image.open(SRC).convert("RGBA")
# 以头部为中心裁一个更大的正方形，并额外留白，避免呆毛和头顶被圆边裁掉
crop = image.crop((75, 0, 835, 760))
canvas = Image.new("RGBA", (800, 800), (255, 255, 255, 255))
canvas.paste(crop, (20, 20))
crop = canvas.resize((size, size), Image.LANCZOS)

mask = Image.new("L", (size * scale, size * scale), 0)
draw = ImageDraw.Draw(mask)
draw.ellipse((8 * scale, 8 * scale, (size - 8) * scale, (size - 8) * scale), fill=255)
mask = mask.resize((size, size), Image.LANCZOS)

logo = Image.new("RGBA", (size, size), (0, 0, 0, 0))
logo.paste(crop, (0, 0), mask)

ring = Image.new("RGBA", (size * scale, size * scale), (0, 0, 0, 0))
ring_draw = ImageDraw.Draw(ring)
ring_draw.ellipse(
    (5 * scale, 5 * scale, (size - 5) * scale, (size - 5) * scale),
    outline=(190, 214, 255, 255),
    width=6 * scale,
)
ring = ring.resize((size, size), Image.LANCZOS)
logo = Image.alpha_composite(logo, ring)

OUT.parent.mkdir(parents=True, exist_ok=True)
logo.save(OUT, "PNG")

ICO.parent.mkdir(parents=True, exist_ok=True)
logo.save(ICO, sizes=[(16, 16), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])

print(OUT)
print(ICO)
