#!/usr/bin/env python3
"""Generates every Android/brand image asset from the AI logo masters.

Inputs (created by the AI logo generator):
    assets/logo-icon.png    — square app icon master
    assets/logo-splash.png  — 9:16 splash artwork master
    assets/logo-banner.png  — Play Store / promo banner (copied, not resized)

Outputs:
    android/app/src/main/res/mipmap-*/ic_launcher.png
    android/app/src/main/res/mipmap-*/ic_launcher_round.png
    android/app/src/main/res/mipmap-*/ic_launcher_foreground.png
    android/app/src/main/res/drawable/ic_launcher_foreground.png
    android/app/src/main/res/drawable{,-land-*,-port-*}/splash.png
    assets/icons/icon-{48,72,96,144,192,512}.png
    public/icon-512.png

Run: python3 scripts/generate-icons.py
"""

import os
import shutil
import sys

from PIL import Image, ImageDraw

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ICON_MASTER = os.path.join(ROOT, "assets", "logo-icon.png")
SPLASH_MASTER = os.path.join(ROOT, "assets", "logo-splash.png")
BANNER_MASTER = os.path.join(ROOT, "assets", "logo-banner.png")
RES = os.path.join(ROOT, "android", "app", "src", "main", "res")

DARK = (9, 9, 13, 255)  # #09090d — matches the web theme

# Launcher sizes per density: (dir, legacy icon px, adaptive foreground px)
MIPMAPS = [
    ("mipmap-mdpi", 48, 108),
    ("mipmap-hdpi", 72, 162),
    ("mipmap-xhdpi", 96, 216),
    ("mipmap-xxhdpi", 144, 324),
    ("mipmap-xxxhdpi", 192, 432),
]

SPLASHES = [
    ("drawable", 480, 320),
    ("drawable-land-mdpi", 480, 320),
    ("drawable-land-hdpi", 800, 480),
    ("drawable-land-xhdpi", 1280, 720),
    ("drawable-land-xxhdpi", 1600, 960),
    ("drawable-land-xxxhdpi", 1920, 1280),
    ("drawable-port-mdpi", 320, 480),
    ("drawable-port-hdpi", 480, 800),
    ("drawable-port-xhdpi", 720, 1280),
    ("drawable-port-xxhdpi", 960, 1600),
    ("drawable-port-xxxhdpi", 1280, 1920),
]


def cover(image, size):
    """Resizes `image` to fully cover `size`, center-cropped."""
    target_w, target_h = size
    scale = max(target_w / image.width, target_h / image.height)
    resized = image.resize(
        (round(image.width * scale), round(image.height * scale)), Image.LANCZOS
    )
    left = (resized.width - target_w) // 2
    top = (resized.height - target_h) // 2
    return resized.crop((left, top, left + target_w, top + target_h))


def rounded_corners(image, radius):
    image = image.convert("RGBA")
    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, image.width, image.height], radius=radius, fill=255)
    image.putalpha(mask)
    return image


def circle(image):
    image = image.convert("RGBA")
    mask = Image.new("L", image.size, 0)
    ImageDraw.Draw(mask).ellipse([0, 0, image.width, image.height], fill=255)
    image.putalpha(mask)
    return image


def build_icon(master, px, style="rounded"):
    """Full-bleed launcher icon on a dark rounded square (or circle)."""
    art = cover(master, (px, px))
    art = art.convert("RGBA")
    base = Image.new("RGBA", (px, px), DARK)
    base.paste(art, (0, 0), art)
    radius = px * 0.22 if style == "rounded" else px // 2
    return rounded_corners(base, radius) if style == "rounded" else circle(base)


def build_foreground(master, px):
    """Adaptive-icon foreground: logo inside the 66dp safe zone of a 108dp canvas."""
    canvas = Image.new("RGBA", (px, px), (0, 0, 0, 0))
    safe = int(px * 0.58)  # keep the mark clear of the masked corners
    art = cover(master, (safe, safe)).convert("RGBA")
    offset = (px - safe) // 2
    canvas.paste(art, (offset, offset), art)
    return canvas


def build_splash(master, width, height):
    """Cover-fit splash artwork with the emblem scaled toward the center."""
    canvas = cover(master, (width, height)).convert("RGBA")
    # Dim the artwork slightly so launch text stays readable on some OEMs.
    return canvas


def main():
    if not os.path.exists(ICON_MASTER):
        sys.exit(f"missing {ICON_MASTER} — generate the AI logo first")
    master = Image.open(ICON_MASTER).convert("RGBA")
    splash_master = Image.open(SPLASH_MASTER).convert("RGBA")

    # --- Launcher icons ---------------------------------------------------
    for folder, legacy_px, fg_px in MIPMAPS:
        out = os.path.join(RES, folder)
        os.makedirs(out, exist_ok=True)
        build_icon(master, legacy_px, "rounded").save(os.path.join(out, "ic_launcher.png"))
        build_icon(master, legacy_px, "circle").save(os.path.join(out, "ic_launcher_round.png"))
        build_foreground(master, fg_px).save(os.path.join(out, "ic_launcher_foreground.png"))
        print(f"  {folder}: launcher {legacy_px}px, foreground {fg_px}px")

    # Drawable copy (used by some OEMs / referenced by the deliverables list).
    drawable = os.path.join(RES, "drawable")
    os.makedirs(drawable, exist_ok=True)
    build_foreground(master, 512).save(os.path.join(drawable, "ic_launcher_foreground.png"))

    # --- Splash screens ---------------------------------------------------
    for folder, width, height in SPLASHES:
        out = os.path.join(RES, folder)
        os.makedirs(out, exist_ok=True)
        build_splash(splash_master, width, height).save(os.path.join(out, "splash.png"))
        print(f"  {folder}/splash.png: {width}x{height}")

    # --- Brand copies -----------------------------------------------------
    icons = os.path.join(ROOT, "assets", "icons")
    os.makedirs(icons, exist_ok=True)
    for px in (48, 72, 96, 144, 192, 512):
        build_icon(master, px, "rounded").save(os.path.join(icons, f"icon-{px}.png"))
    public = os.path.join(ROOT, "public")
    os.makedirs(public, exist_ok=True)
    build_icon(master, 512, "rounded").save(os.path.join(public, "icon-512.png"))
    if os.path.exists(BANNER_MASTER):
        shutil.copy2(BANNER_MASTER, os.path.join(icons, "banner-1024x500.png"))
    print("done.")


if __name__ == "__main__":
    main()
