/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.net;

import static com.android.internal.telephony.DataConnectionTracker.CMD_SET_POLICY_DATA_ENABLE;
import static com.android.internal.telephony.DataConnectionTracker.CMD_SET_USER_DATA_ENABLE;
import static com.android.internal.telephony.DataConnectionTracker.DISABLED;
import static com.android.internal.telephony.DataConnectionTracker.ENABLED;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.NetworkInfo.DetailedState;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.TelephonyManager;
import android.telephony.MSimTelephonyManager;
import android.text.TextUtils;
import android.util.Slog;

import com.android.internal.telephony.DataConnectionTracker;
import com.android.internal.telephony.ITelephony;
import com.android.internal.telephony.msim.ITelephonyMSim;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.TelephonyIntents;
import com.android.internal.util.AsyncChannel;
import android.net.ConnectivityManager;
import android.telephony.MSimTelephonyManager;
import java.io.CharArrayWriter;
import java.io.PrintWriter;
import com.android.internal.telephony.MSimConstants;
/**
 * Track the state of mobile data connectivity. This is done by
 * receiving broadcast intents from the Phone process whenever
 * the state of data connectivity changes.
 *
 * {@hide}
 */

public class MSimMobileDataStateTracker implements NetworkStateTracker
{

    private static final String TAG = "MSimMobileDataStateTracker";
    private static final boolean DBG = true;
    private static final boolean VDBG = true;

    public class DataConnectionInfo {
        public Phone.DataState mMobileDataState ;
        public NetworkInfo mNetworkInfo ;
        public boolean mTeardownRequested = false;
        public LinkProperties mLinkProperties ;
        public LinkCapabilities mLinkCapabilities ;
        public boolean mPrivateDnsRouteSet = false;
        public boolean mDefaultRouteSet = false;
        public boolean mUserDataEnabled = true;
        public boolean mPolicyDataEnabled = true;
        public AsyncChannel mDataConnectionTrackerAc ;
        public Messenger mMessenger ;
    };
    private final int PHONE_COUNT = MSimTelephonyManager.getDefault().getPhoneCount();
    private DataConnectionInfo[] mConnecInfo = new DataConnectionInfo[PHONE_COUNT];
    //private Phone.DataState mMobileDataState;
    private ITelephony mPhoneService;
    private ITelephonyMSim mMSimPhoneService;

    private String mApnType;
    //private NetworkInfo mNetworkInfo;
    //private boolean mTeardownRequested = false;
    private Handler mTarget;
    private Context mContext;
    //private LinkProperties mLinkProperties;
    //private LinkCapabilities mLinkCapabilities;
    //private boolean mPrivateDnsRouteSet = false;
    //private boolean mDefaultRouteSet = false;
    // NOTE: these are only kept for debugging output; actual values are
    // maintained in DataConnectionTracker.
    //protected boolean mUserDataEnabled = true;
    //protected boolean mPolicyDataEnabled = true;

    private Handler mHandler;
    //private AsyncChannel mDataConnectionTrackerAc;
    //private Messenger mMessenger;

    /**
     * Create a new MobileDataStateTracker
     * @param netType the ConnectivityManager network type
     * @param tag the name of this network
     */
    public MSimMobileDataStateTracker(int netType, String tag)
    {
        for(int i =0;i<PHONE_COUNT;i++) {
          mConnecInfo[i] = new DataConnectionInfo();
          mConnecInfo[i].mNetworkInfo= new NetworkInfo(netType,
                                       TelephonyManager.getDefault().getNetworkType(), tag,
                                       TelephonyManager.getDefault().getNetworkTypeName());
          mConnecInfo[i].mNetworkInfo.setSubscription(i);
          mConnecInfo[i].mMobileDataState = Phone.DataState.DISCONNECTED;
        }
        mApnType = networkTypeToApnType(netType);
    }


    /**
     * Begin monitoring data connectivity.
     *
     * @param context is the current Android context
     * @param target is the Hander to which to return the events.
     */
    public void startMonitoring(Context context, Handler target)
    {
        mTarget = target;
        mContext = context;

        mHandler = new MdstHandler(target.getLooper(), this);

        IntentFilter filter = new IntentFilter();
        filter.addAction(TelephonyIntents.ACTION_ANY_DATA_CONNECTION_STATE_CHANGED);
        filter.addAction(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED);
        filter.addAction(DataConnectionTracker.ACTION_DATA_CONNECTION_TRACKER_MESSENGER);

        mContext.registerReceiver(new MSimMobileDataStateReceiver(), filter);
    }

    static class MdstHandler extends Handler
    {
        private MSimMobileDataStateTracker mMdst;

        MdstHandler(Looper looper, MSimMobileDataStateTracker mdst)
        {
            super(looper);
            mMdst = mdst;
        }

        @Override
        public void handleMessage(Message msg)
        {
            switch (msg.what)
            {
            case AsyncChannel.CMD_CHANNEL_HALF_CONNECTED:
                if (msg.arg1 == AsyncChannel.STATUS_SUCCESSFUL)
                {
                    if (VDBG)
                    {
                        mMdst.log("MdstHandler connected,subscription is:"+msg.arg2);
                    }

                    mMdst.mConnecInfo[msg.arg2].mDataConnectionTrackerAc = (AsyncChannel) msg.obj;
                }
                else
                {
                    if (VDBG)
                    {
                        mMdst.log("MdstHandler %s NOT connected error=" + msg.arg1);
                    }
                }

                break;

            case AsyncChannel.CMD_CHANNEL_DISCONNECTED:
                if (VDBG) mMdst.log("Disconnected from DataStateTracker,subscription is:"+msg.arg2);
                mMdst.mConnecInfo[msg.arg2].mDataConnectionTrackerAc = null;
                break;

            default:
            {
                if (VDBG) mMdst.log("Ignorning unknown message=" + msg);

                break;
            }
            }
        }
    }

    public boolean isPrivateDnsRouteSet()
    {
        return mConnecInfo[getDefaultSubscription()].mPrivateDnsRouteSet;
    }

    public boolean isPrivateDnsRouteSet(int subscription)
    {
        return mConnecInfo[subscription].mPrivateDnsRouteSet;
    }

    public void privateDnsRouteSet(boolean enabled)
    {
        mConnecInfo[getDefaultSubscription()].mPrivateDnsRouteSet = enabled;
    }

    public void privateDnsRouteSet(boolean enabled,int subscription)
    {
        mConnecInfo[subscription].mPrivateDnsRouteSet = enabled;
    }

    public NetworkInfo getNetworkInfo()
    {
        return mConnecInfo[getDefaultSubscription()].mNetworkInfo;
    }

    public NetworkInfo getNetworkInfo(int subscription)
    {
     log("getNetworkInfo subscription=" + subscription);
        return mConnecInfo[subscription].mNetworkInfo;
    }

    public boolean isDefaultRouteSet()
    {
        return mConnecInfo[getDefaultSubscription()].mDefaultRouteSet;
    }

    public boolean isDefaultRouteSet(int subscription)
    {
        return mConnecInfo[subscription].mDefaultRouteSet;
    }

    public void defaultRouteSet(boolean enabled)
    {
        mConnecInfo[getDefaultSubscription()].mDefaultRouteSet = enabled;
    }

    public void defaultRouteSet(boolean enabled,int subscription)
    {
        mConnecInfo[subscription].mDefaultRouteSet = enabled;
    }

    /**
     * This is not implemented.
     */
    public void releaseWakeLock()
    {
    }

    private class MSimMobileDataStateReceiver extends BroadcastReceiver
    {
        @Override
        public void onReceive(Context context, Intent intent)
        {
            if (intent.getAction().equals(TelephonyIntents.
                                          ACTION_ANY_DATA_CONNECTION_STATE_CHANGED))
            {
                String apnType = intent.getStringExtra(Phone.DATA_APN_TYPE_KEY);
                int subscription = intent.getIntExtra(Phone.DATA_SUBSCRIPTION_KEY,0);
                if (!TextUtils.equals(apnType, mApnType))
                {
                    return;
                }

                if (VDBG)
                {
                    log(String.format("Broadcast received: ACTION_ANY_DATA_CONNECTION_STATE_CHANGED"
                                      + "mApnType=%s %s received apnType=%s,subscription = %d", mApnType,
                                      TextUtils.equals(apnType, mApnType) ? "==" : "!=", apnType,subscription));
                }

                mConnecInfo[subscription].mNetworkInfo.setSubtype(TelephonyManager.getDefault().getNetworkType(),
                                        TelephonyManager.getDefault().getNetworkTypeName());
                Phone.DataState state = Enum.valueOf(Phone.DataState.class,
                                                     intent.getStringExtra(Phone.STATE_KEY));
                String reason = intent.getStringExtra(Phone.STATE_CHANGE_REASON_KEY);
                String apnName = intent.getStringExtra(Phone.DATA_APN_KEY);
                mConnecInfo[subscription].mNetworkInfo.setRoaming(intent.getBooleanExtra(Phone.DATA_NETWORK_ROAMING_KEY,
                                        false));

                if (VDBG)
                {
                    log(mApnType + " setting isAvailable to " +
                        !intent.getBooleanExtra(Phone.NETWORK_UNAVAILABLE_KEY, false));
                }

                mConnecInfo[subscription].mNetworkInfo.setIsAvailable(!intent.getBooleanExtra(Phone.NETWORK_UNAVAILABLE_KEY,
                                            false));

                if (DBG)
                {
                    log("Received state=" + state + ", old=" + mConnecInfo[subscription].mMobileDataState +
                        ", reason=" + (reason == null ? "(unspecified)" : reason));
                }

                if (mConnecInfo[subscription].mMobileDataState != state)
                {
                    mConnecInfo[subscription].mMobileDataState = state;

                    switch (state)
                    {
                    case DISCONNECTED:
                        if(isTeardownRequested(subscription))
                        {
                            setTeardownRequested(false,subscription);
                        }
                        mConnecInfo[subscription].mLinkProperties = null;
                        mConnecInfo[subscription].mLinkCapabilities = null;
                        setDetailedState(DetailedState.DISCONNECTED, reason, apnName,subscription);
                        // can't do this here - ConnectivityService needs it to clear stuff
                        // it's ok though - just leave it to be refreshed next time
                        // we connect.
                        //if (DBG) log("clearing mInterfaceName for "+ mApnType +
                        //        " as it DISCONNECTED");
                        //mInterfaceName = null;
                        break;

                    case CONNECTING:
                        setDetailedState(DetailedState.CONNECTING, reason, apnName,subscription);
                        break;

                    case SUSPENDED:
                        setDetailedState(DetailedState.SUSPENDED, reason, apnName,subscription);
                        break;

                    case CONNECTED:
                        mConnecInfo[subscription].mLinkProperties = intent.getParcelableExtra(
                                              Phone.DATA_LINK_PROPERTIES_KEY);

                        if (mConnecInfo[subscription].mLinkProperties == null)
                        {
                            loge("CONNECTED event did not supply link properties.");
                            mConnecInfo[subscription].mLinkProperties = new LinkProperties();
                        }

                        mConnecInfo[subscription].mLinkCapabilities = intent.getParcelableExtra(
                                                Phone.DATA_LINK_CAPABILITIES_KEY);

                        if (mConnecInfo[subscription].mLinkCapabilities == null)
                        {
                            loge("CONNECTED event did not supply link capabilities.");
                            mConnecInfo[subscription].mLinkCapabilities = new LinkCapabilities();
                        }

                        setDetailedState(DetailedState.CONNECTED, reason, apnName,subscription);
                        break;
                    }
                }
                else
                {
                    // There was no state change. Check if LinkProperties has been updated.
                    if (TextUtils.equals(reason, Phone.REASON_LINK_PROPERTIES_CHANGED))
                    {
                        mConnecInfo[subscription].mLinkProperties = intent.getParcelableExtra(Phone.DATA_LINK_PROPERTIES_KEY);

                        if (mConnecInfo[subscription].mLinkProperties == null)
                        {
                            loge("No link property in LINK_PROPERTIES change event.");
                            mConnecInfo[subscription].mLinkProperties = new LinkProperties();
                        }

                        // Just update reason field in this NetworkInfo
                        mConnecInfo[subscription].mNetworkInfo.setDetailedState(mConnecInfo[subscription].mNetworkInfo.getDetailedState(), reason,
                                                      mConnecInfo[subscription].mNetworkInfo.getExtraInfo());
                        Message msg = mTarget.obtainMessage(EVENT_CONFIGURATION_CHANGED,
                                                            mConnecInfo[subscription].mNetworkInfo);
                        msg.sendToTarget();
                    }
                }
            }
            else if (intent.getAction().
                     equals(TelephonyIntents.ACTION_DATA_CONNECTION_FAILED))
            {
                String apnType = intent.getStringExtra(Phone.DATA_APN_TYPE_KEY);
                int subscription = intent.getIntExtra(Phone.DATA_SUBSCRIPTION_KEY,0);
                if (!TextUtils.equals(apnType, mApnType))
                {
                    if (DBG)
                    {
                        log(String.format(
                                "Broadcast received: ACTION_ANY_DATA_CONNECTION_FAILED ignore, " +
                                "mApnType=%s != received apnType=%s", mApnType, apnType));
                    }

                    return;
                }

                String reason = intent.getStringExtra(Phone.FAILURE_REASON_KEY);
                String apnName = intent.getStringExtra(Phone.DATA_APN_KEY);

                if (DBG)
                {
                    log("Received " + intent.getAction() +
                        " broadcast" + reason == null ? "" : "(" + reason + ")");
                }

                setDetailedState(DetailedState.FAILED, reason, apnName,subscription);
            }
            else if (intent.getAction().
                     equals(DataConnectionTracker.ACTION_DATA_CONNECTION_TRACKER_MESSENGER))
            {
                if (VDBG) log(mApnType + " got ACTION_DATA_CONNECTION_TRACKER_MESSENGER");
                int subscription = intent.getIntExtra(Phone.DATA_SUBSCRIPTION_KEY,0);
                if (VDBG) log(" got subscription is:"+subscription);
                mConnecInfo[subscription].mMessenger = intent.getParcelableExtra(DataConnectionTracker.EXTRA_MESSENGER);
                AsyncChannel ac = new AsyncChannel(subscription);
                ac.connect(mContext, MSimMobileDataStateTracker.this.mHandler, mConnecInfo[subscription].mMessenger);
            }
            else
            {
                if (DBG) log("Broadcast received: ignore " + intent.getAction());
            }
        }
    }

    private void getPhoneService(boolean forceRefresh)
    {
        if (MSimTelephonyManager.getDefault().isMultiSimEnabled())
        {
            if (mMSimPhoneService == null || forceRefresh)
            {
                mMSimPhoneService = ITelephonyMSim.Stub.asInterface(
                                        ServiceManager.getService("phone_msim"));
            }

            return;
        }

        if ((mPhoneService == null) || forceRefresh)
        {
            mPhoneService = ITelephony.Stub.asInterface(ServiceManager.getService("phone"));
        }
    }

    /**
     * Report whether data connectivity is possible.
     */
    public boolean isAvailable()
    {
        return mConnecInfo[getDefaultSubscription()].mNetworkInfo.isAvailable();
    }

    /**
     * Report whether data connectivity is possible.
     */
    public boolean isAvailable(int subscription)
    {
        return mConnecInfo[subscription].mNetworkInfo.isAvailable();
    }

    /**
     * Return the system properties name associated with the tcp buffer sizes
     * for this network.
     */
    public String getTcpBufferSizesPropName()
    {
        String networkTypeStr = "unknown";
        TelephonyManager tm = new TelephonyManager(mContext);

        //TODO We have to edit the parameter for getNetworkType regarding CDMA
        switch(tm.getNetworkType())
        {
        case TelephonyManager.NETWORK_TYPE_GPRS:
            networkTypeStr = "gprs";
            break;

        case TelephonyManager.NETWORK_TYPE_EDGE:
            networkTypeStr = "edge";
            break;

        case TelephonyManager.NETWORK_TYPE_UMTS:
            networkTypeStr = "umts";
            break;

        case TelephonyManager.NETWORK_TYPE_HSDPA:
            networkTypeStr = "hsdpa";
            break;

        case TelephonyManager.NETWORK_TYPE_HSUPA:
            networkTypeStr = "hsupa";
            break;

        case TelephonyManager.NETWORK_TYPE_HSPA:
        case TelephonyManager.NETWORK_TYPE_HSPAP:
            networkTypeStr = "hspa";
            break;

        case TelephonyManager.NETWORK_TYPE_CDMA:
            networkTypeStr = "cdma";
            break;

        case TelephonyManager.NETWORK_TYPE_1xRTT:
            networkTypeStr = "1xrtt";
            break;

        case TelephonyManager.NETWORK_TYPE_EVDO_0:
            networkTypeStr = "evdo";
            break;

        case TelephonyManager.NETWORK_TYPE_EVDO_A:
            networkTypeStr = "evdo";
            break;

        case TelephonyManager.NETWORK_TYPE_EVDO_B:
            networkTypeStr = "evdo_b";
            break;

        case TelephonyManager.NETWORK_TYPE_IDEN:
            networkTypeStr = "iden";
            break;

        case TelephonyManager.NETWORK_TYPE_LTE:
            networkTypeStr = "lte";
            break;

        case TelephonyManager.NETWORK_TYPE_EHRPD:
            networkTypeStr = "ehrpd";
            break;

        default:
            loge("unknown network type: " + tm.getNetworkType());
        }

        return "net.tcp.buffersize." + networkTypeStr;
    }

    /**
     * Tear down mobile data connectivity, i.e., disable the ability to create
     * mobile data connections.
     * TODO - make async and return nothing?
     */
    public boolean teardown()
    {
        setTeardownRequested(true);
        return (setEnableApn(mApnType, false) != Phone.APN_REQUEST_FAILED);
    }

    public boolean teardown(int subscription)
    {
        setTeardownRequested(true,subscription);
        return (setEnableApn(mApnType, false, subscription) != Phone.APN_REQUEST_FAILED);
    }

    /**
     * Record the detailed state of a network, and if it is a
     * change from the previous state, send a notification to
     * any listeners.
     * @param state the new @{code DetailedState}
     * @param reason a {@code String} indicating a reason for the state change,
     * if one was supplied. May be {@code null}.
     * @param extraInfo optional {@code String} providing extra information about the state change
     */
    private void setDetailedState(NetworkInfo.DetailedState state, String reason,
                                  String extraInfo)
    {
        if (DBG) log("setDetailed state, old ="
                         + mConnecInfo[getDefaultSubscription()].mNetworkInfo.getDetailedState() + " and new state=" + state);

        if (state != mConnecInfo[getDefaultSubscription()].mNetworkInfo.getDetailedState())
        {
            boolean wasConnecting = (mConnecInfo[getDefaultSubscription()].mNetworkInfo.getState() == NetworkInfo.State.CONNECTING);
            String lastReason = mConnecInfo[getDefaultSubscription()].mNetworkInfo.getReason();

            /*
             * If a reason was supplied when the CONNECTING state was entered, and no
             * reason was supplied for entering the CONNECTED state, then retain the
             * reason that was supplied when going to CONNECTING.
             */
            if (wasConnecting && state == NetworkInfo.DetailedState.CONNECTED && reason == null
                    && lastReason != null)
                reason = lastReason;

            mConnecInfo[getDefaultSubscription()].mNetworkInfo.setDetailedState(state, reason, extraInfo);
            Message msg = mTarget.obtainMessage(EVENT_STATE_CHANGED, new NetworkInfo(mConnecInfo[getDefaultSubscription()].mNetworkInfo));
            msg.sendToTarget();
        }
    }

    /**
     * Record the detailed state of a network, and if it is a
     * change from the previous state, send a notification to
     * any listeners.
     * @param state the new @{code DetailedState}
     * @param reason a {@code String} indicating a reason for the state change,
     * if one was supplied. May be {@code null}.
     * @param extraInfo optional {@code String} providing extra information about the state change
     */
    private void setDetailedState(NetworkInfo.DetailedState state, String reason,
                                  String extraInfo,int subscription)
    {
        if (DBG) log("setDetailed state, old ="
                         + mConnecInfo[getDefaultSubscription()].mNetworkInfo.getDetailedState() + " and new state=" + state);

        if (state != mConnecInfo[subscription].mNetworkInfo.getDetailedState())
        {
            boolean wasConnecting = (mConnecInfo[subscription].mNetworkInfo.getState() == NetworkInfo.State.CONNECTING);
            String lastReason = mConnecInfo[subscription].mNetworkInfo.getReason();

            /*
             * If a reason was supplied when the CONNECTING state was entered, and no
             * reason was supplied for entering the CONNECTED state, then retain the
             * reason that was supplied when going to CONNECTING.
             */
            if (wasConnecting && state == NetworkInfo.DetailedState.CONNECTED && reason == null
                    && lastReason != null)
                reason = lastReason;

            mConnecInfo[subscription].mNetworkInfo.setDetailedState(state, reason, extraInfo);
            Message msg = mTarget.obtainMessage(EVENT_STATE_CHANGED,
                                                new NetworkInfo(mConnecInfo[subscription].mNetworkInfo)
                                                );
            msg.sendToTarget();
        }
    }

    public void setTeardownRequested(boolean isRequested)
    {
        mConnecInfo[getDefaultSubscription()].mTeardownRequested = isRequested;
    }

    public boolean isTeardownRequested()
    {
        return mConnecInfo[getDefaultSubscription()].mTeardownRequested;
    }

    public void setTeardownRequested(boolean isRequested,int subscription)
    {
        mConnecInfo[subscription].mTeardownRequested = isRequested;
    }

    public boolean isTeardownRequested(int subscription)
    {
        return mConnecInfo[subscription].mTeardownRequested;
    }

    /**
     * Re-enable mobile data connectivity after a {@link #teardown()}.
     * TODO - make async and always get a notification?
     */
    public boolean reconnect()
    {
        boolean retValue = false; //connected or expect to be?
        setTeardownRequested(false);

        switch (setEnableApn(mApnType, true))
        {
        case Phone.APN_ALREADY_ACTIVE:
            // need to set self to CONNECTING so the below message is handled.
            retValue = true;
            break;

        case Phone.APN_REQUEST_STARTED:
            // set IDLE here , avoid the following second FAILED not sent out
            mConnecInfo[getDefaultSubscription()].mNetworkInfo.setDetailedState(DetailedState.IDLE, null, null);
            retValue = true;
            break;

        case Phone.APN_REQUEST_FAILED:
        case Phone.APN_TYPE_NOT_AVAILABLE:
            break;

        default:
            loge("Error in reconnect - unexpected response.");
            break;
        }

        return retValue;
    }

    public boolean reconnect(int subscription)
    {
        boolean retValue = false; //connected or expect to be?
        setTeardownRequested(false,subscription);
        log("********reconnect with subscription is:"+subscription);
        switch (setEnableApn(mApnType, true, subscription))
        {
        case Phone.APN_ALREADY_ACTIVE:
            // need to set self to CONNECTING so the below message is handled.
            retValue = true;
            break;

        case Phone.APN_REQUEST_STARTED:
            // set IDLE here , avoid the following second FAILED not sent out
            mConnecInfo[subscription].mNetworkInfo.setDetailedState(DetailedState.IDLE, null, null);
            retValue = true;
            break;

        case Phone.APN_REQUEST_FAILED:
        case Phone.APN_TYPE_NOT_AVAILABLE:
            break;

        default:
            loge("Error in reconnect - unexpected response.");
            break;
        }

        return retValue;
    }

    /**
     * Turn on or off the mobile radio. No connectivity will be possible while the
     * radio is off. The operation is a no-op if the radio is already in the desired state.
     * @param turnOn {@code true} if the radio should be turned on, {@code false} if
     */
    public boolean setRadio(boolean turnOn)
    {
        getPhoneService(false);

        /*
         * If the phone process has crashed in the past, we'll get a
         * RemoteException and need to re-reference the service.
         */
        for (int retry = 0; retry < 2; retry++)
        {
            if (MSimTelephonyManager.getDefault().isMultiSimEnabled())
            {
                if (mMSimPhoneService == null)
                {
                    loge("Ignoring mobile radio request because "
                         + "could not acquire MSim Phone Service");
                    break;
                }

                try
                {
                    boolean result = true;

                    for (int i = 0; i < MSimTelephonyManager.getDefault().getPhoneCount(); i++)
                    {
                        result = result && mMSimPhoneService.setRadio(turnOn, i);
                    }

                    return result;
                }
                catch (RemoteException e)
                {
                    if (retry == 0) getPhoneService(true);
                }
            }
            else
            {
                if (mPhoneService == null)
                {
                    loge("Ignoring mobile radio request because could not acquire PhoneService");
                    break;
                }

                try
                {
                    return mPhoneService.setRadio(turnOn);
                }
                catch (RemoteException e)
                {
                    if (retry == 0) getPhoneService(true);
                }
            }
        }

        loge("Could not set radio power to " + (turnOn ? "on" : "off"));
        return false;
    }

    public void setUserDataEnable(boolean enabled)
    {
        if (DBG) log("setUserDataEnable: E enabled=" + enabled);

        final AsyncChannel channel = mConnecInfo[getDefaultSubscription()].mDataConnectionTrackerAc;

        if (channel != null)
        {
            channel.sendMessage(CMD_SET_USER_DATA_ENABLE, enabled ? ENABLED : DISABLED);
            mConnecInfo[getDefaultSubscription()].mUserDataEnabled = enabled;
        }

        if (VDBG) log("setUserDataEnable: X enabled=" + enabled);
    }

    public void setPolicyDataEnable(boolean enabled)
    {
        if (DBG) log("setPolicyDataEnable(enabled=" + enabled + ")");

        final AsyncChannel channel = mConnecInfo[getDefaultSubscription()].mDataConnectionTrackerAc;

        if (channel != null)
        {
            channel.sendMessage(CMD_SET_POLICY_DATA_ENABLE, enabled ? ENABLED : DISABLED);
            mConnecInfo[getDefaultSubscription()].mPolicyDataEnabled = enabled;
        }
    }

    public void setUserDataEnable(boolean enabled,int subscription)
    {
        if (DBG) log("setUserDataEnable: E enabled=" + enabled);

        final AsyncChannel channel = mConnecInfo[subscription].mDataConnectionTrackerAc;

        if (channel != null)
        {
            channel.sendMessage(CMD_SET_USER_DATA_ENABLE, enabled ? ENABLED : DISABLED);
            mConnecInfo[subscription].mUserDataEnabled = enabled;
        }

        if (VDBG) log("setUserDataEnable: X enabled=" + enabled);
    }

    public void setPolicyDataEnable(boolean enabled,int subscription)
    {
        if (DBG) log("setPolicyDataEnable(enabled=" + enabled + ")");

        final AsyncChannel channel = mConnecInfo[subscription].mDataConnectionTrackerAc;

        if (channel != null)
        {
            channel.sendMessage(CMD_SET_POLICY_DATA_ENABLE, enabled ? ENABLED : DISABLED);
            mConnecInfo[subscription].mPolicyDataEnabled = enabled;
        }
    }

    /**
     * carrier dependency is met/unmet
     * @param met
     */
    public void setDependencyMet(boolean met)
    {
        Bundle bundle = Bundle.forPair(DataConnectionTracker.APN_TYPE_KEY, mApnType);

        try
        {
            if (DBG) log("setDependencyMet: E met=" + met);

            Message msg = Message.obtain();
            msg.what = DataConnectionTracker.CMD_SET_DEPENDENCY_MET;
            msg.arg1 = (met ? DataConnectionTracker.ENABLED : DataConnectionTracker.DISABLED);
            msg.setData(bundle);
            mConnecInfo[getDefaultSubscription()].mDataConnectionTrackerAc.sendMessage(msg);

            if (VDBG) log("setDependencyMet: X met=" + met);
        }
        catch (NullPointerException e)
        {
            loge("setDependencyMet: X mAc was null" + e);
        }
    }

    /**
     * carrier dependency is met/unmet
     * @param met
     */
    public void setDependencyMet(boolean met,int subscription)
    {
        Bundle bundle = Bundle.forPair(DataConnectionTracker.APN_TYPE_KEY, mApnType);

        try
        {
            if (DBG) log("setDependencyMet: E met=" + met);

            Message msg = Message.obtain();
            msg.what = DataConnectionTracker.CMD_SET_DEPENDENCY_MET;
            msg.arg1 = (met ? DataConnectionTracker.ENABLED : DataConnectionTracker.DISABLED);
            msg.setData(bundle);
            mConnecInfo[subscription].mDataConnectionTrackerAc.sendMessage(msg);

            if (VDBG) log("setDependencyMet: X met=" + met);
        }
        catch (NullPointerException e)
        {
            loge("setDependencyMet: X mAc was null" + e);
        }
    }

    public String toString()
    {
        final CharArrayWriter writer = new CharArrayWriter();
        final PrintWriter pw = new PrintWriter(writer);
        pw.print("Mobile data state: ");
        pw.println(mConnecInfo[0].mMobileDataState);
        pw.print("Data enabled: user=");
        pw.print(mConnecInfo[0].mUserDataEnabled);
        pw.print(", policy=");
        pw.println(mConnecInfo[0].mPolicyDataEnabled);
        return writer.toString();
    }

    /**
      * Internal method supporting the ENABLE_MMS feature.
      * @param apnType the type of APN to be enabled or disabled (e.g., mms)
      * @param enable {@code true} to enable the specified APN type,
      * {@code false} to disable it.
      * @return an integer value representing the outcome of the request.
      */
    private int setEnableApn(String apnType, boolean enable)
    {
        getPhoneService(false);

        /*
         * If the phone process has crashed in the past, we'll get a
         * RemoteException and need to re-reference the service.
         */
        for (int retry = 0; retry < 2; retry++)
        {
            if (MSimTelephonyManager.getDefault().isMultiSimEnabled())
            {
                if (mMSimPhoneService == null)
                {
                    loge("Ignoring feature request because could not acquire MSim Phone Service");
                    break;
                }

                try
                {
                    if (enable)
                    {
                        return mMSimPhoneService.enableApnType(apnType);
                    }
                    else
                    {
                        return mMSimPhoneService.disableApnType(apnType);
                    }
                }
                catch (RemoteException e)
                {
                    if (retry == 0) getPhoneService(true);
                }
            }
            else
            {
                if (mPhoneService == null)
                {
                    loge("Ignoring feature request because could not acquire PhoneService");
                    break;
                }

                try
                {
                    if (enable)
                    {
                        return mPhoneService.enableApnType(apnType);
                    }
                    else
                    {
                        return mPhoneService.disableApnType(apnType);
                    }
                }
                catch (RemoteException e)
                {
                    if (retry == 0) getPhoneService(true);
                }
            }
        }

        loge("Could not " + (enable ? "enable" : "disable") + " APN type \"" + apnType + "\"");
        return Phone.APN_REQUEST_FAILED;
    }

    private int setEnableApn(String apnType, boolean enable, int subscription)
    {
        getPhoneService(false);
        log("******setEnable, enable = " + enable + "subscription = " + subscription +"isMultiSimEnabled = "+MSimTelephonyManager.getDefault().isMultiSimEnabled());
        /*
        * If the phone process has crashed in the past, we'll get a
        * RemoteException and need to re-reference the service.
        */
        for (int retry = 0; retry < 2; retry++)
        {
            if (MSimTelephonyManager.getDefault().isMultiSimEnabled())//TelephonyManager.getDefault().isMultiSimEnabled()
            {
                if (mMSimPhoneService == null)
                {
                    loge("Ignoring feature request because could not acquire MSim Phone Service");
                    break;
                }

                try
                {
                    if (enable)
                    {
                        log("begin to call enableAPnTpewithSubscription ---enable = " + enable);
                       // return mMSimPhoneService.enableApnType(apnType);//enableApnTypeWithSubScription(apnType, subscription);
                        return mMSimPhoneService.enableApnTypeWithSubScription(apnType, subscription);
                    }
                    else
                    {
                       log("begin to call enableAPnTpewithSubscription ---enable = " + enable);
                       // return mMSimPhoneService.disableApnType(apnType);//disableApnTypeWithSubScription(apnType, subscription);
                        return mMSimPhoneService.disableApnTypeWithSubScription(apnType, subscription);
                    }
                }
                catch (RemoteException e)
                {
                    if (retry == 0) getPhoneService(true);
                }
            }
            else
            {
                log("TelephonyManager.getDefault().isMultiSimEnabled() = " + MSimTelephonyManager.getDefault().isMultiSimEnabled());
                if (mPhoneService == null)
                {
                    loge("Ignoring feature request because could not acquire PhoneService");
                    break;
                }

                try
                {
                    if (enable)
                    {
                        return mPhoneService.enableApnType(apnType);
                    }
                    else
                    {
                        return mPhoneService.disableApnType(apnType);
                    }
                }
                catch (RemoteException e)
                {
                    if (retry == 0) getPhoneService(true);
                }
            }
        }

        loge("Could not " + (enable ? "enable" : "disable") + " APN type \"" + apnType + "\"");
        return Phone.APN_REQUEST_FAILED;
    }

    public static String networkTypeToApnType(int netType)
    {
        switch(netType)
        {
        case ConnectivityManager.TYPE_MOBILE:
            return Phone.APN_TYPE_DEFAULT;  // TODO - use just one of these

        case ConnectivityManager.TYPE_MOBILE_MMS:
            return Phone.APN_TYPE_MMS;

        case ConnectivityManager.TYPE_MOBILE_SUPL:
            return Phone.APN_TYPE_SUPL;

        case ConnectivityManager.TYPE_MOBILE_DUN:
            return Phone.APN_TYPE_DUN;

        case ConnectivityManager.TYPE_MOBILE_HIPRI:
            return Phone.APN_TYPE_HIPRI;

        case ConnectivityManager.TYPE_MOBILE_FOTA:
            return Phone.APN_TYPE_FOTA;

        case ConnectivityManager.TYPE_MOBILE_IMS:
            return Phone.APN_TYPE_IMS;

        case ConnectivityManager.TYPE_MOBILE_CBS:
            return Phone.APN_TYPE_CBS;

        default:
            sloge("Error mapping networkType " + netType + " to apnType.");
            return null;
        }
    }

    /**
     * @see android.net.NetworkStateTracker#getLinkProperties()
     */
    public LinkProperties getLinkProperties()
    {
        return new LinkProperties(mConnecInfo[getDefaultSubscription()].mLinkProperties);
    }

    /**
     * @see android.net.NetworkStateTracker#getLinkCapabilities()
     */
    public LinkCapabilities getLinkCapabilities()
    {
        return new LinkCapabilities(mConnecInfo[getDefaultSubscription()].mLinkCapabilities);
    }

    /**
     * @see android.net.NetworkStateTracker#getLinkProperties()
     */
    public LinkProperties getLinkProperties(int subscription)
    {
        return new LinkProperties(mConnecInfo[subscription].mLinkProperties);
    }

    /**
     * @see android.net.NetworkStateTracker#getLinkCapabilities()
     */
    public LinkCapabilities getLinkCapabilities(int subscription)
    {
        return new LinkCapabilities(mConnecInfo[subscription].mLinkCapabilities);
    }

    private int getDefaultSubscription() {
        getPhoneService(false);
        try {
            return mMSimPhoneService.getDefaultSubscription();
        } catch (RemoteException ex) {
            return MSimConstants.DEFAULT_SUBSCRIPTION;
        } catch (NullPointerException ex) {
            return MSimConstants.DEFAULT_SUBSCRIPTION;
        }
    }
    private void log(String s)
    {
        Slog.d(TAG, mApnType + ": " + s);
    }

    private void loge(String s)
    {
        Slog.e(TAG, mApnType + ": " + s);
    }

    static private void sloge(String s)
    {
        Slog.e(TAG, s);
    }
}
