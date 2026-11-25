"""Check the optimized app's exported document against independent Android observations."""
import json
import sys
from pathlib import Path

path, server = sys.argv[1:]
header, *events = [json.loads(line) for line in Path(path).read_text().splitlines()]
uid, disabled = (0, 2) if server == "root" else (2000, 3)
assert header["schema"] == 1 and header["recordingAtExport"] is False
assert header["version"] == "1.13.3" and header["sdk"] == 36 and header["writeErrorsThisProcess"] == 0
assert len({e["session"] for e in events if e["step"] == "session_started"}) >= 2
assert events[-1]["step"] == "logging_stopped"
for session in {e["session"] for e in events}:
    seq = [e["seq"] for e in events if e["session"] == session]
    assert seq == sorted(set(seq))
ops = {}
for event in events:
    ops.setdefault(event["operation"], []).append(event)


def contains(rows, step, **fields):
    return any(row["step"] == step and all(row.get(k) == v for k, v in fields.items()) for row in rows)


def check(step, **fields):
    return step, fields


def operation(source, action, *checks):
    for rows in ops.values():
        if rows[0]["source"] == source and rows[0]["action"] == action and all(
                contains(rows, step, **fields) for step, fields in checks):
            return rows
    raise AssertionError((source, action, checks))


clock = "com.android.deskclock"
service = next(e["package"] for e in events if e["step"] == "enabled_setting" and e["beforeSetting"] == 4)
operation("home_all", "freeze", check("permission", serviceUid=uid, granted=True),
          check("enabled_setting", package=clock, requestedSetting=disabled),
          check("android_call", package=clock, requestedSetting=disabled),
          check("readback", package=clock, observedSetting=disabled, verified=True),
          check("outcome", package=clock, result="changed"))
operation("home_all", "freeze", check("skipped", package=clock, reason="Already in the requested state"),
          check("batch_result", changed=0, unchanged=2))
assert any(contains(rows, "home_row", package=clock, installedForUser=True) for rows in ops.values())
operation("home_all", "unfreeze", check("android_call", package=service, requestedSetting=1),
          check("readback", package=service, observedSetting=1, verified=True))
transition = operation("home_status", "freeze", check("enabled_setting", package=service, beforeSetting=4),
                       check("android_call", package=service, requestedSetting=disabled))
if server == "shell":
    assert contains(transition, "readback", package=service, observedSetting=4, backendAccepted=False)
    assert any(e["step"] == "android_error" and e["errorType"].endswith("SecurityException")
               and "Shell cannot change component state" in e["error"] for e in transition)
    assert contains(transition, "outcome", package=service, result="failed")
else:
    assert contains(transition, "readback", package=service, observedSetting=2, verified=True)
    operation("home_status", "freeze", check("enabled_setting", package=service, beforeSetting=3),
              check("readback", package=service, observedSetting=2, verified=True))
print(f"Verified {len(events)} exported decisions, {len(ops)} operations, Shizuku UID {uid}, server {server}.")
