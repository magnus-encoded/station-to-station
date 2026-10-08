"""Builds ludwig-tour.html (one file, no network) from the character file and its assets.

    python3 docs/prototypes/tour-virtual-friend/build.py

PROTOTYPE: throwaway. It answers "how should the Tour read, look and behave?" for #607 and
is the spec the implementation tickets point at. Nothing in the app imports it.
"""
import base64, json, pathlib

here = pathlib.Path(__file__).resolve().parent
char_dir = here.parents[2] / "fixtures" / "tour" / "character"
assets = char_dir / "assets"

def uri(path, mime):
    return f"data:{mime};base64," + base64.b64encode(path.read_bytes()).decode()

html = (here / "ludwig-tour.src.html").read_text()
html = html.replace("__CHARACTER__", json.dumps(json.loads((char_dir / "character.json").read_text()), ensure_ascii=False))
html = html.replace("__AVATAR__", uri(assets / "ludwig_avatar.png", "image/png"))
html = html.replace("__BUST__", uri(assets / "ludwig_cutout_2x.png", "image/png"))
html = html.replace("__SELFIE__", uri(assets / "ludwig_selfie.jpg", "image/jpeg"))
(here / "ludwig-tour.html").write_text(html)
print("wrote", here / "ludwig-tour.html", len(html), "bytes")
