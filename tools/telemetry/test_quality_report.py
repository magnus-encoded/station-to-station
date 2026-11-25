import json
import unittest
from quality_report import summarize


def batch(name="feature.loading", attrs=None, span_id="1", ms=2500):
    return json.dumps({"resourceSpans": [{"scopeSpans": [{"spans": [{
        "traceId": "a", "spanId": span_id, "name": name,
        "startTimeUnixNano": "1000000000", "endTimeUnixNano": str(1000000000 + ms * 1000000),
        "attributes": [{"key": k, "value": {"stringValue": v}} for k, v in (attrs or {}).items()]
    }]}]}]})


class ReportTest(unittest.TestCase):
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
