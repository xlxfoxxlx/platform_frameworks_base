/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.keyguard;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.TypedArray;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.telephony.ServiceState;
import android.telephony.SubscriptionInfo;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.text.method.SingleLineTransformationMethod;
import android.util.AttributeSet;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import com.android.internal.telephony.IccCardConstants;
import com.android.internal.telephony.IccCardConstants.State;
import com.android.internal.telephony.TelephonyIntents;
import com.android.settingslib.WirelessUtils;

import android.telephony.TelephonyManager;

import com.android.systemui.statusbar.policy.FiveGServiceClient;
import com.android.systemui.statusbar.policy.FiveGServiceClient.FiveGServiceState;

public class CarrierText extends TextView {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "CarrierText";

    private static CharSequence mSeparator;

    private final boolean mIsEmergencyCallCapable;

    private boolean mTelephonyCapable;

    private boolean mShowMissingSim;

    private boolean mShowAirplaneMode;

    private KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    private WifiManager mWifiManager;

    private boolean[] mSimErrorState = new boolean[TelephonyManager.getDefault().getPhoneCount()];

    private FiveGServiceClient mFiveGServiceClient;

    private final KeyguardUpdateMonitorCallback mCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onRefreshCarrierInfo() {
            if (DEBUG) Log.d(TAG, "onRefreshCarrierInfo(), mTelephonyCapable: "
                    + Boolean.toString(mTelephonyCapable));
            updateCarrierText();
        }

        public void onFinishedGoingToSleep(int why) {
            setSelected(false);
        };

        public void onStartedWakingUp() {
            setSelected(true);
        };

        @Override
        public void onTelephonyCapable(boolean capable) {
            if (DEBUG) Log.d(TAG, "onTelephonyCapable() mTelephonyCapable: "
                    + Boolean.toString(capable));
            mTelephonyCapable = capable;
            updateCarrierText();
        }

        public void onSimStateChanged(int subId, int slotId, IccCardConstants.State simState) {
            if (slotId < 0) {
                Log.d(TAG, "onSimStateChanged() - slotId invalid: " + slotId
                        + " mTelephonyCapable: " + Boolean.toString(mTelephonyCapable));
                return;
            }

            if (DEBUG) Log.d(TAG,"onSimStateChanged: " + getStatusForIccState(simState));
            if (getStatusForIccState(simState) == StatusMode.SimIoError) {
                mSimErrorState[slotId] = true;
                updateCarrierText();
            } else if (mSimErrorState[slotId]) {
                mSimErrorState[slotId] = false;
                updateCarrierText();
            }
        }
    };

    /**
     * The status of this lock screen. Primarily used for widgets on LockScreen.
     */
    private static enum StatusMode {
        Normal, // Normal case (sim card present, it's not locked)
        NetworkLocked, // SIM card is 'network locked'.
        SimMissing, // SIM card is missing.
        SimMissingLocked, // SIM card is missing, and device isn't provisioned; don't allow access
        SimPukLocked, // SIM card is PUK locked because SIM entered wrong too many times
        SimLocked, // SIM card is currently locked
        SimPermDisabled, // SIM card is permanently disabled due to PUK unlock failure
        SimNotReady, // SIM is not ready yet. May never be on devices w/o a SIM.
        SimIoError, // SIM card is faulty
        SimUnknown // SIM card is unknown
    }

    public CarrierText(Context context) {
        this(context, null);
    }

    public CarrierText(Context context, AttributeSet attrs) {
        super(context, attrs);
        mIsEmergencyCallCapable = context.getResources().getBoolean(
                com.android.internal.R.bool.config_voice_capable);
        boolean useAllCaps;
        TypedArray a = context.getTheme().obtainStyledAttributes(
                attrs, R.styleable.CarrierText, 0, 0);
        try {
            useAllCaps = a.getBoolean(R.styleable.CarrierText_allCaps, false);
            mShowAirplaneMode = a.getBoolean(R.styleable.CarrierText_showAirplaneMode, false);
            mShowMissingSim = a.getBoolean(R.styleable.CarrierText_showMissingSim, false);
        } finally {
            a.recycle();
        }
        setTransformationMethod(new CarrierTextTransformationMethod(mContext, useAllCaps));

        mWifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
    }

    /**
     * Checks if there are faulty cards. Adds the text depending on the slot of the card
     * @param text: current carrier text based on the sim state
     * @param noSims: whether a valid sim card is inserted
     * @return text
    */
    private CharSequence updateCarrierTextWithSimIoError(CharSequence text, boolean noSims) {
        final CharSequence carrier = "";
        CharSequence carrierTextForSimIOError = getCarrierTextForSimState(
            IccCardConstants.State.CARD_IO_ERROR, carrier);
        for (int index = 0; index < mSimErrorState.length; index++) {
            if (mSimErrorState[index]) {
                // In the case when no sim cards are detected but a faulty card is inserted
                // overwrite the text and only show "Invalid card"
                if (noSims) {
                    return concatenate(carrierTextForSimIOError,
                        getContext().getText(com.android.internal.R.string.emergency_calls_only));
                } else if (index == 0) {
                    // prepend "Invalid card" when faulty card is inserted in slot 0
                    text = concatenate(carrierTextForSimIOError, text);
                } else {
                    // concatenate "Invalid card" when faulty card is inserted in slot 1
                    text = concatenate(text, carrierTextForSimIOError);
                }
            }
        }
        return text;
    }

    protected void updateCarrierText() {
        boolean allSimsMissing = true;
        boolean anySimReadyAndInService = false;
        boolean showLocale = getContext().getResources().getBoolean(
                com.android.systemui.R.bool.config_monitor_locale_change);
        boolean showRat = getContext().getResources().getBoolean(
                com.android.systemui.R.bool.config_display_rat);
        CharSequence displayText = null;

        List<SubscriptionInfo> subs = mKeyguardUpdateMonitor.getSubscriptionInfo(false);
        final int N = subs.size();
        if (DEBUG) Log.d(TAG, "updateCarrierText(): " + N);
        for (int i = 0; i < N; i++) {
            CharSequence networkClass = "";
            int subId = subs.get(i).getSubscriptionId();
            State simState = mKeyguardUpdateMonitor.getSimState(subId);
            if (showRat) {
                ServiceState ss = mKeyguardUpdateMonitor.mServiceStates.get(subId);
                if (ss != null && (ss.getDataRegState() == ServiceState.STATE_IN_SERVICE
                        || ss.getVoiceRegState() == ServiceState.STATE_IN_SERVICE)) {
                    int networkType = TelephonyManager.NETWORK_TYPE_UNKNOWN;
                    if (ss.getRilDataRadioTechnology() !=
                            ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
                        networkType = ss.getDataNetworkType();
                    } else if (ss.getRilVoiceRadioTechnology() !=
                                ServiceState.RIL_RADIO_TECHNOLOGY_UNKNOWN) {
                        networkType = ss.getVoiceNetworkType();
                    }
                    networkClass = networkClassToString(TelephonyManager
                            .getNetworkClass(networkType));
                    String fiveGNetworkClass = get5GNetworkClass(subs.get(i));
                    if ( fiveGNetworkClass != null ) {
                        networkClass = fiveGNetworkClass;
                    }

                }
            }
            CharSequence carrierName = subs.get(i).getCarrierName();
            if ((showLocale || showRat) && !TextUtils.isEmpty(carrierName)) {
                String[] names = carrierName.toString().split(mSeparator.toString(), 2);
                StringBuilder newCarrierName = new StringBuilder();
                for (int j = 0; j < names.length; j++) {
                    if (showLocale) {
                        names[j] = getLocalString(
                                names[j], com.android.systemui.R.array.origin_carrier_names,
                                com.android.systemui.R.array.locale_carrier_names);
                    }
                    if (!TextUtils.isEmpty(names[j])) {
                        if (!TextUtils.isEmpty(networkClass) && showRat) {
                            names[j] = new StringBuilder().append(names[j]).append(" ")
                                    .append(networkClass).toString();
                        }
                        if (j > 0 && names[j].equals(names[j-1])) {
                            continue;
                        }
                        if (j > 0) newCarrierName.append(mSeparator);
                        newCarrierName.append(names[j]);
                    }
                }
                carrierName = newCarrierName.toString();
            }
            CharSequence carrierTextForSimState = getCarrierTextForSimState(simState, carrierName);
            if (DEBUG) {
                Log.d(TAG, "Handling (subId=" + subId + "): " + simState + " " + carrierName);
            }
            if (carrierTextForSimState != null) {
                allSimsMissing = false;
                displayText = concatenate(displayText, carrierTextForSimState);
            }
            if (simState == IccCardConstants.State.READY) {
                ServiceState ss = mKeyguardUpdateMonitor.mServiceStates.get(subId);
                if (ss != null && ss.getDataRegState() == ServiceState.STATE_IN_SERVICE) {
                    // hack for WFC (IWLAN) not turning off immediately once
                    // Wi-Fi is disassociated or disabled
                    if (ss.getRilDataRadioTechnology() != ServiceState.RIL_RADIO_TECHNOLOGY_IWLAN
                            || (mWifiManager.isWifiEnabled()
                                    && mWifiManager.getConnectionInfo() != null
                                    && mWifiManager.getConnectionInfo().getBSSID() != null)) {
                        if (DEBUG) {
                            Log.d(TAG, "SIM ready and in service: subId=" + subId + ", ss=" + ss);
                        }
                        anySimReadyAndInService = true;
                    }
                }
            }
        }
        if (allSimsMissing) {
            if (N != 0) {
                // Shows "No SIM card | Emergency calls only" on devices that are voice-capable.
                // This depends on mPlmn containing the text "Emergency calls only" when the radio
                // has some connectivity. Otherwise, it should be null or empty and just show
                // "No SIM card"
                // Grab the first subscripton, because they all should contain the emergency text,
                // described above.
                displayText =  makeCarrierStringOnEmergencyCapable(
                        getMissingSimMessage(), subs.get(0).getCarrierName());
            } else {
                // We don't have a SubscriptionInfo to get the emergency calls only from.
                // Grab it from the old sticky broadcast if possible instead. We can use it
                // here because no subscriptions are active, so we don't have
                // to worry about MSIM clashing.
                CharSequence text =
                        getContext().getText(com.android.internal.R.string.emergency_calls_only);
                Intent i = getContext().registerReceiver(null,
                        new IntentFilter(TelephonyIntents.SPN_STRINGS_UPDATED_ACTION));
                if (i != null) {
                    String spn = "";
                    String plmn = "";
                    if (i.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_SPN, false)) {
                        spn = i.getStringExtra(TelephonyIntents.EXTRA_SPN);
                    }
                    if (i.getBooleanExtra(TelephonyIntents.EXTRA_SHOW_PLMN, false)) {
                        plmn = i.getStringExtra(TelephonyIntents.EXTRA_PLMN);
                    }
                    if (DEBUG) Log.d(TAG, "Getting plmn/spn sticky brdcst " + plmn + "/" + spn);
                    if (Objects.equals(plmn, spn)) {
                        text = plmn;
                    } else {
                        text = concatenate(plmn, spn);
                    }
                }
                displayText =  makeCarrierStringOnEmergencyCapable(getMissingSimMessage(), text);
            }
        }

        displayText = updateCarrierTextWithSimIoError(displayText, allSimsMissing);
        // APM (airplane mode) != no carrier state. There are carrier services
        // (e.g. WFC = Wi-Fi calling) which may operate in APM.
        if (!anySimReadyAndInService && WirelessUtils.isAirplaneModeOn(mContext)) {
            displayText = getAirplaneModeMessage();
        }
        setText(displayText);
    }

    private String getMissingSimMessage() {
        return mShowMissingSim && mTelephonyCapable
                ? getContext().getString(R.string.keyguard_missing_sim_message_short) : "";
    }

    private String getAirplaneModeMessage() {
        return mShowAirplaneMode
                ? getContext().getString(R.string.airplane_mode) : "";
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mSeparator = getResources().getString(
                com.android.internal.R.string.kg_text_message_separator);
        boolean shouldMarquee = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        setSelected(shouldMarquee); // Allow marquee to work.
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (ConnectivityManager.from(mContext).isNetworkSupported(
                ConnectivityManager.TYPE_MOBILE)) {
            mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(mContext);
            mKeyguardUpdateMonitor.registerCallback(mCallback);

            mFiveGServiceClient = FiveGServiceClient.getInstance(mContext);
            mFiveGServiceClient.registerCallback(mCallback);
        } else {
            // Don't listen and clear out the text when the device isn't a phone.
            mKeyguardUpdateMonitor = null;
            setText("");
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mKeyguardUpdateMonitor != null) {
            mKeyguardUpdateMonitor.removeCallback(mCallback);
        }
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);

        // Only show marquee when visible
        if (visibility == VISIBLE) {
            setEllipsize(TextUtils.TruncateAt.MARQUEE);
        } else {
            setEllipsize(TextUtils.TruncateAt.END);
        }
    }

    /**
     * Top-level function for creating carrier text. Makes text based on simState, PLMN
     * and SPN as well as device capabilities, such as being emergency call capable.
     *
     * @param simState
     * @param text
     * @param spn
     * @return Carrier text if not in missing state, null otherwise.
     */
    private CharSequence getCarrierTextForSimState(IccCardConstants.State simState,
            CharSequence text) {
        CharSequence carrierText = null;
        StatusMode status = getStatusForIccState(simState);
        switch (status) {
            case Normal:
                carrierText = text;
                break;

            case SimNotReady:
                // Null is reserved for denoting missing, in this case we have nothing to display.
                carrierText = ""; // nothing to display yet.
                break;

            case NetworkLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        mContext.getText(R.string.keyguard_network_locked_message), text);
                break;

            case SimMissing:
                carrierText = null;
                break;

            case SimPermDisabled:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(
                                R.string.keyguard_permanent_disabled_sim_message_short),
                        text);
                break;

            case SimMissingLocked:
                carrierText = null;
                break;

            case SimLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_sim_locked_message),
                        text);
                break;

            case SimPukLocked:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_sim_puk_locked_message),
                        text);
                break;
            case SimIoError:
                carrierText = makeCarrierStringOnEmergencyCapable(
                        getContext().getText(R.string.keyguard_sim_error_message_short),
                        text);
                break;
            case SimUnknown:
                carrierText = null;
                break;
        }

        return carrierText;
    }

    /*
     * Add emergencyCallMessage to carrier string only if phone supports emergency calls.
     */
    private CharSequence makeCarrierStringOnEmergencyCapable(
            CharSequence simMessage, CharSequence emergencyCallMessage) {
        if (mIsEmergencyCallCapable) {
            return concatenate(simMessage, emergencyCallMessage);
        }
        return simMessage;
    }

    /**
     * Determine the current status of the lock screen given the SIM state and other stuff.
     */
    private StatusMode getStatusForIccState(IccCardConstants.State simState) {
        // Since reading the SIM may take a while, we assume it is present until told otherwise.
        if (simState == null) {
            return StatusMode.Normal;
        }

        final boolean missingAndNotProvisioned =
                !KeyguardUpdateMonitor.getInstance(mContext).isDeviceProvisioned()
                && (simState == IccCardConstants.State.ABSENT ||
                        simState == IccCardConstants.State.PERM_DISABLED);

        // Assume we're NETWORK_LOCKED if not provisioned
        simState = missingAndNotProvisioned ? IccCardConstants.State.NETWORK_LOCKED : simState;
        switch (simState) {
            case ABSENT:
                return StatusMode.SimMissing;
            case NETWORK_LOCKED:
                return StatusMode.SimMissingLocked;
            case NOT_READY:
                return StatusMode.SimNotReady;
            case PIN_REQUIRED:
                return StatusMode.SimLocked;
            case PUK_REQUIRED:
                return StatusMode.SimPukLocked;
            case READY:
                return StatusMode.Normal;
            case PERM_DISABLED:
                return StatusMode.SimPermDisabled;
            case UNKNOWN:
                return StatusMode.SimUnknown;
            case CARD_IO_ERROR:
                return StatusMode.SimIoError;
        }
        return StatusMode.SimUnknown;
    }

    private static CharSequence concatenate(CharSequence plmn, CharSequence spn) {
        final boolean plmnValid = !TextUtils.isEmpty(plmn);
        final boolean spnValid = !TextUtils.isEmpty(spn);
        if (plmnValid && spnValid) {
            return new StringBuilder().append(plmn).append(mSeparator).append(spn).toString();
        } else if (plmnValid) {
            return plmn;
        } else if (spnValid) {
            return spn;
        } else {
            return "";
        }
    }

    private CharSequence getCarrierHelpTextForSimState(IccCardConstants.State simState,
            String plmn, String spn) {
        int carrierHelpTextId = 0;
        StatusMode status = getStatusForIccState(simState);
        switch (status) {
            case NetworkLocked:
                carrierHelpTextId = R.string.keyguard_instructions_when_pattern_disabled;
                break;

            case SimMissing:
                carrierHelpTextId = R.string.keyguard_missing_sim_instructions_long;
                break;

            case SimPermDisabled:
                carrierHelpTextId = R.string.keyguard_permanent_disabled_sim_instructions;
                break;

            case SimMissingLocked:
                carrierHelpTextId = R.string.keyguard_missing_sim_instructions;
                break;

            case Normal:
            case SimLocked:
            case SimPukLocked:
                break;
        }

        return mContext.getText(carrierHelpTextId);
    }

    private class CarrierTextTransformationMethod extends SingleLineTransformationMethod {
        private final Locale mLocale;
        private final boolean mAllCaps;

        public CarrierTextTransformationMethod(Context context, boolean allCaps) {
            mLocale = context.getResources().getConfiguration().locale;
            mAllCaps = allCaps;
        }

        @Override
        public CharSequence getTransformation(CharSequence source, View view) {
            source = super.getTransformation(source, view);

            if (mAllCaps && source != null) {
                source = source.toString().toUpperCase(mLocale);
            }

            return source;
        }
    }

    private String networkClassToString (int networkClass) {
        final int[] classIds = {
            com.android.systemui.R.string.config_rat_unknown,
            com.android.systemui.R.string.config_rat_2g,
            com.android.systemui.R.string.config_rat_3g,
            com.android.systemui.R.string.config_rat_4g };
        String classString = null;
        if (networkClass < classIds.length) {
            classString = getContext().getResources().getString(classIds[networkClass]);
        }
        return (classString == null) ? "" : classString;
    }

    /**
     * parse the string to current language.
     *
     * @param originalString original string
     * @param originNamesId the id of the original string array.
     * @param localNamesId the id of the local string keys.
     * @return local language string
     */
    private String getLocalString(String originalString,
            int originNamesId, int localNamesId) {
        String[] origNames = getContext().getResources().getStringArray(originNamesId);
        String[] localNames = getContext().getResources().getStringArray(localNamesId);
        for (int i = 0; i < origNames.length; i++) {
            if (origNames[i].equalsIgnoreCase(originalString)) {
                return getContext().getString(getContext().getResources().getIdentifier(
                        localNames[i], "string", "com.android.systemui"));
            }
        }
        return originalString;
    }

    private String get5GNetworkClass(SubscriptionInfo sub) {
        int slotIndex = sub.getSimSlotIndex();
        int subId = sub.getSubscriptionId();

        if ( mFiveGServiceClient == null ) {
            mFiveGServiceClient = FiveGServiceClient.getInstance(mContext);
            mFiveGServiceClient.registerCallback(mCallback);
        }
        FiveGServiceState fiveGServiceState =
                mFiveGServiceClient.getCurrentServiceState(slotIndex);
        if ( fiveGServiceState.isConnectedOnNsaMode() && isDataRegisteredOnLte(subId)) {
            return mContext.getResources().getString(R.string.data_connection_5g);
        }

        return null;
    }

    private boolean isDataRegisteredOnLte(int subId) {
        TelephonyManager telephonyManager = (TelephonyManager)
                mContext.getSystemService(Context.TELEPHONY_SERVICE);
        int dataType = telephonyManager.getDataNetworkType(subId);
        if (  dataType == TelephonyManager.NETWORK_TYPE_LTE ||
                dataType == TelephonyManager.NETWORK_TYPE_LTE_CA) {
            return true;
        }else{
            return false;
        }
    }
}
