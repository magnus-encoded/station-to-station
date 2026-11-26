import json
import unittest
from quality_report import summarize, select_device


def batch(name="feature.loading", attrs=None, span_id="1", ms=2500):
    return json.dumps({"resourceSpans": [{"scopeSpans": [{"spans": [{
        "traceId": "a", "spanId": span_id, "name": name,
        "startTimeUnixNano": "1000000000", "endTimeUnixNano": str(1000000000 + ms * 1000000),
        "attributes": [{"key": k, "value": {"stringValue": v}} for k, v in (attrs or {}).items()]
    }]}]}]})


class ReportTest(unittest.TestCase):
    def test_model_selection_follows_wireless_port_changes_with_two_phones(self):
        inventory = "old:123    device model:Pixel_7_Pro\nnew:456\tdevice model:Pixel_11_Pro\n"
        self.assertEqual("new:456", select_device("Pixel_11_Pro", inventory))
        self.assertEqual("new:789", select_device("Pixel_11_Pro", inventory.replace("new:456", "new:789")))

    def test_model_selection_rejects_missing_or_ambiguous_targets(self):
        with self.assertRaises(RuntimeError):
            select_device("Pixel_11_Pro", "")
        with self.assertRaises(RuntimeError):
            select_device("Pixel_11_Pro", "a\tdevice model:Pixel_11_Pro\nb\tdevice model:Pixel_11_Pro\n")

    def test_duplicates_and_partial_batches_do_not_inflate_loads(self):
        line = batch(attrs={"feature.name": "search", "outcome": "settled"})
        report = summarize(line + "\n\n" + line + "\n{")
        self.assertIn("Unique spans: 1", report)
        self.assertIn("ignored: 1", report)
        self.assertIn("search: 1/1 settled loads", report)
        self.assertIn("| search | 1 | 2500 | — | 0 |", report)

    def test_interrupted_loads_are_not_reported_as_success_or_latency(self):
        report = summarize(batch(attrs={"feature.name": "search", "outcome": "interrupted"}))
        self.assertNotIn("settled loads over", report)
        self.assertIn("| search | 0 | — | — | 1 |", report)

    def test_failures_remain_visible_without_loading_samples(self):
        report = summarize(batch(name="feature.error", attrs={"screen.name": "programme"}))
        self.assertIn("feature.error: 1 observation(s) on programme", report)
        self.assertNotIn("No failure", report)


if __name__ == "__main__":
    unittest.main()
