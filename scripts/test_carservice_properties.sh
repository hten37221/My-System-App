#!/usr/bin/env bash
# CarService config/get smoke with optional typed write/restore for one property.
set -euo pipefail

SERIAL=${1:?usage: $0 SERIAL CATALOG_JSON EVIDENCE_JSON [PROPERTY_HEX]}
CATALOG=${2:?usage: $0 SERIAL CATALOG_JSON EVIDENCE_JSON [PROPERTY_HEX]}
EVIDENCE=${3:?usage: $0 SERIAL CATALOG_JSON EVIDENCE_JSON [PROPERTY_HEX]}
PROPERTY_FILTER=${4:-}
ADB_BIN=${ADB_BIN:-adb}
TEST_WRITABLE=${TEST_WRITABLE:-0}

mkdir -p "$(dirname "$EVIDENCE")"
"$ADB_BIN" -s "$SERIAL" get-state >/dev/null

python3 - "$SERIAL" "$CATALOG" "$EVIDENCE" "$ADB_BIN" "$TEST_WRITABLE" "$PROPERTY_FILTER" <<'PY'
import datetime
import json
import pathlib
import re
import subprocess
import sys

serial, catalog_path, evidence_path, adb_bin, test_writable, property_filter = sys.argv[1:]
catalog = json.loads(pathlib.Path(catalog_path).read_text(encoding="utf-8"))["properties"]

def adb(*args):
    return subprocess.run([adb_bin, "-s", serial, *args], text=True, capture_output=True, timeout=30)

def area_ids(config):
    ids = []
    for line in config.splitlines():
        match = re.search(r"areaId:[^(]+\((0x[0-9a-fA-F]+)\)", line)
        if match:
            ids.append(int(match.group(1), 16))
    return ids or [0]

def runtime_access(config):
    for line in config.splitlines():
        match = re.search(r"^\s*access:([A-Z_]+)\(", line)
        if match:
            return match.group(1)
    return None

def value_after_get(output):
    """Convert the typed HalPropValue rendering to a set-property-value token.

    CarService prints booleans in upper case and enum values as NAME(0xNN),
    while the shell setter accepts a scalar numeric token.  Keeping this
    conversion in the acceptance script avoids treating a successful GET as a
    false write failure.
    """
    matches = re.findall(r"(?:Value|value)\s*:\s*([^\n]+)", output)
    if not matches:
        return None
    token = matches[-1].strip()
    if re.match(r"true\b", token, flags=re.IGNORECASE):
        # The API 37 car_service shell parser accepts boolean literals, not
        # integer aliases.  Returning "1" makes a restore of TRUE silently
        # become FALSE while still reporting exit 0.
        return "true"
    if re.match(r"false\b", token, flags=re.IGNORECASE):
        return "false"
    enum_code = re.search(r"\((0x[0-9a-fA-F]+)\)", token)
    if enum_code:
        return str(int(enum_code.group(1), 16))
    scalar = re.match(r"[-+]?\d+(?:\.\d+)?", token)
    return scalar.group(0) if scalar else None

def candidate(value_type):
    if value_type == "Boolean":
        return "true"
    if value_type == "Float":
        return "21.0"
    return "1"

def command_succeeded(result, require_value=False):
    """Treat shell commands that print an error but exit 0 as failures."""
    output = result.stdout.strip()
    if result.returncode != 0 or not output:
        return False
    lowered = output.lower()
    if any(marker in lowered for marker in (
        "cannot get property value",
        "failed to get",
        "error:",
        "exception",
        "denied",
    )):
        return False
    return not require_value or value_after_get(result.stdout) is not None

results = []
for entry in catalog:
    prop_hex = entry["property_id"]["hexadecimal"]
    if property_filter and prop_hex.lower() != property_filter.lower():
        continue
    cfg = adb("shell", "cmd", "car_service", "get-carpropertyconfig", prop_hex)
    result = {
        "catalog_key": entry["catalog_key"],
        "property_id": prop_hex,
        "config": "NOT_RUN",
        "runtime_access": None,
        "area_ids": [],
        "get": "NOT_RUN",
        "write_restore": "NOT_RUN",
        "details": [],
    }
    if not cfg.stdout.strip():
        result["config"] = "SKIPPED_UNSUPPORTED"
        results.append(result)
        continue
    result["config"] = "PASS" if cfg.returncode == 0 else "FAIL"
    result["runtime_access"] = runtime_access(cfg.stdout)
    result["area_ids"] = area_ids(cfg.stdout)
    if entry["access"] != "WRITE_ONLY":
        gets = []
        for area in result["area_ids"]:
            got = adb("shell", "cmd", "car_service", "get-property-value", prop_hex, hex(area))
            gets.append({
                "area_id": area,
                "exit": got.returncode,
                "ok": command_succeeded(got, require_value=True),
                "output": got.stdout[-2000:],
            })
        result["details"].extend(gets)
        result["get"] = "PASS" if all(item["ok"] for item in gets) else "FAIL"
    else:
        result["get"] = "NOT_APPLICABLE_WRITE_ONLY"
    if test_writable == "1" and entry["access"] == "READ_WRITE":
        all_pass = True
        for area in result["area_ids"]:
            original = adb("shell", "cmd", "car_service", "get-property-value", prop_hex, hex(area))
            original_value = value_after_get(original.stdout)
            if original_value is None:
                result["details"].append({"area_id": area, "write": "BLOCKED_NO_PARSEABLE_INITIAL_VALUE"})
                all_pass = False
                continue
            target = candidate(entry["value_type"])
            set_result = adb("shell", "cmd", "car_service", "set-property-value", prop_hex, hex(area), target)
            restore_result = adb("shell", "cmd", "car_service", "set-property-value", prop_hex, hex(area), original_value)
            verify = adb("shell", "cmd", "car_service", "get-property-value", prop_hex, hex(area))
            write_ok = command_succeeded(set_result)
            restore_ok = command_succeeded(restore_result)
            verify_ok = command_succeeded(verify, require_value=True)
            passed = write_ok and restore_ok and verify_ok
            all_pass = all_pass and passed
            result["details"].append({
                "area_id": area,
                "write_exit": set_result.returncode,
                "write_ok": write_ok,
                "restore_exit": restore_result.returncode,
                "restore_ok": restore_ok,
                "verify_exit": verify.returncode,
                "verify_ok": verify_ok,
                "original": original_value,
            })
        result["write_restore"] = "PASS" if all_pass else "FAIL"
    elif entry["access"] == "WRITE_ONLY":
        result["write_restore"] = "NOT_APPLICABLE_WRITE_ONLY"
    results.append(result)

payload = {
    "schema": "vehicle-control-carservice-smoke-v1",
    "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "serial": serial,
    "catalog": str(pathlib.Path(catalog_path)),
    "test_writable": test_writable == "1",
    "property_filter": property_filter or None,
    "results": results,
}
pathlib.Path(evidence_path).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({
    "evidence": evidence_path,
    "catalog_rows": len(results),
    "config_pass": sum(x["config"] == "PASS" for x in results),
    "unsupported": sum(x["config"] == "SKIPPED_UNSUPPORTED" for x in results),
    "get_pass": sum(x["get"] == "PASS" for x in results),
}, ensure_ascii=False))
PY
