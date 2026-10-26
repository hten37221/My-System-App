#!/usr/bin/env bash
# Orchestrate read-only capability smoke and diagnostics for a booted image.
set -euo pipefail

SERIAL=${1:?usage: $0 SERIAL [OUT_DIR]}
OUT_ROOT=${2:-${OUT_DIR:?set OUT_DIR or pass an output directory}}
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
APP_ROOT=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)
CATALOG="$APP_ROOT/vehicle/catalog/vehicle_property_catalog.json"
EVIDENCE_DIR="$OUT_ROOT/runtime-evidence/e2e-$(date -u +%Y%m%dT%H%M%SZ)-$SERIAL"
ADB_BIN=${ADB_BIN:-adb}

"$ADB_BIN" -s "$SERIAL" shell getprop sys.boot_completed | grep -qx '1'
"$SCRIPT_DIR/test_vehicle_properties.sh" "$SERIAL" "$CATALOG" "$EVIDENCE_DIR/raw-vhal-smoke.json"
"$SCRIPT_DIR/test_carservice_properties.sh" "$SERIAL" "$CATALOG" "$EVIDENCE_DIR/carservice-smoke.json"
"$SCRIPT_DIR/capture_vehicle_control_diagnostics.sh" "$SERIAL" "$EVIDENCE_DIR/diagnostics"
printf '%s\n' "Vehicle Control E2E smoke evidence: $EVIDENCE_DIR"
