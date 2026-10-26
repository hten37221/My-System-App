#!/usr/bin/env bash
# Raw AIDL VHAL capability/read/write smoke. No application state is used as a fallback.
set -euo pipefail

SERIAL=${1:?usage: $0 SERIAL CATALOG_JSON EVIDENCE_JSON [PROPERTY_HEX]}
CATALOG=${2:?usage: $0 SERIAL CATALOG_JSON EVIDENCE_JSON [PROPERTY_HEX]}
EVIDENCE=${3:?usage: $0 SERIAL CATALOG_JSON EVIDENCE_JSON [PROPERTY_HEX]}
PROPERTY_FILTER=${4:-}
ADB_BIN=${ADB_BIN:-adb}
TEST_WRITABLE=${TEST_WRITABLE:-0}

mkdir -p "$(dirname "$EVIDENCE")"
"$ADB_BIN" -s "$SERIAL" get-state >/dev/null
"$ADB_BIN" -s "$SERIAL" root >/dev/null 2>&1 || true
"$ADB_BIN" -s "$SERIAL" wait-for-device >/dev/null

RAW_LIST="${EVIDENCE}.raw-list.txt"
"$ADB_BIN" -s "$SERIAL" shell dumpsys android.hardware.automotive.vehicle.IVehicle/default --list >"$RAW_LIST"

python3 - "$SERIAL" "$CATALOG" "$EVIDENCE" "$RAW_LIST" "$ADB_BIN" "$TEST_WRITABLE" "$PROPERTY_FILTER" <<'PY'
import datetime
import json
import pathlib
import re
import subprocess
import sys

serial, catalog_path, evidence_path, raw_path, adb_bin, test_writable, property_filter = sys.argv[1:]
catalog = json.loads(pathlib.Path(catalog_path).read_text(encoding="utf-8"))["properties"]
raw = pathlib.Path(raw_path).read_text(encoding="utf-8")
raw_areas = {}
for line in raw.splitlines():
    match = re.search(r"propID:\s*(0x[0-9a-fA-F]+).*areaIDs:\s*\[([^]]*)\]", line)
    if match:
        raw_areas[int(match.group(1), 16)] = [int(x, 16) for x in re.findall(r"0x[0-9a-fA-F]+|\d+", match.group(2))]

def adb(*args):
    return subprocess.run([adb_bin, "-s", serial, *args], text=True, capture_output=True, timeout=30)

def raw_call(*args):
    return adb("shell", "dumpsys", "android.hardware.automotive.vehicle.IVehicle/default", *args)

def runtime_access(config):
    for line in config.splitlines():
        match = re.search(r"^\s*access:([A-Z_]+)\(", line)
        if match:
            return match.group(1)
    return None

def candidate(value_type):
    if value_type == "Boolean":
        return ["-i", "1"]
    if value_type == "Integer":
        return ["-i", "1"]
    if value_type == "Float":
        return ["-f", "21.0"]
    return ["-i", "1"]

results = []
for entry in catalog:
    prop_hex = entry["property_id"]["hexadecimal"]
    if property_filter and prop_hex.lower() != property_filter.lower():
        continue
    prop_id = entry["property_id"]["decimal"]
    result = {
        "catalog_key": entry["catalog_key"],
        "property_id": prop_hex,
        "source": entry["source"],
        "catalog_access": entry["access"],
        "raw_present": prop_id in raw_areas,
        "raw_area_ids": raw_areas.get(prop_id, []),
        "raw_get": "NOT_RUN",
        "write_restore": "NOT_RUN",
        "details": [],
    }
    if prop_id not in raw_areas:
        result["raw_get"] = "SKIPPED_UNSUPPORTED"
        results.append(result)
        continue
    config_result = adb("shell", "cmd", "car_service", "get-carpropertyconfig", prop_hex)
    config = config_result.stdout
    result["runtime_access"] = runtime_access(config)
    result["config_exit"] = config_result.returncode
    areas = raw_areas[prop_id] or [0]
    if entry["access"] != "WRITE_ONLY":
        get_result = raw_call("--get", prop_hex)
        result["raw_get"] = "PASS" if get_result.returncode == 0 else "FAIL"
        result["details"].append({"operation": "get", "exit": get_result.returncode, "output": get_result.stdout[-4000:]})
    if test_writable == "1" and entry["access"] == "READ_WRITE":
        all_pass = True
        for area in areas:
            area_hex = hex(area)
            saved = raw_call("--save-prop", prop_hex, "-a", area_hex)
            set_result = raw_call("--set", prop_hex, "-a", area_hex, *candidate(entry["value_type"]))
            read_result = raw_call("--get", prop_hex)
            restore = raw_call("--restore-prop", prop_hex, "-a", area_hex)
            restored_read = raw_call("--get", prop_hex)
            passed = all(x.returncode == 0 for x in (saved, set_result, read_result, restore, restored_read))
            all_pass = all_pass and passed
            result["details"].append({
                "area_id": area,
                "save_exit": saved.returncode,
                "set_exit": set_result.returncode,
                "get_after_set_exit": read_result.returncode,
                "restore_exit": restore.returncode,
                "get_after_restore_exit": restored_read.returncode,
            })
        result["write_restore"] = "PASS" if all_pass else "FAIL"
    elif entry["access"] == "WRITE_ONLY":
        result["write_restore"] = "NOT_APPLICABLE_WRITE_ONLY"
    results.append(result)

payload = {
    "schema": "vehicle-control-raw-vhal-smoke-v1",
    "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat(),
    "serial": serial,
    "catalog": str(pathlib.Path(catalog_path)),
    "raw_list_count": len(raw_areas),
    "test_writable": test_writable == "1",
    "property_filter": property_filter or None,
    "results": results,
}
pathlib.Path(evidence_path).write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
print(json.dumps({
    "evidence": evidence_path,
    "raw_list_count": len(raw_areas),
    "catalog_rows": len(results),
    "raw_get_pass": sum(x["raw_get"] == "PASS" for x in results),
    "unsupported": sum(x["raw_get"] == "SKIPPED_UNSUPPORTED" for x in results),
    "write_restore_pass": sum(x["write_restore"] == "PASS" for x in results),
}, ensure_ascii=False))
PY
