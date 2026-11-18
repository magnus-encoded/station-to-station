"""merge-gate through its one seam: a fake `gh` on PATH serving canned PR JSON."""
import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

GATE = Path(__file__).with_name("merge-gate")

FAKE_GH = """#!/usr/bin/env bash
echo "$@" >> "$GH_LOG"
if [ "$1 $2" = "pr view" ]; then cat "$GH_PR"; fi
if [ "$1" = "api" ]; then
  case "$*" in *pulls/7/files) cat "$GH_FILES" ;; *pulls/7/commits) cat "$GH_COMMITS" ;; esac
fi
exit 0
"""

BODY = "Fixes the thing.\n\nCloses #12\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n"
MARK = {"Claude": "🤖 Generated with [Claude Code](https://claude.com/claude-code)", "Codex": "Written by Codex"}
VISUAL = [{"path": "android/app/src/main/java/app/ui/BillScreen.kt"}]


def review(tool, extra="", state="APPROVED", login="reviewer", oid="abc123"):
    return {"commit": {"oid": oid}, "author": {"login": login}, "state": state, "body": f"LGTM\n{extra}\n{MARK[tool]}\n"}


def agent_review(tool, line="Verdict: approve", oid="abc123"):
    body = f"LGTM\n\n{line}\n{MARK[tool]}\n"
    return {"commit": {"oid": oid}, "author": {"login": "magnus-encoded"}, "state": "COMMENTED", "body": body}


def pr(**over):
    base = {
        "isDraft": False,
        "baseRefName": "main",
        "state": "OPEN",
        "mergeable": "MERGEABLE",
        "author": {"login": "magnus-encoded"},
        "body": BODY,
        "statusCheckRollup": [{"status": "COMPLETED", "conclusion": "SUCCESS"}],
        "reviews": [review("Codex")],
        "files": [{"path": "tools/merge-gate"}],
        "commits": [{"messageBody": ""}],
        "headRefOid": "abc123",
    }
    base.update(over)
    return base


class MergeGateTest(unittest.TestCase):
    def run_gate(self, data, *args):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            gh = tmp / "gh"
            gh.write_text(FAKE_GH)
            gh.chmod(0o755)
            data = dict(data)
            data.setdefault("changedFiles", len(data["files"]))
            rest_commits = data.pop("rest_commits", data["commits"])
            files = [[{"filename": f["path"]} for f in data.pop("files")]]
            commits = [[{"commit": {"message": c["messageBody"]}} for c in rest_commits]]
            (tmp / "pr.json").write_text(json.dumps(data))
            (tmp / "files.json").write_text(json.dumps(files))
            (tmp / "commits.json").write_text(json.dumps(commits))
            (tmp / "log").touch()
            env = dict(os.environ, PATH=f"{tmp}:{os.environ['PATH']}", GH_LOG=str(tmp / "log"),
                       GH_PR=str(tmp / "pr.json"), GH_FILES=str(tmp / "files.json"),
                       GH_COMMITS=str(tmp / "commits.json"))
            result = subprocess.run([str(GATE), "7", *args], env=env, capture_output=True, text=True)
            return result, "pr merge 7 --squash --match-head-commit abc123" in (tmp / "log").read_text()

    def assertMerges(self, data):
        result, merged = self.run_gate(data)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertTrue(merged)

    def assertRefuses(self, data, *codes):
        result, merged = self.run_gate(data)
        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(merged)
        self.assertEqual(sorted(l.split(":")[0] for l in result.stdout.splitlines()), sorted(codes))

    def test_all_green_squash_merges(self):
        self.assertMerges(pr())

    def test_red_ci_is_refused(self):
        self.assertRefuses(pr(statusCheckRollup=[{"status": "COMPLETED", "conclusion": "FAILURE"}]), "ci-red")

    def test_pending_ci_is_refused(self):
        self.assertRefuses(pr(statusCheckRollup=[{"status": "IN_PROGRESS", "conclusion": ""}]), "ci-pending")

    def test_conflicting_pr_is_refused(self):
        self.assertRefuses(pr(mergeable="CONFLICTING"), "conflict")

    def test_draft_is_refused(self):
        self.assertRefuses(pr(isDraft=True), "draft")

    def test_review_by_the_author_tool_does_not_count(self):
        self.assertRefuses(pr(reviews=[review("Claude")]), "no-review")

    def test_review_without_a_tool_footer_does_not_count(self):
        self.assertRefuses(pr(reviews=[{"commit": {"oid": "abc123"}, "author": {"login": "r"}, "state": "APPROVED", "body": "LGTM"}]), "no-review")

    def test_comment_only_review_does_not_count(self):
        self.assertRefuses(pr(reviews=[review("Codex", state="COMMENTED")]), "no-review")

    def test_dependabot_merges_on_any_agent_approval_without_body_checks(self):
        self.assertMerges(pr(author={"login": "app/dependabot"}, body="Bumps foo.", reviews=[review("Claude")]))

    def test_pr_by_two_tools_needs_a_reviewer_outside_both(self):
        body = BODY + MARK["Codex"] + "\n"
        self.assertRefuses(pr(body=body, reviews=[review("Codex")]), "no-review")

    def test_owner_approval_counts_for_a_pr_by_two_tools(self):
        body = BODY + MARK["Codex"] + "\n"
        owner = {"commit": {"oid": "abc123"}, "author": {"login": "magnus-encoded"}, "state": "APPROVED", "body": "ok"}
        self.assertMerges(pr(body=body, reviews=[owner]))

    def test_owner_account_approval_marked_with_the_author_tool_does_not_count(self):
        marked = review("Claude", login="magnus-encoded")
        self.assertRefuses(pr(reviews=[marked]), "no-review")

    def test_later_changes_requested_overrides_an_earlier_approval(self):
        reviews = [review("Codex"), review("Codex", state="CHANGES_REQUESTED")]
        self.assertRefuses(pr(reviews=reviews), "changes-requested", "no-review")

    def test_changes_requested_by_another_reviewer_blocks_despite_an_approval(self):
        reviews = [review("Codex"), review("Claude", state="CHANGES_REQUESTED", login="other")]
        self.assertRefuses(pr(reviews=reviews), "changes-requested")

    def test_later_approval_clears_earlier_changes_requested(self):
        self.assertMerges(pr(reviews=[review("Codex", state="CHANGES_REQUESTED"), review("Codex")]))

    def test_approval_of_an_older_commit_needs_re_review(self):
        self.assertRefuses(pr(reviews=[review("Codex", oid="old999")]), "no-review")

    def test_marker_in_a_blockquote_is_not_a_footer(self):
        body = "Closes #12\n\n> " + MARK["Claude"] + "\n"
        self.assertRefuses(pr(body=body, reviews=[review("Codex")]), "body-footer")

    def test_marker_before_the_footer_block_is_prose(self):
        body = "Closes #12\n\n```\n" + MARK["Claude"] + "\n```\n\nMore notes.\n"
        self.assertRefuses(pr(body=body, reviews=[review("Codex")]), "body-footer")

    def test_body_without_a_footer_is_refused_even_with_an_owner_approval(self):
        owner = {"commit": {"oid": "abc123"}, "author": {"login": "magnus-encoded"}, "state": "APPROVED", "body": "ok"}
        self.assertRefuses(pr(body="Closes #12\n", reviews=[owner]), "body-footer")

    def test_commit_trailers_alone_do_not_satisfy_the_footer(self):
        commits = [{"messageBody": "Co-Authored-By: Claude <noreply@anthropic.com>"}]
        self.assertRefuses(pr(body="Closes #12\n", commits=commits), "body-footer")

    def test_indented_code_before_the_footer_does_not_void_it(self):
        body = "Closes #12\n\n    code\n\n" + MARK["Claude"] + "\n"
        self.assertMerges(pr(body=body))

    def test_owner_approval_mentioning_a_marker_anywhere_is_not_human(self):
        quoted = {"commit": {"oid": "abc123"}, "author": {"login": "magnus-encoded"}, "state": "APPROVED",
                  "body": "    " + MARK["Claude"] + "\n\nok"}
        self.assertRefuses(pr(reviews=[quoted]), "no-review")

    def test_marker_must_be_the_whole_line_from_column_zero(self):
        for line in (" " + MARK["Claude"], "Not " + MARK["Claude"], MARK["Claude"] + " really"):
            self.assertRefuses(pr(body="Closes #12\n\n" + line + "\n"), "body-footer")

    def test_commit_coauthor_trailer_adds_an_author_tool(self):
        commits = [{"messageBody": "Co-Authored-By: Codex <noreply@openai.com>"}]
        self.assertRefuses(pr(commits=commits, reviews=[review("Codex")]), "no-review")

    def test_agent_verdict_comment_approves(self):
        self.assertMerges(pr(reviews=[agent_review("Codex")]))

    def test_verdict_by_the_author_tool_does_not_approve(self):
        self.assertRefuses(pr(reviews=[agent_review("Claude")]), "no-review")

    def test_verdict_comment_without_a_marker_does_not_approve(self):
        r = agent_review("Codex")
        r["body"] = "LGTM\n\nVerdict: approve\n"
        self.assertRefuses(pr(reviews=[r]), "no-review")

    def test_verdict_line_outside_the_final_paragraph_does_not_approve(self):
        r = agent_review("Codex")
        r["body"] = "Verdict: approve\n\nbut see below\n\n" + MARK["Codex"] + "\n"
        self.assertRefuses(pr(reviews=[r]), "no-review")

    def test_later_changes_verdict_overrides_an_earlier_verdict_approval(self):
        reviews = [agent_review("Codex"), agent_review("Codex", "Verdict: changes")]
        self.assertRefuses(pr(reviews=reviews), "changes-requested", "no-review")

    def test_verdict_approval_of_an_older_commit_needs_re_review(self):
        self.assertRefuses(pr(reviews=[agent_review("Codex", oid="old999")]), "no-review")

    def test_empty_ci_rollup_is_refused(self):
        self.assertRefuses(pr(statusCheckRollup=[]), "no-ci")

    def test_marker_inside_a_closed_fence_at_the_end_is_not_a_footer(self):
        body = "Closes #12\n\n```\n" + MARK["Claude"] + "\n```\n"
        self.assertRefuses(pr(body=body), "body-footer")

    def test_verdict_inside_a_closed_fence_does_not_approve(self):
        r = agent_review("Codex")
        r["body"] = "LGTM\n\n```\nVerdict: approve\n```\n" + MARK["Codex"] + "\n"
        self.assertRefuses(pr(reviews=[r]), "no-review")

    def test_footer_before_a_later_fence_is_not_counted(self):
        self.assertRefuses(pr(body=BODY + "\n```\nlog\n```\n"), "body-footer")

    def test_pr_not_targeting_main_is_refused(self):
        self.assertRefuses(pr(baseRefName="release"), "wrong-base")

    def test_files_count_mismatch_is_too_large(self):
        self.assertRefuses(pr(changedFiles=5), "too-large")

    def test_commits_count_mismatch_is_too_large(self):
        self.assertRefuses(pr(rest_commits=[]), "too-large")

    def test_two_hundred_fifty_commits_is_too_large(self):
        commits = [{"messageBody": ""}] * 250
        self.assertRefuses(pr(commits=commits), "too-large")

    def test_review_that_quotes_a_marker_does_not_count(self):
        r = {"commit": {"oid": "abc123"}, "author": {"login": "r"}, "state": "APPROVED", "body": "> " + MARK["Codex"]}
        self.assertRefuses(pr(reviews=[r]), "no-review")

    def test_missing_closes_line_is_refused(self):
        self.assertRefuses(pr(body="Fixes.\n\n"+MARK["Claude"]+"\n"), "body-closes")

    def test_visual_pr_without_screenshot_is_refused(self):
        self.assertRefuses(pr(files=VISUAL), "screenshot")

    def test_ios_ui_path_is_visual(self):
        self.assertRefuses(pr(files=[{"path": "ios/StationToStation/UI/StationView.swift"}]), "screenshot")

    def test_visual_pr_with_screenshot_in_non_author_approval_merges(self):
        self.assertMerges(pr(files=VISUAL, reviews=[review("Codex", "![bill](https://example.com/bill.png)")]))

    def test_completed_check_without_a_conclusion_is_refused(self):
        for conclusion in (None, ""):
            self.assertRefuses(pr(statusCheckRollup=[{"__typename": "CheckRun", "status": "COMPLETED", "conclusion": conclusion}]), "ci-unknown")

    def test_check_run_without_a_status_is_refused(self):
        self.assertRefuses(pr(statusCheckRollup=[{"__typename": "CheckRun", "conclusion": "SUCCESS"}]), "ci-unknown")

    def test_unknown_check_status_or_conclusion_is_refused(self):
        for record in ({"status": "SOMETHING", "conclusion": "SUCCESS"}, {"status": "COMPLETED", "conclusion": "MYSTERY"}):
            self.assertRefuses(pr(statusCheckRollup=[record]), "ci-unknown")

    def test_malformed_check_records_are_refused(self):
        for record in ({}, None, "SUCCESS", {"__typename": "Other", "status": "COMPLETED", "conclusion": "SUCCESS"}):
            self.assertRefuses(pr(statusCheckRollup=[record]), "ci-unknown")

    def test_one_incomplete_record_blocks_otherwise_green_checks(self):
        green = {"__typename": "CheckRun", "status": "COMPLETED", "conclusion": "SUCCESS"}
        self.assertRefuses(pr(statusCheckRollup=[green, {"__typename": "CheckRun", "status": "COMPLETED", "conclusion": None}]), "ci-unknown")

    def test_status_context_with_an_unknown_state_is_refused(self):
        for record in ({"__typename": "StatusContext", "state": "WEIRD"}, {"__typename": "StatusContext"},
                       {"__typename": "StatusContext", "state": None}):
            self.assertRefuses(pr(statusCheckRollup=[record]), "ci-unknown")

    def test_status_context_states_are_reported(self):
        self.assertRefuses(pr(statusCheckRollup=[{"__typename": "StatusContext", "state": "FAILURE"}]), "ci-red")
        self.assertRefuses(pr(statusCheckRollup=[{"__typename": "StatusContext", "state": "PENDING"}]), "ci-pending")

    def test_real_check_run_and_status_context_shapes_merge_when_green(self):
        rollup = [
            {"__typename": "CheckRun", "name": "build", "status": "COMPLETED", "conclusion": "SUCCESS", "workflowName": "ci"},
            {"__typename": "CheckRun", "name": "lint", "status": "COMPLETED", "conclusion": "NEUTRAL"},
            {"__typename": "CheckRun", "name": "docs", "status": "COMPLETED", "conclusion": "SKIPPED"},
            {"__typename": "StatusContext", "context": "ci/legacy", "state": "SUCCESS", "targetUrl": "https://example.com"},
        ]
        self.assertMerges(pr(statusCheckRollup=rollup))

    def test_cancelled_and_timed_out_checks_are_red(self):
        for conclusion in ("CANCELLED", "TIMED_OUT", "ACTION_REQUIRED"):
            self.assertRefuses(pr(statusCheckRollup=[{"__typename": "CheckRun", "status": "COMPLETED", "conclusion": conclusion}]), "ci-red")

    def test_queued_check_run_is_pending(self):
        self.assertRefuses(pr(statusCheckRollup=[{"__typename": "CheckRun", "status": "QUEUED", "conclusion": None}]), "ci-pending")

    def test_marker_after_an_unclosed_fence_is_not_a_footer(self):
        self.assertRefuses(pr(body="Closes #12\n\n```\n\n" + MARK["Claude"] + "\n"), "body-footer")

    def test_verdict_after_an_unclosed_fence_does_not_approve(self):
        r = agent_review("Codex")
        r["body"] = "LGTM\n\n~~~\nVerdict: approve\n" + MARK["Codex"] + "\n"
        self.assertRefuses(pr(reviews=[r]), "no-review")

    def test_mismatched_or_short_fence_closers_do_not_close(self):
        openers_closers = [("```", "~~~"), ("~~~", "```"), ("````", "```"), ("```", "``"), ("```", "```js")]
        for opener, closer in openers_closers:
            body = f"Closes #12\n\n{opener}\n{closer}\n\n" + MARK["Claude"] + "\n"
            self.assertRefuses(pr(body=body), "body-footer")

    def test_longer_or_matching_fence_closers_close(self):
        for opener, closer in (("```", "````"), ("~~~~", "~~~~~"), ("```", "```")):
            body = f"Closes #12\n\n{opener}\ncode\n{closer}\n\n" + MARK["Claude"] + "\n"
            self.assertMerges(pr(body=body))

    def test_footer_after_a_four_space_indented_fence_line_still_counts(self):
        self.assertMerges(pr(body="Closes #12\n\n    ```\n\n" + MARK["Claude"] + "\n"))

    def test_closes_example_in_a_fence_does_not_close_the_issue(self):
        for fenced in ("```\nCloses #12\n```", "~~~\nFixes #3\n~~~"):
            self.assertRefuses(pr(body=f"Example:\n\n{fenced}\n\n" + MARK["Claude"] + "\n"), "body-closes")

    def test_closes_example_in_an_unclosed_fence_does_not_close_the_issue(self):
        self.assertRefuses(pr(body="```\nCloses #12\n\n" + MARK["Claude"] + "\n"), "body-closes", "body-footer")

    def test_closes_example_in_a_quote_or_code_does_not_close_the_issue(self):
        for example in ("> Closes #12", "  > Resolves #12", "`Closes #12`", "    Fixes #12"):
            self.assertRefuses(pr(body=f"Use:\n\n{example}\n\n" + MARK["Claude"] + "\n"), "body-closes")

    def test_closes_example_in_a_multi_backtick_or_multiline_code_span_does_not_close_the_issue(self):
        for example in ("``Closes #12``", "```Closes #12```", "`Use\nCloses #12`", "``a ` b\nFixes #12 ``"):
            self.assertRefuses(pr(body=f"Use {example} here.\n\n" + MARK["Claude"] + "\n"), "body-closes")

    def test_closes_in_a_lazy_blockquote_continuation_does_not_close_the_issue(self):
        self.assertRefuses(pr(body="> Example:\nCloses #12\n\n" + MARK["Claude"] + "\n"), "body-closes")

    def test_closes_after_a_blank_line_ends_a_quote_and_counts(self):
        self.assertMerges(pr(body="> Example:\n> text\n\nCloses #12\n\n" + MARK["Claude"] + "\n"))

    def test_closes_after_an_unmatched_or_separate_code_span_counts(self):
        for body in ("`a\n\nCloses #12 `b`", "``a` and Closes #12", "`code` then Closes #12"):
            self.assertMerges(pr(body=body + "\n\n" + MARK["Claude"] + "\n"))

    def test_prose_closing_instructions_still_close_the_issue(self):
        for line in ("Closes #12", "This also fixes #12.", "Resolves #12 and `code`", "- Closes #12"):
            self.assertMerges(pr(body=f"{line}\n\n" + MARK["Claude"] + "\n"))

    def test_closes_after_a_closed_fence_counts(self):
        self.assertMerges(pr(body="```\ncode\n```\n\nCloses #12\n\n" + MARK["Claude"] + "\n"))

    def test_ios_feature_presentation_files_are_visual(self):
        base = "ios/StationToStation/Features/"
        for name in ("Gig/CheckInDialog.swift", "Contacts/MediaOfferAlert.swift", "Contacts/FriendsView.swift",
                     "Planning/AddGigSheet.swift", "GigMedia/NightGrid.swift", "GigMedia/MediaPicker.swift",
                     "GigMedia/MediaWindow.swift", "Gig/NightNotes.swift", "Gig/LogEditor.swift",
                     "Gig/GigDeleteAlert.swift", "Contacts/PeopleRails.swift", "Contacts/MaybeLines.swift",
                     "Planning/JoinGigButton.swift", "Gossip/GossipPresenceRow.swift", "Settings/SplashView.swift"):
            self.assertRefuses(pr(files=[{"path": base + name}]), "screenshot")

    def test_ios_feature_controllers_models_and_tests_are_not_visual(self):
        for path in ("ios/StationToStation/Features/Gig/GigController.swift", "ios/StationToStation/Features/StateHost.swift",
                     "ios/StationToStation/Features/Planning/Line.swift",
                     "ios/StationToStationTests/Features/GigControllerTests.swift",
                     "ios/StationToStationTests/Features/FakeState.swift",
                     "ios/StationToStationTests/Features/Gig/CheckInDialogTests.swift"):
            self.assertMerges(pr(files=[{"path": path}]))

    def test_every_failed_condition_is_listed_at_once(self):
        self.assertRefuses(
            pr(isDraft=True, mergeable="CONFLICTING", reviews=[],
               statusCheckRollup=[{"status": "COMPLETED", "conclusion": "FAILURE"}]),
            "draft", "conflict", "ci-red", "no-review")

    def test_dry_run_passes_without_merging(self):
        result, merged = self.run_gate(pr(), "--dry-run")
        self.assertEqual(result.returncode, 0)
        self.assertFalse(merged)

    def test_dry_run_still_reports_refusals(self):
        result, merged = self.run_gate(pr(isDraft=True), "--dry-run")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("draft", result.stdout)


if __name__ == "__main__":
    unittest.main()
