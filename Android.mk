LOCAL_PATH:= $(call my-dir)

########################################
# NCI Configuration
########################################
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := optional

LOCAL_SRC_FILES := \
        $(call all-java-files-under, src)

LOCAL_SRC_FILES += \
        $(call all-java-files-under, nci)

LOCAL_PACKAGE_NAME := NfcNciWatch1
#LOCAL_OVERRIDES_PACKAGES := NfcWatch1
LOCAL_CERTIFICATE := platform

LOCAL_JNI_SHARED_LIBRARIES := libnfc_nci_jni

LOCAL_PROGUARD_ENABLED := disabled

include $(BUILD_PACKAGE)

include $(call all-makefiles-under,$(LOCAL_PATH))
