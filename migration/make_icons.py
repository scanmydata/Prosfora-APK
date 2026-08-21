#!/usr/bin/env python3
"""
Παράγει τα εικονίδια της εφαρμογής από το logo του tovapsimo.gr.

    python migration/make_icons.py

Βγάζει:
- mipmap-*/ic_launcher_foreground.png  → adaptive icon για κάθε πυκνότητα
- drawable-*/splash_logo.png           → λογότυπο της οθόνης εκκίνησης
- assets/branding/oauth-logo.png       → 120×120 για το Google Cloud consent screen
"""
from __future__ import annotations

from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parent.parent
# Το επίσημο λογότυπο· το logo.png του φακέλου είναι άλλο σετ εικονιδίων.
LOGO = ROOT / "assets" / "branding" / "logo-2021.jpg"
# Το πράσινο τρίγωνο μέσα στο λογότυπο — αυτό γίνεται εικονίδιο εφαρμογής,
# γιατί ολόκληρο το λογότυπο είναι πλατύ και το κείμενο γίνεται δυσανάγνωστο.
TRIANGLE_BOX = (55, 12, 494, 235)
SPLASH = ROOT / "assets" / "branding" / "splash.png"
RES = ROOT / "app" / "src" / "main" / "res"

# Το adaptive icon κόβει τα άκρα: μόνο το κεντρικό ~66% είναι σίγουρα ορατό,
# οπότε το λογότυπο μπαίνει σε καμβά με περιθώριο αντί να γεμίζει το πλαίσιο.
SAFE_FRACTION = 0.62

LAUNCHER_SIZES = {
    "mdpi": 108,
    "hdpi": 162,
    "xhdpi": 216,
    "xxhdpi": 324,
    "xxxhdpi": 432,
}

SPLASH_SIZES = {
    "mdpi": 160,
    "hdpi": 240,
    "xhdpi": 320,
    "xxhdpi": 480,
    "xxxhdpi": 640,
}


def trim(image: Image.Image) -> Image.Image:
    """Κόβει τα διάφανα/λευκά περιθώρια ώστε το λογότυπο να γεμίζει τον καμβά."""
    rgba = image.convert("RGBA")
    alpha = rgba.split()[3]
    box = alpha.getbbox()
    if box is None:
        # Αδιαφανής εικόνα — κόβουμε με βάση τη διαφορά από το λευκό
        grey = rgba.convert("L").point(lambda p: 255 if p < 245 else 0)
        box = grey.getbbox()
    return rgba.crop(box) if box else rgba


def fit_square(image: Image.Image, size: int, fraction: float) -> Image.Image:
    canvas = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    target = int(size * fraction)
    scaled = image.copy()
    scaled.thumbnail((target, target), Image.LANCZOS)
    canvas.paste(
        scaled,
        ((size - scaled.width) // 2, (size - scaled.height) // 2),
        scaled,
    )
    return canvas


def main() -> int:
    full = Image.open(LOGO).convert("RGBA")
    logo = trim(full.crop(TRIANGLE_BOX))
    print(f"Τρίγωνο λογοτύπου: {logo.size}")

    for density, size in LAUNCHER_SIZES.items():
        out = RES / f"mipmap-{density}"
        out.mkdir(parents=True, exist_ok=True)
        fit_square(logo, size, SAFE_FRACTION).save(out / "ic_launcher_foreground.png")
        print(f"  mipmap-{density}/ic_launcher_foreground.png  {size}×{size}")

    for density, size in SPLASH_SIZES.items():
        out = RES / f"drawable-{density}"
        out.mkdir(parents=True, exist_ok=True)
        # Η splash icon της Android 12+ κόβεται σε κύκλο· ίδιο ασφαλές περιθώριο
        fit_square(logo, size, 0.66).save(out / "splash_logo.png")

        print(f"  drawable-{density}/splash_logo.png  {size}×{size}")

    # Η εικόνα εκκίνησης μπαίνει μία φορά σε nodpi και συμπιέζεται σε WebP:
    # το πρωτότυπο PNG είναι 1.5 MB και θα φούσκωνε το APK χωρίς λόγο.
    nodpi = RES / "drawable-nodpi"
    nodpi.mkdir(parents=True, exist_ok=True)
    splash = Image.open(SPLASH).convert("RGB")
    splash.thumbnail((1080, 2340), Image.LANCZOS)
    splash.save(nodpi / "splash_background.webp", "WEBP", quality=86, method=6)
    size_kb = (nodpi / "splash_background.webp").stat().st_size // 1024
    print(f"  drawable-nodpi/splash_background.webp  {splash.size}  {size_kb} KB")

    oauth = ROOT / "assets" / "branding" / "oauth-logo.png"
    square = fit_square(logo, 120, 0.86)
    flat = Image.new("RGB", square.size, (255, 255, 255))
    flat.paste(square, mask=square.split()[3])
    flat.save(oauth)
    print(f"\nGoogle consent screen: {oauth.relative_to(ROOT)}  120×120")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
