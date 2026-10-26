# AOSP platform and system-server compile JARs

The JARs below this directory are generated from the matching
`sdk_car_mysystemapp_x86_64` AOSP build dated 2026-07-26. They are committed so
Android Studio on another development machine can compile source that uses
hidden and system APIs.

They are declared as Gradle `compileOnly` dependencies and are never packaged
into `MySystemApp.apk`; the AVD system image supplies the actual framework code
at runtime.

The Gradle Java compile task places these JARs before the public Android SDK
`android.jar`. This is required not only for classes missing from the public SDK,
but also for hidden members of classes that exist in both JAR sets (for example,
hidden `PackageManager` methods).

## Platform JARs

Files under `platform/` are placed ahead of the public SDK `android.jar`.

| JAR group | API coverage |
| --- | --- |
| `framework.jar` | Base framework, including PackageManager, UserManager, ActivityManager, PowerManager, ServiceManager, and SystemProperties. |
| `framework-wifi.jar` | Wi-Fi APIs, including WifiManager. |
| `framework-bluetooth.jar` | Bluetooth APIs, including BluetoothManager. |
| `framework-location.jar` | Location APIs, including LocationManager. |
| `framework-connectivity*.jar`, `framework-tethering.jar` | Connectivity, VCN, network, and tethering APIs. |
| `framework-media.jar`, `framework-mediaprovider.jar` | Media and MediaProvider module APIs. |
| `framework-nfc.jar` | NFC APIs. |
| `framework-telephony.jar`, `framework-telecom.jar`, `telephony-common.jar` | Telephony, Telecom, and `com.android.internal.telephony.*`. |
| `framework-permission-s.jar` | Permission and Role module APIs. |
| `android.car.jar` | Android Automotive APIs. |

Activity, Package, User, Audio, Display, Power, Sensor, Notification, Storage,
Camera, USB, Biometrics, App, and most internal Binder interfaces are part of
the base `framework.jar`.

## System-server JARs

Files under `system-server/` expose `com.android.server.*` implementations for
source navigation and compilation. Those classes run in `system_server` or
modular service processes and generally cannot be loaded directly by an app,
even a privileged platform-signed app. App code should communicate with them
through Binder manager/interface APIs.

`IMountService` and `IVibratorService` are legacy names absent from Android 17.
Use `IStorageManager` and `IVibratorManagerService` respectively.

To refresh these JARs after rebuilding AOSP, run `./sync_platform_jars.sh` from
the project root and commit the changed JARs with the corresponding image build.
