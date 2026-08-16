#!/usr/bin/env python3
"""Put a signed .aab on a Play track.

    PLAY_SERVICE_ACCOUNT_JSON="$(cat key.json)" \
        tools/publish_play.py app-release.aab --track internal --commit

The Play Console's browser upload is the other way to do this, and it is the
reason this file exists: the .aab is ~12MB, which no automation can hand to a
file picker, and the console's own flow separates "bundle exists" from "bundle
is on a track" in a way that silently leaves a release nobody receives.

Requires a service account with release permission *on this app* — account-level
permission alone returns a bare 403 PERMISSION_DENIED with no hint as to why.
"""

import argparse
import hashlib
import json
import os
import sys

from google.oauth2 import service_account
from googleapiclient.discovery import build
from googleapiclient.http import MediaFileUpload

PACKAGE = "io.github.magnusencoded.stationtostation"


def main() -> int:
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("aab", help="path to the signed bundle")
    p.add_argument("--track", default="internal",
                   help="internal | alpha | beta | production (default: internal)")
    # Production wants a staged roll-out; the testing tracks do not support one.
    # Absent this flag the release goes out whole, which is what every track
    # below production should do.
    p.add_argument("--rollout", type=float, metavar="FRACTION",
                   help="staged roll-out fraction, e.g. 0.1 — production only")
    p.add_argument("--name", help="release name (default: Play's auto-generated one)")
    # What the bundle *would* be if uploaded. Lets a rebuild of an already
    # published tag skip the upload Play would reject and go straight to the
    # track change, which is the whole point of promoting a build.
    p.add_argument("--version-code", type=int,
                   help="skip the upload if this versionCode is already in the library")
    # Committing is opt-in rather than opt-out. Without the edit, an upload is a
    # bundle sitting in the library harming nobody; with it, someone's phone
    # updates. The dangerous direction should be the one you have to type.
    p.add_argument("--commit", action="store_true",
                   help="actually release. Omitted, this uploads and rolls back.")
    args = p.parse_args()

    raw = os.environ.get("PLAY_SERVICE_ACCOUNT_JSON")
    if not raw:
        print("PLAY_SERVICE_ACCOUNT_JSON is unset (expects the key's JSON, not a path)",
              file=sys.stderr)
        return 2

    # The secret is set by a human at a shell, and Windows PowerShell can put a
    # BOM through a pipeline. json.loads reports that as "Expecting value: line 1
    # column 1", which reads like a corrupt key rather than an invisible byte.
    creds = service_account.Credentials.from_service_account_info(
        json.loads(raw.strip().lstrip("﻿").strip()),
        scopes=["https://www.googleapis.com/auth/androidpublisher"])
    edits = build("androidpublisher", "v3", credentials=creds,
                  cache_discovery=False).edits()

    edit_id = edits.insert(body={}, packageName=PACKAGE).execute()["id"]

    # Play refuses a versionCode it has already seen, so re-running a publish
    # would die on the upload rather than doing the part that matters.
    #
    # Two ways that happens, and they need different answers. A retried job hands
    # over the same file, so the sha1 finds it. A dispatch re-*builds* the tag:
    # same versionCode, different bytes — zip timestamps and the signing nonce
    # see to that — so the sha1 misses and only the number identifies it. Hence
    # --version-code, which the workflow already knows and passes.
    bundles = edits.bundles().list(
        packageName=PACKAGE, editId=edit_id).execute().get("bundles", [])
    by_sha = {b["sha1"]: b["versionCode"] for b in bundles}
    codes = {b["versionCode"] for b in bundles}

    digest = hashlib.sha1(open(args.aab, "rb").read()).hexdigest()

    if args.version_code in codes:
        version_code = args.version_code
        print(f"versionCode {version_code} is already in the library; "
              "releasing it without re-uploading", flush=True)
    elif digest in by_sha:
        version_code = by_sha[digest]
        print(f"this exact bundle is already uploaded as {version_code}; reusing it",
              flush=True)
    else:
        # Resumable: a 12MB body over a flaky link is worth retrying in pieces
        # rather than restarting a release because one TCP connection died.
        media = MediaFileUpload(args.aab, mimetype="application/octet-stream",
                                resumable=True)
        version_code = edits.bundles().upload(
            packageName=PACKAGE, editId=edit_id, media_body=media).execute()["versionCode"]
        print(f"uploaded versionCode {version_code}", flush=True)

    if not args.commit:
        # Deleting the edit discards the whole transaction, so this really is a
        # rehearsal: the bundle does not linger in the library afterwards.
        edits.delete(packageName=PACKAGE, editId=edit_id).execute()
        print("dry run: nothing released. Re-run with --commit.")
        return 0

    release = {"versionCodes": [str(version_code)]}
    if args.rollout is not None:
        release["status"] = "inProgress"
        release["userFraction"] = args.rollout
    else:
        release["status"] = "completed"
    if args.name:
        release["name"] = args.name

    # tracks().update replaces the track's release list rather than appending to
    # it. That is what we want — a track serves one build — but it also means an
    # existing release's name and notes are gone unless they are restated here.
    edits.tracks().update(packageName=PACKAGE, editId=edit_id, track=args.track,
                          body={"releases": [release]}).execute()
    edits.commit(packageName=PACKAGE, editId=edit_id).execute()
    print(f"released {version_code} to {args.track}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
