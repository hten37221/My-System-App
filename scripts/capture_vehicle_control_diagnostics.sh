#!/usr/bin/env bash
# Capture reproducible device diagnostics without mutating vehicle properties.
set -euo pipefail

SERIAL=${1:?usage: $0 SERIAL EVIDENCE_DIR}
EVIDENCE_DIR=${2:?usage: $0 SERIAL EVIDENCE_DIR}
ADB_BIN=${ADB_BIN:-adb}

mkdir -p "$EVIDENCE_DIR"
"$ADB_BIN" -s "$SERIAL" get-state >/dev/null
"$ADB_BIN" -s "$SERIAL" shell getprop >"$EVIDENCE_DIR/getprop.txt"
"$ADB_BIN" -s "$SERIAL" shell dumpsys package com.android.mysystemapp >"$EVIDENCE_DIR/package-mysystemapp.txt"
"$ADB_BIN" -s "$SERIAL" shell cmd overlay list >"$EVIDENCE_DIR/overlay-list.txt"
"$ADB_BIN" -s "$SERIAL" shell cmd car_service -h >"$EVIDENCE_DIR/car-service-help.txt"
"$ADB_BIN" -s "$SERIAL" shell dumpsys android.hardware.automotive.vehicle.IVehicle/default --list >"$EVIDENCE_DIR/raw-vhal-list.txt"
"$ADB_BIN" -s "$SERIAL" shell logcat -d -b all -v threadtime >"$EVIDENCE_DIR/logcat-all.txt"
printf '%s\n' "Diagnostics written to $EVIDENCE_DIR"
