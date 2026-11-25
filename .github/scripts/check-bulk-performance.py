"""Compare package-state transitions and work performed by two APKs on the same emulator."""
import json
import statistics
import sys
from pathlib import Path

report = Path(sys.argv[1])
expected = {f"com.aistra.hail.testfixture.bench{i}" for i in range(1, 13)}
times = {}
for build, code in (("baseline", 40), ("current", 41)):
    rows = [json.loads(line) for line in (report / f"benchmark-{build}.jsonl").read_text().splitlines()]
    header, events = rows[0], rows[1:]
    assert header["build"] == code and header["writeErrorsThisProcess"] == 0
    sessions = {e["session"] for e in events if e["step"] == "session_started" and e["build"] == code}
    operations = {}
    for e in events:
        if e["session"] in sessions and e["source"] == "home_all":
            operations.setdefault(e["operation"], []).append(e)

    for action, target in (("freeze", 3), ("unfreeze", 1)):
        samples = []
        stops = []
        for trace in operations.values():
            if trace[0]["action"] != action:
                continue
            end = next(e for e in trace if e["step"] == "batch_result")
            assert end["changed"] == len(expected) and end["failed"] == end["missing"] == end["unchanged"] == 0
            verified = {e["package"] for e in trace if e["step"] == "readback" and e.get("verified") and e["observedSetting"] == target}
            assert verified == expected, (build, action, verified)
            calls = [e for e in trace if e["step"] == "android_call"]
            stop = [e for e in trace if e["step"] == "force_stop"]
            if build == "current":
                assert len([e for e in trace if e["step"] == "bulk_write" and e["size"] == 12]) == 1
                assert not any(e["step"] == "bulk_write_fallback" for e in trace)
                assert len(calls) == 12 and all(e["method"].endswith(".setComponentEnabledSettings") for e in calls)
                if action == "freeze":
                    assert len(stop) <= 1
                    skipped = {e["package"] for e in trace if e["step"] == "force_stop_skipped"}
                    assert skipped | {e["package"] for e in stop} == expected
            else:
                assert len(calls) == 12 and all(e["method"].endswith(".setApplicationEnabledSetting") for e in calls)
                if action == "freeze":
                    assert len(stop) == 12
            stops.append(len(stop))
            samples.append(end["elapsedMs"])
        assert len(samples) == 6, (build, action, samples)
        median = statistics.median(samples[1:])
        times[build, action] = median
        print(f"{build} {action}: {median:g} ms median, measured {samples[1:]}, warm-up {samples[0]}, explicit force-stops {stops[1:]}")
for action in ("freeze", "unfreeze"):
    print(f"{action}: {times['baseline', action]:g} ms to {times['current', action]:g} ms for 12 packages")
