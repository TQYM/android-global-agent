$(call inherit-product, \
    device/google/cuttlefish/vsoc_arm64_only/phone/aosp_cf.mk)
$(call inherit-product, \
    system_ext/global_agent/global_agent_product.mk)

PRODUCT_NAME := aosp_global_agent_arm64_phone
PRODUCT_DEVICE := vsoc_arm64_only
PRODUCT_MANUFACTURER := GlobalAgent
PRODUCT_MODEL := Global Agent AOSP 15 arm64 phone
