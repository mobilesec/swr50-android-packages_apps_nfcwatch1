package com.android.nfc_watch;

import android.app.KeyguardManager;
import android.content.Context;
import android.os.PowerManager;

/**
 * Helper class for determining the current screen state for NFC activities.
 */
class ScreenStateHelper {

    static final int SCREEN_STATE_UNKNOWN = 0;
    static final int SCREEN_STATE_OFF_FINAL = 1;
    static final int SCREEN_STATE_OFF = 2;
    static final int SCREEN_STATE_ON_LOCKED = 3;
    static final int SCREEN_STATE_ON_UNLOCKED = 4;

    private final PowerManager mPowerManager;
    //private final KeyguardManager mKeyguardManager;

    ScreenStateHelper(Context context) {
        //mKeyguardManager = (KeyguardManager)
        //        context.getSystemService(Context.KEYGUARD_SERVICE);
        mPowerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    }

    int checkScreenState() {
        if (!mPowerManager.isInteractive()) {
            return SCREEN_STATE_OFF_FINAL;
        //} else if (mKeyguardManager.isKeyguardLocked()) {
        //    return SCREEN_STATE_ON_LOCKED;
        } else {
            return SCREEN_STATE_ON_UNLOCKED;
        }
    }

    /**
     * For debugging only - no i18n
     */
    static String screenStateToString(int screenState) {
        switch (screenState) {
            case SCREEN_STATE_OFF_FINAL:
                return "OFF_FINAL";
            case SCREEN_STATE_OFF:
                return "OFF";
            case SCREEN_STATE_ON_LOCKED:
                return "ON_LOCKED";
            case SCREEN_STATE_ON_UNLOCKED:
                return "ON_UNLOCKED";
            default:
                return "UNKNOWN";
        }
    }
}
