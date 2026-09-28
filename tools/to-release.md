---
name: to-release
description: Release what has landed on main to Google Play. Picks the version (incremental by default, or minor, major, or an explicit X.Y.Z), writes the CHANGELOG.md entry and any privacy policy change, merges them, and tags the tip of main so android-release.yml publishes it. Use when at least one change has been merged to main since the last v* tag and the user wants it released, e.g. "/to-release", "/to-release minor", "ship it", "cut 1.12.0", "release what's on main".
argument-hint: "[incremental|minor|major|X.Y.Z]"
---

# /to-release

Argument: `$ARGUMENTS`. Leave it empty for `incremental`.

The scripts do the deterministic steps: choosing the version, bumping it, and
tagging. Use them rather than doing those steps by hand, because they carry the
checks. Your job is the part that needs judgement: the changelog entry and the
privacy policy.

A tag on main is what publishes. `android-release.yml` builds the tag, sends the
entry's `<!-- play -->` summary to Google Play as the en-GB "What's new", and
turns the rest into the GitHub release. Everything therefore has to be on main
before the tag.

## 1. Choose the version

```
tools/release.py next $ARGUMENTS
```

It prints the version, which is V below, or it refuses. When there is nothing
new on main since the last tag, tell the user that and stop. If main already
carries a version that was bumped but never tagged, a keyword picks that
version, and the script says so in its output.

## 2. Prepare the release branch

```
git fetch origin && git switch -c release/V origin/main
tools/release.py bump V        # skip if `next` said main already carries V
```

## 3. Find what changed

Read `git log --oneline <last tag>..origin/main` and the merged PRs in that
range with `gh pr view <n>`. PR bodies explain why a change was made, and the
entry needs that. Commit subjects mostly just say what changed. Group the
changes by what someone using the app notices, not by PR:
- five PRs polishing one screen become one sentence;
- a refactor with no visible effect gets no mention at all;
- a version that never reached Play is folded into this entry.

If CHANGELOG.md already has an entry for V, check it against the range instead
of rewriting it.

## 4. Audit privacy before writing

This step matters most. Play's "What's new" is the only place a tester learns
what changed, so they must not discover a change to what the app shares by
accident.

For each change, ask three questions:
- Does anything now leave the phone that didn't before?
- Does anything now go to someone new: a service, a Contact, or phones nearby?
- Is anything now stored that wasn't?

Check the code rather than trusting the PR title. Find the payload that is
built and sent, for example a Contact sync manifest or the parameters of a
setlist.fm query. Note which fields go, to whom, and when: on a tap, or on
their own. Note what deliberately does *not* go as well.

A yes to any question makes the release privacy-affecting. In that case, make
these changes to `docs/privacy-policy.md` on the same branch:
- Add a bullet under "## What has changed", newest first:
  `- **V, <day Month year>: <headline>.** <what now leaves, to whom, when; what was true before>`.
  Keep that exact format. The version in the bullet is what makes
  `changelog.py --check` require a "Privacy:" lead in the changelog.
- Correct every section the change touches: the short version, the services
  table, "What is stored", and the feature's own section. The policy has to
  describe the app as it is now.
- Update "Last updated".

## 5. Write the entry

Put it at the top of CHANGELOG.md, below the header, as `## V — YYYY-MM-DD`.
Read the two most recent entries first and match their voice.

Write for someone who uses the app, is curious, and has never read the code.
Describe what they can do now and what has stopped going wrong. Don't mention
which class moved.

The `<!-- play -->` summary:
- Plain text of at most 500 characters. Play shows it raw.
- If the release is privacy-affecting, open with `Privacy:` and state the
  change plainly, before any feature.
- Then the features, in order of how much a user would care.
- British spelling, because the listing is en-GB.
- No PR numbers, no code names.
- Name a limitation wherever leaving it out would mislead. Spotify export works
  for five accounts only, so never advertise it without saying so.

After the summary comes the fuller account:
- one paragraph per feature, starting with a bold lead;
- a **Privacy.** paragraph first when the release is privacy-affecting, linking
  to the policy's "What has changed";
- a short **Fixes.** paragraph at the end.

## 6. Check, open the PR, get the go-ahead

```
tools/changelog.py --check
tools/changelog.py V --play      # what Play will show
```

Commit, push, and open a PR titled `V: <headline>`. Merges are squash merges.

Then show the user:
- the Play text, verbatim;
- whether you judged the release privacy-affecting, and the code evidence for
  that judgement;
- whether Play Console's **Data safety** form may need an edit. The scripts
  cannot change it.

Wait for the user's OK before merging. What they approve here is what reaches
testers.

## 7. Merge and tag

```
gh pr merge <n> --squash --delete-branch
gh run list --workflow android.yml --branch main --limit 1    # then gh run watch <id>
tools/release.py tag V            # dry run: every check, no tag
tools/release.py tag V --push
```

The version bump touches `android/`, so merging starts `android.yml` on main.
`tag` refuses until that run is green on the current Android tree.

When `tag` refuses, report the reason and stop. Never tag by hand to get
around a refusal. Each check stands for a failure that has already happened:
- a version mismatch;
- a missing entry, which leaves "What's new" empty on Play;
- an untested build.

## 8. Report

Watch `android-release.yml` for the new tag with `gh run watch`. Then report:
- the version and the tag;
- the Play track, which is alpha by default;
- whether the run went green;
- links to the PR, the run, and the GitHub release.

A red run needs its failing step read and reported. Re-running it won't help:
Play refusals, such as an undeclared permission, do not fix themselves. See
"Facts that bite" in `docs/release-checklist.md`.
