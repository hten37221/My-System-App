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
    echo "MY_SYSTEM_APP_ROOT must point to the checkout containing build_phone_avd.sh." >&2
    exit 2
fi
if [[ "${OUT_DIR}" != /* ]]; then
    OUT_DIR="${AOSP_ROOT}/${OUT_DIR}"
fi
OUT_DIR="$(realpath -m -- "${OUT_DIR}")"
mkdir -p -- "${OUT_DIR}" "${PUBLISH_DIR}"
PUBLISH_DIR="$(realpath -e -- "${PUBLISH_DIR}")"

export AOSP_ROOT MY_SYSTEM_APP_ROOT OUT_DIR PUBLISH_DIR MY_SYSTEM_APP_LUNCH_TARGET
PUBLISH_ZIP="${PUBLISH_DIR}/sdk-repo-linux-system-images.zip"
PHONE_PUBLISH_ZIP="${PUBLISH_DIR}/sdk-repo-linux-system-images-phone-mysystemapp.zip"

# Run outside the terminal scope so a terminal/session closure cannot stop the
# build. The script is passed directly to systemd, avoiding shell expansion of
# its pipeline exit-status handling.
if [[ "${MY_SYSTEM_APP_PHONE_BUILD_IN_SYSTEMD:-}" != "1" ]]; then
    if systemctl is-active --quiet systemd-oomd.service || \
            systemctl is-active --quiet systemd-oomd.socket; then
        echo "systemd-oomd is active and previously killed this AOSP build."
        echo "Run: sudo systemctl stop systemd-oomd.service systemd-oomd.socket"
        exit 1
    fi

    BUILD_RUN_ID="$(date +%Y%m%d-%H%M%S)"
    BUILD_UNIT="mysystemapp-phone-build-${BUILD_RUN_ID}"
    BUILD_LOG_FILE="${OUT_DIR}/logs/${BUILD_UNIT}.log"
    mkdir -p "$(dirname "${BUILD_LOG_FILE}")"
    : > "${BUILD_LOG_FILE}"
    systemd-run --user --quiet --collect --unit="${BUILD_UNIT}" \
        --setenv=MY_SYSTEM_APP_PHONE_BUILD_IN_SYSTEMD=1 \
        --setenv=MY_SYSTEM_APP_PHONE_BUILD_LOG_FILE="${BUILD_LOG_FILE}" \
        --setenv=AOSP_ROOT="${AOSP_ROOT}" \
        --setenv=MY_SYSTEM_APP_ROOT="${MY_SYSTEM_APP_ROOT}" \
        --setenv=OUT_DIR="${OUT_DIR}" \
        --setenv=PUBLISH_DIR="${PUBLISH_DIR}" \
        --setenv=MY_SYSTEM_APP_LUNCH_TARGET="${MY_SYSTEM_APP_LUNCH_TARGET}" \
        "${MY_SYSTEM_APP_ROOT}/build_phone_avd.sh"
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
PHONE_PRODUCT_OUT="$(realpath -m -- "${ANDROID_PRODUCT_OUT}")"

LOG_FILE="${MY_SYSTEM_APP_PHONE_BUILD_LOG_FILE:-${OUT_DIR}/logs/build-phone-$(date +%Y%m%d-%H%M%S).log}"
mkdir -p "$(dirname "${LOG_FILE}")"
echo "Logging to ${LOG_FILE}"

# Use AOSP's default parallelism.
m MySystemApp 2>&1 | tee "${LOG_FILE}"
MODULE_STATUS="${PIPESTATUS[0]}"
if [[ "${MODULE_STATUS}" -ne 0 ]]; then
    exit "${MODULE_STATUS}"
fi

m emu_img_zip 2>&1 | tee -a "${LOG_FILE}"
IMAGE_STATUS="${PIPESTATUS[0]}"
if [[ "${IMAGE_STATUS}" -ne 0 ]]; then
    exit "${IMAGE_STATUS}"
fi

SOURCE_ZIP="${PHONE_PRODUCT_OUT}/sdk-repo-linux-system-images.zip"
if [[ ! -s "${SOURCE_ZIP}" ]]; then
    echo "Missing system image ZIP: ${SOURCE_ZIP}" | tee -a "${LOG_FILE}"
    exit 1
fi

unzip -tq "${SOURCE_ZIP}" 2>&1 | tee -a "${LOG_FILE}"
mkdir -p "${PUBLISH_DIR}"
cp --preserve=mode,timestamps "${SOURCE_ZIP}" "${PHONE_PUBLISH_ZIP}"
cp --preserve=mode,timestamps "${SOURCE_ZIP}" "${PUBLISH_ZIP}.phone.tmp"
mv -f "${PUBLISH_ZIP}.phone.tmp" "${PUBLISH_ZIP}"
sha256sum "${SOURCE_ZIP}" "${PHONE_PUBLISH_ZIP}" "${PUBLISH_ZIP}" | tee -a "${LOG_FILE}"
echo "Phone system image published to ${PUBLISH_ZIP}" | tee -a "${LOG_FILE}"
