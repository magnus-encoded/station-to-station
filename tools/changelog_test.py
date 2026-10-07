import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import changelog  # noqa: E402

POLICY = "# Policy\n\n## What has changed\n\n- **2.0.1, 1 January 2027: x.** y\n\n## Next\n"


def entry(version: str, play: str) -> str:
    return f"## {version} — 2027-01-01\n\n<!-- play -->\n{play}\n<!-- /play -->\n\nBody.\n\n"


class Kind(unittest.TestCase):
    def test_fix_lines_make_a_fix_release(self):
        self.assertEqual(changelog.kind("Fix: a.\nFix: b."), "fix")

    def test_prose_makes_a_feature_release_whatever_the_version(self):
        self.assertEqual(changelog.kind("A new screen, and a fix."), "feature")


class FixFormat(unittest.TestCase):
    def check(self, *entries: str, policy: str = "") -> list[str]:
        return changelog.check("".join(entries), policy)

    def test_one_fix_line_per_fix_passes(self):
        self.assertEqual(self.check(entry("2.0.2", "Fix: a.\nFix: b.")), [])

    def test_prose_mixed_into_fix_lines_fails(self):
        problems = self.check(entry("2.0.2", "Fix: a.\nThings got better in several ways."))
        self.assertTrue(any("one \"Fix: …\" line per fix" in p for p in problems), problems)

    def test_privacy_lines_lead_a_fix_release(self):
        self.assertEqual(self.check(entry("2.0.1", "Privacy: x.\nFix: a."), policy=POLICY), [])

    def test_privacy_after_a_fix_fails(self):
        self.assertNotEqual(self.check(entry("2.0.2", "Fix: a.\nPrivacy: x.")), [])

    def test_a_patch_with_features_keeps_prose(self):
        self.assertEqual(self.check(entry("2.0.3", "A new screen.")), [])

    def test_the_real_changelog_passes(self):
        self.assertEqual(changelog.check(changelog.CHANGELOG.read_text(encoding="utf-8"),
                                         changelog.POLICY.read_text(encoding="utf-8")), [])


if __name__ == "__main__":
    unittest.main()
