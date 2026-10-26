# Phone emulator product that installs the platform-signed MySystemApp.
$(call inherit-product, device/generic/goldfish/64bitonly/product/sdk_phone64_x86_64.mk)

PRODUCT_NAME := sdk_phone_mysystemapp_x86_64
PRODUCT_MODEL := Android Phone with MySystemApp on x86_64 emulator

PRODUCT_PACKAGES += \
    MySystemApp
