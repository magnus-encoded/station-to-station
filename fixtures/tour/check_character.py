#!/usr/bin/env python3
"""Validate the swappable Tour character without third-party dependencies."""
import json
from pathlib import Path
import sys

CARD_STEPS = {f"S{i}" for i in range(1, 20)} - {"S9", "S17"}

def validate(path):
    data = json.loads(path.read_text())
    def string(obj, key, limit=None):
        value = obj.get(key)
        if not isinstance(value, str) or not value.strip():
            raise ValueError(f"{key}: required non-empty string")
        if limit and len(value.split()) > limit:
            raise ValueError(f"{key}: exceeds {limit} words")
        return value
    for key in ("name", "username", "avatar", "selfie", "cutout"):
        string(data, key)
    for group, keys in {"notes": ("gapFill", "setlistFill"), "playlist": ("title", "description")}.items():
        if not isinstance(data.get(group), dict):
            raise ValueError(f"{group}: required object")
        for key in keys:
            string(data[group], key)
    lines = data.get("lines")
    if not isinstance(lines, dict) or set(lines) != CARD_STEPS:
        raise ValueError("lines: must contain exactly S1-S8, S10-S16, S18, S19")
    for step, line in lines.items():
        if not isinstance(line, dict):
            raise ValueError(f"{step}: required object")
        try:
            string(line, "do", 20)
            for key, limit in (("why", 30), ("ios", 20), ("android", 20)):
                if key in line:
                    string(line, key, limit)
        except ValueError as error:
            raise ValueError(f"{step}.{error}") from error
    for key, suffixes in {"avatar": [".png"], "selfie": [".jpg"], "cutout": [".webp", "_1x.png", "_2x.png", "_3x.png"]}.items():
        name = data[key]
        if Path(name).name != name:
            raise ValueError(f"{key}: must be an asset base name")
        for suffix in suffixes:
            asset = path.parent / "assets" / (name + suffix)
            if not asset.is_file():
                raise ValueError(f"{key}: missing asset {asset}")
    return data

if __name__ == "__main__":
    path = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).parent / "character" / "character.json"
    try:
        validate(path)
    except (ValueError, OSError, TypeError) as error:
        sys.exit(f"Character invalid: {error}")
    print("Tour character valid")
