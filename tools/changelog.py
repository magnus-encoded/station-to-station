#!/usr/bin/env python3
"""Read one release's notes out of CHANGELOG.md.

    tools/changelog.py 1.11.0 --play    # Play's "What's new": the marked summary
    tools/changelog.py 1.11.0           # the whole section, for the GitHub release
    tools/changelog.py --check          # every Play summary fits, and a version
                                        # the privacy policy names leads with it
    tools/changelog.py 1.12.1 --kind    # "fix" or "feature": the format it was written in

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
POLICY = CHANGELOG.parent / "docs" / "privacy-policy.md"

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


def kind(notes: str) -> str:
    """"fix" when the Play summary is written as "Fix: …" lines, which a release
    with no features takes; "feature" for prose. Not the version: a patch can
    carry features, so whether there are any is the writer's judgement."""
    return "fix" if re.search(r"^Fix: ", notes, re.M) else "feature"


def fix_format_problems(notes: str) -> list[str]:
    """A fix release's Play summary: optional "Privacy:" lines, then one
    "Fix: …" line per fix. Order by severity is judgement, not checked."""
    problems = []
    seen_fix = False
    for line in notes.splitlines():
        if not line.strip():
            continue
        if line.startswith("Fix: "):
            seen_fix = True
        elif line.startswith("Privacy:") and not seen_fix:
            pass
        else:
            problems.append(f"a fix release's Play summary is one \"Fix: …\" line per fix, "
                            f"not: {line[:60]}")
    if not seen_fix:
        problems.append("a fix release's Play summary needs at least one \"Fix: …\" line")
    return problems


def privacy_versions(policy: str) -> set[str]:
    """Versions the privacy policy's "What has changed" list names, each as a
    bullet opening "**1.11.0, <date>: …". These are the releases that changed
    what leaves the phone or who it reaches."""
    changed = policy.split("## What has changed", 1)[-1].split("\n## ", 1)[0]
    return set(re.findall(r"^- \*\*([0-9]+(?:\.[0-9]+)+),", changed, re.M))


def check(text: str, policy: str) -> list[str]:
    problems = []
    must_lead_with_privacy = privacy_versions(policy)
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
        # Play's "What's new" is the one place a tester is told what changed, so a
        # change to what the app shares is said there first, not after the features.
        if must_lead_with_privacy & set(versions) and not notes.startswith("Privacy:"):
            problems.append(f"{name}: the privacy policy lists a change in this version, "
                            "so its Play summary must open with \"Privacy:\"")
        if kind(notes) == "fix":
            problems += [f"{name}: {p}" for p in fix_format_problems(notes)]
    missing = must_lead_with_privacy - {v for vs, _ in sections(text) for v in vs}
    for version in sorted(missing):
        problems.append(f"{version}: in the privacy policy's changes, but not in CHANGELOG.md")
    return problems


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    p.add_argument("version", nargs="?", help="e.g. 1.11.0 or v1.11.0")
    p.add_argument("--play", action="store_true", help="only the Play summary")
    p.add_argument("--kind", action="store_true",
                   help="print fix or feature: the format the entry is written in")
    p.add_argument("--check", action="store_true",
                   help="validate every entry instead of printing one")
    args = p.parse_args()

    text = CHANGELOG.read_text(encoding="utf-8")

    if args.check:
        problems = check(text, POLICY.read_text(encoding="utf-8"))
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1 if problems else 0

    if not args.version:
        p.error("a version is required unless --check is given")
    try:
        body = find(args.version, text)
        if args.kind:
            print(kind(play_notes(body)))
        else:
            print(play_notes(body) if args.play else full_notes(body))
    except LookupError as e:
        print(e, file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
