/*
 * Copyright (c) 2010-12, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 *
 */

package com.android.internal.telephony.msim;

import java.util.HashMap;
import java.util.regex.PatternSyntaxException;
import android.content.ActivityNotFoundException;
import com.android.internal.telephony.CommandsInterface;
import com.android.internal.telephony.CommandException;
import com.android.internal.telephony.uicc.UiccController;
import com.android.internal.telephony.msim.Subscription.SubscriptionStatus;
import com.android.internal.telephony.MSimConstants;
import com.android.internal.telephony.MSimConstants.CardUnavailableReason;
import com.android.internal.telephony.CallManager;
import android.telephony.MSimTelephonyManager;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncResult;
import android.os.Handler;
import android.os.Message;
import android.os.Registrant;
import android.os.RegistrantList;
import android.os.SystemProperties;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import com.android.internal.telephony.Phone;

import android.net.ConnectivityManager;
import android.provider.Settings;
import android.content.ContentResolver;
import com.qrd.plugin.feature_query.FeatureQuery;

/**
 * Keep track of all the subscription related informations.
 * Includes:
 *  - User Preferred Subscriptions
 *  - Current Active Subscriptions
 *  - Subscription Readiness - based on the UNSOL SUB STATUS
 *  - Current DDS
 *  - SetSubscription Mode to be set only once
 * Provides the functionalities
 *  - Activate or deactivate Subscriptions
 *  - Set DDS (Designated Data Subscription)
 * Handles
 *  - UNSOL SUB STATUS changes from modem
 */
public class SubscriptionManager extends Handler {
    static final String LOG_TAG = "SubscriptionManager";

    private class SetUiccSubsParams {
        public SetUiccSubsParams(int sub, SubscriptionStatus status) {
            subId = sub;
            subStatus = status;
        }
        public int subId;      // sub id
        public SubscriptionStatus subStatus;  // Activation status - activate or deactivate?
    }

    /**
     * Class to maintain the current subscription info in SubscriptionManager.
     */
    private class PhoneSubscriptionInfo {
        public Subscription sub;  // subscription
        public boolean subReady;  // subscription readiness
        //public SubscriptionStatus subStatus;  // status
        public String cause;   // Set in case of subStatus is DEACTIVATED

        PhoneSubscriptionInfo() {
            sub = new Subscription();
            subReady = false;
            cause = null;
        }
    }
    
    public static boolean networkModeChangedBySettings = false;

    //***** Class Variables
    private static SubscriptionManager sSubscriptionManager;

    public static int NUM_SUBSCRIPTIONS = 2;

    // Number of fields in the user preferred subscription property
    private static int USER_PREF_SUB_FIELDS = 6;

    // Mode types, refer to the property set in qcril
    private static final String PROPERTY_RIL_SUBSMODE = "ril.subsmode";
    private static final String MODE_1x = "1x";
    private static final String MODE_GW = "gw";
    private static final String MODE_UNKNOWN = "unknown";

    //***** Events
    private static final int EVENT_CARD_INFO_AVAILABLE = 0;
    private static final int EVENT_CARD_INFO_NOT_AVAILABLE = 1;
    private static final int EVENT_ALL_CARD_INFO_AVAILABLE = 2;
    private static final int EVENT_SET_SUBSCRIPTION_MODE_DONE = 3;
    private static final int EVENT_SET_UICC_SUBSCRIPTION_DONE = 4;
    private static final int EVENT_SUBSCRIPTION_STATUS_CHANGED = 5;
    private static final int EVENT_SET_DATA_SUBSCRIPTION_DONE = 6;
    private static final int EVENT_CLEANUP_DATA_CONNECTION_DONE = 7;
    private static final int EVENT_ALL_DATA_DISCONNECTED = 8;
    private static final int EVENT_RADIO_ON = 9;
    private static final int EVENT_RADIO_OFF_OR_NOT_AVAILABLE = 10;
    private static final int EVENT_EMER_CALL_END = 12;
    private static final int EVENT_SET_PREFERRED_NETWORK_TYPE = 13;
    private static final int EVENT_GET_PREFERRED_NETWORK_TYPE = 14;


    // Set Subscription Return status
    public static final String SUB_ACTIVATE_SUCCESS = "ACTIVATE SUCCESS";
    public static final String SUB_ACTIVATE_FAILED = "ACTIVATE FAILED";
    public static final String SUB_ACTIVATE_NOT_SUPPORTED = "ACTIVATE NOT SUPPORTED";
    public static final String SUB_DEACTIVATE_SUCCESS = "DEACTIVATE SUCCESS";
    public static final String SUB_DEACTIVATE_FAILED = "DEACTIVATE FAILED";
    public static final String SUB_DEACTIVATE_NOT_SUPPORTED = "DEACTIVATE NOT SUPPORTED";
    public static final String SUB_NOT_CHANGED = "NO CHANGE IN SUBSCRIPTION";

    // Sub status from RIL
    private static final int SUB_STATUS_DEACTIVATED = 0;
    private static final int SUB_STATUS_ACTIVATED = 1;

    private RegistrantList[] mSubDeactivatedRegistrants;
    private RegistrantList[] mSubActivatedRegistrants;

    private Context mContext;
    private CommandsInterface[] mCi;

    // The User preferred subscription information
    private SubscriptionData mUserPrefSubs = null;
    private CardSubscriptionManager mCardSubMgr;

    private boolean mSetSubsModeRequired = true;

    private boolean[] mCardInfoAvailable = {false, false};

    private HashMap<SubscriptionId, Subscription> mActivatePending;
    private HashMap<SubscriptionId, Subscription> mDeactivatePending;

    private HashMap<SubscriptionId, PhoneSubscriptionInfo> mCurrentSubscriptions;

    private boolean mAllCardsStatusAvailable = false;

    private boolean mSetDdsRequired = true;

    private int mCurrentDds;
    private int mQueuedDds;
    private boolean mDisableDdsInProgress;

    private boolean mSetSubscriptionInProgress = false;

    //private MSimProxyManager mMSimProxyManager;

    private boolean mDataActive = false;

    private boolean[] mIsNewCard = {false, false};

    private Message mSetDdsCompleteMsg;

    private RegistrantList mSetSubscriptionRegistrants = new RegistrantList();

    private String[] mSubResult = new String [NUM_SUBSCRIPTIONS];

    private boolean[] mRadioOn = {false, false};
    
    //indicate the checked status of card SLOT
    public boolean[] mCardShouldEnable = {false, false};


    /**
     * Subscription Id
     */
    private enum SubscriptionId {
        SUB0,
        SUB1
    }

    /**
     * Get singleton instance of SubscriptionManager.
     * @param context
     * @param uiccMgr
     * @param ci
     * @return
     */
    public static SubscriptionManager getInstance(Context context,
            UiccController uiccController, CommandsInterface[] ci)
    {
        Log.d(LOG_TAG, "getInstance");
        if (sSubscriptionManager == null) {
            sSubscriptionManager = new SubscriptionManager(context, uiccController, ci);
        }
        return sSubscriptionManager;
    }

    /**
     * Get singleton instance of SubscriptionManager.
     * @return
     */
    public static SubscriptionManager getInstance() {
        return sSubscriptionManager;
    }

    /**
     * Constructor.
     * @param context
     * @param uiccManager
     * @param ci
     */
    private SubscriptionManager(Context context, UiccController uiccController,
            CommandsInterface[] ci) {
        logd("Constructor - Enter");

        mContext = context;

        // Read the user preferred subscriptions from the system property
        getUserPreferredSubs();

        mCardSubMgr = CardSubscriptionManager.getInstance(context, uiccController, ci);
        for (int i=0; i < MSimConstants.RIL_MAX_CARDS; i++) {
            mCardSubMgr.registerForCardInfoAvailable(i, this,
                    EVENT_CARD_INFO_AVAILABLE, new Integer(i));
            mCardSubMgr.registerForCardInfoUnavailable(i, this,
                    EVENT_CARD_INFO_NOT_AVAILABLE, new Integer(i));
        }
        mCardSubMgr.registerForAllCardsInfoAvailable(this, EVENT_ALL_CARD_INFO_AVAILABLE, null);

        mCi = ci;
        for (int i = 0; i < mCi.length; i++) {
            mCi[i].registerForSubscriptionStatusChanged(this,
                    EVENT_SUBSCRIPTION_STATUS_CHANGED, new Integer(i));
            mCi[i].registerForOffOrNotAvailable(this, EVENT_RADIO_OFF_OR_NOT_AVAILABLE,
                    new Integer(i));
            mCi[i].registerForOn(this, EVENT_RADIO_ON, new Integer(i));
        }

        mSubDeactivatedRegistrants = new RegistrantList[MSimConstants.RIL_MAX_CARDS];
        mSubActivatedRegistrants = new RegistrantList[MSimConstants.RIL_MAX_CARDS];
        for (int i = 0; i < MSimConstants.RIL_MAX_CARDS; i++) {
            mSubDeactivatedRegistrants[i] = new RegistrantList();
            mSubActivatedRegistrants[i] = new RegistrantList();
        }
        mActivatePending = new HashMap<SubscriptionId, Subscription>();
        mDeactivatePending = new HashMap<SubscriptionId, Subscription>();
        for (SubscriptionId t : SubscriptionId.values()) {
            mActivatePending.put(t, null);
            mDeactivatePending.put(t, null);
        }

        //mMSimProxyManager = MSimProxyManager.getInstance();
        // Get the current active dds
        mCurrentDds =  MSimPhoneFactory.getDataSubscription();
        logd("In MSimProxyManager constructor current active dds is:" + mCurrentDds);

        mCurrentSubscriptions = new HashMap<SubscriptionId, PhoneSubscriptionInfo>();
        for (SubscriptionId t : SubscriptionId.values()) {
            mCurrentSubscriptions.put(t, new PhoneSubscriptionInfo());
        }
        logd("Constructor - Exit");
    }

    @Override
    public void handleMessage(Message msg) {
        AsyncResult ar;
        Integer subId;
        switch(msg.what) {
            case EVENT_RADIO_OFF_OR_NOT_AVAILABLE:
                ar = (AsyncResult)msg.obj;
                subId = (Integer)ar.userObj;
                logd("EVENT_RADIO_OFF_OR_NOT_AVAILABLE on SUB: " + subId);
                mRadioOn[subId] = false;
                if (!isAllRadioOn()) {
                    mSetSubscriptionInProgress = false;
                    mSetDdsRequired = true;
                }
                break;

            case EVENT_RADIO_ON:
                ar = (AsyncResult)msg.obj;
                subId = (Integer)ar.userObj;
                logd("EVENT_RADIO_ON on SUB: " + subId);
                mRadioOn[subId] = true;
                break;

            case EVENT_CARD_INFO_AVAILABLE:
                logd("EVENT_CARD_INFO_AVAILABLE");
                processCardInfoAvailable((AsyncResult)msg.obj);
                break;

            case EVENT_CARD_INFO_NOT_AVAILABLE:
                logd("EVENT_CARD_INFO_NOT_AVAILABLE");
                processCardInfoNotAvailable((AsyncResult)msg.obj);
                break;

            case EVENT_ALL_CARD_INFO_AVAILABLE:
                logd("EVENT_ALL_CARD_INFO_AVAILABLE");
                processAllCardsInfoAvailable();
                break;

            case EVENT_SET_SUBSCRIPTION_MODE_DONE:
                logd("EVENT_SET_SUBSCRIPTION_MODE_DONE");
                processSetSubscriptionModeDone();
                break;

            case EVENT_SET_UICC_SUBSCRIPTION_DONE:
                logd("EVENT_SET_UICC_SUBSCRIPTION_DONE");
                processSetUiccSubscriptionDone((AsyncResult)msg.obj);
                break;

            case EVENT_SUBSCRIPTION_STATUS_CHANGED:
                logd("EVENT_SUBSCRIPTION_STATUS_CHANGED");
                processSubscriptionStatusChanged((AsyncResult)msg.obj);
                break;

            case EVENT_CLEANUP_DATA_CONNECTION_DONE:
                logd("EVENT_CLEANUP_DATA_CONNECTION_DONE");
                processCleanupDataConnectionDone((Integer)msg.obj);
                break;

            case EVENT_SET_DATA_SUBSCRIPTION_DONE:
                logd("EVENT_SET_DATA_SUBSCRIPTION_DONE");
                processSetDataSubscriptionDone((AsyncResult)msg.obj);
                break;

            case EVENT_ALL_DATA_DISCONNECTED:
                Log.d(LOG_TAG, "EVENT_ALL_DATA_DISCONNECTED");
                processAllDataDisconnected((AsyncResult)msg.obj);
                break;

            case EVENT_EMER_CALL_END:
                Log.d(LOG_TAG, "EVENT_EMER_CALL_END, set uicc subscription");
                CallManager.getInstance().unregisterForDisconnect(this);
                if (!mSetSubscriptionInProgress && isAllRadioOn() && isAllCardsInfoAvailable()) {
                    processActivateRequests();
                }
                break;

            default:
                break;
        }
    }

    /**
     * Handles EVENT_ALL_DATA_DISCONNECTED.
     * This method invoked in case of modem initiated subscription deactivation.
     * Subscription deactivated notification already received and all the data
     * connections are cleaned up.  Now mark the subscription as DEACTIVATED and
     * set the DDS to the available subscription.
     *
     * @param ar
     */
    private void processAllDataDisconnected(AsyncResult ar) {
        /*
         * Check if the DDS switch is in progress. If so update the DDS
         * subscription.
         */
        if (mDisableDdsInProgress) {
            processDisableDataConnectionDone(ar);
        }

        Integer sub = (Integer)ar.userObj;
        SubscriptionId subId = SubscriptionId.values()[sub];
        logd("processAllDataDisconnected: sub = " + sub
                + " - subscriptionReadiness[" + sub + "] = "
                + getCurrentSubscriptionReadiness(subId));
        if (!getCurrentSubscriptionReadiness(subId)) {
            resetCurrentSubscription(subId);
            // Update the subscription preferences
            updateSubPreferences();
            notifySubscriptionDeactivated(sub);
        }
    }

    /**
     * Handles the SET_DATA_SUBSCRIPTION_DONE event
     * @param ar
     */
    private void processSetDataSubscriptionDone(AsyncResult ar) {
        if (ar.exception == null) {
            logd("Register for the all data disconnect");
            MSimProxyManager.getInstance().registerForAllDataDisconnected(mCurrentDds, this,
                    EVENT_ALL_DATA_DISCONNECTED, new Integer(mCurrentDds));
        } else {
            Log.d(LOG_TAG, "setDataSubscriptionSource Failed : ");
            // Reset the flag.
            mDisableDdsInProgress = false;

            // Send the message back to callee with result.
            if (mSetDdsCompleteMsg != null) {
                AsyncResult.forMessage(mSetDdsCompleteMsg, false, null);
                logd("posting failure message");
                mSetDdsCompleteMsg.sendToTarget();
                mSetDdsCompleteMsg = null;
            }

            MSimProxyManager.getInstance().enableDataConnectivity(mCurrentDds);
        }
    }

    private void processDisableDataConnectionDone(AsyncResult ar) {
        //if SUCCESS
        if (ar != null) {
            // Mark this as the current dds
            MSimPhoneFactory.setDataSubscription(mQueuedDds);

            if (mCurrentDds != mQueuedDds) {
                // The current DDS is changed.  Call update to unregister for all the
                // events in DCT to avoid unnecessary processings in the non-DDS.
                MSimProxyManager.getInstance().updateDataConnectionTracker(mCurrentDds);
            }

            mCurrentDds = mQueuedDds;

            // Update the DCT corresponds to the new DDS.
            MSimProxyManager.getInstance().updateDataConnectionTracker(mCurrentDds);

            // Enable the data connectivity on new dds.
            logd("setDataSubscriptionSource is Successful"
                    + "  Enable Data Connectivity on Subscription " + mCurrentDds);
            /*add by YELLOWSTONE_wangzhihui for FEATURE_DATA_CONNECT_FOR_W_PLUS_G 20121123 begin*/
            if(FeatureQuery.FEATURE_DATA_CONNECT_FOR_W_PLUS_G) {
               ConnectivityManager mConnMgr = (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
               mConnMgr.setPreferredSubscription(mCurrentDds);
            }
            /*add by YELLOWSTONE_wangzhihui for FEATURE_DATA_CONNECT_FOR_W_PLUS_G 20121123 end*/
            MSimProxyManager.getInstance().enableDataConnectivity(mCurrentDds);
            mDataActive = true;
        } else {
            //This should not occur as it is a self posted message
            Log.d(LOG_TAG, "Disabling Data Subscription Failed");
        }

        // Reset the flag.
        mDisableDdsInProgress = false;

        // Send the message back to callee.
        if (mSetDdsCompleteMsg != null) {
            AsyncResult.forMessage(mSetDdsCompleteMsg, true, null);
            logd("Enable Data Connectivity Done!! Sending the cnf back!");
            mSetDdsCompleteMsg.sendToTarget();
            mSetDdsCompleteMsg = null;
        }
    }

    /**
     * Handles the EVENT_CLEANUP_DATA_CONNECTION_DONE.
     * @param ar
     */
    private void processCleanupDataConnectionDone(Integer subId) {
        if (!mRadioOn[subId]) {
           logd("processCleanupDataConnectionDone: Radio Not Available on subId = " + subId);
           return;
        }

        // Cleanup data connection is done!  Start processing the
        // pending deactivate requests now.
        mDataActive = false;
        startNextPendingDeactivateRequests();
    }

    /**
     * Handles the EVENT_SUBSCRIPTION_STATUS_CHANGED.
     * @param ar
     */
    private void processSubscriptionStatusChanged(AsyncResult ar) {
        Integer subId = (Integer)ar.userObj;
        int actStatus = ((int[])ar.result)[0];
        logd("handleSubscriptionStatusChanged sub = " + subId
                + " actStatus = " + actStatus);

        if (!mRadioOn[subId]) {
           logd("processSubscriptionStatusChanged: Radio Not Available on subId = " + subId);
           return;
        }

        updateSubscriptionReadiness(subId, (actStatus == SUB_STATUS_ACTIVATED));
        if (actStatus == SUB_STATUS_ACTIVATED) { // Subscription Activated
            // Shall update the DDS here
            if (mSetDdsRequired) {
                if (subId == mCurrentDds) {
                    logd("setDataSubscription on " + mCurrentDds);
                    // Set mQueuedDds so that when the set data sub src is done, it will
                    // update the system property and enable the data connectivity.
                    mQueuedDds = mCurrentDds;
                    mDisableDdsInProgress = true;
                    Message msgSetDdsDone = Message.obtain(this, EVENT_SET_DATA_SUBSCRIPTION_DONE,
                            new Integer(mCurrentDds));
                    // Set Data Subscription preference at RIL
                    mCi[mCurrentDds].setDataSubscription(msgSetDdsDone);
                    mSetDdsRequired = false;
                }
            }
//            notifySubscriptionActivated(subId);
        } else if (actStatus == SUB_STATUS_DEACTIVATED) {
            // Subscription is deactivated from below layers.
            // In case if this is DDS subscription, then wait for the all data disconnected
            // indication from the lower layers to mark the subscription as deactivated.
            if (subId == mCurrentDds) {
                logd("Register for the all data disconnect");
                MSimProxyManager.getInstance().registerForAllDataDisconnected(subId, this,
                        EVENT_ALL_DATA_DISCONNECTED, new Integer(subId));
            } else {
                resetCurrentSubscription(SubscriptionId.values()[subId]);
                updateSubPreferences();
//                notifySubscriptionDeactivated(subId);
            }
        } else {
            logd("handleSubscriptionStatusChanged INVALID");
        }
    }
    
    //this method is a fix for processSubscriptionStatusChanged. EVENT_SET_UICC_SUBSCRIPTION_DONE is not supported, 
    //so processSubscriptionStatusChanged will not be invoked as a callback. This leads to some card status issue
    private void processSubscriptionAfterSetUiccSubscription(Integer subId, int actStatus) {
        logd("processSubscriptionAfterSetUiccSubscription sub = " + subId
                + " actStatus = " + actStatus);

        if (!mRadioOn[subId]) {
           logd("processSubscriptionAfterSetUiccSubscription: Radio Not Available on subId = " + subId);
           return;
        }

        updateSubscriptionReadiness(subId, (actStatus == SUB_STATUS_ACTIVATED));
        if (actStatus == SUB_STATUS_ACTIVATED) { // Subscription Activated
            // Shall update the DDS here
            if (mSetDdsRequired) {
                if (subId == mQueuedDds) {
                    logd("setDataSubscription on " + mQueuedDds);
                    // Set mQueuedDds so that when the set data sub src is done, it will
                    // update the system property and enable the data connectivity.
                    //mQueuedDds = mCurrentDds;
                    setDataSubscription(mQueuedDds,null);
                    mSetDdsRequired = false;
                }
            }
            notifySubscriptionActivated(subId);
        } else if (actStatus == SUB_STATUS_DEACTIVATED) {
            // Subscription is deactivated from below layers.
            // In case if this is DDS subscription, then wait for the all data disconnected
            // indication from the lower layers to mark the subscription as deactivated.
            if (subId == mCurrentDds) {
                logd("Register for the all data disconnect");
                MSimProxyManager.getInstance().registerForAllDataDisconnected(subId, this,
                        EVENT_ALL_DATA_DISCONNECTED, new Integer(subId));
            } else {
                resetCurrentSubscription(SubscriptionId.values()[subId]);
                updateSubPreferences();
                notifySubscriptionDeactivated(subId);
            }
        } else {
            logd("handleSubscriptionStatusChanged INVALID");
        }
    }

    /**
     * Handles the EVENT_SET_UICC_SUBSCRPTION_DONE.
     * @param ar
     */
    private void processSetUiccSubscriptionDone(AsyncResult ar) {
        SetUiccSubsParams setSubParam = (SetUiccSubsParams)ar.userObj;
        String cause = null;
        SubscriptionStatus subStatus = SubscriptionStatus.SUB_INVALID;
        Subscription currentSub = null;

        if (!mRadioOn[setSubParam.subId]) {
           logd("processSetUiccSubscriptionDone: Radio Not Available on subId = "
                + setSubParam.subId);
           return;
        }

        
        //temp disable exception check, because EVENT_SET_UICC_SUBSCRIPTION_DONE is not supported on 40400, we use setRadioPower instead
        
        /*if (ar.exception != null) {
            // SET_UICC_SUBSCRIPTION failed

            if (ar.exception instanceof CommandException ) {
                CommandException.Error error = ((CommandException) (ar.exception))
                    .getCommandError();
                if (error != null &&
                        error ==  CommandException.Error.SUBSCRIPTION_NOT_SUPPORTED) {
                    cause = SUB_DEACTIVATE_NOT_SUPPORTED;
                }
            }

            if (setSubParam.subStatus == SubscriptionStatus.SUB_ACTIVATE) {
                // Set uicc subscription failed for activating the sub.
                logd("subscription of SUB:" + setSubParam.subId + " Activate Failed");
                if (cause == null) {
                    cause = SUB_ACTIVATE_FAILED;
                }
                subStatus = SubscriptionStatus.SUB_DEACTIVATED;
                currentSub = mActivatePending.get(SubscriptionId.values()[setSubParam.subId]);

                // Clear the pending activate request list
                mActivatePending.put(SubscriptionId.values()[setSubParam.subId], null);
            } else if (setSubParam.subStatus == SubscriptionStatus.SUB_DEACTIVATE) {
                // Set uicc subscription failed for deactivating the sub.
                logd("subscription of SUB:" + setSubParam.subId + " Deactivate Failed");

                // If there is any pending request for activate the same sub
                // which means user might have tried to deactivate the sub, and
                // and activate with another app from any of the cards
                if (cause == null) {
                    if (isAnyPendingActivateRequest(setSubParam.subId )) {
                        cause = SUB_ACTIVATE_FAILED;
                        // Activate failed means the sub is active with the current app.
                        // We cannot proceed with a activate request for particular
                        // subscription which is active with another application.
                        // Clear the pending activate entry
                        mActivatePending.put(SubscriptionId.values()[setSubParam.subId], null);
                    } else {
                        cause = SUB_DEACTIVATE_FAILED;
                    }
                }
                subStatus = SubscriptionStatus.SUB_ACTIVATED;
                currentSub = mDeactivatePending.get(SubscriptionId.values()[setSubParam.subId]);
                // Clear the deactivate pending entry
                mDeactivatePending.put(SubscriptionId.values()[setSubParam.subId], null);

                if (mCurrentDds == setSubParam.subId) {
                    // Deactivating the current DDS is failed. Try bring up data again.
                    MSimProxyManager.getInstance().enableDataConnectivity(mCurrentDds);
                }
            }else {
                logd("UNKOWN: SHOULD NOT HIT HERE");
            }
        } else */
        
        
        {
            // SET_UICC_SUBSCRIPTION success
            if (setSubParam.subStatus == SubscriptionStatus.SUB_ACTIVATE) {
                // Activate Success!!
                logd("subscription of SUB:" + setSubParam.subId + " Activated");
                subStatus = SubscriptionStatus.SUB_ACTIVATED;
                cause = SUB_ACTIVATE_SUCCESS;
                currentSub = mActivatePending.get(SubscriptionId.values()[setSubParam.subId]);

                // Clear the pending activate request list
                mActivatePending.put(SubscriptionId.values()[setSubParam.subId], null);
                
                //2012-08-01 update subscription data in MSimCDMAPhone, otherwise Phone can not get IccRecords-Start*/
                notifySubscriptionActivated(setSubParam.subId);
              //2012-08-01 update subscription data in MSimCDMAPhone, otherwise Phone can not get IccRecords-End*/
                
                //send intent to notify Contact we are ready to load ADN records
                Intent activatedIntent = new Intent("com.android.telephony.SubscriptionManager.cardactivated");
                activatedIntent.putExtra("subid", setSubParam.subId);
                mContext.sendBroadcast(activatedIntent);
                //send intent to notify Contact we are ready to load ADN records
                
            } else if (setSubParam.subStatus == SubscriptionStatus.SUB_DEACTIVATE) {
                // Deactivate success
                logd("subscription of SUB:" + setSubParam.subId + " Deactivated");
                subStatus = SubscriptionStatus.SUB_DEACTIVATED;
                cause = SUB_DEACTIVATE_SUCCESS;
                currentSub = mDeactivatePending.get(SubscriptionId.values()[setSubParam.subId]);
                // Clear the deactivate pending entry
                mDeactivatePending.put(SubscriptionId.values()[setSubParam.subId], null);
                // Deactivate completed!
                notifySubscriptionDeactivated(setSubParam.subId);
            } else {
                logd("UNKOWN: SHOULD NOT HIT HERE");
            }
        }

        logd("set uicc subscription done. update the current subscriptions");
        // Update the current subscription for this subId;
        updateCurrentSubscription(setSubParam.subId,
                currentSub,
                subStatus,
                cause);
        // do not saveUserPreferredSubscription in case of failure
        //if (ar.exception == null) {
        //just save, because EVENT_SET_UICC_SUBSCRIPTION_DONE is unsupported, will always return with exception
            saveUserPreferredSubscription(setSubParam.subId,
                    getCurrentSubscription(SubscriptionId.values()[setSubParam.subId]));
        /*} else {
            // Failure case: update the subscription readiness properly.
            updateSubscriptionReadiness(setSubParam.subId,
                    (subStatus == SubscriptionStatus.SUB_ACTIVATED));
        }*/

        mSubResult[setSubParam.subId] = cause;

        if (startNextPendingDeactivateRequests()) {
            // There are deactivate requests.
        } else if (startNextPendingActivateRequests()) {
            // There are activate requests.
        } else {
            mSetSubscriptionInProgress = false;
            updateSubPreferences();
            // DONE! notify now!
            if (mSetSubscriptionRegistrants != null) {
                mSetSubscriptionRegistrants.notifyRegistrants(
                        new AsyncResult(null, mSubResult, null));
                        //new AsyncResult(null, getSetSubscriptionResults(), null));
            }
        }
        
        //call this method instead of EVENT_SUBSCRIPTION_STATUS_CHANGED's callback
        int mActStatus = -1;
        if(setSubParam.subStatus == SubscriptionStatus.SUB_ACTIVATE)
        {
        	mActStatus = 1;
        	
        }else if(setSubParam.subStatus == SubscriptionStatus.SUB_DEACTIVATE)
        {
        	mActStatus = 0;
        	
        }
        
        processSubscriptionAfterSetUiccSubscription(Integer.valueOf(setSubParam.subId),mActStatus);
        
    }

    /**
     * Returns the set subscription status.
     * @return
     */
    private String[] getSetSubscriptionResults() {
        String result[] = new String[NUM_SUBSCRIPTIONS];
        for (int i = 0; i < NUM_SUBSCRIPTIONS; i++) {
            result[i] = mCurrentSubscriptions.get(SubscriptionId.values()[i]).cause;
        }

        return result;
    }

    /**
     * Updates the subscriptions preferences based on the number of active subscriptions.
     */
    private void updateSubPreferences() {
        int activeSubCount = 0;
        Subscription activeSub = null;

        for (SubscriptionId sub: SubscriptionId.values()) {
            if (getCurrentSubscriptionStatus(sub) == SubscriptionStatus.SUB_ACTIVATED) {
                activeSubCount++;
                activeSub = getCurrentSubscription(sub);
            }
        }

        // If there is only one active subscription, set user preferred settings
        // for voice/sms/data subscription to this subscription.
        if (activeSubCount == 1) {
            logd("updateSubPreferences: only SUB:" + activeSub.subId
                    + " is Active.  Update the default/voice/sms and data subscriptions");
            MSimPhoneFactory.setVoiceSubscription(activeSub.subId);
            MSimPhoneFactory.setSMSSubscription(activeSub.subId);
            MSimPhoneFactory.setPromptEnabled(false);

            logd("updateSubPreferences: current defaultSub = "
                    + MSimPhoneFactory.getDefaultSubscription());
            logd("updateSubPreferences: current mCurrentDds = " + mCurrentDds);
            if (MSimPhoneFactory.getDefaultSubscription() != activeSub.subId) {
                MSimPhoneFactory.setDefaultSubscription(activeSub.subId);
            }
             /*Add by YELLOWSTONE_hanjj for FEATURE_DATA_CONNECT_FOR_G 20121116 begin*/
             //if (FeatureQuery.FEATURE_DATA_CONNECT_FOR_G) {
                if (MSimPhoneFactory.getDataSubscription() != activeSub.subId) {
                    MSimPhoneFactory.setDataSubscription(activeSub.subId);
                }
            //}
            /*Add by YELLOWSTONE_hanjj for FEATURE_DATA_CONNECT_FOR_G 20121116 end*/
            if (mCurrentDds != activeSub.subId) {
                // Currently selected DDS subscription is not in activated state.
                // So set the DDS to the only active subscription available now.
                // Directly set the Data Subscription Source to the only activeSub if it
                // is READY. If the SUBSCRIPTION_READY event is not yet received on this
                // subscription, wait for the event to set the Data Subscription Source.
                SubscriptionId subId = SubscriptionId.values()[activeSub.subId];
                if (getCurrentSubscriptionReadiness(subId)) {
                    mQueuedDds = activeSub.subId;
                    Message callback = Message.obtain(this, EVENT_SET_DATA_SUBSCRIPTION_DONE,
                            Integer.toString(activeSub.subId));
                    mDisableDdsInProgress = true;
                    logd("update setDataSubscription to " + activeSub.subId);

                    //if (FeatureQuery.FEATURE_DATA_CONNECT_FOR_G) {
                       if (callback != null){
                            AsyncResult.forMessage(callback,true, null);
                            callback.sendToTarget();
                            callback = null;
                       } 
                    //} else {	
                    //   mCi[activeSub.subId].setDataSubscription(callback);
                    //}

                    mSetDdsRequired = false;
                } else {
                    // Set the flag and update the mCurrentDds, so that when subscription
                    // ready event receives, it will set the dds properly.
                    mSetDdsRequired = true;
                    mCurrentDds = activeSub.subId;
                    //MSimPhoneFactory.setDataSubscription(mCurrentDds);
                }
            }
        }
    }

    /**
     * Handles EVENT_SET_SUBSCRIPTION_MODE_DONE.
     */
    private void processSetSubscriptionModeDone() {
    	if(mCardShouldEnable[0] == true && mCardShouldEnable[1] == true)
    	{
    		if (!isAllRadioOn()) {
    			logd("processSetSubscriptionModeDone: Radio Not Available");
    			return;
    		}
    	}

        startNextPendingActivateRequests();
    }

    /**
     * Handles EVENT_ALL_CARDS_INFO_AVAILABLE.
     */
    private void processAllCardsInfoAvailable() {
        if (!isAllRadioOn()) {
           logd("processAllCardsInfoAvailable: Radio Not Available ");
           return;
        }

        int availableCards = 0;
        mAllCardsStatusAvailable = true;

        if (CallManager.getInstance().getState() != Phone.State.IDLE) {
            logd("processAllCardsInfoAvailable: has an emergency call, wait until it is over");
            CallManager.getInstance().registerForDisconnect(this, EVENT_EMER_CALL_END, null);
            return;
        }

        for (int i = 0; i < MSimConstants.RIL_MAX_CARDS; i++) {
            if (mCardInfoAvailable[i] || mCardSubMgr.isCardAbsentOrError(i)) {
                availableCards++;
            }
        }
        // Process any pending activate requests if there is any.
        if (availableCards == MSimConstants.RIL_MAX_CARDS
            && !mSetSubscriptionInProgress) {
            processActivateRequests();
        }

        //when dual sim mode and card change, show card change prompt
        if (MSimTelephonyManager.getDefault().isMultiSimEnabled() && isCardChanaged()) {
            logd("goto activity that show card has changed!");
            try {
                Intent intent = new Intent("android.intent.action.CARD_CHANGED");
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                mContext.startActivity(intent);
            } catch(ActivityNotFoundException e) {
                logd("not found activity that deal with android.intent.action.CARD_CHANGED");
            }
        }
        
        
       /* if (isNewCardAvailable()) {
            // NEW CARDs Available!!!
            // Notify the USER HERE!!!
            notifyNewCardsAvailable();
        }*/
    }

    /**
     * Handles EVENT_CARDS_INFO_AVAILABLE.
     * New cards available.
     * @param ar
     */
    private void processCardInfoAvailable(AsyncResult ar) {
        Integer cardIndex = (Integer)ar.userObj;

        if (!mRadioOn[cardIndex]) {
           logd("processCardInfoAvailable: Radio Not Available on cardIndex = " + cardIndex);
           return;
        }

        mCardInfoAvailable[cardIndex] = true;

        // Card info on slot cardIndex is available.
        // Check if any user preferred subscriptions are available in
        // this card.  If there is any, and which are not yet activated,
        // activate them!
        SubscriptionData cardSubInfo = mCardSubMgr.getCardSubscriptions(cardIndex);
        Subscription userSub = mUserPrefSubs.subscription[cardIndex];

        logd("processCardInfoAvailable: cardIndex = " + cardIndex
                + "\n Card Sub Info = " + cardSubInfo);

        // If this is a new card(no user preferred subscriptions are from
        // this card), then notify a prompt to user.  Let user select
        // the subscriptions from new card!
        mIsNewCard [cardIndex] = true;
        if (cardSubInfo.hasSubscription(userSub)) {
            mIsNewCard[cardIndex] = false;

            int subId = cardIndex;
            Subscription currentSub = getCurrentSubscription(SubscriptionId.values()[subId]);

            logd("processCardInfoAvailable: subId = " + subId
                    + "\n user pref sub = " + userSub
                    + "\n current sub   = " + currentSub);

            // TODO Need to check if this subscription is already in the pending list!
            // If already there, no need to add again!

            Subscription sub = new Subscription();
            sub.copyFrom(cardSubInfo.getSubscription(userSub));
            sub.slotId = cardIndex;
            sub.subId = subId;
            if (((mUserPrefSubs.subscription[subId].subStatus == SubscriptionStatus.SUB_ACTIVATED)
                || (mUserPrefSubs.subscription[subId].subStatus == SubscriptionStatus.SUB_INVALID))
                && (currentSub.subStatus != SubscriptionStatus.SUB_ACTIVATED)) {
                // Need to activate this Subscription!!! - userSub.subId
                // Push to the queue, so that start the SET_UICC_SUBSCRIPTION
                // only when the both cards are ready.
                logd("processCardInfoAvailable --DEBUG--: subId = "
                     + subId + " need to activate!!!");

                sub.subStatus = SubscriptionStatus.SUB_ACTIVATE;
                mActivatePending.put(SubscriptionId.values()[subId], sub);
            } else if ((mUserPrefSubs.subscription[subId].subStatus == SubscriptionStatus.SUB_DEACTIVATED)
                       && (currentSub.subStatus != SubscriptionStatus.SUB_DEACTIVATED)) {
                //if the subscription is deactivated, should set card info to current subscription
                sub.subStatus = SubscriptionStatus.SUB_DEACTIVATED;
                currentSub.copyFrom(sub);
            }
        }
        logd("processCardInfoAvailable: mIsNewCard [" + cardIndex + "] = "
                + mIsNewCard [cardIndex]);
        
        final int cardIndexClone = cardIndex;
        
        new Thread(new Runnable() {
			
			@Override
			public void run() {
				
						
					try {
						Thread.sleep(2000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					Log.d(LOG_TAG, "waiting for card1LOADED");
					
				
				/*try {
					//gsm.sim0.cardmode maybe set a little late, so here we wait for a while
					Thread.sleep(2000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}*/
				
				boolean doubleModeCard = false;
				//only process this for slot_0
				if(cardIndexClone == 0)
				{
					//check card type
					String cardType = SystemProperties.get("gsm.sim0.cardmode", "");
					Log.d(LOG_TAG, "cardType : " + cardType);
					
					if(cardType.equalsIgnoreCase("2") || cardType.equalsIgnoreCase("4") )
					{
						doubleModeCard = true;
						Log.d(LOG_TAG, "doubleModeCard : " + String.valueOf(doubleModeCard));
					}
					
					//if single CDMA type card inserted or a new card inserted, we should restore this property
					if(!doubleModeCard || mIsNewCard [cardIndexClone])
					{
						SystemProperties.set("persist.radio.networkmode", "0");
						
					}
					
				}
				
				//network mode choice dialog can be displayed only when card 1 not changed
				//also the network mode is GSM at last time
				//change network mode in Settings, which lead to reinit, then we should not display network mode choice
				Log.d(LOG_TAG, "networkModeChangedBySettings : " + String.valueOf(networkModeChangedBySettings));
				
				if(!mIsNewCard[0] && SystemProperties.getInt("persist.radio.networkmode", 0) == 1 && doubleModeCard && !networkModeChangedBySettings)
				{
					try {
						Thread.sleep(25000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					
					logd("card in SLOT_1 not changed and GSM is selected at last time");
					Intent intent = new Intent("android.intent.action.BootNetworkChoiceActivity");
					intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
					mContext.startActivity(intent); 
					
				}
				
				
			}
		}).start();

        if (mIsNewCard [cardIndex]) {
            // NEW CARDs Available, notify the USER HERE!!!
            //notifyNewCardsAvailable();

            // !!! HERE we set the default app and activate it !!!
            // Need to activate this Subscription!!! - userSub.subId
            // Push to the queue, so that start the SET_UICC_SUBSCRIPTION
            // only when the both cards are ready.
            Subscription newSub = new Subscription();
            int appIndex = getAppIndexByMode(cardIndex, getPreferredMode(cardIndex));
            newSub.copyFrom(cardSubInfo.subscription[appIndex]);
            newSub.slotId = cardIndex;
            // !!! HERE force slotId = subId
            newSub.subId = cardIndex;
            newSub.subStatus = SubscriptionStatus.SUB_ACTIVATE;
            setDefaultAppIndex(newSub);

            mActivatePending.put(SubscriptionId.values()[cardIndex], newSub);
            mIsNewCard[cardIndex] = false;
        }

        if(mCardShouldEnable[0] == true && mCardShouldEnable[1] == true)
        {
        	if (!isAllCardsInfoAvailable()) {
        		logd("All cards info not available!! Waiting for all info before processing");
        		return;
        	}
        	
        }
         //Airplane mode emergency call, need to wait until call is over,
        if (CallManager.getInstance().getState() != Phone.State.IDLE) {
            logd("processCardInfoAvailable: has an emergency call, wait until it is over");
            CallManager.getInstance().registerForDisconnect(this, EVENT_EMER_CALL_END, null);
            return;
        }

        logd("--DEBUG--: processCardInfoAvailable: " + mSetSubscriptionInProgress);
        if (!mSetSubscriptionInProgress) {
            processActivateRequests();
        }
    }

    public void setDefaultAppIndex(Subscription sub) {
        int cardIndex = sub.slotId;
        String mode = getPreferredMode(cardIndex);
        int appIndex = getAppIndexByMode(cardIndex, mode);

        if (MODE_1x.equals(mode)) {
            sub.m3gpp2Index = appIndex;
            sub.m3gppIndex = Subscription.SUBSCRIPTION_INDEX_INVALID;
        } else if (MODE_GW.equals(mode)) {
            sub.m3gpp2Index = Subscription.SUBSCRIPTION_INDEX_INVALID;
            sub.m3gppIndex = appIndex;
        } else {
            // FIXME:
            // !!! HERE should be wrong, then we prefer which mode?
            logd("failed to get preferred mode, set w/g mode by default!");
            sub.m3gpp2Index = Subscription.SUBSCRIPTION_INDEX_INVALID;
            sub.m3gppIndex = appIndex;
        }
    }

    private int getAppIndexByMode(int cardIndex, String mode) {
        logd("getAppIndexByMode(" + cardIndex + ", " + mode + ")" );

        SubscriptionData cardSub = mCardSubMgr.getCardSubscriptions(cardIndex);
        int appIndex = Subscription.SUBSCRIPTION_INDEX_INVALID;

        if (cardSub != null) {
            for (int i = 0; i < cardSub.getLength(); i++) {
                Subscription sub = cardSub.subscription[i];
                if (MODE_1x.equals(mode)) {
                    if (("RUIM").equals(sub.appType)) {
                        logd("find the first RUIM appIndex " + i);
                        appIndex = i;
                        break;
                    } else if (("CSIM").equals(sub.appType)) {
                        logd("find the first CSIM appIndex " + i);
                        appIndex = i;
                        break;
                    }
                } else if (MODE_GW.equals(mode)) {
                    if (("USIM").equals(sub.appType)) {
                        logd("find the first USIM appIndex " + i);
                        appIndex = i;
                        break;
                    } else if (("SIM").equals(sub.appType)) {
                        logd("find the first SIM appIndex " + i);
                        appIndex = i;
                        break;
                    }
                }
            }
            if (appIndex == Subscription.SUBSCRIPTION_INDEX_INVALID) {
                Log.e(LOG_TAG, "failed to find the preferred app, use the first appIndex");
                appIndex = 0;
            }
        }
        return appIndex;
    }

    /** NOTE :
     *   The proper ril.subsmode is set in qcril,
     *   only 1x, gw and unknown are valid for each subscription.
     */
    private String getPreferredMode(int cardIndex) {
        String modes = SystemProperties.get(PROPERTY_RIL_SUBSMODE);
        logd(PROPERTY_RIL_SUBSMODE + "=[" + modes + "]");
        String[] mode = modes.split(",");
        if (cardIndex >= mode.length) {
            return MODE_UNKNOWN;
        } else {
            return mode[cardIndex];
        }
    }

    private boolean isPresentInActivatePendingList(Subscription userSub) {
        for (SubscriptionId sub: SubscriptionId.values()) {
            Subscription actPendingSub = mActivatePending.get(sub);
            if (userSub != null && userSub.isSame(actPendingSub)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Notify new cards available.
     * Starts the SetSubscription activity.
     */
    void notifyNewCardsAvailable() {
        logd("notifyNewCardsAvailable" );
        Intent setSubscriptionIntent = new Intent(Intent.ACTION_MAIN);
        setSubscriptionIntent.setClassName("com.android.phone",
                "com.android.phone.SetSubscription");
        setSubscriptionIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        setSubscriptionIntent.putExtra("NOTIFY_NEW_CARD_AVAILABLE", true);

        mContext.startActivity(setSubscriptionIntent);
    }

    private boolean isAllRadioOn() {
        boolean result = true;
        for (boolean radioOn : mRadioOn) {
            result = result && radioOn;
        }
        return result;
    }

    private boolean isAllCardsInfoAvailable() {
        boolean result = true;
        for (boolean available : mCardInfoAvailable) {
            result = result && available;
        }
        return result || mAllCardsStatusAvailable;
    }
    private boolean isNewCardAvailable() {
        boolean result = false;
        for (boolean isNew : mIsNewCard) {
            result = result || isNew;
        }
        return result;
    }

    /**
     * Handles EVENT_CARDS_INFO_NOT_AVAILABLE..
     * Card has been removed!!
     * @param ar
     */
    private void processCardInfoNotAvailable(AsyncResult ar) {
        if (ar.exception != null || ar.result == null) {
            logd("processCardInfoNotAvailable - Exception");
            return;
        }

        Integer cardIndex = (Integer)ar.userObj;
        CardUnavailableReason reason = (CardUnavailableReason)ar.result;

        logd("processCardInfoNotAvailable on cardIndex = " + cardIndex
                + " reason = " + reason);

        mCardInfoAvailable[cardIndex] = false;

        // Set subscription is required if both the cards are unavailable
        // and when those are available next time!

        mSetSubsModeRequired = !mCardInfoAvailable[0] && !mCardInfoAvailable[1];
        logd("processCardInfoNotAvailable mSetSubsModeRequired = " + mSetSubsModeRequired);

        // Reset the current subscription and notify the subscriptions deactivated.
        // Notify only in case of radio off and SIM Refresh reset.
        if (reason == CardUnavailableReason.REASON_RADIO_UNAVAILABLE
                || reason == CardUnavailableReason.REASON_SIM_REFRESH_RESET) {
            // Card has been removed from slot - cardIndex.
            // Mark the active subscription from this card as de-activated!!
            for (SubscriptionId sub: SubscriptionId.values()) {
                if (getCurrentSubscription(sub).slotId == cardIndex) {
                    resetCurrentSubscription(sub);
                    notifySubscriptionDeactivated(sub.ordinal());
                }
            }
        }

        if (reason == CardUnavailableReason.REASON_RADIO_UNAVAILABLE) {
            mAllCardsStatusAvailable = false;
        }
    }


    /**
     * Prints the pending list. For debugging.
     */
    private void printPendingActivateRequests() {
        logd("ActivatePending Queue : ");
        for (SubscriptionId sub: SubscriptionId.values()) {
            Subscription newSub = mActivatePending.get(sub);
            logd(sub + ":" + newSub);
        }
    }

    /**
     * Prints the pending list. For debugging.
     */
    private void printPendingDeactivateRequests() {
        logd("DeactivatePending Queue : ");
        for (SubscriptionId sub: SubscriptionId.values()) {
            Subscription newSub = mDeactivatePending.get(sub);
            logd(sub + ":" + newSub);
        }
    }

    /**
     * Start one deactivate from the pending deactivate request queue.
     * If the deactivate is required for the DDS SUB, then initiate
     * clean up the data connection and deactivate later.
     * @return true if deactivate is started.
     */
    private boolean startNextPendingDeactivateRequests() {
        printPendingDeactivateRequests();

        for (SubscriptionId sub: SubscriptionId.values()) {
            Subscription newSub = mDeactivatePending.get(sub);
            if (newSub != null && newSub.subStatus == SubscriptionStatus.SUB_DEACTIVATE) {
                if (!validateDeactivationRequest(newSub)) {
                    // Not a valid entry. Clear the deactivate pending entry
                    mDeactivatePending.put(sub, null);
                    continue;
                }

                logd("startNextPendingDeactivateRequests: Need to deactivating SUB : " + newSub);
                if (mCurrentDds == newSub.subId && mDataActive) {
                    // This is the DDS.
                    // Tear down all the data calls on this subscription. Once the
                    // clean up completed, the set uicc subscription request with
                    // deactivate will be sent to deactivate this subscription.
                    logd("Deactivate all the data calls if there is any");
                    Message allDataCleanedUpMsg = Message.obtain(this,
                            EVENT_CLEANUP_DATA_CONNECTION_DONE, mCurrentDds);
                    MSimProxyManager.getInstance().disableDataConnectivity(
                            mCurrentDds, allDataCleanedUpMsg);
                    mSetDdsRequired = true;
                } else {
                    logd("startNextPendingDeactivateRequests: Deactivating now");
                    SetUiccSubsParams setSubParam = new SetUiccSubsParams(
                            newSub.subId, newSub.subStatus);
                    Message msgSetUiccSubDone = Message.obtain(this,
                            EVENT_SET_UICC_SUBSCRIPTION_DONE,
                            setSubParam);
                    mCi[newSub.subId].setUiccSubscription(newSub.slotId,
                            newSub.getAppIndex(),
                            newSub.subId,
                            newSub.subStatus.ordinal(),
                            msgSetUiccSubDone);
/*add by YELLOWSTONE_yangliu for deactivating card begin*/
                    if(!mCardShouldEnable[newSub.subId])
                    {
                    	MSimPhoneFactory.getPhone(newSub.subId).setRadioPower(false);
                    	
                    }else
                    {
                    	logd("card enabled, should not turn off again");
                    }
/*add by YELLOWSTONE_yangliu for deactivating card end*/
                }
                // process one request at a time!!
                return true;
            }
        }
        return false;
    }

    /**
     * Process activate requests.  Set the subscription mode if required.
     */
    private void processActivateRequests() {
        logd("processActivateRequests: mSetSubscriptionInProgress = "
                 + mSetSubscriptionInProgress
                 + " mSetSubsModeRequired = " + mSetSubsModeRequired);
        if (!mSetSubscriptionInProgress) {
            /*if (mSetSubsModeRequired) {
                mSetSubscriptionInProgress  = setSubscriptionMode();
                if (mSetSubscriptionInProgress) {
                    mSetSubsModeRequired = false;
                }
                return;
            }*/
            mSetSubscriptionInProgress = startNextPendingActivateRequests();
        }
    }


    private boolean validateDeactivationRequest(Subscription sub) {
        // Check the parameters here!
        // subStatus, subId, slotId, appIndex
        if (sub.subStatus == Subscription.SubscriptionStatus.SUB_DEACTIVATE
                && (sub.subId >= 0 && sub.subId < NUM_SUBSCRIPTIONS)
                && (sub.slotId >= 0 && sub.slotId < NUM_SUBSCRIPTIONS)
                && (sub.getAppIndex() >= 0
                        && (mCardSubMgr.getCardSubscriptions(sub.slotId) != null)
                        && sub.getAppIndex() <
                        mCardSubMgr.getCardSubscriptions(sub.slotId).getLength())) {
            return true;
        }
        return false;
    }

    private boolean validateActivationRequest(Subscription sub) {
        // Check the parameters here!
        // subStatus, subId, slotId, appIndex
        if (sub.subStatus == Subscription.SubscriptionStatus.SUB_ACTIVATE
                && (sub.subId >= 0 && sub.subId < NUM_SUBSCRIPTIONS)
                && (sub.slotId >= 0 && sub.slotId < NUM_SUBSCRIPTIONS)
                && (sub.getAppIndex() >= 0
                        && (mCardSubMgr.getCardSubscriptions(sub.slotId) != null)
                        && sub.getAppIndex() <
                        mCardSubMgr.getCardSubscriptions(sub.slotId).getLength())) {
            return true;
        }
        return false;
    }

    /**
     * Start one activate request from the pending activate request queue.
     * @return true if activate request is started.
     */
    private boolean startNextPendingActivateRequests() {
        printPendingActivateRequests();

        for (SubscriptionId sub: SubscriptionId.values()) {
            Subscription newSub = mActivatePending.get(sub);
            if (newSub != null && newSub.subStatus == SubscriptionStatus.SUB_ACTIVATE) {
                if (!validateActivationRequest(newSub)) {
                    // Not a valid entry.  Clear the pending activate request list
                    mActivatePending.put(sub, null);
                    continue;
                }

                // We need to update the phone object for the new subscription.
                MSimProxyManager.getInstance().checkAndUpdatePhoneObject(newSub);

                logd("startNextPendingActivateRequests: Activating SUB : " + newSub);
                SetUiccSubsParams setSubParam = new SetUiccSubsParams(
                        newSub.subId, newSub.subStatus);
                Message msgSetUiccSubDone = Message.obtain(this,
                        EVENT_SET_UICC_SUBSCRIPTION_DONE,
                        setSubParam);
                mCi[newSub.subId].setUiccSubscription(newSub.slotId,
                        newSub.getAppIndex(),
                        newSub.subId,
                        newSub.subStatus.ordinal(),
                        msgSetUiccSubDone);

                // process one request at a time!!
                return true;
            }
        }
        return false;
    }

    private boolean isAnyPendingActivateRequest(int subId) {
        Subscription newSub = mActivatePending.get(SubscriptionId.values()[subId]);
        if (newSub != null
                && newSub.subStatus == SubscriptionStatus.SUB_ACTIVATE) {
            return true;
        }
        return false;
    }

    private void updateCurrentSubscription(int subId, Subscription subscription,
            SubscriptionStatus subStatus, String cause) {
        SubscriptionId sub = SubscriptionId.values()[subId];

        logd("updateCurrentSubscription: subId = " + sub
                + " subStatus = " + subStatus + "\n subscription = " + subscription);

        if (subStatus == SubscriptionStatus.SUB_ACTIVATED) {
            getCurrentSubscription(sub).copyFrom(subscription);
        } else {
            getCurrentSubscription(sub).clear();
            // If not activated, mark as deactivated always!!
            subStatus = SubscriptionStatus.SUB_DEACTIVATED;
        }
        getCurrentSubscription(sub).subStatus = subStatus;
        if (cause == null) {
            cause = SUB_NOT_CHANGED;
        }
        mCurrentSubscriptions.get(sub).cause = cause;
        mCurrentSubscriptions.get(sub).subReady = false;
    }

    private void updateSubscriptionReadiness(int subId, boolean ready) {
        SubscriptionId sub = SubscriptionId.values()[subId];
        logd("updateSubscriptionReadiness(" + subId + "," + ready + ")");

        // Set subscription ready to true only if subscription is activated!
        if (ready && getCurrentSubscription(sub).subStatus == SubscriptionStatus.SUB_ACTIVATED) {
            mCurrentSubscriptions.get(sub).subReady = true;
            return;
        }
        // Subscription is not activated.  So irrespective of the ready, set to false.
        mCurrentSubscriptions.get(sub).subReady = false;
    }

    /**
     * Reset the subscriptions.  Mark the selected subscription as Deactivated.
     * @param subId
     */
    private void resetCurrentSubscription(SubscriptionId subId){
        getCurrentSubscription(subId).clear();
        getCurrentSubscription(subId).subStatus = SubscriptionStatus.SUB_DEACTIVATED;

        mCurrentSubscriptions.get(subId).cause = null;
        mCurrentSubscriptions.get(subId).subReady = false;
    }

    private Subscription getCurrentSubscription(SubscriptionId subId) {
        return mCurrentSubscriptions.get(subId).sub;
    }

    public Subscription getCurrentSubscription(int subId) {
        return getCurrentSubscription(SubscriptionId.values()[subId]);
    }

    private SubscriptionStatus getCurrentSubscriptionStatus(SubscriptionId subId) {
        return mCurrentSubscriptions.get(subId).sub.subStatus;
    }

    private boolean getCurrentSubscriptionReadiness(SubscriptionId subId) {
        return mCurrentSubscriptions.get(subId).subReady;
    }

    public boolean isSubActive(int subscription) {
        Subscription currentSelSub = getCurrentSubscription(subscription);
        loge("setSubscriptionMode isSubActive currentSelSub.subStatus = " + currentSelSub.subStatus);
        return (currentSelSub.subStatus == SubscriptionStatus.SUB_ACTIVATED);
    }

    /**
     * Set subscription mode.  Count number of activate requests and
     * set the mode only if the count is 1 or 2.
     * @return true if set subscription mode is started.
     */
    private boolean setSubscriptionMode() {
        // If subscription mode is not set
        int numSubsciptions = 0;
        for (SubscriptionId sub: SubscriptionId.values()) {
            Subscription pendingSub = mActivatePending.get(sub);
            if (pendingSub != null
                    && pendingSub.subStatus == SubscriptionStatus.SUB_ACTIVATE) {
                numSubsciptions++;
            }
        }

        logd("setSubscriptionMode numSubsciptions = " + numSubsciptions);

        if (numSubsciptions > 0 && numSubsciptions <= NUM_SUBSCRIPTIONS) {
            Message setSubsModeDone = Message.obtain(this,
                    EVENT_SET_SUBSCRIPTION_MODE_DONE,
                    null);
            mCi[0].setSubscriptionMode(numSubsciptions, setSubsModeDone);
            return true;
        }
        return false;
    }


    /**
     * Notifies the SUB subId is deactivated.
     * @param subId
     */
    private void notifySubscriptionDeactivated(int subId) {
        mSubDeactivatedRegistrants[subId].notifyRegistrants();
    }

    /**
     * Notifies the SUB subId is activated.
     * @param subId
     */
    private void notifySubscriptionActivated(int subId) {
        mSubActivatedRegistrants[subId].notifyRegistrants();
    }

    /**
     * Set Uicc Subscriptions
     * Algorithm:
     * 1. Process the set subscription request if not in progress, return false if
     *    already in progress.
     * 2. Read each requested SUB
     * 3. If user selected a different app for a SUB and previous status of SUB is
     *    ACTIVATED, then need to deactivate it.
     *    Add to the pending Deactivate request Queue.
     * 4. If user selected an app for SUB to ACTIVATE
     *    Add to the pending Activate request Queue.
     * 5. Start deactivate requests
     * 6. If no deactivate requests, start activate requests.
     *    In case of deactivate requests started, the pending activate requests with
     *    be processed after finishing the deactivate.
     * 7. If any set uicc subscription started, return true.
     *
     * @param subData - Contains the required SUB1 and SUB2 subscription information.
     *        To activate a SUB, set the subStatus to ACTIVATE
     *        To deactivate, set the subStatus to DEACTIVATE
     *        To keep the subscription with out any change, set the sub to current sub.
     * @return true if the requested set subscriptions are started.
     *         false if there is no request to update the subscriptions
     *               or if already a set subscription is in progress.
     */
    public boolean setSubscription(SubscriptionData subData) {
        boolean ret = false;

        // Return failure if the set uicc subscription is already in progress.
        // Ideally the setSubscription should not be called when there is a
        // activate/deactivate in undergoing.  Whoever calling should aware of
        // set subscription status.
        if (mSetSubscriptionInProgress) {
            return false;
        }

        mSubResult[0] = SUB_NOT_CHANGED;
        mSubResult[1] = SUB_NOT_CHANGED;

        // Check what are the user preferred subscriptions.
        for (SubscriptionId subId: SubscriptionId.values()) {
            // If previous subscription is not same as the requested subscription
            //    (ie., the user must have marked this subscription as deactivate or
            //    selected a new sim app for this subscription), then deactivate the
            //    previous subscription.
            if (!getCurrentSubscription(subId).equals(subData.subscription[subId.ordinal()])) {
                if (getCurrentSubscriptionStatus(subId) == SubscriptionStatus.SUB_ACTIVATED) {
                    logd("Need to deactivate current SUB :" + subId);
                    Subscription newSub = new Subscription();
                    newSub.copyFrom(getCurrentSubscription(subId));
                    newSub.subStatus = SubscriptionStatus.SUB_DEACTIVATE;
                    mDeactivatePending.put(subId, newSub);
                } else if (getCurrentSubscriptionStatus(subId) == SubscriptionStatus.SUB_DEACTIVATED
                        && subData.subscription[subId.ordinal()].subStatus ==
                        SubscriptionStatus.SUB_DEACTIVATE) {
                    // This subscription is already in deactivated state!
                }
            }
            if (subData.subscription[subId.ordinal()].subStatus ==
                    SubscriptionStatus.SUB_ACTIVATE) {
                logd("Need to activate new SUB : " + subId);
                Subscription newSub = new Subscription();
                newSub.copyFrom(subData.subscription[subId.ordinal()]);
                mActivatePending.put(subId, newSub);
            }
        }

        // Start the set uicc subscription only if
        if (!mSetSubscriptionInProgress) {
            boolean deactivateInProgress = startNextPendingDeactivateRequests();
            if (deactivateInProgress) {
                mSetSubscriptionInProgress = true;
            } else {
                processActivateRequests();
            }
        }

        if (mSetSubscriptionInProgress) {
            // No set uicc request to process.
            ret = true;
        }
        return ret;
    }

    /**
     * Sets the designated data subscription source(DDS).
     * @param subscription
     * @param onCompleteMsg
     */
    public void setDataSubscription(int subscription, Message onCompleteMsg) {
        boolean result = false;
        RuntimeException exception;

        logd("setDataSubscription: mCurrentDds = "
                + mCurrentDds + " new subscription = " + subscription);

        if (!mDisableDdsInProgress) {

            if (!getCurrentSubscriptionReadiness(SubscriptionId.values()[subscription])) {
                logd("setDataSubscription: requested SUB:" + subscription
                        + " is not yet activated, returning failure");
                exception = new RuntimeException("Subscription not active");
            } else if (mCurrentDds != subscription) {
                /*add by YELLOWSTONE_hanjianjun for modify the function about switch data subcription 20121123 begin*/
                /*boolean flag = MSimProxyManager.getInstance()
                         .disableDataConnectivityFlag(mCurrentDds);*/
                MSimProxyManager.getInstance()
                         .disableDataConnectivity(mCurrentDds,null);
                /*add by YELLOWSTONE_hanjianjun for modify the function about switch data subcription 20121123 end*/
                mSetDdsCompleteMsg = onCompleteMsg;
                mQueuedDds = subscription;
                mDisableDdsInProgress = true;

                // Set the DDS in cmd interface
                Message msgSetDataSub = Message.obtain(this,
                        EVENT_SET_DATA_SUBSCRIPTION_DONE,
                        new Integer(mQueuedDds));
                Log.d(LOG_TAG, "Set DDS to " + mQueuedDds
                        + " Calling cmd interface setDataSubscription");

                //if (FeatureQuery.FEATURE_DATA_CONNECT_FOR_G) {
                    if (msgSetDataSub != null) {
                        AsyncResult.forMessage(msgSetDataSub,true, null);
                        msgSetDataSub.sendToTarget();
                        msgSetDataSub = null;
                    } 
                //} else {	
                //   mCi[mQueuedDds].setDataSubscription(msgSetDataSub);
                //}
                return;

            } else {
                logd("Current subscription is same as requested Subscription");
                result = true;
                exception = null;
            }
        } else {
            logd("DDS switch in progress. Sending false");
            exception = new RuntimeException("DDS switch in progress");
        }

        // Send the message back to callee with result.
        if (onCompleteMsg != null) {
            AsyncResult.forMessage(onCompleteMsg, result, exception);
            onCompleteMsg.sendToTarget();
        }
    }


    /**
     * Notifies handler when the SUB subId is deactivated.
     * @param subId
     * @param h
     * @param what
     * @param obj
     */
    public void registerForSubscriptionDeactivated(int subId, Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        synchronized (mSubDeactivatedRegistrants[subId]) {
            mSubDeactivatedRegistrants[subId].add(r);
        }
    }

    public void unregisterForSubscriptionDeactivated(int subId, Handler h) {
        synchronized (mSubDeactivatedRegistrants[subId]) {
            mSubDeactivatedRegistrants[subId].remove(h);
        }
    }

    /**
     * Notifies handler when the SUB subId is activated.
     * @param subId
     * @param h
     * @param what
     * @param obj
     */
    public void registerForSubscriptionActivated(int subId, Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        synchronized (mSubActivatedRegistrants[subId]) {
            mSubActivatedRegistrants[subId].add(r);
        }
    }

    public void unregisterForSubscriptionActivated(int subId, Handler h) {
        synchronized (mSubActivatedRegistrants[subId]) {
            mSubActivatedRegistrants[subId].remove(h);
        }
    }

    /**
     * Register for set subscription completed notification.
     * @param h
     * @param what
     * @param obj
     */
    public synchronized void registerForSetSubscriptionCompleted(Handler h, int what, Object obj) {
        Registrant r = new Registrant (h, what, obj);
        mSetSubscriptionRegistrants.add(r);
    }

    /**
     * Unregister for set subscription completed.
     * @param h
     */
    public synchronized void unRegisterForSetSubscriptionCompleted(Handler h) {
        mSetSubscriptionRegistrants.remove(h);
    }


    /**
     *  This function will read from the User Preferred Subscription from the
     *  system property, parse and populate the member variable mUserPrefSubs.
     *  User Preferred Subscription is stored in the system property string as
     *    iccId,appType,appId,activationStatus,3gppIndex,3gpp2Index
     *  If the the property is not set already, then set it to the default values
     *  for appType to USIM and activationStatus to ACTIVATED.
     */
    private void getUserPreferredSubs() {
        boolean errorOnParsing = false;

        mUserPrefSubs = new SubscriptionData(NUM_SUBSCRIPTIONS);

        for(int i = 0; i < NUM_SUBSCRIPTIONS; i++) {
            String strUserSub = Settings.System.getString(mContext.getContentResolver(),
                    Settings.System.USER_PREFERRED_SUBS[i]);
            if (strUserSub != null) {
                Log.d(LOG_TAG, "getUserPreferredSubs: strUserSub = " + strUserSub);

                try {
                    String splitUserSub[] = strUserSub.split(",");

                    // There should be 6 fields in the user preferred settings.
                    if (splitUserSub.length == USER_PREF_SUB_FIELDS) {
                        if (!TextUtils.isEmpty(splitUserSub[0])) {
                            mUserPrefSubs.subscription[i].iccId = splitUserSub[0];
                        }
                        if (!TextUtils.isEmpty(splitUserSub[1])) {
                            mUserPrefSubs.subscription[i].appType = splitUserSub[1];
                        }
                        if (!TextUtils.isEmpty(splitUserSub[2])) {
                            mUserPrefSubs.subscription[i].appId = splitUserSub[2];
                        }

                        try {
                            int subStatus = Integer.parseInt(splitUserSub[3]);
                            mUserPrefSubs.subscription[i].subStatus =
                                SubscriptionStatus.values()[subStatus];
                        } catch (NumberFormatException ex) {
                            Log.e(LOG_TAG, "getUserPreferredSubs: NumberFormatException: " + ex);
                            mUserPrefSubs.subscription[i].subStatus =
                                SubscriptionStatus.SUB_INVALID;
                        }

                        try {
                            mUserPrefSubs.subscription[i].m3gppIndex =
                                Integer.parseInt(splitUserSub[4]);
                        } catch (NumberFormatException ex) {
                            Log.e(LOG_TAG,
                                    "getUserPreferredSubs:m3gppIndex: NumberFormatException: "
                                    + ex);
                            mUserPrefSubs.subscription[i].m3gppIndex =
                                Subscription.SUBSCRIPTION_INDEX_INVALID;
                        }

                        try {
                            mUserPrefSubs.subscription[i].m3gpp2Index =
                                Integer.parseInt(splitUserSub[5]);
                        } catch (NumberFormatException ex) {
                            Log.e(LOG_TAG,
                                    "getUserPreferredSubs:m3gpp2Index: NumberFormatException: "
                                    + ex);
                            mUserPrefSubs.subscription[i].m3gpp2Index =
                                Subscription.SUBSCRIPTION_INDEX_INVALID;
                        }

                    } else {
                        Log.e(LOG_TAG,
                                "getUserPreferredSubs: splitUserSub.length != "
                                + USER_PREF_SUB_FIELDS);
                        errorOnParsing = true;
                    }
                } catch (PatternSyntaxException pe) {
                    Log.e(LOG_TAG,
                            "getUserPreferredSubs: PatternSyntaxException while split : "
                            + pe);
                    errorOnParsing = true;

                }
            }

            if (strUserSub == null || errorOnParsing) {
                String defaultUserSub = "" + ","        // iccId
                    + "" + ","                          // app type
                    + "" + ","                          // app id
                    + Integer.toString(SubscriptionStatus.SUB_INVALID.ordinal()) // activate state
                    + "," + Subscription.SUBSCRIPTION_INDEX_INVALID   // 3gppIndex in the card
                    + "," + Subscription.SUBSCRIPTION_INDEX_INVALID;  // 3gpp2Index in the card

                Settings.System.putString(mContext.getContentResolver(),
                        Settings.System.USER_PREFERRED_SUBS[i], defaultUserSub);

                mUserPrefSubs.subscription[i].iccId = null;
                mUserPrefSubs.subscription[i].appType = null;
                mUserPrefSubs.subscription[i].appId = null;
                mUserPrefSubs.subscription[i].subStatus = SubscriptionStatus.SUB_INVALID;
                mUserPrefSubs.subscription[i].m3gppIndex = Subscription.SUBSCRIPTION_INDEX_INVALID;
                mUserPrefSubs.subscription[i].m3gpp2Index = Subscription.SUBSCRIPTION_INDEX_INVALID;
            }

            mUserPrefSubs.subscription[i].subId = i;

            logd("getUserPreferredSubs: mUserPrefSubs.subscription[" + i + "] = "
                    + mUserPrefSubs.subscription[i]);
            
            if(mUserPrefSubs.subscription[0].subStatus == SubscriptionStatus.SUB_DEACTIVATED)
            {
            	mCardShouldEnable[0] = false;
            	
            }else
            {
            	mCardShouldEnable[0] = true;
            	
            }
            
            if(mUserPrefSubs.subscription[1].subStatus == SubscriptionStatus.SUB_DEACTIVATED)
            {
            	mCardShouldEnable[1] = false;
            	
            }else
            {
            	mCardShouldEnable[1] = true;
            	
            }
        }
    }

    private void saveUserPreferredSubscription(int subIndex, Subscription userPrefSub) {
        String userSub;
        if ((subIndex >= NUM_SUBSCRIPTIONS) || (userPrefSub == null)) {
            Log.d(LOG_TAG, "saveUserPreferredSubscription: INVALID PARAMETERS:"
                    + " subIndex = " + subIndex + " userPrefSub = " + userPrefSub);
            return;
        }

        // Update the user preferred sub
        mUserPrefSubs.subscription[subIndex].copyFrom(userPrefSub);
        mUserPrefSubs.subscription[subIndex].subId = subIndex;

        userSub = ((userPrefSub.iccId != null) ? userPrefSub.iccId : "") + ","
            + ((userPrefSub.appType != null) ? userPrefSub.appType : "") + ","
            + ((userPrefSub.appId != null) ? userPrefSub.appId : "") + ","
            + Integer.toString(userPrefSub.subStatus.ordinal()) + ","
            + Integer.toString(userPrefSub.m3gppIndex) + ","
            + Integer.toString(userPrefSub.m3gpp2Index);

        logd("saveUserPreferredSubscription: userPrefSub = " + userPrefSub);
        logd("saveUserPreferredSubscription: userSub = " + userSub);

        // Construct the string and store in Settings data base at subIndex.
        // update the user pref settings so that next time user is
        // not prompted of the subscriptions
        Settings.System.putString(mContext.getContentResolver(),
                Settings.System.USER_PREFERRED_SUBS[subIndex], userSub);
    }

    private void logd(String string) {
        Log.d(LOG_TAG, string);
    }

    private void loge(String string) {
        Log.e(LOG_TAG, string);
    }

    public int getActiveSubscriptionsCount() {
        int activeSubCount = 0;
        for (SubscriptionId sub: SubscriptionId.values()) {
            if (getCurrentSubscriptionStatus(sub) == SubscriptionStatus.SUB_ACTIVATED) {
                activeSubCount++;
            }
        }
        Log.d(LOG_TAG, "count of subs activated " + activeSubCount);
        return activeSubCount;
    }

    public boolean isSetSubscriptionInProgress() {
        return mSetSubscriptionInProgress;
    }

    /*
     ** check whether a new card insert, include the card change the slot
     */
    private boolean isCardChanaged() {
        for (int cardIndex=0; cardIndex<NUM_SUBSCRIPTIONS; cardIndex++) {
            SubscriptionData cardSubInfo = mCardSubMgr.getCardSubscriptions(cardIndex);
            Subscription userPrefSubscription = mUserPrefSubs.subscription[cardIndex];
            if ((cardSubInfo != null) && (!cardSubInfo.hasSubscription(userPrefSubscription))) {
                return true;
            }
        }
        return false;
    }

    public int getActiveDDS(){
        return mCurrentDds;
    }
    
    //give MultiSimEnabler a shortcut method to set radio power
    public void setRadioPowerOn(int subscriptionID, boolean flag)
    {
//    	mCi[subscriptionID].setRadioPower(flag, null);
    	 MSimPhoneFactory.getPhone(subscriptionID).setRadioPower(flag);
    }

}
