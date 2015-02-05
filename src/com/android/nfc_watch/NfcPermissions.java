package com.android.nfc_watch;


import android.content.Context;

public class NfcPermissions {

    /**
     * NFC ADMIN permission - only for system apps
     */
    public static final String ADMIN_PERM = "com.android.nfc_watch.permission.NFC_ADMIN";
    private static final String ADMIN_PERM_ERROR = "NFC_ADMIN permission required";

    /**
     * Regular NFC permission
     */
    public static final String NFC_PERMISSION = "com.android.nfc_watch.permission.NFC";
    private static final String NFC_PERM_ERROR = "NFC permission required";

    /**
     * HCE service binding permission
     */
    public static final String NFC_SERVICE_BIND_PERMISSION = "com.android.nfc_watch.permission.BIND_NFC_SERVICE";

    public static void validateUserId(int userId) {
        if (userId != 0) {
            throw new SecurityException("userId passed in is not the calling user.");
        }
    }

    public static void enforceAdminPermissions(Context context) {
        context.enforceCallingOrSelfPermission(ADMIN_PERM, ADMIN_PERM_ERROR);
    }


    public static void enforceUserPermissions(Context context) {
        context.enforceCallingOrSelfPermission(NFC_PERMISSION, NFC_PERM_ERROR);
    }
}
