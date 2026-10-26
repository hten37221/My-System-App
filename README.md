# My-System-App - Vehicle Control 3.0

The latest [UI audit fixes and verification](docs/vehicle-control/UI_AUDIT_FIXES.md) cover lighting, explicit Stop, DPAD editing and independently focusable Details actions.

[Tab navigation feedback](docs/vehicle-control/TAB_NAVIGATION_FEEDBACK.md) adds immediate selected/loading feedback and prevents overlapping navigation requests while the next group is rendered.

Privileged Android Automotive application for viewing and adjusting vehicle settings through an interactive vehicle model. The app retains `com.android.mysystemapp/.MainActivity` and platform signing to update the application in the existing image.

The detailed exterior and projection math originate in Automotive-App. Capability discovery and vehicle controls originate in My-System-App/Car-Settings. The simulator's local vehicle state and cosmetic customizer are not used.

## Vehicle settings

- Doors, locks, child locks, windows, roof glass and mirrors with independent area selection.
- Five seats, support/headrest adjustments, memory, occupancy, belts and steering adjustments.
- Zoned HVAC, temperature, airflow, recirculation, defrost, heating and ventilation.
- Exterior/interior lighting and driver assistance, including existing vendor ADAS properties.
- Native Valet Mode. Drive/Pet explicitly show unsupported on this image.

All original 90 control definitions / 89 property IDs are retained. Editors use discovered access, area, range and enum metadata. Confirmed observations drive the model. Writes and gestures obey UXR.

Tap a component to select its controls. Drag to orbit, pinch to zoom or double tap to reset. Camera presets and the area list support alternative selection. Camera/cabin reveal only affect display.

## Build and device verification

Use the existing toolchain and local platform keystore. Signing secrets remain outside Git. The connected image must already contain the privileged, platform-signed package; installing an ordinary debug key build does not grant Car permissions.

```powershell
.\scripts\verify_automotive_avd.ps1 -Serial emulator-5554
```

The verifier builds both APKs, runs unit tests, checks the image/app signing certificates, installs the current artifacts, checks system/privileged flags and Valet grants, runs all app instrumentation classes and captures evidence. It rejects skipped tests. `-SkipBuild` and `-SkipInstall` reuse artifacts; the installed APK hashes must still match.

Device verification targets only `emulator-5554`. The matrix reads, writes, checks callbacks/readback and restores readable/writable values per property/area. Write-only memory actions are acknowledged separately: there is no readable memory-slot snapshot to restore. Tests also restore driving-state emulation. The script never changes VHAL configuration or the system image.

See [architecture](docs/vehicle-control/REBUILD_IMPLEMENTATION.md), [verification instructions](docs/vehicle-control/VERIFICATION.md), [acceptance report](docs/vehicle-control/REBUILD_VERIFICATION_REPORT.md) and [property catalog](vehicle/catalog/vehicle_property_catalog.json). Local APKs, reports, screenshots, video and traces belong under `build/vehicle-evidence/`; evidence must identify the tested APK and device. Older Settings-shell documents describe the source implementation, not the Vehicle Control 3.0 app shell.
