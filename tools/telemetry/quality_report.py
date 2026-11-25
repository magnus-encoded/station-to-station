#!/usr/bin/env python3
"""Collect local debug OTLP traces over adb and rank evidence for investigation."""
import argparse
from collections import Counter, defaultdict
from datetime import datetime, timezone
import json
import math
from pathlib import Path
import subprocess
from statistics import median

PACKAGE = "io.github.magnusencoded.stationtostation.debug"


def summarize(text):
    spans = {}
    malformed = 0
    builds = set()
    for line in text.splitlines():
        if not line.strip():
            continue
        try:
            for resource in json.loads(line)["resourceSpans"]:
                attributes = {a["key"]: next(iter(a["value"].values())) for a in resource.get("resource", {}).get("attributes", [])}
                if attributes.get("build.sha"):
                    builds.add(attributes["build.sha"] + " / " + attributes.get("service.version", "unknown"))
                for scope in resource["scopeSpans"]:
                    for span in scope["spans"]:
                        spans[(span["traceId"], span["spanId"])] = span
        except (ValueError, KeyError, TypeError):
            malformed += 1
    counts = Counter()
    timings = defaultdict(list)
    newest = 0
    interrupted = Counter()
    for span in spans.values():
        attrs = {a["key"]: next(iter(a["value"].values())) for a in span.get("attributes", [])}
        name = span["name"]
        screen = attrs.get("screen.name", "unknown")
        counts[(name, screen)] += 1
        end = int(span["endTimeUnixNano"])
        newest = max(newest, end)
        if name in ("feature.loading", "app.ready"):
            feature = attrs.get("feature.name", "app.ready")
            if attrs.get("outcome") == "interrupted":
                interrupted[feature] += 1
            else:
                timings[feature].append((end - int(span["startTimeUnixNano"])) / 1e6)
    lines = ["# Debug feature quality", "", "Generated: " + datetime.now(timezone.utc).isoformat(),
             f"Unique spans: {len(spans)}; incomplete/invalid batches ignored: {malformed}.",
             "Latest trace: " + (datetime.fromtimestamp(newest / 1e9, timezone.utc).isoformat() if newest else "none"),
             "Builds: " + (", ".join(sorted(builds)) or "unknown"),
             "", "Debug timings are diagnostic only. Confirm performance on the measure build.",
             "Loading settled means the busy flag cleared; it does not prove success.",
             "Main-thread stalls are watchdog observations, not OS-confirmed ANRs.", "", "## Investigation candidates", ""]
    candidates = []
    for (name, screen), count in sorted(counts.items()):
        if name in ("app.crash", "app.main_thread_stall", "feature.error"):
            candidates.append(f"- {name}: {count} observation(s) on {screen}; reproduce and correlate before changing behavior.")
    for feature, samples in sorted(timings.items()):
        slow = sum(t > 2000 for t in samples)
        if slow:
            candidates.append(f"- {feature}: {slow}/{len(samples)} settled loads over 2 seconds; inspect blocking work and dependency latency.")
    lines += candidates or ["No failure or slow-load candidates in the collected sample."]
    lines += ["", "## Feature loading", "", "| Feature | Settled | Median ms | p95 ms (≥5 samples) | Interrupted |",
              "|---|---:|---:|---:|---:|"]
    for feature in sorted(set(timings) | set(interrupted)):
        values = sorted(timings[feature])
        p50 = f"{median(values):.0f}" if values else "—"
        p95 = f"{values[math.ceil(len(values)*.95)-1]:.0f}" if len(values) >= 5 else "—"
        lines.append(f"| {feature} | {len(values)} | {p50} | {p95} | {interrupted[feature]} |")
    lines += ["", "## Observed screen entries", ""]
    lines += [f"- {screen}: {count}" for (name, screen), count in sorted(counts.items()) if name == "screen.enter"]
    lines += ["", "Unobserved features are unassessed. No entered text, names, IDs, URLs, exception messages or credentials are collected."]
    return "\n".join(lines) + "\n"


def collect(serial):
    prefix = ["adb"] + (["-s", serial] if serial else [])
    chunks = []
    for filename in ("spans.2.jsonl", "spans.1.jsonl", "spans.jsonl"):
        result = subprocess.run(prefix + ["shell", "-T", "run-as", PACKAGE, "cat", "no_backup/telemetry/" + filename],
                                capture_output=True, timeout=20)
        if result.returncode == 0:
            chunks.append(result.stdout.decode("utf-8", errors="replace"))
    if not chunks:
        raise RuntimeError("No debug traces available; check device connection and launch the telemetry build.")
    return "\n".join(chunks)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial")
    parser.add_argument("--input", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    text = args.input.read_text() if args.input else collect(args.serial)
    report = summarize(text)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    temporary = args.output.with_suffix(".tmp")
    temporary.write_text(report)
    temporary.replace(args.output)


if __name__ == "__main__":
    main()
