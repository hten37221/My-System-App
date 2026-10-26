#!/usr/bin/env python3
"""Verify two-area isolation and restoration through the local CarService CLI.

This is deliberately an external test: it does not use app state as a source
of truth.  Each applicable property is changed in area A, checked against area
B, then changed in B, checked against A, and finally restored in both areas.
"""

from __future__ import annotations

import argparse
import datetime as dt
import json
import pathlib
import re
import subprocess
import time
from typing import Any


def command(adb: str, serial: str, *args: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        [adb, "-s", serial, *args],
        text=True,
        capture_output=True,
        timeout=30,
    )


def shell(adb: str, serial: str, *args: str) -> subprocess.CompletedProcess[str]:
    return command(adb, serial, "shell", *args)


def parse_value(output: str) -> str | None:
    values = re.findall(r"(?:Value|value)\s*:\s*([^\n]+)", output)
    if not values:
        return None
    token = values[-1].strip()
    if re.match(r"true\b", token, re.IGNORECASE):
        return "1"
    if re.match(r"false\b", token, re.IGNORECASE):
        return "0"
    enum = re.search(r"\((0x[0-9a-fA-F]+)\)", token)
    if enum:
        return str(int(enum.group(1), 16))
    scalar = re.match(r"[-+]?\d+(?:\.\d+)?", token)
    return scalar.group(0) if scalar else None


def get_value(adb: str, serial: str, prop: str, area: int) -> tuple[str | None, str]:
    result = shell(adb, serial, "cmd", "car_service", "get-property-value", prop, hex(area))
    return parse_value(result.stdout), result.stdout[-2000:]


def set_value(adb: str, serial: str, prop: str, area: int, value: str) -> bool:
    result = shell(adb, serial, "cmd", "car_service", "set-property-value", prop, hex(area), value)
    output = result.stdout.lower()
    return result.returncode == 0 and not any(
        marker in output for marker in ("error", "exception", "denied", "failed")
    )


def wire_value(entry: dict[str, Any], value: str) -> str:
    if entry["value_type"] == "Boolean":
        return "true" if value in {"1", "true"} else "false"
    return value


def same_value(entry: dict[str, Any], actual: str | None, expected: str) -> bool:
    if actual is None:
        return False
    if entry["value_type"] == "Float":
        try:
            return abs(float(actual) - float(expected)) < 0.001
        except ValueError:
            return False
    return actual == expected


def config_areas(config: str) -> list[int]:
    return [int(value, 16) for value in re.findall(r"areaId:[^(]+\((0x[0-9a-fA-F]+)\)", config)] or [0]


def config_range(config: str, area: int) -> tuple[float, float] | None:
    area_pattern = rf"areaId:[^\n]*\({re.escape(hex(area))}\)[^\n]*"
    line = re.search(area_pattern, config)
    candidate = line.group(0) if line else config
    floating = re.search(r"f min:([-+]?\d+(?:\.\d+)?), f max:([-+]?\d+(?:\.\d+)?)", candidate)
    if floating:
        low, high = float(floating.group(1)), float(floating.group(2))
        if high > low:
            return low, high
    integer = re.search(r"i min:([-+]?\d+), i max:([-+]?\d+)", candidate)
    if integer:
        return float(integer.group(1)), float(integer.group(2))
    return None


def candidate(entry: dict[str, Any], current: str, config: str, area: int) -> str | None:
    if entry["value_type"] == "Boolean":
        # CarService's boolean parser accepts true/false; numeric 0/1 is
        # reported as a successful command but is ignored by this image's
        # FakeVehicleHardware.
        return "false" if current in {"1", "true"} else "true"
    enum_values = [str(value) for value in entry.get("enum_values", []) if str(value) != current]
    if enum_values:
        return enum_values[0]
    if entry["value_type"] != "Float":
        advertised = re.search(r"configArray:\[([^\]]*)\]", config)
        if advertised:
            values = [token for token in re.findall(r"[-+]?\d+", advertised.group(1)) if token != current]
            if values:
                return values[0]
    bounds = config_range(config, area)
    if bounds is None:
        return None
    low, high = bounds
    if low == high:
        return None
    now = float(current)
    target = now + 1 if now < high else low
    if entry["value_type"] == "Float":
        return f"{target:g}"
    return str(int(target))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("serial")
    parser.add_argument("catalog")
    parser.add_argument("evidence")
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--property", dest="property_filter")
    args = parser.parse_args()

    catalog = json.loads(pathlib.Path(args.catalog).read_text(encoding="utf-8"))["properties"]
    results: list[dict[str, Any]] = []
    for entry in catalog:
        prop = entry["property_id"]["hexadecimal"]
        if args.property_filter and prop.lower() != args.property_filter.lower():
            continue
        result: dict[str, Any] = {
            "catalog_key": entry["catalog_key"],
            "property_id": prop,
            "area_isolation": "NOT_RUN",
            "areas": [],
            "details": [],
        }
        if entry["access"] != "READ_WRITE":
            result["area_isolation"] = "NOT_APPLICABLE_ACCESS"
            results.append(result)
            continue
        config = shell(args.adb, args.serial, "cmd", "car_service", "get-carpropertyconfig", prop).stdout
        if not config.strip():
            result["area_isolation"] = "SKIPPED_UNSUPPORTED"
            results.append(result)
            continue
        areas = config_areas(config)
        result["areas"] = areas
        if len(areas) < 2:
            result["area_isolation"] = "NOT_APPLICABLE_ONE_AREA"
            results.append(result)
            continue
        area_a, area_b = areas[:2]
        originals: dict[int, str] = {}
        try:
            for area in (area_a, area_b):
                value, output = get_value(args.adb, args.serial, prop, area)
                if value is None:
                    result["area_isolation"] = "UNAVAILABLE_NO_VALUE"
                    result["details"].append({"area_id": area, "get_output": output})
                    break
                originals[area] = value
            else:
                target_a = candidate(entry, originals[area_a], config, area_a)
                target_b = candidate(entry, originals[area_b], config, area_b)
                if target_a is None or target_b is None:
                    result["area_isolation"] = "UNAVAILABLE_NO_ALTERNATE"
                elif not set_value(args.adb, args.serial, prop, area_a, wire_value(entry, target_a)):
                    result["area_isolation"] = "FAIL_SET_A"
                else:
                    time.sleep(1.0)
                    after_a, _ = get_value(args.adb, args.serial, prop, area_a)
                    after_b, _ = get_value(args.adb, args.serial, prop, area_b)
                    expected_a = "1" if target_a == "true" else "0" if target_a == "false" else target_a
                    a_changed = same_value(entry, after_a, expected_a)
                    b_unchanged = same_value(entry, after_b, originals[area_b])
                    if not set_value(args.adb, args.serial, prop, area_b, wire_value(entry, target_b)):
                        result["area_isolation"] = "FAIL_SET_B"
                    else:
                        time.sleep(1.0)
                        final_a, _ = get_value(args.adb, args.serial, prop, area_a)
                        final_b, _ = get_value(args.adb, args.serial, prop, area_b)
                        expected_b = "1" if target_b == "true" else "0" if target_b == "false" else target_b
                        b_changed = same_value(entry, final_b, expected_b)
                        a_unchanged = same_value(entry, final_a, expected_a)
                        restored = False
                        if set_value(args.adb, args.serial, prop, area_a, wire_value(entry, originals[area_a])) and set_value(
                            args.adb, args.serial, prop, area_b, wire_value(entry, originals[area_b])
                        ):
                            time.sleep(1.0)
                            restored_a, _ = get_value(args.adb, args.serial, prop, area_a)
                            restored_b, _ = get_value(args.adb, args.serial, prop, area_b)
                            restored = same_value(entry, restored_a, originals[area_a]) and same_value(
                                entry, restored_b, originals[area_b]
                            )
                        result["area_isolation"] = "PASS" if all((a_changed, b_unchanged, b_changed, a_unchanged, restored)) else "FAIL"
                        result["details"].append(
                            {
                                "area_a": area_a,
                                "area_b": area_b,
                                "original_a": originals[area_a],
                                "original_b": originals[area_b],
                                "target_a": target_a,
                                "target_b": target_b,
                                "a_changed": a_changed,
                                "b_unchanged_after_a": b_unchanged,
                                "b_changed": b_changed,
                                "a_unchanged_after_b": a_unchanged,
                                "restored": restored,
                            }
                        )
        finally:
            # A failed intermediate step must not leave a vehicle control changed.
            for area, value in originals.items():
                set_value(args.adb, args.serial, prop, area, wire_value(entry, value))
        results.append(result)

    payload = {
        "schema": "vehicle-control-carservice-area-isolation-v1",
        "timestamp": dt.datetime.now(dt.timezone.utc).isoformat(),
        "serial": args.serial,
        "catalog": str(pathlib.Path(args.catalog)),
        "property_filter": args.property_filter,
        "results": results,
    }
    evidence = pathlib.Path(args.evidence)
    evidence.parent.mkdir(parents=True, exist_ok=True)
    evidence.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    from collections import Counter

    print(json.dumps({"evidence": str(evidence), "rows": len(results), "status": Counter(x["area_isolation"] for x in results)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
