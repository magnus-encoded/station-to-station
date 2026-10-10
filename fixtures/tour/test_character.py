"""Broken-copy tests for the character contract; no third-party dependencies."""
import copy
from datetime import datetime
import json
from pathlib import Path
import tempfile
import unittest
from check_character import validate

class CharacterTests(unittest.TestCase):
    def setUp(self):
        self.source = Path(__file__).parent / "character" / "character.json"
        self.data = json.loads(self.source.read_text())

    def check_broken(self, mutate):
        with tempfile.TemporaryDirectory() as temp:
            path = Path(temp) / "character.json"
            (path.parent / "assets").symlink_to(self.source.parent / "assets", target_is_directory=True)
            data = copy.deepcopy(self.data)
            mutate(data)
            path.write_text(json.dumps(data))
            with self.assertRaises(ValueError): validate(path)

    def test_ludwig_history_is_varied_and_within_his_lifetime(self):
        history = self.data["history"]
        self.assertEqual(len(history), len({night["artist"] for night in history}))
        for night in history:
            date = datetime.strptime(night["date"], "%d-%m-%Y")
            self.assertTrue(datetime(1770, 12, 16) <= date <= datetime(1827, 3, 26))
    def test_unknown_screen(self): self.check_broken(lambda d: d["lines"]["S7"].update(screens={"elsewhere": {"do": "Read this."}}))
    def test_overlong_screen(self): self.check_broken(lambda d: d["lines"]["S7"]["screens"]["exchange"].update({"do": "word " * 21}))
    def test_nested_screen(self): self.check_broken(lambda d: d["lines"]["S7"]["screens"]["exchange"].update(screens={"room": {"do": "Read this."}}))
    def test_missing_history(self): self.check_broken(lambda d: d.pop("history"))
    def test_bad_history_date(self): self.check_broken(lambda d: d["history"][0].update(date="31-02-1800"))
    def test_real_character(self): validate(self.source)
    def test_missing_satisfied_copy(self):
        for step in ("S5", "S6", "S8", "S10", "S13"):
            self.check_broken(lambda d: d["lines"][step].pop("already"))
    def test_missing_failed(self): self.check_broken(lambda d: d["lines"]["S3"].pop("failed"))
    def test_missing_gap(self): self.check_broken(lambda d: d["lines"]["S14"].pop("gap"))
    def test_missing_nohit(self): self.check_broken(lambda d: d["lines"]["S3"].pop("nohit", None))
    def test_missing_opener_fallback(self): self.check_broken(lambda d: d["lines"]["S14"]["screens"]["room"].pop("fallback"))
    def test_unknown_placeholder(self): self.check_broken(lambda d: d["lines"]["S14"].update({"do": "Play {song}."}))
    def test_placeholder_on_wrong_step(self): self.check_broken(lambda d: d["lines"]["S1"].update({"do": "Play {opener}."}))
    def test_missing_set_complete_card(self): self.check_broken(lambda d: d["lines"].pop("S17"))
    def test_missing_name(self): self.check_broken(lambda d: d.pop("name"))
    def test_missing_step(self): self.check_broken(lambda d: d["lines"].pop("S16"))
    def test_overlong_do(self): self.check_broken(lambda d: d["lines"]["S1"].update({"do": "word " * 21}))
    def test_overlong_why(self): self.check_broken(lambda d: d["lines"]["S1"].update({"why": "word " * 31}))
    def test_overlong_override(self): self.check_broken(lambda d: d["lines"]["S1"].update({"android": "word " * 21}))
    def test_missing_asset(self): self.check_broken(lambda d: d.update(avatar="absent"))
    def test_non_string_override(self): self.check_broken(lambda d: d["lines"]["S1"].update(ios=42))

if __name__ == "__main__": unittest.main()
