#!/usr/bin/env python3
"""Inject typed CarService errors and verify UI error/reconnect behavior."""

from __future__ import annotations

import argparse
import datetime as dt
import importlib.util
import json
import pathlib
import sys
import time
import xml.etree.ElementTree as ET


def load_harness():
    path = pathlib.Path(__file__).with_name("vehicle_ui_matrix.py")
    spec = importlib.util.spec_from_file_location("vehicle_ui_matrix", path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"cannot load {path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def error_visible(root: ET.Element, title: str) -> bool:
    markers = ("unavailable", "couldn’t apply", "could not apply", "lỗi")
    title_node = next((node for node in root.iter("node") if node.attrib.get("text") == title), None)
    if title_node is None:
        return False
    parents = {}
    for parent in root.iter("node"):
        for child in parent:
            parents[id(child)] = parent
    parent = parents.get(id(title_node))
    while parent is not None:
        # Stop at the nearest interactive row.  Scanning the whole section
        # makes a normal "Unavailable" sibling (for example Door movement)
        # look like a stale error on Door lock.
        if (
            parent.attrib.get("clickable") == "true"
            or parent.attrib.get("checkable") == "true"
        ):
            return any(
                any(marker in node.attrib.get("text", "").lower() for marker in markers)
                for node in parent.iter("node")
            )
        parent = parents.get(id(parent))
    return False


def scan_error_ui(module, harness, row, initial_root):
    """Scan the lazy list so a banner and its row can be seen in one run.

    Vehicle screens with a top-view zone selector often place the row below
    the first uiautomator viewport.  A single dump therefore produced false
    negatives even when the error banner was rendered.  Keep the strongest
    observations across deterministic short scrolls instead of restarting the
    activity (which would erase pending/error state).
    """
    roots = [initial_root]
    marker_seen = False
    row_seen = False
    control_enabled = None
    for index in range(18):
        root = roots[-1]
        texts = [node.attrib.get("text", "") for node in root.iter("node")]
        row_here = any(text == row.title for text in texts)
        marker_here = error_visible(root, row.title)
        marker_seen = marker_seen or marker_here
        row_seen = row_seen or row_here
        control = harness.control_node(root, row.title)
        if control is not None:
            control_enabled = control.attrib.get("enabled")
        if row_here and (marker_here or control_enabled == "false"):
            return root, {
                "row_seen": row_seen,
                "marker_seen": marker_seen,
                "control_enabled": control_enabled,
                "scan_dumps": index + 1,
            }
        if index == 17:
            break
        harness.shell("input", "swipe", "900", "880", "900", "500", "450")
        time.sleep(0.25)
        roots.append(harness.dump(f"error-scan-{row.feature}"))
    return roots[-1], {
        "row_seen": row_seen,
        "marker_seen": marker_seen,
        "control_enabled": control_enabled,
        "scan_dumps": len(roots),
    }


def trigger_write(module, harness, row, node, root, area: int, original: str | None) -> None:
    """Create a real UI write before injecting an error callback."""
    if row.kind == "switch":
        harness.tap_node(node)
    elif row.kind == "slider":
        left, top, right, bottom = harness.bounds(node)
        harness.shell(
            "input",
            "tap",
            str((left + right) // 2),
            str((top + bottom) // 2),
        )
    elif row.kind == "enum":
        value_node = harness.enum_value_node(root, row.title)
        harness.tap_node(value_node if value_node is not None else node)
        time.sleep(0.2)
        menu = harness.dump("error-menu")
        options = [
            candidate
            for candidate in menu.iter("node")
            if candidate.attrib.get("content-desc")
            and not candidate.attrib.get("content-desc", "").startswith("Information about ")
        ]
        # Do not blindly select the last option: a menu can already be on its
        # current value (Headlights=Automatic in the AVD), which completes a
        # write before the injected error and gives no pending/error surface.
        for index, option in enumerate(options):
            harness.tap_node(option)
            time.sleep(0.25)
            if harness.scalar(harness.car_get(row.property_id, area)) != original:
                break
            if index + 1 < len(options):
                value_node = harness.enum_value_node(harness.dump("error-enum-reopen"), row.title)
                harness.tap_node(value_node if value_node is not None else node)
                time.sleep(0.15)
                menu = harness.dump("error-menu-reopen")
                options = [
                    candidate
                    for candidate in menu.iter("node")
                    if candidate.attrib.get("content-desc")
                    and not candidate.attrib.get("content-desc", "").startswith("Information about ")
                ]


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("serial")
    parser.add_argument("output")
    parser.add_argument("--adb", default="adb")
    parser.add_argument(
        "--property",
        action="append",
        dest="properties",
        help="property hexadecimal ID; repeat for multiple rows",
    )
    parser.add_argument(
        "--reboot-on-reconnect",
        action="store_true",
        help="reboot the emulator before the recovery check (strong VHAL reset)",
    )
    args = parser.parse_args()

    module = load_harness()
    out = pathlib.Path(args.output)
    out.mkdir(parents=True, exist_ok=True)
    harness = module.Harness(args.serial, out, args.adb)
    wanted = {value.lower() for value in (args.properties or [])}
    rows = [row for row in module.rows() if not wanted or row.property_id.lower() in wanted]
    results = []
    try:
        for row in rows:
            entry = {
                "feature": row.feature,
                "title": row.title,
                "property_id": row.property_id,
                "area_hint": row.area_hint,
                "error_injection": "NOT_RUN",
                "ui_error": "NOT_RUN",
                "reconnect": "NOT_RUN",
            }
            try:
                node, root = harness.find_row(row)
                if node is None:
                    entry["status"] = "NOT_VISIBLE"
                    results.append(entry)
                    continue
                areas = harness.car_config_areas(row.property_id)
                area = row.area_hint if row.area_hint is not None else areas[0]
                original = harness.scalar(harness.car_get(row.property_id, area))
                entry["area"] = area
                entry["initial"] = original
                trigger_write(module, harness, row, node, root, area, original)
                time.sleep(0.1)
                injected = harness.call(
                    "shell",
                    "cmd",
                    "car_service",
                    "inject-error-event",
                    row.property_id,
                    hex(area),
                    "1",
                )
                entry["error_command"] = injected.stdout[-1000:]
                entry["error_injection"] = "PASS" if injected.returncode == 0 else "FAIL"
                time.sleep(0.8)
                error_root = harness.dump(f"error-{row.feature}")
                error_path = out / f"error-{row.property_id.lower().replace('0x', '')}.xml"
                # dump() already stores an XML; retain a stable per-property copy.
                error_root, scan = scan_error_ui(module, harness, row, error_root)
                error_path.write_text(ET.tostring(error_root, encoding="unicode"), encoding="utf-8")
                entry["error_scan"] = scan
                control = harness.control_node(error_root, row.title)
                entry["control_enabled_after_error"] = (
                    scan["control_enabled"]
                    if scan["control_enabled"] is not None
                    else (None if control is None else control.attrib.get("enabled"))
                )
                entry["ui_error_visible"] = bool(
                    scan["marker_seen"] and scan["row_seen"]
                ) or error_visible(error_root, row.title)
                entry["ui_error"] = (
                    "PASS"
                    if entry["error_injection"] == "PASS"
                    and (entry["ui_error_visible"] or (control is not None and control.attrib.get("enabled") == "false"))
                    else "FAIL"
                )
                # A clean activity restart must not retain the error/pending state.
                # A valid value event clears the injected FakeVehicleHardware
                # error before a fresh process reconnects. This models the
                # recovery path while preserving the error XML above.
                if original is not None:
                    cleared = harness.call(
                        "shell",
                        "cmd",
                        "car_service",
                        "set-property-value",
                        row.property_id,
                        hex(area),
                        original,
                    )
                    entry["clear_command"] = cleared.stdout[-1000:]
                if args.reboot_on_reconnect:
                    harness.call("reboot", timeout=10)
                    # wait-for-device returns after adbd is reachable; the
                    # explicit boot property check prevents a UI dump while
                    # CarService is still restoring its VHAL session.
                    harness.call("wait-for-device", timeout=120)
                    deadline = time.monotonic() + 120
                    while time.monotonic() < deadline:
                        boot = harness.call(
                            "shell",
                            "getprop",
                            "sys.boot_completed",
                            timeout=10,
                        )
                        if boot.stdout.strip() == "1":
                            break
                        time.sleep(2)
                else:
                    harness.shell("am", "force-stop", "com.android.mysystemapp")
                harness.feature_root(row.feature)
                reconnect_node, reconnect_root = harness.find_row(row)
                reconnect_control = harness.control_node(reconnect_root, row.title)
                reconnect_error = error_visible(reconnect_root, row.title)
                entry["reconnect_error_visible"] = reconnect_error
                entry["reconnect_control_enabled"] = (
                    None if reconnect_control is None else reconnect_control.attrib.get("enabled")
                )
                entry["reconnect"] = "PASS" if not reconnect_error else "FAIL_STALE_ERROR"
                entry["status"] = (
                    "PASS"
                    if entry["error_injection"] == "PASS"
                    and entry["ui_error"] == "PASS"
                    and entry["reconnect"] == "PASS"
                    else "FAIL"
                )
            except Exception as exc:  # retain per-row failure instead of hiding it
                entry["status"] = "ERROR"
                entry["error"] = repr(exc)
            results.append(entry)
    finally:
        harness.shell("am", "force-stop", "com.android.mysystemapp")

    payload = {
        "schema": "vehicle-control-error-matrix-v1",
        "timestamp": dt.datetime.now(dt.timezone.utc).isoformat(),
        "serial": args.serial,
        "properties": [row.property_id for row in rows],
        "results": results,
    }
    path = out / "error-matrix.json"
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"evidence": str(path), "results": results}, ensure_ascii=False))


if __name__ == "__main__":
    main()
