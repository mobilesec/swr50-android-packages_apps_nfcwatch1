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

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.nfc.BeamShareData;
import android.nfc.IAppCallback;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * Interface to listen for P2P events.
 * All callbacks are made from the UI thread.
 */
interface P2pEventListener {
    /**
     * Indicates the user has expressed an intent to share
     * over NFC, but a remote device has not come into range
     * yet. Prompt the user to NFC tap.
     */
    public void onP2pNfcTapRequested();

    /**
     * Indicates the user has expressed an intent to share over
     * NFC, but the link hasn't come up yet and we no longer
     * want to wait for it
     */
    public void onP2pTimeoutWaitingForLink();

    /**
     * Indicates a P2P device is in range.
     * <p>onP2pInRange() and onP2pOutOfRange() will always be called
     * alternately.
     */
    public void onP2pInRange();

    /**
     * Called when a NDEF payload is prepared to send, and confirmation is
     * required. Call Callback.onP2pSendConfirmed() to make the confirmation.
     */
    public void onP2pSendConfirmationRequested();

    /**
     * Called to indicate a send was successful.
     */
    public void onP2pSendComplete();

    /**
     *
     * Called to indicate the link has broken while we were trying to send
     * a message. We'll start a debounce timer for the user to get the devices
     * back together. UI may show a hint to achieve that
     */
    public void onP2pSendDebounce();

    /**
     * Called to indicate a link has come back up after being temporarily
     * broken, and sending is resuming
     */
    public void onP2pResumeSend();

    /**
     * Called to indicate the remote device does not support connection handover
     */
    public void onP2pHandoverNotSupported();

    /**
     * Called to indicate a receive was successful.
     */
    public void onP2pReceiveComplete(boolean playSound);

    /**
     * Indicates the P2P device went out of range.
     */
    public void onP2pOutOfRange();

    public interface Callback {
        public void onP2pSendConfirmed();
        public void onP2pCanceled();
    }
}

/**
 * Manages sending and receiving NDEF message over LLCP link.
 * Does simple debouncing of the LLCP link - so that even if the link
 * drops and returns the user does not know.
 */
class P2pLinkManager implements Handler.Callback, P2pEventListener.Callback {
    static final String TAG = "NfcP2pLinkManager";
    static final boolean DBG = true;

    /** Include this constant as a meta-data entry in the manifest
     *  of an application to disable beaming the market/AAR link, like this:
     *  <pre>{@code
     *  <application ...>
     *      <meta-data android:name="android.nfc.disable_beam_default"
     *          android:value="true" />
     *  </application>
     *  }</pre>
     */
    static final String DISABLE_BEAM_DEFAULT = "android.nfc.disable_beam_default";

    // TODO dynamically assign SAP values
    static final int NDEFPUSH_SAP = 0x10;
    static final int HANDOVER_SAP = 0x14;

    static final int LINK_FIRST_PDU_LIMIT_MS = 200;
    static final int LINK_NOTHING_TO_SEND_DEBOUNCE_MS = 750;
    static final int LINK_SEND_PENDING_DEBOUNCE_MS = 3000;
    static final int LINK_SEND_CONFIRMED_DEBOUNCE_MS = 5000;
    static final int LINK_SEND_COMPLETE_DEBOUNCE_MS = 250;
    static final int LINK_SEND_CANCELED_DEBOUNCE_MS = 250;

    // The amount of time we wait for the link to come up
    // after a user has manually invoked Beam.
    static final int WAIT_FOR_LINK_TIMEOUT_MS = 10000;

    static final int MSG_DEBOUNCE_TIMEOUT = 1;
    static final int MSG_RECEIVE_COMPLETE = 2;
    static final int MSG_RECEIVE_HANDOVER = 3;
    static final int MSG_SEND_COMPLETE = 4;
    static final int MSG_START_ECHOSERVER = 5;
    static final int MSG_STOP_ECHOSERVER = 6;
    static final int MSG_HANDOVER_NOT_SUPPORTED = 7;
    static final int MSG_SHOW_CONFIRMATION_UI = 8;
    static final int MSG_WAIT_FOR_LINK_TIMEOUT = 9;

    // values for mLinkState
    static final int LINK_STATE_DOWN = 1;
    static final int LINK_STATE_WAITING_PDU = 2;
    static final int LINK_STATE_UP = 3;
    static final int LINK_STATE_DEBOUNCE = 4;

    // values for mSendState
    static final int SEND_STATE_NOTHING_TO_SEND = 1;
    static final int SEND_STATE_NEED_CONFIRMATION = 2;
    static final int SEND_STATE_PENDING = 3;
    static final int SEND_STATE_SENDING = 4;
    static final int SEND_STATE_COMPLETE = 5;
    static final int SEND_STATE_CANCELED = 6;

    // return values for doSnepProtocol
    static final int SNEP_SUCCESS = 0;
    static final int SNEP_FAILURE = 1;

    // return values for doHandover
    static final int HANDOVER_SUCCESS = 0;
    static final int HANDOVER_FAILURE = 1;
    static final int HANDOVER_UNSUPPORTED = 2;

    final Context mContext;
    final P2pEventListener mEventListener;
    final Handler mHandler;
    final ForegroundUtils mForegroundUtils;

    final int mDefaultMiu;
    final int mDefaultRwSize;

    // Locked on NdefP2pManager.this
    PackageManager mPackageManager;
    int mLinkState;
    int mSendState;  // valid during LINK_STATE_UP or LINK_STATE_DEBOUNCE
    boolean mIsSendEnabled;
    boolean mIsReceiveEnabled;
    NdefMessage mMessageToSend;  // not valid in SEND_STATE_NOTHING_TO_SEND
    Uri[] mUrisToSend;  // not valid in SEND_STATE_NOTHING_TO_SEND
    int mSendFlags; // not valid in SEND_STATE_NOTHING_TO_SEND
    IAppCallback mCallbackNdef;
    int mNdefCallbackUid;
    SendTask mSendTask;
    SharedPreferences mPrefs;
    ConnectTask mConnectTask;
    boolean mLlcpServicesConnected;
    boolean mLlcpConnectDelayed;
    long mLastLlcpActivationTime;

    public P2pLinkManager(Context context, Object handoverManager, int defaultMiu,
            int defaultRwSize) {
        mPackageManager = context.getPackageManager();
        mContext = context;
        mEventListener = new P2pEventManager(context, this);
        mHandler = new Handler(this);
        mLinkState = LINK_STATE_DOWN;
        mSendState = SEND_STATE_NOTHING_TO_SEND;
        mIsSendEnabled = false;
        mIsReceiveEnabled = false;
        mPrefs = context.getSharedPreferences(NfcService.PREF, Context.MODE_PRIVATE);
        mDefaultMiu = defaultMiu;
        mDefaultRwSize = defaultRwSize;
        mLlcpServicesConnected = false;
        mNdefCallbackUid = -1;
        mForegroundUtils = ForegroundUtils.getInstance();
     }

    /**
     * May be called from any thread.
     * Assumes that NFC is already on if any parameter is true.
     */
    public void enableDisable(boolean sendEnable, boolean receiveEnable) {
        synchronized (this) {
            if (!mIsReceiveEnabled && receiveEnable) {
            } else if (mIsReceiveEnabled && !receiveEnable) {
                if (DBG) Log.d(TAG, "enableDisable: llcp deactivate");
                onLlcpDeactivated ();
            }
            mIsSendEnabled = sendEnable;
            mIsReceiveEnabled = receiveEnable;
        }
    }

    /**
     * May be called from any thread.
     * @return whether the LLCP link is in an active or debounce state
     */
    public boolean isLlcpActive() {
        synchronized (this) {
            return mLinkState != LINK_STATE_DOWN;
        }
    }

    /**
     * Set NDEF callback for sending.
     * May be called from any thread.
     * NDEF callbacks may be set at any time (even if NFC is
     * currently off or P2P send is currently off). They will become
     * active as soon as P2P send is enabled.
     */
    public void setNdefCallback(IAppCallback callbackNdef, int callingUid) {
        synchronized (this) {
            mCallbackNdef = callbackNdef;
            mNdefCallbackUid = callingUid;
        }
    }


    public void onManualBeamInvoke(BeamShareData shareData) {
        synchronized (P2pLinkManager.this)    {
            if (mLinkState != LINK_STATE_DOWN) {
                return;
            }
            if (mForegroundUtils.getForegroundUids().contains(mNdefCallbackUid)) {
                // Try to get data from the registered NDEF callback
                prepareMessageToSend(false);
            }
            if (mMessageToSend == null && mUrisToSend == null && shareData != null) {
                // No data from the NDEF callback, get data from ShareData
                if (shareData.uris != null) {
                    mUrisToSend = shareData.uris;
                } else if (shareData.ndefMessage != null) {
                    mMessageToSend = shareData.ndefMessage;
                }
            }
            if (mMessageToSend != null ||
                    (mUrisToSend != null && false)) {
                mSendState = SEND_STATE_PENDING;
                mEventListener.onP2pNfcTapRequested();
                scheduleTimeoutLocked(MSG_WAIT_FOR_LINK_TIMEOUT, WAIT_FOR_LINK_TIMEOUT_MS);
            }
        }
    }

    /**
     * Must be called on UI Thread.
     */
    public void onLlcpActivated() {
        Log.i(TAG, "LLCP activated");

        synchronized (P2pLinkManager.this) {
            mLastLlcpActivationTime = SystemClock.elapsedRealtime();
            mLlcpConnectDelayed = false;
            switch (mLinkState) {
                case LINK_STATE_DOWN:
                    if (DBG) Log.d(TAG, "onP2pInRange()");
                    mLinkState = LINK_STATE_WAITING_PDU;
                    mEventListener.onP2pInRange();
                    if (mSendState == SEND_STATE_PENDING) {
                        if (DBG) Log.d(TAG, "Sending pending data.");
                        mHandler.removeMessages(MSG_WAIT_FOR_LINK_TIMEOUT);
                        mSendState = SEND_STATE_SENDING;
                        onP2pSendConfirmed(false);
                    } else {
                        mSendState = SEND_STATE_NOTHING_TO_SEND;
                        prepareMessageToSend(true);
                        if (mMessageToSend != null ||
                                (mUrisToSend != null && false)) {
                            // Ideally we would delay showing the Beam animation until
                            // we know for certain the other side has SNEP/handover.
                            // Unfortunately, the NXP LLCP implementation has a bug that
                            // delays the first SYMM for 750ms if it is the initiator.
                            // This will cause our SNEP connect to be delayed as well,
                            // and the animation will be delayed for about a second.
                            // Alternatively, we could have used WKS as a hint to start
                            // the animation, but we are only correctly setting the WKS
                            // since Jelly Bean.
                            if ((mSendFlags & NfcAdapter.FLAG_NDEF_PUSH_NO_CONFIRM) != 0) {
                                mSendState = SEND_STATE_SENDING;
                                onP2pSendConfirmed(false);
                            } else {
                                mSendState = SEND_STATE_NEED_CONFIRMATION;
                                if (DBG) Log.d(TAG, "onP2pSendConfirmationRequested()");
                                mEventListener.onP2pSendConfirmationRequested();
                            }
                        }
                    }
                    break;
                case LINK_STATE_WAITING_PDU:
                    if (DBG) Log.d(TAG, "Unexpected onLlcpActivated() in LINK_STATE_WAITING_PDU");
                    return;
                case LINK_STATE_UP:
                    if (DBG) Log.d(TAG, "Duplicate onLlcpActivated()");
                    return;
                case LINK_STATE_DEBOUNCE:
                    if (mSendState == SEND_STATE_SENDING) {
                        // Immediately connect and try to send again
                        mLinkState = LINK_STATE_UP;
                        connectLlcpServices();
                    } else {
                        mLinkState = LINK_STATE_WAITING_PDU;
                    }
                    mHandler.removeMessages(MSG_DEBOUNCE_TIMEOUT);
                    break;
            }
        }
    }

    /**
     * Must be called on UI Thread.
     */
    public void onLlcpFirstPacketReceived() {
        synchronized (P2pLinkManager.this) {
            long totalTime = SystemClock.elapsedRealtime() - mLastLlcpActivationTime;
            if (DBG) Log.d(TAG, "Took " + Long.toString(totalTime) + " to get first LLCP PDU");
            switch (mLinkState) {
                case LINK_STATE_UP:
                    if (DBG) Log.d(TAG, "Dropping first LLCP packet received");
                    break;
                case LINK_STATE_DOWN:
                case LINK_STATE_DEBOUNCE:
                   Log.e(TAG, "Unexpected first LLCP packet received");
                   break;
                case LINK_STATE_WAITING_PDU:
                    mLinkState = LINK_STATE_UP;
                    if (mSendState == SEND_STATE_NOTHING_TO_SEND)
                        break;
                    if (totalTime <  LINK_FIRST_PDU_LIMIT_MS || mSendState == SEND_STATE_SENDING) {
                        connectLlcpServices();
                    } else {
                        mLlcpConnectDelayed = true;
                    }
                    break;
            }
        }
    }

    public void onUserSwitched(int userId) {
        // Update the cached package manager in case of user switch
        synchronized (P2pLinkManager.this) {
        }
    }

    void prepareMessageToSend(boolean generatePlayLink) {
        synchronized (P2pLinkManager.this) {
            mMessageToSend = null;
            mUrisToSend = null;
            if (!mIsSendEnabled) {
                return;
            }

            List<Integer> foregroundUids = mForegroundUtils.getForegroundUids();
            if (foregroundUids.isEmpty()) {
                Log.e(TAG, "Could not determine foreground UID.");
                return;
            }

            if (mCallbackNdef != null) {
                if (foregroundUids.contains(mNdefCallbackUid)) {
                    try {
                        BeamShareData shareData = mCallbackNdef.createBeamShareData();
                        mMessageToSend = shareData.ndefMessage;
                        mUrisToSend = shareData.uris;
                        mSendFlags = shareData.flags;
                        return;
                    } catch (Exception e) {
                        Log.e(TAG, "Failed NDEF callback: " + e.getMessage());
                    }
                } else {
                    // This is not necessarily an error - we no longer unset callbacks from
                    // the app process itself (to prevent IPC calls on every pause).
                    // Hence it may simply be a stale callback.
                    if (DBG) Log.d(TAG, "Last registered callback is not running in the foreground.");
                }
            }

            // fall back to default NDEF for the foreground activity, unless the
            // application disabled this explicitly in their manifest.
            String[] pkgs = mPackageManager.getPackagesForUid(foregroundUids.get(0));
            if (pkgs != null && pkgs.length >= 1) {
                if (!generatePlayLink || beamDefaultDisabled(pkgs[0])
                        || isManagedOrBeamDisabled(foregroundUids.get(0))) {
                    if (DBG) Log.d(TAG, "Disabling default Beam behavior");
                    mMessageToSend = null;
                    mUrisToSend = null;
                } else {
                    mMessageToSend = createDefaultNdef(pkgs[0]);
                    mUrisToSend = null;
                }
            }

            if (DBG) Log.d(TAG, "mMessageToSend = " + mMessageToSend);
            if (DBG) Log.d(TAG, "mUrisToSend = " + mUrisToSend);
        }
    }

    private boolean isManagedOrBeamDisabled(int uid) {
        return false;
    }

    boolean beamDefaultDisabled(String pkgName) {
        try {
            ApplicationInfo ai = mPackageManager.getApplicationInfo(pkgName,
                    PackageManager.GET_META_DATA);
            if (ai == null || ai.metaData == null) {
                return false;
            }
            return ai.metaData.getBoolean(DISABLE_BEAM_DEFAULT);
        } catch (NameNotFoundException e) {
            return false;
        }
    }

    NdefMessage createDefaultNdef(String pkgName) {
        NdefRecord appUri = NdefRecord.createUri(Uri.parse(
                "http://play.google.com/store/apps/details?id=" + pkgName + "&feature=beam"));
        NdefRecord appRecord = NdefRecord.createApplicationRecord(pkgName);
        return new NdefMessage(new NdefRecord[] { appUri, appRecord });
    }

    void disconnectLlcpServices() {
        synchronized (this) {
            if (mConnectTask != null) {
                mConnectTask.cancel(true);
                mConnectTask = null;
            }
            mLlcpServicesConnected = false;
        }
    }

    /**
     * Must be called on UI Thread.
     */
    public void onLlcpDeactivated() {
        Log.i(TAG, "LLCP deactivated.");
        synchronized (this) {
            switch (mLinkState) {
                case LINK_STATE_DOWN:
                case LINK_STATE_DEBOUNCE:
                    Log.i(TAG, "Duplicate onLlcpDectivated()");
                    break;
                case LINK_STATE_WAITING_PDU:
                case LINK_STATE_UP:
                    // Debounce
                    mLinkState = LINK_STATE_DEBOUNCE;
                    int debounceTimeout = 0;
                    switch (mSendState) {
                        case SEND_STATE_NOTHING_TO_SEND:
                            debounceTimeout = 0;
                            break;
                        case SEND_STATE_NEED_CONFIRMATION:
                            debounceTimeout = LINK_SEND_PENDING_DEBOUNCE_MS;
                            break;
                        case SEND_STATE_SENDING:
                            debounceTimeout = LINK_SEND_CONFIRMED_DEBOUNCE_MS;
                            break;
                        case SEND_STATE_COMPLETE:
                            debounceTimeout = LINK_SEND_COMPLETE_DEBOUNCE_MS;
                            break;
                        case SEND_STATE_CANCELED:
                            debounceTimeout = LINK_SEND_CANCELED_DEBOUNCE_MS;
                    }
                    scheduleTimeoutLocked(MSG_DEBOUNCE_TIMEOUT, debounceTimeout);
                    if (mSendState == SEND_STATE_SENDING) {
                        Log.e(TAG, "onP2pSendDebounce()");
                        mEventListener.onP2pSendDebounce();
                    }
                    cancelSendNdefMessage();
                    disconnectLlcpServices();
                    break;
            }
         }
     }

    void onHandoverUnsupported() {
        mHandler.sendEmptyMessage(MSG_HANDOVER_NOT_SUPPORTED);
    }

    void onSendComplete(NdefMessage msg, long elapsedRealtime) {
        // Make callbacks on UI thread
        mHandler.sendEmptyMessage(MSG_SEND_COMPLETE);
    }

    void sendNdefMessage() {
        synchronized (this) {
            cancelSendNdefMessage();
            mSendTask = new SendTask();
            mSendTask.execute();
        }
    }

    void cancelSendNdefMessage() {
        synchronized (P2pLinkManager.this) {
            if (mSendTask != null) {
                mSendTask.cancel(true);
            }
        }
    }

    void connectLlcpServices() {
        synchronized (P2pLinkManager.this) {
            if (mConnectTask != null) {
                Log.e(TAG, "Still had a reference to mConnectTask!");
            }
            mConnectTask = new ConnectTask();
            mConnectTask.execute();
        }
    }

    // Must be called on UI-thread
    void onLlcpServicesConnected() {
        if (DBG) Log.d(TAG, "onLlcpServicesConnected");
        synchronized (P2pLinkManager.this) {
            if (mLinkState != LINK_STATE_UP) {
                return;
            }
            mLlcpServicesConnected = true;
            if (mSendState == SEND_STATE_SENDING) {
                // FIXME Keep state to make sure this is only called when in debounce
                // and remove logic in P2pEventManager to keep track.
                mEventListener.onP2pResumeSend();
                sendNdefMessage();
            } else {
                // User still needs to confirm, or we may have received something already.
            }
        }
    }

    final class ConnectTask extends AsyncTask<Void, Void, Boolean> {
        @Override
        protected void onPostExecute(Boolean result)  {
            if (isCancelled()) {
                if (DBG) Log.d(TAG, "ConnectTask was cancelled");
                return;
            }
            if (result) {
                onLlcpServicesConnected();
            } else {
                Log.e(TAG, "Could not connect required NFC transports");
            }
        }

        @Override
        protected Boolean doInBackground(Void... params) {
            synchronized (P2pLinkManager.this) {
                if (isCancelled()) {
                    // Cancelled by onLlcpDeactivated on UI thread
                    return false;
                } else {
                    // Once assigned, these are the responsibility of
                    // the code on the UI thread to release - typically
                    // through onLlcpDeactivated().
                    return false;
                }
            }
        }
    };

    final class SendTask extends AsyncTask<Void, Void, Void> {

        int doHandover(Uri[] uris) throws IOException {
            return HANDOVER_UNSUPPORTED;
        }

        int doSnepProtocol(NdefMessage msg) throws IOException {
            return SNEP_FAILURE;
        }

        @Override
        public Void doInBackground(Void... args) {
            return null;
        }
    };


    void onReceiveHandover() {
        mHandler.obtainMessage(MSG_RECEIVE_HANDOVER).sendToTarget();
    }

    void onReceiveComplete(NdefMessage msg) {
        // Make callbacks on UI thread
        mHandler.obtainMessage(MSG_RECEIVE_COMPLETE, msg).sendToTarget();
    }

    @Override
    public boolean handleMessage(Message msg) {
        switch (msg.what) {
            case MSG_START_ECHOSERVER:
                synchronized (this) {
                    break;
                }
            case MSG_STOP_ECHOSERVER:
                synchronized (this) {
                    break;
                }
            case MSG_WAIT_FOR_LINK_TIMEOUT:
                synchronized (this) {
                    // User wanted to send something but no link
                    // came up. Just cancel the send
                    mSendState = SEND_STATE_NOTHING_TO_SEND;
                    mEventListener.onP2pTimeoutWaitingForLink();
                }
                break;
            case MSG_DEBOUNCE_TIMEOUT:
                synchronized (this) {
                    if (mLinkState != LINK_STATE_DEBOUNCE) {
                        break;
                    }
                    if (DBG) Log.d(TAG, "Debounce timeout");
                    mLinkState = LINK_STATE_DOWN;
                    mSendState = SEND_STATE_NOTHING_TO_SEND;
                    mMessageToSend = null;
                    mUrisToSend = null;
                    if (DBG) Log.d(TAG, "onP2pOutOfRange()");
                    mEventListener.onP2pOutOfRange();
                }
                break;
            case MSG_RECEIVE_HANDOVER:
                // We're going to do a handover request
                synchronized (this) {
                    if (mLinkState == LINK_STATE_DOWN) {
                        break;
                    }
                    if (mSendState == SEND_STATE_SENDING) {
                        cancelSendNdefMessage();
                    }
                    mSendState = SEND_STATE_NOTHING_TO_SEND;
                    if (DBG) Log.d(TAG, "onP2pReceiveComplete()");
                    mEventListener.onP2pReceiveComplete(false);
                }
                break;
            case MSG_RECEIVE_COMPLETE:
                NdefMessage m = (NdefMessage) msg.obj;
                synchronized (this) {
                    if (mLinkState == LINK_STATE_DOWN) {
                        break;
                    }
                    if (mSendState == SEND_STATE_SENDING) {
                        cancelSendNdefMessage();
                    }
                    mSendState = SEND_STATE_NOTHING_TO_SEND;
                    if (DBG) Log.d(TAG, "onP2pReceiveComplete()");
                    mEventListener.onP2pReceiveComplete(true);
                    NfcService.getInstance().sendMockNdefTag(m);
                }
                break;
            case MSG_HANDOVER_NOT_SUPPORTED:
                synchronized (P2pLinkManager.this) {
                    mSendTask = null;

                    if (mLinkState == LINK_STATE_DOWN || mSendState != SEND_STATE_SENDING) {
                        break;
                    }
                    mSendState = SEND_STATE_NOTHING_TO_SEND;
                    if (DBG) Log.d(TAG, "onP2pHandoverNotSupported()");
                    mEventListener.onP2pHandoverNotSupported();
                }
                break;
            case MSG_SEND_COMPLETE:
                synchronized (P2pLinkManager.this) {
                    mSendTask = null;

                    if (mLinkState == LINK_STATE_DOWN || mSendState != SEND_STATE_SENDING) {
                        break;
                    }
                    mSendState = SEND_STATE_COMPLETE;
                    mHandler.removeMessages(MSG_DEBOUNCE_TIMEOUT);
                    if (DBG) Log.d(TAG, "onP2pSendComplete()");
                    mEventListener.onP2pSendComplete();
                    if (mCallbackNdef != null) {
                        try {
                            mCallbackNdef.onNdefPushComplete();
                        } catch (Exception e) {
                            Log.e(TAG, "Failed NDEF completed callback: " + e.getMessage());
                        }
                    }
                }
                break;
        }
        return true;
    }

    int getMessageSize(NdefMessage msg) {
        if (msg != null) {
            return msg.toByteArray().length;
        } else {
            return 0;
        }
    }

    int getMessageTnf(NdefMessage msg) {
        if (msg == null) {
            return NdefRecord.TNF_EMPTY;
        }
        NdefRecord records[] = msg.getRecords();
        if (records == null || records.length == 0) {
            return NdefRecord.TNF_EMPTY;
        }
        return records[0].getTnf();
    }

    String getMessageType(NdefMessage msg) {
        if (msg == null) {
            return "null";
        }
        NdefRecord records[] = msg.getRecords();
        if (records == null || records.length == 0) {
            return "null";
        }
        NdefRecord record = records[0];
        switch (record.getTnf()) {
            case NdefRecord.TNF_ABSOLUTE_URI:
                // The actual URI is in the type field, don't log it
                return "uri";
            case NdefRecord.TNF_EXTERNAL_TYPE:
            case NdefRecord.TNF_MIME_MEDIA:
            case NdefRecord.TNF_WELL_KNOWN:
                return new String(record.getType(), StandardCharsets.UTF_8);
            default:
                return "unknown";
        }
    }

    int getMessageAarPresent(NdefMessage msg) {
        if (msg == null) {
            return 0;
        }
        NdefRecord records[] = msg.getRecords();
        if (records == null) {
            return 0;
        }
        for (NdefRecord record : records) {
            if (record.getTnf() == NdefRecord.TNF_EXTERNAL_TYPE &&
                    Arrays.equals(NdefRecord.RTD_ANDROID_APP, record.getType())) {
                return 1;
            }
        }
        return 0;
    }

    @Override
    public void onP2pSendConfirmed() {
        onP2pSendConfirmed(true);
    }

    private void onP2pSendConfirmed(boolean requireConfirmation) {
        if (DBG) Log.d(TAG, "onP2pSendConfirmed()");
        synchronized (this) {
            if (mLinkState == LINK_STATE_DOWN || (requireConfirmation
                    && mSendState != SEND_STATE_NEED_CONFIRMATION)) {
                return;
            }
            mSendState = SEND_STATE_SENDING;
            if (mLinkState == LINK_STATE_WAITING_PDU) {
                // We could decide to wait for the first PDU here; but
                // that makes us vulnerable to cases where for some reason
                // this event is not propagated up by the stack. Instead,
                // try to connect now.
                mLinkState = LINK_STATE_UP;
                connectLlcpServices();
            } else if (mLinkState == LINK_STATE_UP && mLlcpServicesConnected) {
                sendNdefMessage();
            } else if (mLinkState == LINK_STATE_UP && mLlcpConnectDelayed) {
                // Connect was delayed to interop with pre-MR2 stacks; send connect now.
                connectLlcpServices();
            } else if (mLinkState == LINK_STATE_DEBOUNCE) {
                // Restart debounce timeout and tell user to tap again
                scheduleTimeoutLocked(MSG_DEBOUNCE_TIMEOUT, LINK_SEND_CONFIRMED_DEBOUNCE_MS);
                mEventListener.onP2pSendDebounce();
            }
        }
    }


    @Override
    public void onP2pCanceled() {
        synchronized (this) {
            mSendState = SEND_STATE_CANCELED;
            if (mLinkState == LINK_STATE_DOWN) {
                // If we were waiting for the link to come up, stop doing so
                mHandler.removeMessages(MSG_WAIT_FOR_LINK_TIMEOUT);
            } else if (mLinkState == LINK_STATE_DEBOUNCE) {
                // We're in debounce state so link is down. Reschedule debounce
                // timeout to occur sooner, we don't want to wait any longer.
                scheduleTimeoutLocked(MSG_DEBOUNCE_TIMEOUT, LINK_SEND_CANCELED_DEBOUNCE_MS);
            } else {
                // Link is up, nothing else to do but wait for link to go down
            }
        }
    }

    void scheduleTimeoutLocked(int what, int timeout) {
        // Cancel any outstanding debounce timeouts.
        mHandler.removeMessages(what);
        mHandler.sendEmptyMessageDelayed(what, timeout);
    }

    static String sendStateToString(int state) {
        switch (state) {
            case SEND_STATE_NOTHING_TO_SEND:
                return "SEND_STATE_NOTHING_TO_SEND";
            case SEND_STATE_NEED_CONFIRMATION:
                return "SEND_STATE_NEED_CONFIRMATION";
            case SEND_STATE_SENDING:
                return "SEND_STATE_SENDING";
            case SEND_STATE_COMPLETE:
                return "SEND_STATE_COMPLETE";
            case SEND_STATE_CANCELED:
                return "SEND_STATE_CANCELED";
            default:
                return "<error>";
        }
    }

    static String linkStateToString(int state) {
        switch (state) {
            case LINK_STATE_DOWN:
                return "LINK_STATE_DOWN";
            case LINK_STATE_DEBOUNCE:
                return "LINK_STATE_DEBOUNCE";
            case LINK_STATE_UP:
                return "LINK_STATE_UP";
            case LINK_STATE_WAITING_PDU:
                return "LINK_STATE_WAITING_PDU";
            default:
                return "<error>";
        }
    }

    void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        synchronized (this) {
            pw.println("mIsSendEnabled=" + mIsSendEnabled);
            pw.println("mIsReceiveEnabled=" + mIsReceiveEnabled);
            pw.println("mLinkState=" + linkStateToString(mLinkState));
            pw.println("mSendState=" + sendStateToString(mSendState));

            pw.println("mCallbackNdef=" + mCallbackNdef);
            pw.println("mMessageToSend=" + mMessageToSend);
            pw.println("mUrisToSend=" + mUrisToSend);
        }
    }
}
