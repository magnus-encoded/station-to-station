#!/usr/bin/env python3
"""Read one release's notes out of CHANGELOG.md.

    tools/changelog.py 1.11.0 --play    # Play's "What's new": the marked summary
    tools/changelog.py 1.11.0           # the whole section, for the GitHub release
    tools/changelog.py --check          # every entry's Play summary fits

A version may be given with or without its tag's leading "v". A heading can name
several versions ("## 1.2.3, 1.2.2, 1.2.1 — date") when they shipped as one.

Standard library only, so the release workflow can run it before installing
anything, and fail a tag that has no notes before a build has been paid for.
"""

import argparse
import re
import sys
from pathlib import Path

CHANGELOG = Path(__file__).resolve().parent.parent / "CHANGELOG.md"

# Play's own limit for one language's release notes. The API rejects a longer
# text at commit time, after the upload, so it is enforced here instead.
PLAY_LIMIT = 500

HEADING = re.compile(r"^## (?P<versions>[0-9][0-9., ]*?) — ", re.M)
PLAY = re.compile(r"<!-- play -->\n(?P<text>.*?)\n<!-- /play -->", re.S)


def sections(text: str) -> list[tuple[list[str], str]]:
    """(versions, body) for every release heading, in file order."""
    heads = list(HEADING.finditer(text))
    out = []
    for i, h in enumerate(heads):
        end = heads[i + 1].start() if i + 1 < len(heads) else len(text)
        versions = [v.strip() for v in h.group("versions").split(",")]
        out.append((versions, text[h.end():end]))
    return out


def find(version: str, text: str) -> str:
    version = version.removeprefix("v")
    for versions, body in sections(text):
        if version in versions:
            return body
    raise LookupError(f"CHANGELOG.md has no entry for {version}")


def play_notes(body: str) -> str:
    m = PLAY.search(body)
    if not m:
        raise LookupError("the entry has no <!-- play --> summary")
    return m.group("text").strip()


def full_notes(body: str) -> str:
    """The section below its heading, less the Play summary, which the fuller
    account already says at length."""
    body = body.split("\n", 1)[1] if "\n" in body else ""
    return unwrap(re.sub(r"\n{3,}", "\n\n", PLAY.sub("", body)).strip())


def unwrap(text: str) -> str:
    """Join hard-wrapped lines back into their paragraph or list item. A GitHub
    release renders every newline as a break, so the file's 80-column wrapping
    would otherwise come out as ragged short lines."""
    out: list[str] = []
    for line in text.split("\n"):
        starts_block = not line.strip() or re.match(r"\s*([-*] |#|<)", line)
        if out and out[-1].strip() and not starts_block:
            out[-1] += " " + line.strip()
        else:
            out.append(line)
    return "\n".join(out)


def check(text: str) -> list[str]:
    problems = []
    for versions, body in sections(text):
        name = ", ".join(versions)
        try:
            notes = play_notes(body)
        except LookupError as e:
            problems.append(f"{name}: {e}")
            continue
        if len(notes) > PLAY_LIMIT:
            problems.append(f"{name}: Play summary is {len(notes)} characters, "
                            f"over Play's {PLAY_LIMIT}")
    return problems


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("version", nargs="?", help="e.g. 1.11.0 or v1.11.0")
    p.add_argument("--play", action="store_true", help="only the Play summary")
    p.add_argument("--check", action="store_true",
                   help="validate every entry instead of printing one")
    args = p.parse_args()

    text = CHANGELOG.read_text(encoding="utf-8")

    if args.check:
        problems = check(text)
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1 if problems else 0

    if not args.version:
        p.error("a version is required unless --check is given")
    try:
        body = find(args.version, text)
        print(play_notes(body) if args.play else full_notes(body))
    except LookupError as e:
        print(e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
