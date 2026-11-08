#!/usr/bin/env python3
"""Generates assets/discord_voice_bridge/font/icons.png (8px PUA icon row).

Vanilla 26.3 ships no symbol glyphs, so the /dvc menu's status icons live in
the private-use area (U+E000+) via a bitmap font merged additively into the
default font (assets/minecraft/font/default.json). Kept at 8px tall (the vanilla
text height) so the icons sit on a chat line without overflowing it. Re-run only
when the icons change; the PNG is committed.
"""
import os
from PIL import Image, ImageDraw

CELL = 8
OUT = os.path.join(os.path.dirname(__file__),
                   "..", "src", "main", "resources",
                   "assets", "discord_voice_bridge", "font", "icons.png")

GREEN = (40, 210, 60, 255)
RED = (235, 60, 60, 255)
YELLOW = (245, 205, 40, 255)
LIGHT = (235, 235, 235, 255)


def dot(color):
    img = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((1, 1, 6, 6), fill=color)
    return img


def ring(color):
    img = Image.new("RGBA", (CELL, CELL), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    d.ellipse((1, 1, 6, 6), outline=color, width=1)
    d.point((4, 4), fill=color)
    return img


# Order must match the `chars` row in the font json (one cell each).
ICONS = [
    dot(GREEN),   # E000 status: unmuted / heard
    dot(RED),     # E001 status: muted
    dot(YELLOW),  # E002 status: not heard / not listening
    ring(LIGHT),  # E003 section marker (discord users)
]

width = CELL * len(ICONS)
canvas = Image.new("RGBA", (width, CELL), (0, 0, 0, 0))
for i, icon in enumerate(ICONS):
    canvas.paste(icon, (i * CELL, 0))

os.makedirs(os.path.dirname(OUT), exist_ok=True)
canvas.save(OUT)
print("wrote", os.path.normpath(OUT), canvas.size)

# Write the matching font json (one row, one cell per icon, PUA U+E000+).
row = "".join("\\u%04x" % (0xE000 + i) for i in range(len(ICONS)))
font_json = os.path.join(os.path.dirname(OUT), "icons.json")
with open(font_json, "w", encoding="utf-8") as f:
    f.write(
        "{\n"
        "  \"providers\": [\n"
        "    {\n"
        "      \"type\": \"bitmap\",\n"
        "      \"file\": \"discord_voice_bridge:font/icons.png\",\n"
        "      \"height\": 8,\n"
        "      \"ascent\": 6,\n"
        "      \"chars\": [\n"
        f"        \"{row}\"\n"
        "      ]\n"
        "    }\n"
        "  ]\n"
        "}\n"
    )
print("wrote", os.path.normpath(font_json))
