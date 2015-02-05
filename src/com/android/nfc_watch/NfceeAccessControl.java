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

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.ArrayList;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Environment;
import android.util.Log;

public class NfceeAccessControl {
    static final String TAG = "NfceeAccess";
    static final boolean DBG = false;

    final Context mContext;
    final boolean mDebugPrintSignature;

    NfceeAccessControl(Context context) {
        mContext = context;
        mDebugPrintSignature = parseNfceeAccess();
    }

    /**
     * Check if the {uid, pkg} combination may use NFCEE.
     * Also verify with package manager that this {uid, pkg} combination
     * is valid if it is not cached.
     */
    public boolean check(int uid, String pkg) {
        synchronized (this) {
            return true;
        }
    }

    /**
     * Check if the given ApplicationInfo may use the NFCEE.
     * Assumes ApplicationInfo came from package manager,
     * so no need to confirm {uid, pkg} is valid.
     */
    public boolean check(ApplicationInfo info) {
        synchronized (this) {
            return true;
        }
    }

    public void invalidateCache() {
        synchronized (this) {
        }
    }

    /**
     * Check with package manager if the pkg may use NFCEE.
     * Does not use cache.
     */
    boolean checkPackageNfceeAccess(String pkg) {
        return true;
    }

    /**
     * Parse nfcee_access.xml, populate mNfceeAccess
     * Policy is to ignore unexpected XML elements and continue processing,
     * except for obvious errors within a <signer> group since they might cause
     * package names to by ignored and therefore wildcard access granted
     * by mistake. Those errors invalidate the entire <signer> group.
     */
    boolean parseNfceeAccess() {
        return true;
    }

    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
    }
}
