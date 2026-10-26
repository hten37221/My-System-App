$(call inherit-product, device/generic/car/sdk_car_x86_64.mk)

PRODUCT_NAME := sdk_car_mysystemapp_x86_64
PRODUCT_MODEL := Car with MySystemApp on x86_64 emulator

PRODUCT_PACKAGES += \
    MySystemApp \
    MySystemAppVehicleControlVhalConfig
