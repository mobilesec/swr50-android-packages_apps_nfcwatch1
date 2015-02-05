/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.nfc_watch;

import android.app.NotificationManager;
import android.content.Context;
import android.content.res.Configuration;
import android.os.Vibrator;

/**
 * Manages vibration, sound and animation for P2P events.
 */
public class P2pEventManager implements P2pEventListener {
    static final String TAG = "NfcP2pEventManager";
    static final boolean DBG = true;

    static final long[] VIBRATION_PATTERN = {0, 100, 10000};

    final Context mContext;
    final NfcService mNfcService;
    final P2pEventListener.Callback mCallback;
    final Vibrator mVibrator;
    final NotificationManager mNotificationManager;

    // only used on UI thread
    boolean mSending;
    boolean mNdefSent;
    boolean mNdefReceived;
    boolean mInDebounce;

    public P2pEventManager(Context context, P2pEventListener.Callback callback) {
        mNfcService = NfcService.getInstance();
        mContext = context;
        mCallback = callback;
        mVibrator = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        mNotificationManager = (NotificationManager) mContext.getSystemService(
                Context.NOTIFICATION_SERVICE);

        mSending = false;
        final int uiModeType = mContext.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_TYPE_MASK;
        if (uiModeType == Configuration.UI_MODE_TYPE_APPLIANCE) {
            // "Appliances" don't intrinsically have a way of confirming this, so we
            // don't use the UI and just autoconfirm where necessary.
            // Don't instantiate SendUi or else we'll use memory and never reclaim it.
        } else {
        }
    }

    @Override
    public void onP2pInRange() {
        mNfcService.playSound(NfcService.SOUND_START);
        mNdefSent = false;
        mNdefReceived = false;
        mInDebounce = false;

        mVibrator.vibrate(VIBRATION_PATTERN, -1);
    }

    @Override
    public void onP2pNfcTapRequested() {
        mNfcService.playSound(NfcService.SOUND_START);
        mNdefSent = false;
        mNdefReceived = false;
        mInDebounce = false;

        mVibrator.vibrate(VIBRATION_PATTERN, -1);
    }

    @Override
    public void onP2pTimeoutWaitingForLink() {
    }

    @Override
    public void onP2pSendConfirmationRequested() {
        mCallback.onP2pSendConfirmed();
    }

    @Override
    public void onP2pSendComplete() {
        mNfcService.playSound(NfcService.SOUND_END);
        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        mSending = false;
        mNdefSent = true;
    }

    @Override
    public void onP2pHandoverNotSupported() {
        mNfcService.playSound(NfcService.SOUND_ERROR);
        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        mSending = false;
        mNdefSent = false;
    }

    @Override
    public void onP2pReceiveComplete(boolean playSound) {
        mVibrator.vibrate(VIBRATION_PATTERN, -1);
        if (playSound) mNfcService.playSound(NfcService.SOUND_END);
        mNdefReceived = true;
    }

    @Override
    public void onP2pOutOfRange() {
        if (mSending) {
            mNfcService.playSound(NfcService.SOUND_ERROR);
            mSending = false;
        }
        mInDebounce = false;
    }

    //@Override
    public void onSendConfirmed() {
        if (!mSending) {
            mCallback.onP2pSendConfirmed();
        }
        mSending = true;

    }

    //@Override
    public void onCanceled() {
        mCallback.onP2pCanceled();
    }

    @Override
    public void onP2pSendDebounce() {
        mInDebounce = true;
        mNfcService.playSound(NfcService.SOUND_ERROR);
    }

    @Override
    public void onP2pResumeSend() {
        if (mInDebounce) {
            mVibrator.vibrate(VIBRATION_PATTERN, -1);
            mNfcService.playSound(NfcService.SOUND_START);
        }
        mInDebounce = false;
    }

}
