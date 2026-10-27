#!/usr/bin/env python3
"""Copy chosen ig2 candidates into the mod resources at the right sizes, then build the contact sheet.

  python3 tools/art/finalize.py pistol=work/gun_pistol-2.png rifle=work/gun_rifle-1.png:flip \
      icon=work/icon-3.png glint=work/glint-1.png bg=work/menu_bg-2.png
  python3 tools/art/finalize.py --sheet      # only rebuild tools/art/sheet.png

Paths are relative to tools/art/. ':flip' mirrors horizontally (muzzle must point right).
Only resizing / padding / mirroring happens here - all pixels come from the image generator.
"""
import os
import sys

from PIL import Image

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.abspath(os.path.join(HERE, "../../src/main/resources/assets/ironsight"))
GUNS = ["pistol", "revolver", "smg", "vector", "rifle", "burst", "shotgun", "lmg", "dmr", "sniper"]


def fit(im, w, h, resample):
    """Crop to the alpha bbox, scale to fit w x h keeping aspect, centre on transparent canvas."""
    im = im.convert("RGBA")
    bb = im.getchannel("A").getbbox()
    if bb:
        im = im.crop(bb)
    s = min(w / im.width, h / im.height)
    if s != 1:
        im = im.resize((max(1, round(im.width * s)), max(1, round(im.height * s))), resample)
    out = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    out.paste(im, ((w - im.width) // 2, (h - im.height) // 2), im)
    return out


def place(key, src, flip):
    im = Image.open(os.path.join(HERE, src)).convert("RGBA")
    if flip:
        im = im.transpose(Image.FLIP_LEFT_RIGHT)
    if key in GUNS:
        out, dst = fit(im, 32, 32, Image.NEAREST), f"textures/item/{key}.png"
    elif key == "icon":
        out, dst = im.resize((128, 128), Image.LANCZOS), "icon.png"
    elif key == "glint":
        out, dst = fit(im, 32, 32, Image.LANCZOS), "textures/gui/glint.png"
    elif key == "bg":
        out = im.convert("RGB").resize((256, 144), Image.LANCZOS).convert("RGBA")
        dst = "textures/gui/menu_bg.png"
    else:
        sys.exit(f"unknown key {key}")
    path = os.path.join(ASSETS, dst)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out.save(path)
    print(f"{key}: {src}{' (flipped)' if flip else ''} -> {dst} {out.size}")


def sheet():
    cell = 32 * 4
    W = 5 * (cell + 16 + 16 * 2) + 16
    rows = 2
    bg = (54, 58, 64, 255)
    im = Image.new("RGBA", (W, rows * (cell + 16) + 16), bg)
    for i, g in enumerate(GUNS):
        p = os.path.join(ASSETS, f"textures/item/{g}.png")
        if not os.path.exists(p):
            continue
        tex = Image.open(p).convert("RGBA")
        x = 16 + (i % 5) * (cell + 16 + 32)
        y = 16 + (i // 5) * (cell + 16)
        big = tex.resize((cell, cell), Image.NEAREST)
        im.alpha_composite(big, (x, y))
        small = tex.resize((16, 16), Image.BOX).resize((32, 32), Image.NEAREST)  # 16x16 shown at 2x
        im.alpha_composite(small, (x + cell + 8, y + cell - 32))
    im.save(os.path.join(HERE, "sheet.png"))
    print("sheet.png", im.size)


def main():
    args = sys.argv[1:]
    for a in args:
        if a == "--sheet":
            continue
        key, src = a.split("=", 1)
        flip = src.endswith(":flip")
        place(key, src[:-5] if flip else src, flip)
    sheet()


if __name__ == "__main__":
    main()
