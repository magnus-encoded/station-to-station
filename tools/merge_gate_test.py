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

BODY = "Fixes the thing.\n\nCloses #12\n\nTool: Claude\n"
VISUAL = [{"path": "android/app/src/main/java/app/ui/BillScreen.kt"}]


def review(tool, extra="", state="APPROVED"):
    return {"author": {"login": "reviewer"}, "state": state, "body": f"LGTM\n{extra}\nTool: {tool}\n"}


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
            return result, "pr merge 7 --squash" in (tmp / "log").read_text()

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
        self.assertRefuses(pr(reviews=[{"author": {"login": "r"}, "state": "APPROVED", "body": "LGTM"}]), "no-review")

    def test_comment_only_review_does_not_count(self):
        self.assertRefuses(pr(reviews=[review("Codex", state="COMMENTED")]), "no-review")

    def test_dependabot_merges_on_any_agent_approval_without_body_checks(self):
        self.assertMerges(pr(author={"login": "app/dependabot"}, body="Bumps foo.", reviews=[review("Claude")]))

    def test_missing_closes_line_is_refused(self):
        self.assertRefuses(pr(body="Fixes.\n\nTool: Claude\n"), "body-closes")

    def test_visual_pr_without_screenshot_is_refused(self):
        self.assertRefuses(pr(files=VISUAL), "screenshot")

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
