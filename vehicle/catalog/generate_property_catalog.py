#!/usr/bin/env python3
"""Generate the checked-in, machine-readable Vehicle Control property catalog.

The catalog is deliberately generated from the local AOSP property contract and the checked-in
project VHAL config.  It is still checked in as the source consumed by test/report tooling; this
script makes a contract drift fail deterministically instead of relying on copied IDs.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from typing import Any


APP_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_AOSP_ROOT = APP_ROOT.parents[1]
OUTPUT_PATH = APP_ROOT / "vehicle/catalog/vehicle_property_catalog.json"


@dataclass(frozen=True)
class StandardSpec:
    symbol: str
    feature: str
    subfeature: str
    function: str
    control: str
    counts: bool = True
    dependencies: str = "Không có dependency tĩnh; capability thực tế được khám phá khi kết nối xe."


def s(
    symbol: str,
    feature: str,
    subfeature: str,
    function: str,
    control: str,
    counts: bool = True,
    dependencies: str = "Không có dependency tĩnh; capability thực tế được khám phá khi kết nối xe.",
) -> StandardSpec:
    return StandardSpec(symbol, feature, subfeature, function, control, counts, dependencies)


STANDARD_SPECS = [
    # 17 standard ADAS candidates. Keep the list in the same order as the master prompt.
    s("ELECTRONIC_STABILITY_CONTROL_ENABLED", "Driver Assistance", "Vehicle stability", "Electronic stability control", "switch"),
    s("AUTOMATIC_EMERGENCY_BRAKING_ENABLED", "Driver Assistance", "Collision avoidance", "Automatic emergency braking", "switch"),
    s("FORWARD_COLLISION_WARNING_ENABLED", "Driver Assistance", "Collision avoidance", "Forward collision warning", "switch"),
    s("BLIND_SPOT_WARNING_ENABLED", "Driver Assistance", "Collision avoidance", "Blind spot warning", "switch"),
    s("LANE_DEPARTURE_WARNING_ENABLED", "Driver Assistance", "Lane support", "Lane departure warning", "switch"),
    s("LANE_KEEP_ASSIST_ENABLED", "Driver Assistance", "Lane support", "Lane keep assist", "switch"),
    s("LANE_CENTERING_ASSIST_ENABLED", "Driver Assistance", "Lane support", "Lane centering assist", "switch"),
    s("EMERGENCY_LANE_KEEP_ASSIST_ENABLED", "Driver Assistance", "Lane support", "Emergency lane keep assist", "switch"),
    s("CRUISE_CONTROL_ENABLED", "Driver Assistance", "Cruise and speed", "Cruise control", "switch"),
    s("HANDS_ON_DETECTION_ENABLED", "Driver Assistance", "Driver monitoring", "Hands-on detection", "switch"),
    s("DRIVER_DROWSINESS_ATTENTION_SYSTEM_ENABLED", "Driver Assistance", "Driver monitoring", "Drowsiness attention system", "switch"),
    s("DRIVER_DROWSINESS_ATTENTION_WARNING_ENABLED", "Driver Assistance", "Driver monitoring", "Drowsiness attention warning", "switch"),
    s("DRIVER_DISTRACTION_SYSTEM_ENABLED", "Driver Assistance", "Driver monitoring", "Driver distraction system", "switch"),
    s("DRIVER_DISTRACTION_WARNING_ENABLED", "Driver Assistance", "Driver monitoring", "Driver distraction warning", "switch"),
    s("LOW_SPEED_COLLISION_WARNING_ENABLED", "Driver Assistance", "Collision avoidance", "Low speed collision warning", "switch"),
    s("CROSS_TRAFFIC_MONITORING_ENABLED", "Driver Assistance", "Collision avoidance", "Cross traffic monitoring", "switch"),
    s("LOW_SPEED_AUTOMATIC_EMERGENCY_BRAKING_ENABLED", "Driver Assistance", "Collision avoidance", "Low speed automatic emergency braking", "switch"),
    # Seat and steering wheel.
    s("SEAT_FORE_AFT_POS", "Seat Control", "Seat position", "Seat fore/aft position", "slider"),
    s("SEAT_HEIGHT_POS", "Seat Control", "Seat position", "Seat height", "slider"),
    s("SEAT_DEPTH_POS", "Seat Control", "Seat position", "Seat cushion depth", "slider"),
    s("SEAT_TILT_POS", "Seat Control", "Seat position", "Seat tilt", "slider"),
    s("SEAT_BACKREST_ANGLE_1_POS", "Seat Control", "Seat position", "Primary backrest angle", "slider"),
    s("SEAT_BACKREST_ANGLE_2_POS", "Seat Control", "Seat position", "Secondary backrest angle", "slider"),
    s("SEAT_LUMBAR_FORE_AFT_POS", "Seat Control", "Support and headrest", "Lumbar fore/aft support", "slider"),
    s("SEAT_LUMBAR_VERTICAL_POS", "Seat Control", "Support and headrest", "Lumbar vertical support", "slider"),
    s("SEAT_LUMBAR_SIDE_SUPPORT_POS", "Seat Control", "Support and headrest", "Lumbar side support", "slider"),
    s("SEAT_CUSHION_SIDE_SUPPORT_POS", "Seat Control", "Support and headrest", "Cushion side support", "slider"),
    s("SEAT_HEADREST_HEIGHT_POS_V2", "Seat Control", "Support and headrest", "Headrest height", "slider"),
    s("SEAT_HEADREST_ANGLE_POS", "Seat Control", "Support and headrest", "Headrest angle", "slider"),
    s("SEAT_HEADREST_FORE_AFT_POS", "Seat Control", "Support and headrest", "Headrest fore/aft", "slider"),
    s("SEAT_EASY_ACCESS_ENABLED", "Seat Control", "Memory and access", "Easy seat access", "switch"),
    s("SEAT_OCCUPANCY", "Seat Control", "Status", "Seat occupancy", "status", False),
    s("SEAT_BELT_BUCKLED", "Seat Control", "Status", "Seat belt buckled", "status", False),
    s("SEAT_MEMORY_SELECT", "Seat Control", "Memory and access", "Recall seat memory", "slot_action"),
    s("SEAT_MEMORY_SET", "Seat Control", "Memory and access", "Save seat memory", "slot_action"),
    s("STEERING_WHEEL_DEPTH_POS", "Seat Control", "Steering wheel", "Steering wheel depth", "slider"),
    s("STEERING_WHEEL_HEIGHT_POS", "Seat Control", "Steering wheel", "Steering wheel height", "slider"),
    # HVAC. HVAC_DEFROSTER deliberately remains one property entry with two area workflows.
    s("HVAC_POWER_ON", "HVAC", "System", "Climate power", "switch"),
    s("HVAC_AUTO_ON", "HVAC", "System", "Automatic climate", "switch"),
    s("HVAC_DUAL_ON", "HVAC", "System", "Dual/sync climate", "switch"),
    s("HVAC_TEMPERATURE_DISPLAY_UNITS", "HVAC", "System", "Temperature display units", "enum"),
    s("HVAC_TEMPERATURE_SET", "HVAC", "Temperature", "Set temperature", "slider"),
    s("HVAC_TEMPERATURE_CURRENT", "HVAC", "Temperature", "Current temperature", "status", False),
    s("HVAC_FAN_SPEED", "HVAC", "Airflow", "Fan speed", "slider"),
    s("HVAC_FAN_DIRECTION", "HVAC", "Airflow", "Fan direction", "enum"),
    s("HVAC_AC_ON", "HVAC", "Airflow", "A/C", "switch"),
    s("HVAC_MAX_AC_ON", "HVAC", "Airflow", "Max A/C", "switch"),
    s("HVAC_RECIRC_ON", "HVAC", "Airflow", "Recirculation", "switch"),
    s("HVAC_AUTO_RECIRC_ON", "HVAC", "Airflow", "Automatic recirculation", "switch"),
    s("HVAC_DEFROSTER", "HVAC", "Defrost and heat", "Front/rear defroster (independent window area)", "switch"),
    s("HVAC_MAX_DEFROST_ON", "HVAC", "Defrost and heat", "Max defrost", "switch"),
    s("HVAC_SIDE_MIRROR_HEAT", "HVAC", "Defrost and heat", "Mirror heat", "switch"),
    s("HVAC_SEAT_TEMPERATURE", "HVAC", "Seat comfort", "Seat heating/cooling", "slider"),
    s("HVAC_SEAT_VENTILATION", "HVAC", "Seat comfort", "Seat ventilation", "slider"),
    s("HVAC_STEERING_WHEEL_HEAT", "HVAC", "Seat comfort", "Steering wheel heat", "slider"),
    # Lights.
    s("HEADLIGHTS_SWITCH", "Vehicle Lighting", "Exterior", "Headlights switch", "enum"),
    s("HIGH_BEAM_LIGHTS_SWITCH", "Vehicle Lighting", "Exterior", "High beam switch", "enum"),
    s("FOG_LIGHTS_SWITCH", "Vehicle Lighting", "Exterior", "Fog lights switch", "enum"),
    s("HAZARD_LIGHTS_SWITCH", "Vehicle Lighting", "Safety", "Hazard lights switch", "enum"),
    s("CABIN_LIGHTS_SWITCH", "Vehicle Lighting", "Interior", "Cabin lights switch", "enum"),
    s("READING_LIGHTS_SWITCH", "Vehicle Lighting", "Interior", "Reading lights switch", "enum"),
    s("STEERING_WHEEL_LIGHTS_SWITCH", "Vehicle Lighting", "Interior", "Steering-wheel lights switch", "enum"),
    s("SEAT_FOOTWELL_LIGHTS_SWITCH", "Vehicle Lighting", "Interior", "Seat footwell lights switch", "enum"),
    # Doors, windows and mirrors.
    s("DOOR_LOCK", "Door Control", "Doors", "Door lock", "switch"),
    s("DOOR_CHILD_LOCK_ENABLED", "Door Control", "Doors", "Child lock", "switch"),
    s("DOOR_POS", "Door Control", "Doors", "Door position", "slider"),
    s("DOOR_MOVE", "Door Control", "Doors", "Door movement", "enum"),
    s("WINDOW_LOCK", "Door Control", "Windows", "Window lock", "switch"),
    s("WINDOW_POS", "Door Control", "Windows", "Window position", "slider"),
    s("WINDOW_MOVE", "Door Control", "Windows", "Window movement", "enum"),
    s("MIRROR_LOCK", "Door Control", "Mirrors", "Mirror lock", "switch"),
    s("MIRROR_FOLD", "Door Control", "Mirrors", "Mirror fold", "switch"),
    s("MIRROR_AUTO_FOLD_ENABLED", "Door Control", "Mirrors", "Automatic mirror fold", "switch"),
    s("MIRROR_AUTO_TILT_ENABLED", "Door Control", "Mirrors", "Automatic mirror tilt", "switch"),
    s("MIRROR_Z_POS", "Door Control", "Mirrors", "Mirror vertical position", "slider"),
    s("MIRROR_Y_POS", "Door Control", "Mirrors", "Mirror horizontal position", "slider"),
]


VENDOR_SPECS = [
    s("LANE_WARNING_SENSITIVITY", "Driver Assistance", "Lane support", "Lane warning sensitivity", "enum"),
    s("STEERING_INTERVENTION_STRENGTH", "Driver Assistance", "Lane support", "Steering intervention strength", "enum"),
    s("LANE_ALERT_MODE", "Driver Assistance", "Lane support", "Lane alert mode", "enum"),
    s("COLLISION_WARNING_TIMING", "Driver Assistance", "Collision avoidance", "Collision warning timing", "enum"),
    s("ACC_ACCELERATION_PROFILE", "Driver Assistance", "Cruise and speed", "ACC acceleration profile", "enum"),
    s("SPEED_LIMIT_WARNING_ENABLED", "Driver Assistance", "Cruise and speed", "Speed limit warning", "switch"),
    s("SPEED_LIMIT_OFFSET", "Driver Assistance", "Cruise and speed", "Speed limit offset", "slider"),
    s("DRIVER_WARNING_VOLUME", "Driver Assistance", "Profile and feedback", "Driver warning volume", "slider"),
    s("FRONT_PARKING_ASSIST_ENABLED", "Driver Assistance", "Parking and exit", "Front parking assistance", "switch"),
    s("REAR_PARKING_ASSIST_ENABLED", "Driver Assistance", "Parking and exit", "Rear parking assistance", "switch"),
    s("PARKING_WARNING_VOLUME", "Driver Assistance", "Parking and exit", "Parking warning volume", "slider"),
    s("SAFE_EXIT_ASSIST_ENABLED", "Driver Assistance", "Parking and exit", "Safe exit assistance", "switch"),
    s("DRIVER_ASSISTANCE_PROFILE", "Driver Assistance", "Profile and feedback", "Driver assistance profile", "enum"),
]


def git_revision(path: Path) -> str:
    return subprocess.check_output(["git", "-C", str(path), "rev-parse", "HEAD"], text=True).strip()


def permission_to_text(permission: Any) -> str | None:
    if permission is None:
        return None
    if permission["type"] == "single":
        return permission["value"]
    if permission["type"] in {"anyOf", "allOf"}:
        values = ", ".join(filter(None, (permission_to_text(item) for item in permission["value"])))
        return f"{permission['type']}({values})"
    raise ValueError(f"Unsupported permission structure: {permission}")


def rendered_change_mode(value: str) -> str:
    return {"ONCHANGE": "ON_CHANGE"}.get(value, value)


def raw_commands(property_id: int, value_type: str, access: str) -> dict[str, str | None]:
    value_flag = {"Boolean": "-i <0|1>", "Integer": "-i <int32>", "Float": "-f <float>"}.get(value_type, "<typed-value>")
    prop_hex = f"0x{property_id:08X}"
    return {
        "get": (
            f"adb root && adb shell dumpsys android.hardware.automotive.vehicle.IVehicle/default --get {prop_hex}"
            if access in {"READ_ONLY", "READ_WRITE"}
            else None
        ),
        "set": (
            f"adb root && adb shell dumpsys android.hardware.automotive.vehicle.IVehicle/default --set {prop_hex} -a <area-id> {value_flag}"
            if access in {"WRITE_ONLY", "READ_WRITE"}
            else None
        ),
        "restore": (
            f"adb root && adb shell dumpsys android.hardware.automotive.vehicle.IVehicle/default --save-prop {prop_hex} -a <area-id>; "
            f"adb root && adb shell dumpsys android.hardware.automotive.vehicle.IVehicle/default --restore-prop {prop_hex} -a <area-id>"
            if access == "READ_WRITE"
            else None
        ),
    }


def car_service_commands(symbol: str, access: str) -> dict[str, str | None]:
    return {
        "config": f"adb shell cmd car_service get-carpropertyconfig {symbol}",
        "get": f"adb shell cmd car_service get-property-value {symbol} <area-id>" if access in {"READ_ONLY", "READ_WRITE"} else None,
        "set": f"adb shell cmd car_service set-property-value {symbol} <area-id> <typed-value>" if access in {"WRITE_ONLY", "READ_WRITE"} else None,
    }


def unknown_runtime_value() -> dict[str, Any]:
    return {"value": None, "status": "DISCOVER_AT_RUNTIME_ON_NEW_IMAGE"}


def standard_entry(spec: StandardSpec, source: dict[str, Any]) -> dict[str, Any]:
    property_id = source["propertyId"]
    allowed_access = set(source["allowedAccessModes"])
    readable = bool(allowed_access & {"READ", "READ_WRITE"})
    writable = bool(allowed_access & {"WRITE", "READ_WRITE"})
    access = (
        "READ_WRITE"
        if readable and writable
        else "READ_ONLY"
        if readable
        else "WRITE_ONLY"
        if writable
        else "NONE"
    )
    is_adas = spec.feature == "Driver Assistance"
    return {
        "catalog_key": spec.symbol,
        "symbolic_name": spec.symbol,
        "property_id": {"decimal": property_id, "hexadecimal": f"0x{property_id:08X}"},
        "source": "SYSTEM",
        "feature": spec.feature,
        "subfeature": spec.subfeature,
        "user_facing_function": spec.function,
        "counts_as_distinct_user_facing_function": spec.counts,
        "description": source["description"],
        "value_type": source["propertyType"],
        "area_type": source["areaType"],
        "supported_area_ids": [],
        "supported_area_ids_status": "DISCOVER_AT_RUNTIME_ON_NEW_IMAGE",
        "access": access,
        "change_mode": rendered_change_mode(source["changeMode"]),
        "default": unknown_runtime_value(),
        "min": unknown_runtime_value(),
        "max": unknown_runtime_value(),
        "step": unknown_runtime_value(),
        "enum_values": source.get("dataEnums", []),
        "config_array_or_string": {"array": [], "string": "DISCOVER_AT_RUNTIME via cmd car_service get-carpropertyconfig"},
        "read_permission": permission_to_text(source.get("readPermission")),
        "write_permission": permission_to_text(source.get("writePermission")),
        "ux_restriction_policy": "Các thao tác ghi bị khóa khi UX không unrestricted; trạng thái chỉ đọc vẫn hiển thị.",
        "dependency_rules": spec.dependencies,
        "unavailable_error_behavior": "Ẩn hoặc hiển thị unavailable theo capability; lỗi permission/rejected giữ thông báo, không dùng giá trị giả.",
        "application_control_type": spec.control,
        "info_text": "Capability, area, access, range và enum phải được xe công bố trước khi cho phép thao tác.",
        "raw_vhal": raw_commands(property_id, source["propertyType"], access),
        "car_service": car_service_commands(spec.symbol, access),
        "expected_results": (
            "GET trả giá trị thực. SET hợp lệ tạo event/read-back xác nhận; app chỉ đổi Pending sang Confirmed sau event hoặc read-back."
            if access == "READ_WRITE"
            else "GET trả giá trị thực; property không có SET."
            if access == "READ_ONLY"
            else "Property chỉ WRITE; không có GET/restore nguyên giá trị qua API đọc, UI dùng control action và event acknowledgement."
        ),
        "implementation_status": "IMPLEMENTED_CAPABILITY_DRIVEN",
        "emulator_test_status": "NOT_RUN: image fixed đã boot và có representative evidence; chưa chạy đủ raw VHAL/CarService/UI/external-mutation/UXR cho property này.",
        "discovery_disposition": (
            "OLD_AVD_CONFIG_VERIFIED_2026_08_08_PENDING_NEW_IMAGE" if is_adas else "SOURCE_CATALOGED_PENDING_NEW_IMAGE"
        ),
        "source_evidence": [
            "packages/services/Car/car-lib/generated-prop-config/CarSvcProps.json",
            "hardware/interfaces/automotive/vehicle/aidl_property/.../VehicleProperty.aidl",
        ],
    }


def vendor_entry(spec: StandardSpec, source: dict[str, Any]) -> dict[str, Any]:
    property_id = source["property"]
    area = source["areas"][0]
    value_type = "Boolean" if (property_id & 0x00FF0000) == 0x00200000 else "Integer"
    enum_values = area.get("supportedEnumValues", [])
    default_values = source["defaultValue"]["int32Values"]
    return {
        "catalog_key": spec.symbol,
        "symbolic_name": spec.symbol,
        "property_id": {"decimal": property_id, "hexadecimal": f"0x{property_id:08X}"},
        "source": "VENDOR",
        "feature": spec.feature,
        "subfeature": spec.subfeature,
        "user_facing_function": spec.function,
        "counts_as_distinct_user_facing_function": spec.counts,
        "description": f"Thiết lập OEM Driver Assistance: {spec.function}.",
        "value_type": value_type,
        "area_type": "GLOBAL",
        "supported_area_ids": [0],
        "supported_area_ids_status": "SOURCE_VERIFIED_BY_PROJECT_VHAL_CONFIG",
        "access": "READ_WRITE",
        "change_mode": "ON_CHANGE",
        "default": {"value": default_values[0], "status": "SOURCE_VERIFIED_BY_PROJECT_VHAL_CONFIG"},
        "min": {"value": area.get("minInt32Value"), "status": "SOURCE_VERIFIED_BY_PROJECT_VHAL_CONFIG"},
        "max": {"value": area.get("maxInt32Value"), "status": "SOURCE_VERIFIED_BY_PROJECT_VHAL_CONFIG"},
        "step": {"value": 1 if area.get("minInt32Value") is not None else None, "status": "SOURCE_VERIFIED_BY_PROJECT_VHAL_CONFIG"},
        "enum_values": enum_values,
        "config_array_or_string": {"array": [], "string": source["configString"]},
        "read_permission": "android.car.permission.CAR_VENDOR_EXTENSION",
        "write_permission": "android.car.permission.CAR_VENDOR_EXTENSION",
        "ux_restriction_policy": "Các thao tác ghi bị khóa khi UX không unrestricted; trạng thái chỉ đọc vẫn hiển thị.",
        "dependency_rules": "Không có dependency tĩnh; profile OEM có thể yêu cầu phần cứng hỗ trợ.",
        "unavailable_error_behavior": "Nếu config OEM không được CarService công bố thì control unavailable; lỗi rejected/permission không dùng fallback cục bộ.",
        "application_control_type": spec.control,
        "info_text": "Thuộc tính OEM do MySystemApp sở hữu trong dải Driver Assistance vendor 0x6000–0x60FF.",
        "raw_vhal": raw_commands(property_id, value_type, "READ_WRITE"),
        "car_service": car_service_commands(spec.symbol, "READ_WRITE"),
        "expected_results": "VHAL config và FakeVehicleHardware phải nạp default/range/enum; SET sinh ON_CHANGE và app xác nhận bằng event/read-back.",
        "implementation_status": "IMPLEMENTED_VENDOR_CONFIG_PENDING_IMAGE",
        "emulator_test_status": "NOT_RUN: fixed image đã boot và vendor representative 0x21406000 đã được kiểm tra; chưa chạy đủ acceptance riêng cho property này.",
        "discovery_disposition": "FULL_ID_SOURCE_VERIFIED_COLLISION_SCAN_CLEAN_PENDING_NEW_IMAGE",
        "source_evidence": [
            "vehicle/vhal/MySystemAppVehicleControlProperties.json",
            "vehicle/vhal/VehicleControlConfigTest.cpp",
            "vehicle/vhal/validate_config.sh",
        ],
    }


def build_catalog(aosp_root: Path) -> dict[str, Any]:
    car_props_path = aosp_root / "packages/services/Car/car-lib/generated-prop-config/CarSvcProps.json"
    vhal_config_path = APP_ROOT / "vehicle/vhal/MySystemAppVehicleControlProperties.json"
    car_props = json.loads(car_props_path.read_text(encoding="utf-8"))["properties"]
    vendor_config = json.loads(vhal_config_path.read_text(encoding="utf-8"))["properties"]
    vendor_by_symbol = {
        item["configString"].split("symbol=", 1)[1].split(";", 1)[0]: item for item in vendor_config
    }

    missing_standard = [spec.symbol for spec in STANDARD_SPECS if spec.symbol not in car_props]
    missing_vendor = [spec.symbol for spec in VENDOR_SPECS if spec.symbol not in vendor_by_symbol]
    if missing_standard or missing_vendor:
        raise RuntimeError(f"Catalog inputs missing standard={missing_standard} vendor={missing_vendor}")

    entries = [standard_entry(spec, car_props[spec.symbol]) for spec in STANDARD_SPECS]
    entries += [vendor_entry(spec, vendor_by_symbol[spec.symbol]) for spec in VENDOR_SPECS]
    entries.sort(key=lambda entry: (entry["feature"], entry["source"], entry["property_id"]["decimal"]))
    return {
        "schema_version": 1,
        "catalog_identity": "MySystemApp Vehicle Control property catalog",
        "language": "vi",
        "aosp_revision": {
            "packages_services_car": git_revision(aosp_root / "packages/services/Car"),
            "hardware_interfaces": git_revision(aosp_root / "hardware/interfaces"),
        },
        "generation_contract": {
            "standard_source": "packages/services/Car/car-lib/generated-prop-config/CarSvcProps.json",
            "vendor_source": "vehicle/vhal/MySystemAppVehicleControlProperties.json",
            "validation": "vehicle/catalog/validate_catalog.py --check",
            "runtime_rule": "Không suy diễn config/value/area runtime khi image mới chưa boot; các trường tương ứng ghi rõ DISCOVER_AT_RUNTIME_ON_NEW_IMAGE.",
        },
        "runtime_evidence": {
            "old_avd": "Preflight đã khám phá 17 ADAS standard; không dùng làm PASS cho image cuối.",
            "new_image": "PASS_PARTIAL: final image build bằng m PASS; archive host-zip workaround boot trên emulator-5568, API 37; raw VHAL --list=301, 88/89 catalog IDs hiện diện, 86 GET PASS, 84 READ_WRITE write/restore PASS. Fog lights 0x11400E12 không được image công bố; 2 seat-memory property là WRITE-only. FUNCTION_TEST_MATRIX.md giữ NOT_RUN cho row chưa đủ UI/external/UXR.",
            "official_image_build": "out-avd-car-mysystemapp/target/product/emulator_car64_x86_64 (m PASS)",
            "official_image_sha256": {
                "system.img": "ac310c621f8f51ccf863d4a7b2b8ad6996c2f6e3818e9169ef0c7723b6d0417e",
                "vendor.img": "0fdcc95422b7691786abe346649f6f6350b94257d6514b45a965106831af4826",
                "system_ext.img": "f07f77cd46842d74eaf236205b64d594e9b2bd984517ab5e011305e6c116b0a7",
            },
            "published_image": "publish/vehicle-control/sdk-repo-linux-system-images-20260813-streaming.zip",
            "published_image_sha256": "16d23e09200306eae6cf83dc75e06f094a5e3abba30ea1cb58a57489a08bed5a",
            "official_emu_img_zip_status": "BLOCKED: soong_build/soong_zip memory stall/OOM trên host 23 GiB; archive trên là host zip -0 workaround, không phải target m emu_img_zip PASS.",
            "runtime_serial": "emulator-5568",
            "boot_completed": True,
            "fingerprint": "Android/sdk_car_mysystemapp_x86_64/emulator_car64_x86_64:Baklava/CP2A.260605.016/eng.binh:userdebug/test-keys",
            "capability_evidence": "out-avd-car-mysystemapp/runtime-evidence/e2e-20260813T123617Z-emulator-5568/; raw list 301, catalog present 88, absent 1, raw readable GET 86 PASS, raw write/restore 84 PASS, CarService config 88 PASS, GET 86 PASS, targeted HVAC 5/5 PASS.",
            "resume_commands": [
                "adb -s emulator-5568 shell getprop sys.boot_completed",
                "adb -s emulator-5568 shell cmd car_service get-carpropertyconfig 0x21406000",
                "adb -s emulator-5568 root && adb -s emulator-5568 shell dumpsys android.hardware.automotive.vehicle.IVehicle/default --list",
            ],
        },
        "properties": entries,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--aosp-root", type=Path, default=DEFAULT_AOSP_ROOT)
    parser.add_argument("--output", type=Path, default=OUTPUT_PATH)
    parser.add_argument("--write", action="store_true", help="write canonical checked-in JSON")
    parser.add_argument("--check", action="store_true", help="fail when checked-in JSON differs from source contract")
    args = parser.parse_args()
    if args.write == args.check:
        parser.error("choose exactly one of --write or --check")
    resolved_aosp_root = args.aosp_root.resolve()
    required_aosp_source = (
        resolved_aosp_root
        / "packages/services/Car/car-lib/generated-prop-config/CarSvcProps.json"
    )
    if not required_aosp_source.is_file():
        print(
            "AOSP_SOURCE_PARITY: NOT_RUN - missing " + str(required_aosp_source),
            file=sys.stderr,
        )
        return 2
    catalog = build_catalog(resolved_aosp_root)
    rendered = json.dumps(catalog, ensure_ascii=False, indent=2) + "\n"
    if args.write:
        args.output.write_text(rendered, encoding="utf-8")
        print(f"WROTE: {args.output} ({len(catalog['properties'])} entries)")
        return 0
    if not args.output.exists():
        print(f"FAIL: missing catalog {args.output}", file=sys.stderr)
        return 1
    checked_in = json.loads(args.output.read_text(encoding="utf-8"))
    if checked_in != catalog:
        print("FAIL: checked-in catalog differs from the local source contract; run --write", file=sys.stderr)
        return 1
    print(f"PASS: catalog source contract matches ({len(catalog['properties'])} entries)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
