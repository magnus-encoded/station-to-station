import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import release  # noqa: E402

MAIN = "a" * 40


class TagPlan(unittest.TestCase):
    def test_no_tag_anywhere_is_created(self):
        self.assertEqual(release.tag_plan("v1.0.1", MAIN, None, None), "create")

    def test_a_local_tag_at_main_whose_push_failed_is_pushed(self):
        self.assertEqual(release.tag_plan("v1.0.1", MAIN, MAIN, None), "push")

    def test_a_tag_on_origin_is_refused(self):
        with self.assertRaisesRegex(release.Refusal, "already on origin"):
            release.tag_plan("v1.0.1", MAIN, None, MAIN)

    def test_a_stale_local_tag_is_refused_with_the_way_out(self):
        with self.assertRaisesRegex(release.Refusal, "git tag -d v1.0.1"):
            release.tag_plan("v1.0.1", MAIN, "b" * 40, None)


if __name__ == "__main__":
    unittest.main()
