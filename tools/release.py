#!/usr/bin/env python3
"""The deterministic half of a release: choose the version, bump it, tag it.

    tools/release.py next [incremental|minor|major|X.Y.Z]   # default incremental
    tools/release.py bump X.Y.Z                              # Android and iOS
    tools/release.py tag X.Y.Z [--push]                      # dry run without --push

`next` prints the version to release and refuses when main has nothing since the
last tag. A version already bumped on main but never tagged is still waiting to
be released, so a keyword resolves to it rather than skipping past it; only an
explicit X.Y.Z overrides that.

`tag` tags the tip of origin/main, which is what android-release.yml publishes to
Play, and refuses unless that commit carries the version on both platforms, has a
CHANGELOG.md entry that passes `changelog.py --check`, and its Android tree is one
android.yml has already built green. android.yml only runs when android/** moves,
so the green run may sit on an older commit; the tip qualifies when nothing under
android/ changed since.
"""

import argparse
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
GRADLE = "android/gradle.properties"
XCODEGEN = "ios/project.yml"
GRADLE_VERSION = re.compile(r"^appVersionName=(.+)$", re.M)
IOS_VERSION = re.compile(r'^(\s*MARKETING_VERSION:\s*)"([^"]+)"', re.M)

sys.path.insert(0, str(ROOT / "tools"))
import changelog  # noqa: E402


class Refusal(Exception):
    pass


def git(*args: str) -> str:
    return subprocess.run(["git", *args], cwd=ROOT, check=True,
                          capture_output=True, text=True).stdout.strip()


def parse(version: str) -> tuple[int, int, int]:
    m = re.fullmatch(r"v?(\d+)\.(\d+)(?:\.(\d+))?", version.strip())
    if not m:
        raise Refusal(f"not a version: {version}")
    return int(m[1]), int(m[2]), int(m[3] or 0)


def show(v: tuple[int, int, int]) -> str:
    return ".".join(map(str, v))


def last_tag() -> str:
    tags = git("tag", "--list", "v*", "--sort=-v:refname", "--merged", "origin/main")
    if not tags:
        raise Refusal("no v* tag on main to release from")
    return tags.splitlines()[0]


def app_version(ref: str) -> str:
    """The version both platforms carry at ref; they must agree."""
    android = GRADLE_VERSION.search(git("show", f"{ref}:{GRADLE}"))
    ios = IOS_VERSION.search(git("show", f"{ref}:{XCODEGEN}"))
    if not android or not ios:
        raise Refusal(f"no version found in {GRADLE} or {XCODEGEN} at {ref}")
    if android[1].strip() != ios[2]:
        raise Refusal(f"Android says {android[1].strip()}, iOS says {ios[2]} at {ref}")
    return ios[2]


def next_version(bump: str) -> str:
    git("fetch", "--quiet", "--tags", "origin", "main")
    tag = last_tag()
    since = git("rev-list", "--count", f"{tag}..origin/main")
    if since == "0":
        raise Refusal(f"nothing on main since {tag}")
    released, pending = parse(tag), parse(app_version("origin/main"))
    print(f"last tag {tag}; {since} commits on main since; main carries {show(pending)}",
          file=sys.stderr)

    if bump not in ("incremental", "minor", "major"):
        wanted = parse(bump)
        if wanted <= released:
            raise Refusal(f"{show(wanted)} is not after {tag}")
        return show(wanted)
    major, minor, patch = released
    wanted = {"incremental": (major, minor, patch + 1),
              "minor": (major, minor + 1, 0),
              "major": (major + 1, 0, 0)}[bump]
    if pending >= wanted:
        print(f"{show(pending)} is bumped on main but was never tagged, so it is the "
              f"one to release; pass a version to override", file=sys.stderr)
        return show(pending)
    return show(wanted)


def bump(version: str) -> None:
    version = show(parse(version))
    gradle = ROOT / GRADLE
    gradle.write_text(GRADLE_VERSION.sub(f"appVersionName={version}",
                                         gradle.read_text(encoding="utf-8"), count=1),
                      encoding="utf-8")
    xcodegen = ROOT / XCODEGEN
    xcodegen.write_text(IOS_VERSION.sub(rf'\g<1>"{version}"',
                                        xcodegen.read_text(encoding="utf-8"), count=1),
                        encoding="utf-8")
    print(f"{GRADLE} and {XCODEGEN} now carry {version}")


def green_android_sha() -> str:
    out = subprocess.run(
        ["gh", "run", "list", "--workflow", "android.yml", "--branch", "main",
         "--status", "success", "--limit", "1", "--json", "headSha"],
        cwd=ROOT, check=True, capture_output=True, text=True).stdout
    runs = json.loads(out)
    if not runs:
        raise Refusal("android.yml has no green run on main")
    return runs[0]["headSha"]


def tag_plan(name: str, sha: str, local: str | None, remote: str | None) -> str:
    """"create" a new tag, or "push" one already made locally at sha whose push
    never landed. A tag on origin has been published; a local one elsewhere is stale."""
    if remote:
        raise Refusal(f"{name} is already on origin ({remote[:7]}), so it has been published")
    if local and local != sha:
        raise Refusal(f"a local {name} points at {local[:7]}, not main ({sha[:7]}); "
                      f"remove it with `git tag -d {name}` and run again")
    return "push" if local else "create"


def tag_shas(name: str) -> tuple[str | None, str | None]:
    """The commit the tag names locally and on origin; None where it is absent."""
    local = subprocess.run(["git", "rev-parse", "--verify", "--quiet", f"{name}^{{commit}}"],
                           cwd=ROOT, capture_output=True, text=True).stdout.strip() or None
    refs = {}
    for line in git("ls-remote", "--tags", "origin", name).splitlines():
        sha, ref = line.split("\t")
        refs[ref] = sha
    # An annotated tag lists its object and, peeled with ^{}, the commit.
    remote = refs.get(f"refs/tags/{name}^{{}}") or refs.get(f"refs/tags/{name}")
    return local, remote


def tag(version: str, push: bool) -> None:
    version = show(parse(version))
    name = f"v{version}"
    # No --tags: a local tag that differs from origin's fails the fetch ("would
    # clobber existing tag") before tag_plan can say what to do about it.
    git("fetch", "--quiet", "origin", "main")
    sha = git("rev-parse", "origin/main")
    short = sha[:7]

    plan = tag_plan(name, sha, *tag_shas(name))
    carried = app_version(sha)
    if carried != version:
        raise Refusal(f"main ({short}) carries {carried}, not {version}; bump it first")

    text = git("show", f"{sha}:CHANGELOG.md")
    policy = git("show", f"{sha}:docs/privacy-policy.md")
    try:
        changelog.play_notes(changelog.find(version, text))
    except LookupError as e:
        raise Refusal(f"{e} at {short}")
    problems = changelog.check(text, policy)
    if problems:
        raise Refusal("changelog.py --check fails at " + short + ":\n  " + "\n  ".join(problems))

    green = green_android_sha()
    if subprocess.run(["git", "diff", "--quiet", green, sha, "--", "android"],
                      cwd=ROOT).returncode != 0:
        raise Refusal(f"android/ changed since the last green android.yml run "
                      f"({green[:7]}); wait for CI on {short}")

    if not push:
        made = f"push the local {name}" if plan == "push" else f"tag {short} as {name} and push it"
        print(f"[dry run] would {made}; add --push")
        return
    if plan == "create":
        git("tag", name, sha)
    git("push", "origin", name)
    print(f"tagged {short} as {name} and pushed; android-release.yml is publishing it")


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__,
                                formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest="command", required=True)
    n = sub.add_parser("next", help="the version to release")
    n.add_argument("bump", nargs="?", default="incremental",
                   help="incremental (default), minor, major, or X.Y.Z")
    b = sub.add_parser("bump", help="set the version on Android and iOS")
    b.add_argument("version")
    t = sub.add_parser("tag", help="tag the tip of main, which publishes it")
    t.add_argument("version")
    t.add_argument("--push", action="store_true", help="tag and push; without it, a dry run")
    args = p.parse_args()

    try:
        if args.command == "next":
            print(next_version(args.bump))
        elif args.command == "bump":
            bump(args.version)
        else:
            tag(args.version, args.push)
    except Refusal as e:
        print(e, file=sys.stderr)
        return 1
    except subprocess.CalledProcessError as e:
        print(f"{' '.join(e.cmd)} failed: {e.stderr.strip()}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
