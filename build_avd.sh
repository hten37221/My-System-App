#!/bin/bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

require_environment_variable() {
    local variable_name="$1"
    if [[ -z "${!variable_name:-}" ]]; then
        echo "Missing required environment variable: ${variable_name}" >&2
        exit 2
    fi
}

for variable_name in \
        AOSP_ROOT \
        MY_SYSTEM_APP_ROOT \
        OUT_DIR \
        PUBLISH_DIR \
        MY_SYSTEM_APP_LUNCH_TARGET; do
    require_environment_variable "${variable_name}"
done

if [[ "${AOSP_ROOT}" != /* || "${MY_SYSTEM_APP_ROOT}" != /* ]]; then
    echo "AOSP_ROOT and MY_SYSTEM_APP_ROOT must be absolute paths." >&2
    exit 2
fi
if [[ ! -d "${AOSP_ROOT}/.repo" || ! -f "${AOSP_ROOT}/build/envsetup.sh" ]]; then
    echo "AOSP_ROOT is not a complete AOSP checkout: ${AOSP_ROOT}" >&2
    exit 2
fi
if [[ ! -d "${MY_SYSTEM_APP_ROOT}" ]]; then
    echo "MY_SYSTEM_APP_ROOT is not a directory: ${MY_SYSTEM_APP_ROOT}" >&2
    exit 2
fi

AOSP_ROOT="$(realpath -e -- "${AOSP_ROOT}")"
MY_SYSTEM_APP_ROOT="$(realpath -e -- "${MY_SYSTEM_APP_ROOT}")"
if [[ "${SCRIPT_DIR}" != "${MY_SYSTEM_APP_ROOT}" ]]; then
    echo "MY_SYSTEM_APP_ROOT must point to the checkout containing build_avd.sh." >&2
    exit 2
fi
if [[ "${OUT_DIR}" != /* ]]; then
    OUT_DIR="${AOSP_ROOT}/${OUT_DIR}"
fi
OUT_DIR="$(realpath -m -- "${OUT_DIR}")"
mkdir -p -- "${OUT_DIR}" "${PUBLISH_DIR}"
PUBLISH_DIR="$(realpath -e -- "${PUBLISH_DIR}")"

export AOSP_ROOT MY_SYSTEM_APP_ROOT OUT_DIR PUBLISH_DIR MY_SYSTEM_APP_LUNCH_TARGET
PUBLISH_ZIP="${PUBLISH_DIR}/sdk-repo-linux-system-images-automotive-mysystemapp-v2.zip"

# GNOME Terminal places all of its children in one systemd scope.  systemd-oomd
# was killing that entire scope while emu_img_zip built the super image, which
# also closed the terminal window.  Submit the actual build as a detached user
# service with memory pressure throttling instead.  Do not use --pipe/--wait:
# the client process would remain in the terminal scope and can still make the
# terminal a victim of systemd-oomd.
if [[ "${MY_SYSTEM_APP_BUILD_IN_SYSTEMD:-}" != "1" ]]; then
    if systemctl is-active --quiet systemd-oomd.service || \
            systemctl is-active --quiet systemd-oomd.socket; then
        echo "systemd-oomd is active and can kill an unrestricted AOSP build."
        echo "Run: sudo systemctl stop systemd-oomd.service systemd-oomd.socket"
        exit 1
    fi

    BUILD_RUN_ID="$(date +%Y%m%d-%H%M%S)"
    BUILD_UNIT="mysystemapp-image-build-${BUILD_RUN_ID}"
    BUILD_LOG_FILE="${OUT_DIR}/logs/build-avd-${BUILD_RUN_ID}.log"
    mkdir -p "$(dirname "${BUILD_LOG_FILE}")"
    : > "${BUILD_LOG_FILE}"
    # Do not set MemoryHigh here.  Soong's bootstrap can exceed 14 GiB; a
    # cgroup high limit makes it continuously reclaim and swap instead of
    # finishing the generated-ninja step.  systemd-oomd must be temporarily
    # stopped by the caller while this detached build is running.
    systemd-run --user --quiet --collect --unit="${BUILD_UNIT}" \
        --setenv=MY_SYSTEM_APP_BUILD_IN_SYSTEMD=1 \
        --setenv=MY_SYSTEM_APP_BUILD_LOG_FILE="${BUILD_LOG_FILE}" \
        --setenv=AOSP_ROOT="${AOSP_ROOT}" \
        --setenv=MY_SYSTEM_APP_ROOT="${MY_SYSTEM_APP_ROOT}" \
        --setenv=OUT_DIR="${OUT_DIR}" \
        --setenv=PUBLISH_DIR="${PUBLISH_DIR}" \
        --setenv=MY_SYSTEM_APP_LUNCH_TARGET="${MY_SYSTEM_APP_LUNCH_TARGET}" \
        "${MY_SYSTEM_APP_ROOT}/build_avd.sh" "$@"
    echo "Build submitted: ${BUILD_UNIT}.service"
    echo "Follow log: tail -f ${BUILD_LOG_FILE}"
    echo "Check status: systemctl --user status ${BUILD_UNIT}.service"
    exit 0
fi

cd "${AOSP_ROOT}"
source build/envsetup.sh
export SOONG_NINJA=ninja
export SOONG_INCREMENTAL_ANALYSIS=false
lunch "${MY_SYSTEM_APP_LUNCH_TARGET}"
: "${ANDROID_PRODUCT_OUT:?Lunch did not set ANDROID_PRODUCT_OUT}"
PRODUCT_OUT_DIR="$(realpath -m -- "${ANDROID_PRODUCT_OUT}")"

LOG_DIR="${OUT_DIR}/logs"
mkdir -p "${LOG_DIR}"
LOG_FILE="${MY_SYSTEM_APP_BUILD_LOG_FILE:-${LOG_DIR}/build-avd-$(date +%Y%m%d-%H%M%S).log}"

if command -v adb >/dev/null && adb get-state >/dev/null 2>&1; then
    echo "An AVD is running. Stop it before building the system image to avoid an OOM kill."
    exit 1
fi

echo "Logging to ${LOG_FILE}"
{
    (
        cd "${MY_SYSTEM_APP_ROOT}"
        ./gradlew --no-daemon :app:stageAospApk
    )
    test -s "${MY_SYSTEM_APP_ROOT}/prebuilts/MySystemApp.apk"
    m MySystemApp CarSystemUI MySystemAppCarSystemUIOverlay
    m emu_img_zip
    SOURCE_ZIP="${PRODUCT_OUT_DIR}/sdk-repo-linux-system-images.zip"
    test -s "${SOURCE_ZIP}"
    unzip -tq "${SOURCE_ZIP}"
    unzip -p "${SOURCE_ZIP}" x86_64/source.properties | \
        /usr/bin/grep -Fqx 'SystemImage.TagId=android-automotive'
    mkdir -p "${PUBLISH_DIR}"
    cp --preserve=mode,timestamps "${SOURCE_ZIP}" "${PUBLISH_ZIP}"
    sha256sum "${SOURCE_ZIP}" "${PUBLISH_ZIP}"
    echo "Automotive system image published to ${PUBLISH_ZIP}"
} 2>&1 | tee "${LOG_FILE}"
