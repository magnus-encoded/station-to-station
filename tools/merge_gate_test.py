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
exit 0
"""

BODY = "Fixes the thing.\n\nCloses #12\n\n🤖 Generated with [Claude Code](https://claude.com/claude-code)\n"
MARK = {"Claude": "🤖 Generated with [Claude Code](https://claude.com/claude-code)", "Codex": "Written by Codex"}
VISUAL = [{"path": "android/app/src/main/java/app/ui/BillScreen.kt"}]


def review(tool, extra="", state="APPROVED", login="reviewer", oid="abc123"):
    return {"commit": {"oid": oid}, "author": {"login": login}, "state": state, "body": f"LGTM\n{extra}\n{MARK[tool]}\n"}


def pr(**over):
    base = {
        "isDraft": False,
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
            (tmp / "pr.json").write_text(json.dumps(data))
            (tmp / "log").touch()
            env = dict(os.environ, PATH=f"{tmp}:{os.environ['PATH']}",
                       GH_LOG=str(tmp / "log"), GH_PR=str(tmp / "pr.json"))
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

    def test_unclosed_fence_before_the_footer_voids_it(self):
        body = "Closes #12\n\n```\ncode\n\n" + MARK["Claude"] + "\n"
        self.assertRefuses(pr(body=body), "body-footer", "unparseable-body")

    def test_indented_code_before_the_footer_does_not_void_it(self):
        body = "Closes #12\n\n    code\n\n" + MARK["Claude"] + "\n"
        self.assertMerges(pr(body=body))

    def test_review_with_an_unclosed_fence_is_refused(self):
        r = review("Codex", "```")
        self.assertRefuses(pr(reviews=[r]), "unparseable-body", "no-review")

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

    def test_merge_is_pinned_to_the_validated_commit(self):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            gh = tmp / "gh"
            gh.write_text(FAKE_GH)
            gh.chmod(0o755)
            (tmp / "pr.json").write_text(json.dumps(pr()))
            (tmp / "log").touch()
            env = dict(os.environ, PATH=f"{tmp}:{os.environ['PATH']}",
                       GH_LOG=str(tmp / "log"), GH_PR=str(tmp / "pr.json"))
            subprocess.run([str(GATE), "7"], env=env, capture_output=True, text=True)
            self.assertIn("--match-head-commit abc123", (tmp / "log").read_text())

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
