#!/usr/bin/env python3
"""Verify every visible Driver Assistance info affordance on a booted AVD."""

from __future__ import annotations

import argparse
import datetime as dt
import hashlib
import importlib.util
import json
import pathlib
import re
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


def bounds(node: dict[str, str]) -> tuple[int, int, int, int]:
    return tuple(int(value) for value in re.findall(r"\d+", node["bounds"]))  # type: ignore[return-value]


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
    expected = [row.title for row in module.rows() if row.feature == "driver"]
    results: dict[str, dict[str, object]] = {}
    try:
        harness.feature_root("driver")
        stagnant = 0
        while len(results) < len(expected) and stagnant < 6:
            root = harness.dump("driver-info")
            visible = [
                node
                for node in root.iter("node")
                if node.attrib.get("content-desc", "").startswith("Information about ")
            ]
            info_node = next(
                (
                    node
                    for node in visible
                    if node.attrib.get("content-desc", "")[len("Information about ") :] not in results
                ),
                None,
            )
            if info_node is None:
                stagnant += 1
                harness.shell("input", "swipe", "900", "880", "900", "500", "500")
                time.sleep(0.35)
                continue
            stagnant = 0
            title = info_node.attrib["content-desc"][len("Information about ") :]
            left, top, right, bottom = bounds(info_node.attrib)
            harness.shell("input", "tap", str((left + right) // 2), str((top + bottom) // 2))
            time.sleep(0.8)
            sheet = harness.dump("driver-info-sheet")
            texts = [n.attrib.get("text", "") for n in sheet.iter("node")]
            if title not in texts:
                time.sleep(0.5)
                sheet = harness.dump("driver-info-sheet-retry")
                texts = [n.attrib.get("text", "") for n in sheet.iter("node")]
            body = " ".join(text for text in texts if text).strip()
            title_ok = title in texts
            limitations_ok = "Limitations" in texts or "Giới hạn" in texts
            dependencies_ok = "Dependencies" in texts or "Điều kiện phụ thuộc" in texts
            results[title] = {
                "title": title,
                "semantics": f"Information about {title}",
                "title_in_sheet": title_ok,
                "limitations_in_sheet": limitations_ok,
                "dependencies_in_sheet": dependencies_ok,
                "body_sha256": hashlib.sha256(body.encode()).hexdigest(),
                "status": "PASS" if title_ok and limitations_ok and dependencies_ok else "FAIL_CONTENT",
            }
            # The Compose TextButton's accessibility node is not itself
            # physically clickable in the automotive bridge. Back is the
            # supported dismiss gesture and returns to the same scroll offset.
            harness.shell("input", "keyevent", "4")
            time.sleep(0.8)
        for title in expected:
            results.setdefault(title, {"title": title, "status": "NOT_VISIBLE"})
    finally:
        harness.shell("am", "force-stop", "com.android.mysystemapp")
    payload = {
        "schema": "vehicle-control-driver-info-matrix-v1",
        "timestamp": dt.datetime.now(dt.timezone.utc).isoformat(),
        "serial": args.serial,
        "expected": len(expected),
        "results": list(results.values()),
    }
    path = out / "driver-info-matrix.json"
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"evidence": str(path), "expected": len(expected), "observed": len(results)}, ensure_ascii=False))


if __name__ == "__main__":
    main()
