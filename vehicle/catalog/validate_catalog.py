#!/usr/bin/env python3
"""Deterministic parser/validator for vehicle_property_catalog.json."""

from __future__ import annotations

import json
import re
import subprocess
import sys
from pathlib import Path
from xml.etree import ElementTree


CATALOG_PATH = Path(__file__).with_name("vehicle_property_catalog.json")
APP_ROOT = CATALOG_PATH.parents[2]
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"
CAR_PERMISSION = re.compile(r"android\.car\.permission\.[A-Z_]+")
REQUIRED_FIELDS = {
    "catalog_key",
    "symbolic_name",
    "property_id",
    "source",
    "feature",
    "subfeature",
    "user_facing_function",
    "counts_as_distinct_user_facing_function",
    "description",
    "value_type",
    "area_type",
    "supported_area_ids",
    "supported_area_ids_status",
    "access",
    "change_mode",
    "default",
    "min",
    "max",
    "step",
    "enum_values",
    "config_array_or_string",
    "read_permission",
    "write_permission",
    "ux_restriction_policy",
    "dependency_rules",
    "unavailable_error_behavior",
    "application_control_type",
    "info_text",
    "raw_vhal",
    "car_service",
    "expected_results",
    "implementation_status",
    "emulator_test_status",
    "discovery_disposition",
    "source_evidence",
}
EXPECTED_FEATURE_COUNTS = {
    "Driver Assistance": 30,
    "Seat Control": 20,
    "HVAC": 18,
    "Vehicle Lighting": 8,
    "Door Control": 13,
}


def fail(message: str) -> None:
    raise ValueError(message)


def validate_entry(index: int, entry: dict) -> None:
    missing = REQUIRED_FIELDS - entry.keys()
    if missing:
        fail(f"entry[{index}] missing fields: {sorted(missing)}")
    property_id = entry["property_id"]
    if set(property_id) != {"decimal", "hexadecimal"} or not isinstance(property_id["decimal"], int):
        fail(f"entry[{index}] has invalid property_id")
    if property_id["hexadecimal"] != f"0x{property_id['decimal']:08X}":
        fail(f"entry[{index}] hexadecimal property ID does not match decimal")
    if entry["source"] not in {"SYSTEM", "VENDOR"}:
        fail(f"entry[{index}] has invalid source")
    if entry["access"] not in {"READ_ONLY", "WRITE_ONLY", "READ_WRITE"}:
        fail(f"entry[{index}] has invalid access")
    if entry["application_control_type"] not in {"switch", "slider", "enum", "slot_action", "status"}:
        fail(f"entry[{index}] has invalid application control type")
    if not isinstance(entry["supported_area_ids"], list) or not isinstance(entry["enum_values"], list):
        fail(f"entry[{index}] area/enum fields must be arrays")
    if not isinstance(entry["counts_as_distinct_user_facing_function"], bool):
        fail(f"entry[{index}] count flag must be boolean")
    for field in ("default", "min", "max", "step"):
        if set(entry[field]) != {"value", "status"}:
            fail(f"entry[{index}] {field} must preserve both value and evidence status")
    if set(entry["config_array_or_string"]) != {"array", "string"}:
        fail(f"entry[{index}] config field is incomplete")
    for field in ("get", "set", "restore"):
        if field not in entry["raw_vhal"]:
            fail(f"entry[{index}] raw_vhal.{field} is missing")
    for field in ("config", "get", "set"):
        if field not in entry["car_service"]:
            fail(f"entry[{index}] car_service.{field} is missing")
    if entry["access"] == "READ_ONLY":
        if entry["raw_vhal"]["set"] is not None or entry["car_service"]["set"] is not None:
            fail(f"entry[{index}] read-only property exposes SET")
        if entry["raw_vhal"]["get"] is None or entry["car_service"]["get"] is None:
            fail(f"entry[{index}] read-only property is missing GET")
    elif entry["access"] == "WRITE_ONLY":
        if entry["raw_vhal"]["get"] is not None or entry["car_service"]["get"] is not None:
            fail(f"entry[{index}] write-only property exposes GET")
        if entry["raw_vhal"]["set"] is None or entry["car_service"]["set"] is None:
            fail(f"entry[{index}] write-only property is missing SET")
    elif entry["raw_vhal"]["set"] is None or entry["car_service"]["set"] is None:
        fail(f"entry[{index}] read-write property is missing SET")
    if entry["source"] == "VENDOR":
        if entry["property_id"]["decimal"] & 0x30000000 != 0x20000000:
            fail(f"entry[{index}] vendor property is not in the VENDOR group")
        if entry["read_permission"] != "android.car.permission.CAR_VENDOR_EXTENSION":
            fail(f"entry[{index}] vendor read permission is not explicit")
    if entry["symbolic_name"] in {"SEAT_MEMORY_SELECT", "SEAT_MEMORY_SET"}:
        if entry["access"] != "WRITE_ONLY":
            fail(f"entry[{index}] seat-memory command must remain WRITE_ONLY")
        if entry["application_control_type"] != "slot_action":
            fail(f"entry[{index}] seat-memory command must use slot_action, not a value slider")


def declared_permissions(path: Path, tag: str) -> set[str]:
    root = ElementTree.parse(path).getroot()
    return {
        child.attrib.get(ANDROID_NAME, child.attrib.get("name"))
        for child in root.findall(f".//{tag}")
        if ANDROID_NAME in child.attrib or "name" in child.attrib
    }


def validate_declared_permissions(properties: list[dict]) -> None:
    manifest = declared_permissions(APP_ROOT / "app/AndroidManifest.xml", "uses-permission")
    allowlist = declared_permissions(APP_ROOT / "permissions/com.android.mysystemapp.xml", "permission")
    for entry in properties:
        for field in ("read_permission", "write_permission"):
            permission = entry[field]
            if permission is None:
                continue
            candidates = set(CAR_PERMISSION.findall(permission))
            if not candidates:
                continue
            declared = manifest & allowlist
            if permission.startswith("anyOf("):
                if not candidates & declared:
                    fail(f"{entry['symbolic_name']} {field} has no declared anyOf permission: {candidates}")
            elif not candidates <= declared:
                fail(f"{entry['symbolic_name']} {field} is not declared in manifest and privapp XML: {candidates - declared}")


def main() -> int:
    parity_status = "NOT_RUN"
    parity_detail = "local AOSP CarSvcProps.json is unavailable"
    try:
        payload = json.loads(CATALOG_PATH.read_text(encoding="utf-8"))
        if payload.get("schema_version") != 1 or payload.get("language") != "vi":
            fail("catalog root schema_version/language is invalid")
        properties = payload.get("properties")
        if not isinstance(properties, list) or not properties:
            fail("catalog has no properties")
        for index, entry in enumerate(properties):
            validate_entry(index, entry)
        symbols = [entry["symbolic_name"] for entry in properties]
        ids = [entry["property_id"]["decimal"] for entry in properties]
        if len(symbols) != len(set(symbols)) or len(ids) != len(set(ids)):
            fail("catalog symbolic names/property IDs must be unique")
        counts = {}
        for entry in properties:
            counts[entry["feature"]] = counts.get(entry["feature"], 0) + 1
        if counts != EXPECTED_FEATURE_COUNTS:
            fail(f"feature coverage mismatch: {counts}")
        validate_declared_permissions(properties)
        vendor = [entry for entry in properties if entry["source"] == "VENDOR"]
        if len(vendor) != 13:
            fail(f"vendor property count must be 13, got {len(vendor)}")
        aosp_root = APP_ROOT.parents[1]
        aosp_source = aosp_root / "packages/services/Car/car-lib/generated-prop-config/CarSvcProps.json"
        if aosp_source.is_file():
            generator = Path(__file__).with_name("generate_property_catalog.py")
            result = subprocess.run(
                [sys.executable, str(generator), "--aosp-root", str(aosp_root), "--check"],
                text=True,
                capture_output=True,
            )
            if result.returncode:
                fail(result.stderr.strip() or result.stdout.strip())
            parity_status = "PASS"
            parity_detail = result.stdout.strip()
    except (OSError, ValueError, json.JSONDecodeError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1
    print(f"STRUCTURAL_VALIDATION: PASS ({len(properties)} properties; 13 vendor)")
    print(f"AOSP_SOURCE_PARITY: {parity_status} - {parity_detail}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
