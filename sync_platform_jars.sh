#!/usr/bin/env bash
# Copies platform and system-server compile JARs from the matching AOSP build.
# The resulting JARs are committed for portable Android Studio compilation, but
# remain compile-only and are never packaged into the APK.
set -euo pipefail

PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"

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
if [[ "${PROJECT_DIR}" != "${MY_SYSTEM_APP_ROOT}" ]]; then
    echo "MY_SYSTEM_APP_ROOT must point to the checkout containing sync_platform_jars.sh." >&2
    exit 2
fi
if [[ "${OUT_DIR}" != /* ]]; then
    OUT_DIR="${AOSP_ROOT}/${OUT_DIR}"
fi
BUILD_OUT="$(realpath -m -- "${OUT_DIR}")"
PLATFORM_LIB_DIR="$PROJECT_DIR/libs/platform"
SYSTEM_SERVER_LIB_DIR="$PROJECT_DIR/libs/system-server"

declare -A PLATFORM_JARS=(
    [framework.jar]="$BUILD_OUT/soong/.intermediates/frameworks/base/framework-minus-apex/android_common/combined/framework.jar"
    [android.car.jar]="$BUILD_OUT/soong/.intermediates/packages/services/Car/car-lib/android.car/android_common/combined/android.car.jar"
    [framework-wifi.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Wifi/framework/framework-wifi.impl/android_common_apex31/combined/framework-wifi.jar"
    [framework-bluetooth.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Bluetooth/framework/framework-bluetooth.impl/android_common/combined/framework-bluetooth.jar"
    [framework-location.jar]="$BUILD_OUT/soong/.intermediates/frameworks/base/location/framework-location.impl/android_common/combined/framework-location.jar"
    [framework-connectivity.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Connectivity/framework/framework-connectivity.impl/android_common/combined/framework-connectivity.jar"
    [framework-connectivity-t.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Connectivity/framework-t/framework-connectivity-t.impl/android_common_apex31/combined/framework-connectivity-t.jar"
    [framework-connectivity-b.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Connectivity/Vcn/framework-b/framework-connectivity-b.impl/android_common_apex31/combined/framework-connectivity-b.jar"
    [framework-tethering.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Connectivity/Tethering/common/TetheringLib/framework-tethering.impl/android_common_apex31/combined/framework-tethering.jar"
    [framework-media.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Media/apex/framework/framework-media.impl/android_common_apex31/combined/framework-media.jar"
    [framework-mediaprovider.jar]="$BUILD_OUT/soong/.intermediates/packages/providers/MediaProvider/apex/framework/framework-mediaprovider.impl/android_common_apex31/combined/framework-mediaprovider.jar"
    [framework-nfc.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Nfc/framework/framework-nfc.impl/android_common_apex36/combined/framework-nfc.jar"
    [framework-telephony.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Telephony/framework/framework-telephony.impl/android_common_apex36/combined/framework-telephony.jar"
    [framework-telecom.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Telephony/telecom/framework/framework-telecom.impl/android_common/combined/framework-telecom.jar"
    [framework-permission-s.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Permission/framework-s/framework-permission-s.impl/android_common/combined/framework-permission-s.jar"
    [telephony-common.jar]="$BUILD_OUT/soong/.intermediates/frameworks/opt/telephony/telephony-common/android_common/combined/telephony-common.jar"
)

declare -A SYSTEM_SERVER_JARS=(
    [services.jar]="$BUILD_OUT/soong/.intermediates/frameworks/base/services/services/android_common/combined/services.jar"
    [services.permission.jar]="$BUILD_OUT/soong/.intermediates/frameworks/base/services/permission/services.permission/android_common/combined/services.permission.jar"
    [service-wifi.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Wifi/service/service-wifi/android_common_apex31/combined/service-wifi.jar"
    [service-bluetooth.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Bluetooth/service/service-bluetooth/android_common/combined/service-bluetooth.jar"
    [service-connectivity.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Connectivity/service/service-connectivity/android_common/combined/service-connectivity.jar"
    [service-telecom.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Telephony/telecom/service/service-telecom.impl/android_common_apex36/combined/service-telecom.jar"
    [service-permission.jar]="$BUILD_OUT/soong/.intermediates/packages/modules/Permission/service/service-permission.impl/android_common_apex31/combined/service-permission.jar"
    [service-jobscheduler.jar]="$BUILD_OUT/soong/.intermediates/frameworks/base/apex/jobscheduler/service/service-jobscheduler/android_common/combined/service-jobscheduler.jar"
)

for jar in "${PLATFORM_JARS[@]}" "${SYSTEM_SERVER_JARS[@]}"; do
    if [[ ! -f "$jar" ]]; then
        echo "Missing AOSP build artifact: $jar" >&2
        echo "Build the matching product first, then rerun this script." >&2
        exit 1
    fi
done

mkdir -p "$PLATFORM_LIB_DIR" "$SYSTEM_SERVER_LIB_DIR"
for name in "${!PLATFORM_JARS[@]}"; do
    cp -f "${PLATFORM_JARS[$name]}" "$PLATFORM_LIB_DIR/$name"
done
for name in "${!SYSTEM_SERVER_JARS[@]}"; do
    cp -f "${SYSTEM_SERVER_JARS[$name]}" "$SYSTEM_SERVER_LIB_DIR/$name"
done

echo "Synchronized Android Studio compile JARs from: $BUILD_OUT"
sha256sum "$PLATFORM_LIB_DIR"/*.jar "$SYSTEM_SERVER_LIB_DIR"/*.jar
