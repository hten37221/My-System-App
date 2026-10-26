#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
CONFIG_PATH="${1:-${SCRIPT_DIR}/MySystemAppVehicleControlProperties.json}"

command -v jq >/dev/null 2>&1 || {
    echo "BLOCKED: jq is required to validate ${CONFIG_PATH}" >&2
    exit 2
}

EXPECTED_IDS='[
  557867008, 557867009, 557867010, 557867011, 557867012,
  555769861, 557867014, 557867015, 555769864, 555769865,
  557867018, 555769867, 557867020
]'
BOOLEAN_IDS='[555769861, 555769864, 555769865, 555769867]'
ENUM_IDS='[557867008, 557867009, 557867010, 557867011, 557867012, 557867020]'

jq -e \
    --argjson expectedIds "${EXPECTED_IDS}" \
    --argjson booleanIds "${BOOLEAN_IDS}" \
    --argjson enumIds "${ENUM_IDS}" '
    .apiVersion == 2 and
    (.properties | type == "array") and
    (.properties | length == 13) and
    ([.properties[].property] | unique | length == 13) and
    (([.properties[].property] | sort) == ($expectedIds | sort)) and
    all(.properties[];
        . as $property |
        ($property.property | type == "number") and
        $property.access == "VehiclePropertyAccess::READ_WRITE" and
        $property.changeMode == "VehiclePropertyChangeMode::ON_CHANGE" and
        ($property.configString | type == "string" and length > 0) and
        ($property.defaultValue.int32Values | type == "array" and length == 1) and
        ($property.areas | type == "array" and length == 1) and
        $property.areas[0].areaId == 0 and
        (if ($booleanIds | index($property.property)) != null then
            (($property.defaultValue.int32Values[0] == 0) or
             ($property.defaultValue.int32Values[0] == 1)) and
            ($property.areas[0] | has("minInt32Value") | not) and
            ($property.areas[0] | has("maxInt32Value") | not)
         else
            ($property.areas[0].minInt32Value | type == "number") and
            ($property.areas[0].maxInt32Value | type == "number") and
            ($property.defaultValue.int32Values[0] >= $property.areas[0].minInt32Value) and
            ($property.defaultValue.int32Values[0] <= $property.areas[0].maxInt32Value) and
            ($property.areas[0].hasSupportedValueInfo.hasMinSupportedValue == true) and
            ($property.areas[0].hasSupportedValueInfo.hasMaxSupportedValue == true) and
            (if ($enumIds | index($property.property)) != null then
                ($property.areas[0].supportedEnumValues | type == "array" and length > 1) and
                ($property.areas[0].supportedEnumValues | index(
                    $property.defaultValue.int32Values[0])) != null and
                ($property.areas[0].hasSupportedValueInfo.hasSupportedValuesList == true)
             else
                ($property.areas[0] | has("supportedEnumValues") | not)
             end)
         end)
    )
' "${CONFIG_PATH}" >/dev/null

echo "PASS: ${CONFIG_PATH} contains the expected 13-property VHAL contract"
