# Prep done while Codex was rate-limited (2026-09-15, 04:50–09:10)

Prepared by Claude Code agents. Nothing was pushed, and `gossip-ble-diagnostics` was not modified.
The uncommitted `docs/gossip-v2-next-pass.md` edit (CI green on 89bd25d, Pixel/Pi status) is still yours to commit.

Read these first. Each one lists its own verification status:

- `scratchpad/prep-443.md`: #443 Gig merge/adoption. Android tests and implementation are on the
  local branch `prep/443-gig-merge` in a worktree, with the iOS mirror work listed. Review it, then cherry-pick.
  **Tests only (commit a5ebd40), with no production change.** One test is `@Ignore`d on an open question
  for the user: when two Gigs merge, `TimelineStore.merging` drops the losing Gig's id, so its public
  Log disappears from the merged night. Options: keep old ids on the surviving Gig, re-publish the facts,
  or retire them. **Decided: none. Treat it as a known edge case.** See the #443 bullet in
  docs/gossip-v2-next-pass.md.
- `scratchpad/prep-444-448.md`: #444 acceptance criteria and the iPhone viability protocol, the #448
  lifecycle test checklist, the #446 device checklist, and where #415 diverges from the code.
- `scratchpad/prep-pi-ble.md`: **Pi Bluetooth restored 2026-09-15 12:59.** After each reboot, run the one-line fix at the bottom of that file.
- `scratchpad/prep-448-tests.md`: Android tests for the #448 lifecycle cases that had none. They are on
  the local branch `prep/448-lifecycle-tests`. Tests marked `@Ignore` are behaviour that isn't implemented yet.
  Nine tests: 7 pass and 2 are `@Ignore`d.
  (a) Once a Gig's grace ends, its Envelopes still relay while another Gig keeps the radio on. That is likely a bug.
  (b) Every Log edit made during participation publishes immediately instead of waiting for Done. Confirm the intended behaviour with the user before changing it.

If a file is missing, that agent did not finish. Do the work yourself instead of assuming it was done.

**Start here instead of resuming:** issue #455 is the consolidated spec and code map, with line spans at commit 71efa2f. A fresh session that reads #455 costs far fewer input tokens than resuming the old one.
