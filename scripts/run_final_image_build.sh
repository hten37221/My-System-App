#!/usr/bin/env bash
# Reproducible full AOSP image build entrypoint for the system-scope unit.
set -euo pipefail

: "${AOSP_ROOT:?AOSP_ROOT must be set}"
: "${MY_SYSTEM_APP_LUNCH_TARGET:?MY_SYSTEM_APP_LUNCH_TARGET must be set}"

export OUT_DIR="${OUT_DIR:-out-avd-car-mysystemapp}"
export SOONG_NINJA=ninja
export NINJA_ARGS=-j1
export SOONG_UI_NINJA_ARGS=-j1
export GOMAXPROCS="${GOMAXPROCS:-2}"
export GOGC="${GOGC:-20}"
export GOMEMLIMIT="${GOMEMLIMIT:-8GiB}"
export USE_RBE="${USE_RBE:-false}"

cd "$AOSP_ROOT"
source build/envsetup.sh
lunch "$MY_SYSTEM_APP_LUNCH_TARGET"

# Keep the requested top-level command as `m`; resource limits are supplied by
# the environment rather than by an explicit `m -j` argument. Optional goals
# (for example `emu_img_zip`) are passed as ordinary m targets.
m "$@"
