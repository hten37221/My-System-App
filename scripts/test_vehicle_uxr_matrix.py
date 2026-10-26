#!/usr/bin/env python3
"""Capture restricted-driving UI evidence for every vehicle-control domain."""

from __future__ import annotations

import argparse
import datetime as dt
import importlib.util
import json
import pathlib
import sys
import time


def load_harness():
    path = pathlib.Path(__file__).with_name("vehicle_ui_matrix.py")
    spec = importlib.util.spec_from_file_location("vehicle_ui_matrix", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("serial")
    parser.add_argument("output")
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()

    module = load_harness()
    out = pathlib.Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    harness = module.Harness(args.serial, out, args.adb)
    features = ("driver", "seat", "lighting", "door", "hvac")
    results = []
    try:
        harness.shell("cmd", "car_service", "enable-uxr", "true")
        harness.shell("cmd", "car_service", "inject-vhal-event", "GEAR_SELECTION", "0", "8")
        time.sleep(1.0)
        for feature in features:
            found = {}
            expected = [row.title for row in module.rows() if row.feature == feature]
            row_evidence = {title: {"title": title, "status": "NOT_VISIBLE"} for title in expected}
            try:
                harness.feature_root(feature)
                for _ in range(24):
                    root = harness.dump(f"uxr-{feature}")
                    for node in root.iter("node"):
                        attrs = node.attrib
                        text = attrs.get("text", "")
                        if attrs.get("enabled") == "false" or "Park the vehicle" in text or "restricted" in text.lower():
                            key = (attrs.get("bounds"), text, attrs.get("content-desc"))
                            found[key] = {
                                key: attrs.get(key)
                                for key in ("text", "content-desc", "class", "enabled", "checked", "bounds")
                            }
                    for title in expected:
                        title_node = harness.text_node(root, title)
                        if title_node is None:
                            continue
                        control = harness.control_node(root, title)
                        parent_map = harness.parents(root)
                        restricted_text = False
                        parent = parent_map.get(id(title_node))
                        while parent is not None:
                            restricted_text = any(
                                "Park the vehicle" in node.attrib.get("text", "")
                                for node in parent.iter("node")
                            )
                            if restricted_text:
                                break
                            parent = parent_map.get(id(parent))
                        restricted_control = control is not None and control.attrib.get("enabled") == "false"
                        candidate = {
                            "title": title,
                            "visible": True,
                            "control_enabled": None if control is None else control.attrib.get("enabled"),
                            "control_class": None if control is None else control.attrib.get("class"),
                            "restricted_reason_visible": restricted_text,
                            "status": "PASS" if restricted_control or restricted_text else "FAIL_NOT_RESTRICTED",
                        }
                        if row_evidence[title]["status"] != "PASS" or candidate["status"] == "PASS":
                            row_evidence[title] = candidate
                    harness.shell("input", "swipe", "900", "880", "900", "500", "500")
                entries = list(found.values())
                row_counts = {
                    "expected": len(expected),
                    "pass": sum(item["status"] == "PASS" for item in row_evidence.values()),
                    "not_visible": sum(item["status"] == "NOT_VISIBLE" for item in row_evidence.values()),
                    "fail": sum(item["status"] == "FAIL_NOT_RESTRICTED" for item in row_evidence.values()),
                }
                uxr_required = feature in {"driver", "seat", "lighting", "door"}
                if uxr_required:
                    domain_status = "PASS" if row_counts["fail"] == 0 else "FAIL_ROW_NOT_RESTRICTED"
                else:
                    # Local policy does not require HVAC controls to be blocked
                    # by the driving-state UXR profile; retain observations but
                    # do not turn an allowed moving-state control into a FAIL.
                    domain_status = "NOT_APPLICABLE_POLICY"
                result = {
                    "feature": feature,
                    "status": domain_status if entries or not uxr_required else "FAIL_NO_RESTRICTED_CONTROL",
                    "uxr_required": uxr_required,
                    "restricted_nodes": entries,
                    "count": len(entries),
                    "row_counts": row_counts,
                    "rows": list(row_evidence.values()),
                }
            except Exception as error:  # evidence must retain the failing domain and reason
                result = {"feature": feature, "status": "ERROR", "error": repr(error), "count": 0}
            results.append(result)
    finally:
        # PARK=4 in the local CarDrivingStateService and disables the test UXR.
        harness.shell("cmd", "car_service", "inject-vhal-event", "GEAR_SELECTION", "0", "4")
        harness.shell("cmd", "car_service", "enable-uxr", "false")
        harness.shell("am", "force-stop", "com.android.mysystemapp")

    payload = {
        "schema": "vehicle-control-uxr-matrix-v1",
        "timestamp": dt.datetime.now(dt.timezone.utc).isoformat(),
        "serial": args.serial,
        "restricted_gear": 8,
        "parked_gear": 4,
        "results": results,
    }
    path = out / "uxr-matrix.json"
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"evidence": str(path), "results": results}, ensure_ascii=False))


if __name__ == "__main__":
    main()
