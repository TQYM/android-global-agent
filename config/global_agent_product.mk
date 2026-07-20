PRODUCT_SOONG_NAMESPACES += system_ext/global_agent

PRODUCT_PACKAGES += \
    global-agentd \
    GlobalAgentBridge \
    GlobalAgentModelGateway \
    privapp-permissions-com.example.globalagent

SYSTEM_EXT_PRIVATE_SEPOLICY_DIRS += \
    system_ext/global_agent/android/sepolicy
