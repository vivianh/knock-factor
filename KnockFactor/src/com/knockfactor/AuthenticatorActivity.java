/*
 * Copyright 2009 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.knockfactor;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Vibrator;
import android.text.ClipboardManager;
import android.text.Html;
import android.util.Log;
import android.util.TypedValue;
import android.view.ContextMenu;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.webkit.WebView;
import android.widget.AdapterView;
import android.widget.AdapterView.AdapterContextMenuInfo;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.knockfactor.AccountDb.OtpType;
import com.knockfactor.dataimport.ImportController;
import com.knockfactor.howitworks.IntroEnterPasswordActivity;
import com.knockfactor.testability.DependencyInjector;
import com.knockfactor.testability.TestableActivity;
import com.knockfactor2.R;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

/**
 * The main activity that displays usernames and codes
 *
 * @author sweis@google.com (Steve Weis)
 * @author adhintz@google.com (Drew Hintz)
 * @author cemp@google.com (Cem Paya)
 * @author klyubin@google.com (Alex Klyubin)
 */
public class AuthenticatorActivity extends TestableActivity {

    /**
     * The tag for log messages
     */
    private static final String LOCAL_TAG = "AuthenticatorActivity";
    private static final long VIBRATE_DURATION = 200L;

    /**
     * Frequency (milliseconds) with which TOTP countdown indicators are updated.
     */
    private static final long TOTP_COUNTDOWN_REFRESH_PERIOD = 100;

    /**
     * Minimum amount of time (milliseconds) that has to elapse from the moment a HOTP code is
     * generated for an account until the moment the next code can be generated for the account.
     * This is to prevent the user from generating too many HOTP codes in a short period of time.
     */
    private static final long HOTP_MIN_TIME_INTERVAL_BETWEEN_CODES = 5000;

    /**
     * The maximum amount of time (milliseconds) for which a HOTP code is displayed after it's been
     * generated.
     */
    private static final long HOTP_DISPLAY_TIMEOUT = 2 * 60 * 1000;

    // @VisibleForTesting
    static final int DIALOG_ID_UNINSTALL_OLD_APP = 12;

    // @VisibleForTesting
    static final int DIALOG_ID_SAVE_KEY = 13;

    /**
     * Intent action to that tells this Activity to initiate the scanning of barcode to add an
     * account.
     */
    // @VisibleForTesting
    static final String ACTION_SCAN_BARCODE =
            AuthenticatorActivity.class.getName() + ".ScanBarcode";

    private View mContentNoAccounts;
    private View mContentAccountsPresent;
    private TextView mEnterPinPrompt;
    private ListView mUserList;
    private PinListAdapter mUserAdapter;
    private PinInfo[] mUsers = {};

    /**
     * Counter used for generating TOTP verification codes.
     */
    private TotpCounter mTotpCounter;

    /**
     * Clock used for generating TOTP verification codes.
     */
    private TotpClock mTotpClock;

    /**
     * Task that periodically notifies this activity about the amount of time remaining until
     * the TOTP codes refresh. The task also notifies this activity when TOTP codes refresh.
     */
    private TotpCountdownTask mTotpCountdownTask;

    /**
     * Phase of TOTP countdown indicators. The phase is in {@code [0, 1]} with {@code 1} meaning
     * full time step remaining until the code refreshes, and {@code 0} meaning the code is refreshing
     * right now.
     */
    private double mTotpCountdownPhase;
    private AccountDb mAccountDb;
    private OtpSource mOtpProvider;

    /**
     * Key under which the {@link #mOldAppUninstallIntent} is stored in the instance state
     * {@link Bundle}.
     */
    private static final String KEY_OLD_APP_UNINSTALL_INTENT = "oldAppUninstallIntent";

    /**
     * {@link Intent} for uninstalling the "old" app or {@code null} if not known/available.
     * <p/>
     * <p/>
     * Note: this field is persisted in the instance state {@link Bundle}. We need to resolve to this
     * error-prone mechanism because showDialog on Eclair doesn't take parameters. Once Froyo is
     * the minimum targetted SDK, this contrived code can be removed.
     */
    private Intent mOldAppUninstallIntent;

    /**
     * Whether the importing of data from the "old" app has been started and has not yet finished.
     */
    private boolean mDataImportInProgress;

    /**
     * Key under which the {@link #mSaveKeyDialogParams} is stored in the instance state
     * {@link Bundle}.
     */
    private static final String KEY_SAVE_KEY_DIALOG_PARAMS = "saveKeyDialogParams";

    /**
     * Parameters to the save key dialog (DIALOG_ID_SAVE_KEY).
     * <p/>
     * <p/>
     * Note: this field is persisted in the instance state {@link Bundle}. We need to resolve to this
     * error-prone mechanism because showDialog on Eclair doesn't take parameters. Once Froyo is
     * the minimum targetted SDK, this contrived code can be removed.
     */
    private SaveKeyDialogParams mSaveKeyDialogParams;

    /**
     * Whether this activity is currently displaying a confirmation prompt in response to the
     * "save key" Intent.
     */
    private boolean mSaveKeyIntentConfirmationInProgress;

    private static final String OTP_SCHEME = "otpauth";
    private static final String TOTP = "totp"; // time-based
    private static final String HOTP = "hotp"; // counter-based
    private static final String SECRET_PARAM = "secret";
    private static final String COUNTER_PARAM = "counter";
    // @VisibleForTesting
    public static final int CHECK_KEY_VALUE_ID = 0;
    // @VisibleForTesting
    public static final int RENAME_ID = 1;
    // @VisibleForTesting
    public static final int REMOVE_ID = 2;
    // @VisibleForTesting
    static final int COPY_TO_CLIPBOARD_ID = 3;
    // @VisibleForTesting
    static final int SCAN_REQUEST = 31337;

    private static final int MESSAGE_READ = 3;
    private static final int MESSAGE_CONNECT = 1;
    private static final int MESSAGE_DISCONNECT = 2;

    private Menu mMenu;

    private BluetoothAdapter mBluetoothAdapter;
    private Handler mHandler;
    private ConnectedThread mConnected;
    private AcceptThread mAccept;

    private final static int REQUEST_ENABLE_BT = 1;
    private static final int SELECTED_PAIR = 2;

    public static final String EXTRA_SELECTED = "com.knockfactor.extras.selected";
    public static final String PREFS_NAME = "com.knockfactor.prefs";
    public static final String PREF_MAC = "com.knockfactor.prefs.mac";

    private KnockEventListener knockListener;
    private Intent mServiceIntent;
    // KnockFactorReceiver mKnockFactorReceiver;
    private static final UUID OUR_UUID = UUID.fromString("d749856c-5143-48fe-8b86-35e4494bd073");


    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.v("knockListener", "authenticator");

        mAccountDb = DependencyInjector.getAccountDb();
        mOtpProvider = DependencyInjector.getOtpProvider();

        // Use a different (longer) title from the one that's declared in the manifest (and the one that
        // the Android launcher displays).
        setTitle(R.string.app_name);

        mTotpCounter = mOtpProvider.getTotpCounter();
        mTotpClock = mOtpProvider.getTotpClock();

        setContentView(R.layout.main);

        // restore state on screen rotation
        Object savedState = getLastNonConfigurationInstance();
        if (savedState != null) {
            mUsers = (PinInfo[]) savedState;
            // Re-enable the Get Code buttons on all HOTP accounts, otherwise they'll stay disabled.
            for (PinInfo account : mUsers) {
                if (account.isHotp) {
                    account.hotpCodeGenerationAllowed = true;
                }
            }
        }

        if (savedInstanceState != null) {
            mOldAppUninstallIntent = savedInstanceState.getParcelable(KEY_OLD_APP_UNINSTALL_INTENT);
            mSaveKeyDialogParams =
                    (SaveKeyDialogParams) savedInstanceState.getSerializable(KEY_SAVE_KEY_DIALOG_PARAMS);
        }

        mUserList = (ListView) findViewById(R.id.user_list);
        mContentNoAccounts = findViewById(R.id.content_no_accounts);
        mContentAccountsPresent = findViewById(R.id.content_accounts_present);
        mContentNoAccounts.setVisibility((mUsers.length > 0) ? View.GONE : View.VISIBLE);
        mContentAccountsPresent.setVisibility((mUsers.length > 0) ? View.VISIBLE : View.GONE);
        TextView noAccountsPromptDetails = (TextView) findViewById(R.id.details);
        noAccountsPromptDetails.setText(
                Html.fromHtml(getString(R.string.welcome_page_details)));

        findViewById(R.id.how_it_works_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                displayHowItWorksInstructions();
            }
        });
        findViewById(R.id.add_account_button).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addAccount();
            }
        });
        mEnterPinPrompt = (TextView) findViewById(R.id.enter_pin_prompt);

        mUserAdapter = new PinListAdapter(this, R.layout.user_row, mUsers);

        mUserList.setVisibility(View.GONE);
        mUserList.setAdapter(mUserAdapter);
        mUserList.setOnItemClickListener(new OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> unusedParent, View row,
                                    int unusedPosition, long unusedId) {
                NextOtpButtonListener clickListener = (NextOtpButtonListener) row.getTag();
                View nextOtp = row.findViewById(R.id.next_otp);
                if ((clickListener != null) && nextOtp.isEnabled()) {
                    clickListener.onClick(row);
                }
                mUserList.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            }
        });

        if (savedInstanceState == null) {
            // This is the first time this Activity is starting (i.e., not restoring previous state which
            // was saved, for example, due to orientation change)
            DependencyInjector.getOptionalFeatures().onAuthenticatorActivityCreated(this);
            importDataFromOldAppIfNecessary();
            handleIntent(getIntent());
        }

        mHandler = new Handler(Looper.getMainLooper()) {

            @Override
            public void handleMessage(Message msg) {
                super.handleMessage(msg);

                switch (msg.what) {
                    case MESSAGE_READ:
                        int bytes = msg.arg1;
                        byte[] buffer = (byte[]) msg.obj;
                        byte[] message = Arrays.copyOf(buffer, bytes);

                        try {
                            String contents = new String(message, "UTF-8");

                            Toast.makeText(getApplicationContext(), contents, Toast.LENGTH_SHORT).show();
                        } catch (UnsupportedEncodingException e) {
                            Log.w("Knock Factor", "Bad encoding!");
                        }

                        break;
                    case MESSAGE_CONNECT:
                        Toast.makeText(getApplicationContext(), "Connected!", Toast.LENGTH_SHORT).show();
                        MenuItem menuItem = mMenu.findItem(R.id.connect);
                        menuItem.setTitle(getResources().getString(R.string.disconnect));

                        break;

                    case MESSAGE_DISCONNECT:
                        Toast.makeText(getApplicationContext(), "Disconnected!", Toast.LENGTH_SHORT).show();
                        MenuItem item = mMenu.findItem(R.id.connect);
                        item.setTitle(getResources().getString(R.string.connect_menu_item));

                        break;
                }
            }

            @Override
            public void dispatchMessage(Message msg) {
                super.dispatchMessage(msg);
            }
        };

        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            // Device does not support Bluetooth
            Toast.makeText(this, "Device does not support bluetooth", Toast.LENGTH_LONG).show();
        } else {
            if (!mBluetoothAdapter.isEnabled()) {
                Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
            } else {
                mAccept = new AcceptThread(getApplicationContext(), mHandler, mBluetoothAdapter, mUsers);
                mAccept.start();
            }
//            Intent discoverableIntent = new
//                    Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
//            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
//            startActivity(discoverableIntent);
        }

        /*
        knockListener = new KnockEventListener((SensorManager)getSystemService(SENSOR_SERVICE));
        mServiceIntent = new Intent(this, KnockFactorService.class);
        startService(mServiceIntent);
        */

        knockListener = new KnockEventListener((SensorManager)getSystemService(SENSOR_SERVICE)) {

            @Override
            public void onSensorChanged(SensorEvent event) {
                super.onSensorChanged(event);

                if (this.knockDetected) {
                    Log.w("Knock Factor", "knock? " + this.knockDetected);

                    mServiceIntent = new Intent(AuthenticatorActivity.this, KnockFactorService.class);
                    mServiceIntent.putExtra("STATUS", this.knockDetected);
                    startService(mServiceIntent);

                    if (mConnected != null) {
                        mConnected.write("knocked".getBytes());
                    }
                }

                this.knockDetected = false;
            }
        };
    }

    /**
     * Reacts to the {@link Intent} that started this activity or arrived to this activity without
     * restarting it (i.e., arrived via {@link #onNewIntent(Intent)}). Does nothing if the provided
     * intent is {@code null}.
     */
    private void handleIntent(Intent intent) {
        if (intent == null) {
            return;
        }

        String action = intent.getAction();
        if (action == null) {
            return;
        }

        if (ACTION_SCAN_BARCODE.equals(action)) {
            scanBarcode();
        } else if (intent.getData() != null) {
            interpretScanResult(intent.getData(), true);
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);

        outState.putParcelable(KEY_OLD_APP_UNINSTALL_INTENT, mOldAppUninstallIntent);
        outState.putSerializable(KEY_SAVE_KEY_DIALOG_PARAMS, mSaveKeyDialogParams);
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return mUsers;  // save state of users and currently displayed PINs
    }

    // Because this activity is marked as singleTop, new launch intents will be
    // delivered via this API instead of onResume().
    // Override here to catch otpauth:// URL being opened from QR code reader.
    @Override
    protected void onNewIntent(Intent intent) {
        Log.i(getString(R.string.app_name), LOCAL_TAG + ": onNewIntent");
        handleIntent(intent);
    }

    @Override
    protected void onStart() {
        super.onStart();

        updateCodesAndStartTotpCountdownTask();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.i(getString(R.string.app_name), LOCAL_TAG + ": onResume");

        importDataFromOldAppIfNecessary();
        // knockListener.resumeListener();
    }

    @Override
    protected void onStop() {
        stopTotpCountdownTask();

        super.onStop();
        // unregisterReceiver(mKnockFactorReceiver);
        // knockListener.pauseListener();
    }

    private void updateCodesAndStartTotpCountdownTask() {
        stopTotpCountdownTask();

        mTotpCountdownTask =
                new TotpCountdownTask(mTotpCounter, mTotpClock, TOTP_COUNTDOWN_REFRESH_PERIOD);
        mTotpCountdownTask.setListener(new TotpCountdownTask.Listener() {
            @Override
            public void onTotpCountdown(long millisRemaining) {
                if (isFinishing()) {
                    // No need to reach to this even because the Activity is finishing anyway
                    return;
                }
                setTotpCountdownPhaseFromTimeTillNextValue(millisRemaining);
            }

            @Override
            public void onTotpCounterValueChanged() {
                if (isFinishing()) {
                    // No need to reach to this even because the Activity is finishing anyway
                    return;
                }
                refreshVerificationCodes();
            }
        });

        mTotpCountdownTask.startAndNotifyListener();
    }

    private void stopTotpCountdownTask() {
        if (mTotpCountdownTask != null) {
            mTotpCountdownTask.stop();
            mTotpCountdownTask = null;
        }
    }

    /**
     * Display list of user emails and updated pin codes.
     */
    protected void refreshUserList() {
        refreshUserList(false);
    }

    private void setTotpCountdownPhase(double phase) {
        mTotpCountdownPhase = phase;
        updateCountdownIndicators();
    }

    private void setTotpCountdownPhaseFromTimeTillNextValue(long millisRemaining) {
        setTotpCountdownPhase(
                ((double) millisRemaining) / Utilities.secondsToMillis(mTotpCounter.getTimeStep()));
    }

    private void refreshVerificationCodes() {
        refreshUserList();
        setTotpCountdownPhase(1.0);
    }

    private void updateCountdownIndicators() {
        for (int i = 0, len = mUserList.getChildCount(); i < len; i++) {
            View listEntry = mUserList.getChildAt(i);
            CountdownIndicator indicator =
                    (CountdownIndicator) listEntry.findViewById(R.id.countdown_icon);
            if (indicator != null) {
                indicator.setPhase(mTotpCountdownPhase);
            }
        }
    }

    public static PinInfo[] getUsers(AccountDb accountDb, OtpSource mOtpProvider) {
        ArrayList<String> usernames = new ArrayList<String>();
        accountDb.getNames(usernames);

        int userCount = usernames.size();

        if (userCount > 0) {
            PinInfo[] users = new PinInfo[userCount];

            for (int i = 0; i < userCount; ++i) {
                String user = usernames.get(i);

                PinInfo currentPin = new PinInfo();
                currentPin.pin = "_ _ _ _ _ _";
                currentPin.hotpCodeGenerationAllowed = true;

                try {
                    users[i] = computePin(currentPin, accountDb, mOtpProvider, user, false);
                } catch (OtpSourceException ignored) {
                }
            }

            return users;
        } else {
            return new PinInfo[0]; // clear any existing user PIN state
        }
    }

    /**
     * Display list of user emails and updated pin codes.
     *
     * @param isAccountModified if true, force full refresh
     */
    // @VisibleForTesting
    public void refreshUserList(boolean isAccountModified) {
        ArrayList<String> usernames = new ArrayList<String>();
        mAccountDb.getNames(usernames);

        int userCount = usernames.size();

        if (userCount > 0) {
            boolean newListRequired = isAccountModified || mUsers.length != userCount;
            if (newListRequired) {
                mUsers = new PinInfo[userCount];
            }

            for (int i = 0; i < userCount; ++i) {
                String user = usernames.get(i);
                try {
                    computeAndDisplayPin(user, i, false);
                } catch (OtpSourceException ignored) {
                }
            }

            if (newListRequired) {
                // Make the list display the data from the newly created array of accounts
                // This forces the list to scroll to top.
                mUserAdapter = new PinListAdapter(this, R.layout.user_row, mUsers);
                mUserList.setAdapter(mUserAdapter);
            }

            mUserAdapter.notifyDataSetChanged();

            if (mUserList.getVisibility() != View.VISIBLE) {
                mUserList.setVisibility(View.VISIBLE);
                registerForContextMenu(mUserList);
            }
        } else {
            mUsers = new PinInfo[0]; // clear any existing user PIN state
            mUserList.setVisibility(View.GONE);
        }

        // Display the list of accounts if there are accounts, otherwise display a
        // different layout explaining the user how this app works and providing the user with an easy
        // way to add an account.
        mContentNoAccounts.setVisibility((mUsers.length > 0) ? View.GONE : View.VISIBLE);
        mContentAccountsPresent.setVisibility((mUsers.length > 0) ? View.VISIBLE : View.GONE);
    }

    public void computeAndDisplayPin(String user, int position,
                                     boolean computeHotp) throws OtpSourceException {
        if (mUsers[position] != null) {
            mUsers[position] = computePin(mUsers[position], mAccountDb, mOtpProvider, user, computeHotp);
        } else {
            PinInfo currentPin = new PinInfo();
            currentPin.pin = getString(R.string.empty_pin);
            currentPin.hotpCodeGenerationAllowed = true;

            mUsers[position]= computePin(currentPin, mAccountDb, mOtpProvider, user, computeHotp);
        }

    }

    /**
     * Computes the PIN and saves it in mUsers. This currently runs in the UI
     * thread so it should not take more than a second or so. If necessary, we can
     * move the computation to a background thread.
     *
     * @param user        the user email to display with the PIN
     * @param computeHotp true if we should increment counter and display new hotp
     */
    public static PinInfo computePin(PinInfo currentPin, AccountDb accountdb, OtpSource otpProvider, String user,
                                     boolean computeHotp) throws OtpSourceException {

        OtpType type = accountdb.getType(user);
        currentPin.isHotp = (type == OtpType.HOTP);

        currentPin.user = user;

        if (!currentPin.isHotp || computeHotp) {
            // Always safe to recompute, because this code path is only
            // reached if the account is:
            // - Time-based, in which case getNextCode() does not change state.
            // - Counter-based (HOTP) and computeHotp is true.
            currentPin.pin = otpProvider.getNextCode(user);
            currentPin.hotpCodeGenerationAllowed = true;
        }

        return currentPin;
    }

    /**
     * Parses a secret value from a URI. The format will be:
     * <p/>
     * otpauth://totp/user@example.com?secret=FFF...
     * otpauth://hotp/user@example.com?secret=FFF...&counter=123
     *
     * @param uri               The URI containing the secret key
     * @param confirmBeforeSave a boolean to indicate if the user should be
     *                          prompted for confirmation before updating the otp
     *                          account information.
     */
    private void parseSecret(Uri uri, boolean confirmBeforeSave) {
        final String scheme = uri.getScheme().toLowerCase();
        final String path = uri.getPath();
        final String authority = uri.getAuthority();
        final String user;
        final String secret;
        final OtpType type;
        final Integer counter;

        if (!OTP_SCHEME.equals(scheme)) {
            Log.e(getString(R.string.app_name), LOCAL_TAG + ": Invalid or missing scheme in uri");
            showDialog(Utilities.INVALID_QR_CODE);
            return;
        }

        if (TOTP.equals(authority)) {
            type = OtpType.TOTP;
            counter = AccountDb.DEFAULT_HOTP_COUNTER; // only interesting for HOTP
        } else if (HOTP.equals(authority)) {
            type = OtpType.HOTP;
            String counterParameter = uri.getQueryParameter(COUNTER_PARAM);
            if (counterParameter != null) {
                try {
                    counter = Integer.parseInt(counterParameter);
                } catch (NumberFormatException e) {
                    Log.e(getString(R.string.app_name), LOCAL_TAG + ": Invalid counter in uri");
                    showDialog(Utilities.INVALID_QR_CODE);
                    return;
                }
            } else {
                counter = AccountDb.DEFAULT_HOTP_COUNTER;
            }
        } else {
            Log.e(getString(R.string.app_name), LOCAL_TAG + ": Invalid or missing authority in uri");
            showDialog(Utilities.INVALID_QR_CODE);
            return;
        }

        user = validateAndGetUserInPath(path);
        if (user == null) {
            Log.e(getString(R.string.app_name), LOCAL_TAG + ": Missing user id in uri");
            showDialog(Utilities.INVALID_QR_CODE);
            return;
        }

        secret = uri.getQueryParameter(SECRET_PARAM);

        if (secret == null || secret.length() == 0) {
            Log.e(getString(R.string.app_name), LOCAL_TAG +
                    ": Secret key not found in URI");
            showDialog(Utilities.INVALID_SECRET_IN_QR_CODE);
            return;
        }

        if (AccountDb.getSigningOracle(secret) == null) {
            Log.e(getString(R.string.app_name), LOCAL_TAG + ": Invalid secret key");
            showDialog(Utilities.INVALID_SECRET_IN_QR_CODE);
            return;
        }

        if (secret.equals(mAccountDb.getSecret(user)) &&
                counter == mAccountDb.getCounter(user) &&
                type == mAccountDb.getType(user)) {
            return;  // nothing to update.
        }

        if (confirmBeforeSave) {
            mSaveKeyDialogParams = new SaveKeyDialogParams(user, secret, type, counter);
            showDialog(DIALOG_ID_SAVE_KEY);
        } else {
            saveSecretAndRefreshUserList(user, secret, null, type, counter);
        }
    }

    private static String validateAndGetUserInPath(String path) {
        if (path == null || !path.startsWith("/")) {
            return null;
        }
        // path is "/user", so remove leading "/", and trailing white spaces
        String user = path.substring(1).trim();
        if (user.length() == 0) {
            return null; // only white spaces.
        }
        return user;
    }

    /**
     * Saves the secret key to local storage on the phone and updates the displayed account list.
     *
     * @param user         the user email address. When editing, the new user email.
     * @param secret       the secret key
     * @param originalUser If editing, the original user email, otherwise null.
     * @param type         hotp vs totp
     * @param counter      only important for the hotp type
     */
    private void saveSecretAndRefreshUserList(String user, String secret,
                                              String originalUser, OtpType type, Integer counter) {
        if (saveSecret(this, user, secret, originalUser, type, counter)) {
            refreshUserList(true);
        }
    }

    /**
     * Saves the secret key to local storage on the phone.
     *
     * @param user         the user email address. When editing, the new user email.
     * @param secret       the secret key
     * @param originalUser If editing, the original user email, otherwise null.
     * @param type         hotp vs totp
     * @param counter      only important for the hotp type
     * @return {@code true} if the secret was saved, {@code false} otherwise.
     */
    static boolean saveSecret(Context context, String user, String secret,
                              String originalUser, OtpType type, Integer counter) {
        if (originalUser == null) {  // new user account
            originalUser = user;
        }
        if (secret != null) {
            AccountDb accountDb = DependencyInjector.getAccountDb();
            accountDb.update(user, secret, originalUser, type, counter);
            DependencyInjector.getOptionalFeatures().onAuthenticatorActivityAccountSaved(context, user);
            // TODO: Consider having a display message that activities can call and it
            //       will present a toast with a uniform duration, and perhaps update
            //       status messages (presuming we have a way to remove them after they
            //       are stale).
            Toast.makeText(context, R.string.secret_saved, Toast.LENGTH_LONG).show();
            ((Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE))
                    .vibrate(VIBRATE_DURATION);
            return true;
        } else {
            Log.e(LOCAL_TAG, "Trying to save an empty secret key");
            Toast.makeText(context, R.string.error_empty_secret, Toast.LENGTH_LONG).show();
            return false;
        }
    }

    /**
     * Converts user list ordinal id to user email
     */
    private String idToEmail(long id) {
        return mUsers[(int) id].user;
    }

    @Override
    public void onCreateContextMenu(ContextMenu menu, View v, ContextMenuInfo menuInfo) {
        super.onCreateContextMenu(menu, v, menuInfo);
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) menuInfo;
        String user = idToEmail(info.id);
        OtpType type = mAccountDb.getType(user);
        menu.setHeaderTitle(user);
        menu.add(0, COPY_TO_CLIPBOARD_ID, 0, R.string.copy_to_clipboard);
        // Option to display the check-code is only available for HOTP accounts.
        if (type == OtpType.HOTP) {
            menu.add(0, CHECK_KEY_VALUE_ID, 0, R.string.check_code_menu_item);
        }
        menu.add(0, RENAME_ID, 0, R.string.rename);
        menu.add(0, REMOVE_ID, 0, R.string.context_menu_remove_account);
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        AdapterContextMenuInfo info = (AdapterContextMenuInfo) item.getMenuInfo();
        Intent intent;
        final String user = idToEmail(info.id); // final so listener can see value
        switch (item.getItemId()) {
            case COPY_TO_CLIPBOARD_ID:
                ClipboardManager clipboard =
                        (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setText(mUsers[(int) info.id].pin);
                return true;
            case CHECK_KEY_VALUE_ID:
                intent = new Intent(Intent.ACTION_VIEW);
                intent.setClass(this, CheckCodeActivity.class);
                intent.putExtra("user", user);
                startActivity(intent);
                return true;
            case RENAME_ID:
                final Context context = this; // final so listener can see value
                final View frame = getLayoutInflater().inflate(R.layout.rename,
                        (ViewGroup) findViewById(R.id.rename_root));
                final EditText nameEdit = (EditText) frame.findViewById(R.id.rename_edittext);
                nameEdit.setText(user);
                new AlertDialog.Builder(this)
                        .setTitle(String.format(getString(R.string.rename_message), user))
                        .setView(frame)
                        .setPositiveButton(R.string.submit,
                                this.getRenameClickListener(context, user, nameEdit))
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return true;
            case REMOVE_ID:
                // Use a WebView to display the prompt because it contains non-trivial markup, such as list
                View promptContentView =
                        getLayoutInflater().inflate(R.layout.remove_account_prompt, null, false);
                WebView webView = (WebView) promptContentView.findViewById(R.id.web_view);
                webView.setBackgroundColor(Color.TRANSPARENT);
                // Make the WebView use the same font size as for the mEnterPinPrompt field
                double pixelsPerDip =
                        TypedValue.applyDimension(
                                TypedValue.COMPLEX_UNIT_DIP, 10, getResources().getDisplayMetrics()) / 10d;
                webView.getSettings().setDefaultFontSize(
                        (int) (mEnterPinPrompt.getTextSize() / pixelsPerDip));
                Utilities.setWebViewHtml(
                        webView,
                        "<html><body style=\"background-color: transparent;\" text=\"white\">"
                                + getString(
                                mAccountDb.isGoogleAccount(user)
                                        ? R.string.remove_google_account_dialog_message
                                        : R.string.remove_account_dialog_message)
                                + "</body></html>");

                new AlertDialog.Builder(this)
                        .setTitle(getString(R.string.remove_account_dialog_title, user))
                        .setView(promptContentView)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton(R.string.remove_account_dialog_button_remove,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int whichButton) {
                                        mAccountDb.delete(user);
                                        refreshUserList(true);
                                    }
                                }
                        )
                        .setNegativeButton(R.string.cancel, null)
                        .show();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    private DialogInterface.OnClickListener getRenameClickListener(final Context context,
                                                                   final String user, final EditText nameEdit) {
        return new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int whichButton) {
                String newName = nameEdit.getText().toString();
                if (newName != user) {
                    if (mAccountDb.nameExists(newName)) {
                        Toast.makeText(context, R.string.error_exists, Toast.LENGTH_LONG).show();
                    } else {
                        saveSecretAndRefreshUserList(newName,
                                mAccountDb.getSecret(user), user, mAccountDb.getType(user),
                                mAccountDb.getCounter(user));
                    }
                }
            }
        };
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main, menu);

        mMenu = menu;

        return true;
    }

    @Override
    public boolean onMenuItemSelected(int featureId, MenuItem item) {
        switch (item.getItemId()) {
            case R.id.add_account:
                addAccount();
                return true;
            case R.id.paired_devices:
                showPairedDevices();
                return true;
            case R.id.connect:
                if (item.getTitle().equals(getResources().getString(R.string.connect_menu_item))) {
                    if (mConnected != null) {
                        mConnected.cancel();
                    }

                    if (mAccept != null) {
                        mAccept.cancel();
                        mAccept = null;
                    }

                    mAccept = new AcceptThread(getApplicationContext(), mHandler, mBluetoothAdapter, mUsers);
                    mAccept.start();
                } else {
                    if (mConnected != null) {
                        mConnected.cancel();
                    }
                }

                return true;
            case R.id.settings:
                showSettings();
                return true;
        }

        return super.onMenuItemSelected(featureId, item);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        Log.i(getString(R.string.app_name), LOCAL_TAG + ": onActivityResult");
        if (requestCode == SCAN_REQUEST && resultCode == Activity.RESULT_OK) {
            // Grab the scan results and convert it into a URI
            String scanResult = (intent != null) ? intent.getStringExtra("SCAN_RESULT") : null;
            Uri uri = (scanResult != null) ? Uri.parse(scanResult) : null;
            interpretScanResult(uri, false);
        } else if (requestCode == SELECTED_PAIR && resultCode == Activity.RESULT_OK) {
            String selected = (intent != null) ? intent.getStringExtra(EXTRA_SELECTED) : null;

            Set<BluetoothDevice> pairedDevices = mBluetoothAdapter.getBondedDevices();
            // If there are paired devices
            if (pairedDevices.size() > 0) {
                // Loop through paired devices
                for (BluetoothDevice device : pairedDevices) {
                    // Add the name and address to an array adapter to show in a ListView
                    if (device.getAddress().equals(selected)) {
                        new ConnectThread(getApplicationContext(), mBluetoothAdapter, device, mHandler, mUsers).start();

                        Toast.makeText(this, "connecting to " + device.getName(), Toast.LENGTH_SHORT).show();

                        break;
                    }
                }
            }
        }
    }

    private void showPairedDevices() {
        startActivityForResult(new Intent(this, BluetoothDevices.class), SELECTED_PAIR);
    }

    private void displayHowItWorksInstructions() {
        startActivity(new Intent(this, IntroEnterPasswordActivity.class));
    }

    private void addAccount() {
        DependencyInjector.getOptionalFeatures().onAuthenticatorActivityAddAccount(this);
    }

    private void scanBarcode() {
        Intent intentScan = new Intent("com.google.zxing.client.android.SCAN");
        intentScan.putExtra("SCAN_MODE", "QR_CODE_MODE");
        intentScan.putExtra("SAVE_HISTORY", false);
        try {
            startActivityForResult(intentScan, SCAN_REQUEST);
        } catch (ActivityNotFoundException error) {
            showDialog(Utilities.DOWNLOAD_DIALOG);
        }
    }

    public static Intent getLaunchIntentActionScanBarcode(Context context) {
        return new Intent(AuthenticatorActivity.ACTION_SCAN_BARCODE)
                .setComponent(new ComponentName(context, AuthenticatorActivity.class));
    }

    private void showSettings() {
        Intent intent = new Intent();
        intent.setClass(this, SettingsActivity.class);
        startActivity(intent);
    }

    /**
     * Interprets the QR code that was scanned by the user.  Decides whether to
     * launch the key provisioning sequence or the OTP seed setting sequence.
     *
     * @param scanResult        a URI holding the contents of the QR scan result
     * @param confirmBeforeSave a boolean to indicate if the user should be
     *                          prompted for confirmation before updating the otp
     *                          account information.
     */
    private void interpretScanResult(Uri scanResult, boolean confirmBeforeSave) {
        if (DependencyInjector.getOptionalFeatures().interpretScanResult(this, scanResult)) {
            // Scan result consumed by an optional component
            return;
        }
        // The scan result is expected to be a URL that adds an account.

        // If confirmBeforeSave is true, the user has to confirm/reject the action.
        // We need to ensure that new results are accepted only if the previous ones have been
        // confirmed/rejected by the user. This is to prevent the attacker from sending multiple results
        // in sequence to confuse/DoS the user.
        if (confirmBeforeSave) {
            if (mSaveKeyIntentConfirmationInProgress) {
                Log.w(LOCAL_TAG, "Ignoring save key Intent: previous Intent not yet confirmed by user");
                return;
            }
            // No matter what happens below, we'll show a prompt which, once dismissed, will reset the
            // flag below.
            mSaveKeyIntentConfirmationInProgress = true;
        }

        // Sanity check
        if (scanResult == null) {
            showDialog(Utilities.INVALID_QR_CODE);
            return;
        }

        // See if the URL is an account setup URL containing a shared secret
        if (OTP_SCHEME.equals(scanResult.getScheme()) && scanResult.getAuthority() != null) {
            parseSecret(scanResult, confirmBeforeSave);
        } else {
            showDialog(Utilities.INVALID_QR_CODE);
        }
    }

    /**
     * This method is deprecated in SDK level 8, but we have to use it because the
     * new method, which replaces this one, does not exist before SDK level 8
     */
    @Override
    protected Dialog onCreateDialog(final int id) {
        Dialog dialog = null;
        switch (id) {
            /**
             * Prompt to download ZXing from Market. If Market app is not installed,
             * such as on a development phone, open the HTTPS URI for the ZXing apk.
             */
            case Utilities.DOWNLOAD_DIALOG:
                AlertDialog.Builder dlBuilder = new AlertDialog.Builder(this);
                dlBuilder.setTitle(R.string.install_dialog_title);
                dlBuilder.setMessage(R.string.install_dialog_message);
                dlBuilder.setIcon(android.R.drawable.ic_dialog_alert);
                dlBuilder.setPositiveButton(R.string.install_button,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int whichButton) {
                                Intent intent = new Intent(Intent.ACTION_VIEW,
                                        Uri.parse(Utilities.ZXING_MARKET));
                                try {
                                    startActivity(intent);
                                } catch (ActivityNotFoundException e) { // if no Market app
                                    intent = new Intent(Intent.ACTION_VIEW,
                                            Uri.parse(Utilities.ZXING_DIRECT));
                                    startActivity(intent);
                                }
                            }
                        }
                );
                dlBuilder.setNegativeButton(R.string.cancel, null);
                dialog = dlBuilder.create();
                break;

            case DIALOG_ID_SAVE_KEY:
                final SaveKeyDialogParams saveKeyDialogParams = mSaveKeyDialogParams;
                dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.save_key_message)
                        .setMessage(saveKeyDialogParams.user)
                        .setIcon(android.R.drawable.ic_dialog_alert)
                        .setPositiveButton(R.string.ok,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int whichButton) {
                                        saveSecretAndRefreshUserList(
                                                saveKeyDialogParams.user,
                                                saveKeyDialogParams.secret,
                                                null,
                                                saveKeyDialogParams.type,
                                                saveKeyDialogParams.counter);
                                    }
                                })
                        .setNegativeButton(R.string.cancel, null)
                        .create();
                // Ensure that whenever this dialog is to be displayed via showDialog, it displays the
                // correct (latest) user/account name. If this dialog is not explicitly removed after it's
                // been dismissed, then next time showDialog is invoked, onCreateDialog will not be invoked
                // and the dialog will display the previous user/account name instead of the current one.
                dialog.setOnDismissListener(new DialogInterface.OnDismissListener() {
                    @Override
                    public void onDismiss(DialogInterface dialog) {
                        removeDialog(id);
                        onSaveKeyIntentConfirmationPromptDismissed();
                    }
                });
                break;

            case Utilities.INVALID_QR_CODE:
                dialog = createOkAlertDialog(R.string.error_title, R.string.error_qr,
                        android.R.drawable.ic_dialog_alert);
                markDialogAsResultOfSaveKeyIntent(dialog);
                break;

            case Utilities.INVALID_SECRET_IN_QR_CODE:
                dialog = createOkAlertDialog(
                        R.string.error_title, R.string.error_uri, android.R.drawable.ic_dialog_alert);
                markDialogAsResultOfSaveKeyIntent(dialog);
                break;

            case DIALOG_ID_UNINSTALL_OLD_APP:
                dialog = new AlertDialog.Builder(this)
                        .setTitle(R.string.dataimport_import_succeeded_uninstall_dialog_title)
                        .setMessage(
                                DependencyInjector.getOptionalFeatures().appendDataImportLearnMoreLink(
                                        this,
                                        getString(R.string.dataimport_import_succeeded_uninstall_dialog_prompt)))
                        .setCancelable(true)
                        .setPositiveButton(
                                R.string.button_uninstall_old_app,
                                new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int whichButton) {
                                        startActivity(mOldAppUninstallIntent);
                                    }
                                })
                        .setNegativeButton(R.string.cancel, null)
                        .create();
                break;

            default:
                dialog =
                        DependencyInjector.getOptionalFeatures().onAuthenticatorActivityCreateDialog(this, id);
                if (dialog == null) {
                    dialog = super.onCreateDialog(id);
                }
                break;
        }
        return dialog;
    }

    private void markDialogAsResultOfSaveKeyIntent(Dialog dialog) {
        dialog.setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                onSaveKeyIntentConfirmationPromptDismissed();
            }
        });
    }

    /**
     * Invoked when a user-visible confirmation prompt for the Intent to add a new account has been
     * dimissed.
     */
    private void onSaveKeyIntentConfirmationPromptDismissed() {
        mSaveKeyIntentConfirmationInProgress = false;
    }

    /**
     * Create dialog with supplied ids; icon is not set if iconId is 0.
     */
    private Dialog createOkAlertDialog(int titleId, int messageId, int iconId) {
        return new AlertDialog.Builder(this)
                .setTitle(titleId)
                .setMessage(messageId)
                .setIcon(iconId)
                .setPositiveButton(R.string.ok, null)
                .create();
    }

    /**
     * A tuple of user, OTP value, and type, that represents a particular user.
     *
     * @author adhintz@google.com (Drew Hintz)
     */
    public static class PinInfo {
        private String pin; // calculated OTP, or a placeholder if not calculated
        private String user;
        private boolean isHotp = false; // used to see if button needs to be displayed

        /**
         * HOTP only: Whether code generation is allowed for this account.
         */
        private boolean hotpCodeGenerationAllowed;
    }


    /**
     * Scale to use for the text displaying the PIN numbers.
     */
    private static final float PIN_TEXT_SCALEX_NORMAL = 1.0f;
    /**
     * Underscores are shown slightly smaller.
     */
    private static final float PIN_TEXT_SCALEX_UNDERSCORE = 0.87f;

    /**
     * Listener for the Button that generates the next OTP value.
     *
     * @author adhintz@google.com (Drew Hintz)
     */
    private class NextOtpButtonListener implements OnClickListener {
        private final Handler mHandler = new Handler();
        private final PinInfo mAccount;

        private NextOtpButtonListener(PinInfo account) {
            mAccount = account;
        }

        @Override
        public void onClick(View v) {
            int position = findAccountPositionInList();
            if (position == -1) {
                throw new RuntimeException("Account not in list: " + mAccount);
            }

            try {
                computeAndDisplayPin(mAccount.user, position, true);
            } catch (OtpSourceException e) {
                DependencyInjector.getOptionalFeatures().onAuthenticatorActivityGetNextOtpFailed(
                        AuthenticatorActivity.this, mAccount.user, e);
                return;
            }

            final String pin = mAccount.pin;

            // Temporarily disable code generation for this account
            mAccount.hotpCodeGenerationAllowed = false;
            mUserAdapter.notifyDataSetChanged();
            // The delayed operation below will be invoked once code generation is yet again allowed for
            // this account. The delay is in wall clock time (monotonically increasing) and is thus not
            // susceptible to system time jumps.
            mHandler.postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            mAccount.hotpCodeGenerationAllowed = true;
                            mUserAdapter.notifyDataSetChanged();
                        }
                    },
                    HOTP_MIN_TIME_INTERVAL_BETWEEN_CODES);
            // The delayed operation below will hide this OTP to prevent the user from seeing this OTP
            // long after it's been generated (and thus hopefully used).
            mHandler.postDelayed(
                    new Runnable() {
                        @Override
                        public void run() {
                            if (!pin.equals(mAccount.pin)) {
                                return;
                            }
                            mAccount.pin = getString(R.string.empty_pin);
                            mUserAdapter.notifyDataSetChanged();
                        }
                    },
                    HOTP_DISPLAY_TIMEOUT);
        }

        /**
         * Gets the position in the account list of the account this listener is associated with.
         *
         * @return {@code 0}-based position or {@code -1} if the account is not in the list.
         */
        private int findAccountPositionInList() {
            for (int i = 0, len = mUsers.length; i < len; i++) {
                if (mUsers[i] == mAccount) {
                    return i;
                }
            }

            return -1;
        }
    }

    /**
     * Displays the list of users and the current OTP values.
     *
     * @author adhintz@google.com (Drew Hintz)
     */
    private class PinListAdapter extends ArrayAdapter<PinInfo> {

        public PinListAdapter(Context context, int userRowId, PinInfo[] items) {
            super(context, userRowId, items);
        }

        /**
         * Displays the user and OTP for the specified position. For HOTP, displays
         * the button for generating the next OTP value; for TOTP, displays the countdown indicator.
         */
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            LayoutInflater inflater = getLayoutInflater();
            PinInfo currentPin = getItem(position);

            View row;
            if (convertView != null) {
                // Reuse an existing view
                row = convertView;
            } else {
                // Create a new view
                row = inflater.inflate(R.layout.user_row, null);
            }
            TextView pinView = (TextView) row.findViewById(R.id.pin_value);
            TextView userView = (TextView) row.findViewById(R.id.current_user);
            View buttonView = row.findViewById(R.id.next_otp);
            CountdownIndicator countdownIndicator =
                    (CountdownIndicator) row.findViewById(R.id.countdown_icon);

            if (currentPin.isHotp) {
                buttonView.setVisibility(View.VISIBLE);
                buttonView.setEnabled(currentPin.hotpCodeGenerationAllowed);
                ((ViewGroup) row).setDescendantFocusability(
                        ViewGroup.FOCUS_BLOCK_DESCENDANTS); // makes long press work
                NextOtpButtonListener clickListener = new NextOtpButtonListener(currentPin);
                buttonView.setOnClickListener(clickListener);
                row.setTag(clickListener);

                countdownIndicator.setVisibility(View.GONE);
            } else { // TOTP, so no button needed
                buttonView.setVisibility(View.GONE);
                buttonView.setOnClickListener(null);
                row.setTag(null);

                countdownIndicator.setVisibility(View.VISIBLE);
                countdownIndicator.setPhase(mTotpCountdownPhase);
            }

            if (getString(R.string.empty_pin).equals(currentPin.pin)) {
                pinView.setTextScaleX(PIN_TEXT_SCALEX_UNDERSCORE); // smaller gap between underscores
            } else {
                pinView.setTextScaleX(PIN_TEXT_SCALEX_NORMAL);
            }
            pinView.setText(currentPin.pin);
            userView.setText(currentPin.user);

            return row;
        }
    }

    private void importDataFromOldAppIfNecessary() {
        if (mDataImportInProgress) {
            return;
        }
        mDataImportInProgress = true;
        DependencyInjector.getDataImportController().start(this, new ImportController.Listener() {
            @Override
            public void onOldAppUninstallSuggested(Intent uninstallIntent) {
                if (isFinishing()) {
                    return;
                }

                mOldAppUninstallIntent = uninstallIntent;
                showDialog(DIALOG_ID_UNINSTALL_OLD_APP);
            }

            @Override
            public void onDataImported() {
                if (isFinishing()) {
                    return;
                }

                refreshUserList(true);

                DependencyInjector.getOptionalFeatures().onDataImportedFromOldApp(
                        AuthenticatorActivity.this);
            }

            @Override
            public void onFinished() {
                if (isFinishing()) {
                    return;
                }

                mDataImportInProgress = false;
            }
        });
    }

    /**
     * Parameters to the {@link AuthenticatorActivity#DIALOG_ID_SAVE_KEY} dialog.
     */
    private static class SaveKeyDialogParams implements Serializable {
        private final String user;
        private final String secret;
        private final OtpType type;
        private final Integer counter;

        private SaveKeyDialogParams(String user, String secret, OtpType type, Integer counter) {
            this.user = user;
            this.secret = secret;
            this.type = type;
            this.counter = counter;
        }
    }

    public static class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private final BluetoothAdapter mBluetoothAdapter;
        private final Handler mHandler;
        private final PinInfo[] mUsers;
        private final Context mContext;

        public ConnectThread(Context context, BluetoothAdapter bluetoothAdapter, BluetoothDevice device, Handler handler, PinInfo[] users) {
            // Use a temporary object that is later assigned to mmSocket,
            // because mmSocket is final
            BluetoothSocket tmp = null;
            mmDevice = device;
            mHandler = handler;
            mUsers = users;
            mContext = context;
            mBluetoothAdapter = bluetoothAdapter;

            // Get a BluetoothSocket to connect with the given BluetoothDevice
            try {
                // MY_UUID is the app's UUID string, also used by the server code
                tmp = device.createRfcommSocketToServiceRecord(OUR_UUID);
            } catch (IOException e) { }
            mmSocket = tmp;
        }

        public void run() {
            // Cancel discovery because it will slow down the connection
            mBluetoothAdapter.cancelDiscovery();

            try {
                // Connect the device through the socket. This will block
                // until it succeeds or throws an exception
                mmSocket.connect();
            } catch (IOException connectException) {
                // Unable to connect; close the socket and get out
                try {
                    mmSocket.close();
                } catch (IOException closeException) { }
                return;
            }

            // Do work to manage the connection (in a separate thread)
            manageConnectedSocket(mContext, mHandler, mmSocket, mUsers);
        }

        /** Will cancel an in-progress connection, and close the socket */
        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) { }
        }
    }

    private static void manageConnectedSocket(Context context, Handler handler, BluetoothSocket socket, PinInfo[] users) {
        new ConnectedThread(context, handler, socket, users).start();
    }

    private void manageConnectedSocket(BluetoothSocket socket) {
        mConnected = new ConnectedThread(this, mHandler, socket, mUsers);
        mConnected.start();
    }

    public static class AcceptThread extends Thread {
        private final BluetoothServerSocket mmServerSocket;
        private BluetoothAdapter mBluetoothAdapter;
        private Handler mHandler;
        private Context mContext;
        private PinInfo[] mUsers;

        public AcceptThread(Context context, Handler handler, BluetoothAdapter adapter, PinInfo[] mUsers) {
            mContext = context;
            mHandler = handler;
            mBluetoothAdapter = adapter;

            // Use a temporary object that is later assigned to mmServerSocket,
            // because mmServerSocket is final
            BluetoothServerSocket tmp = null;
            try {
                // MY_UUID is the app's UUID string, also used by the client code
                tmp = mBluetoothAdapter.listenUsingRfcommWithServiceRecord("Knock Factor", OUR_UUID);
            } catch (IOException e) { }
            mmServerSocket = tmp;
        }

        public void run() {
            BluetoothSocket socket = null;
            // Keep listening until exception occurs or a socket is returned
            while (true) {
                try {
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    break;
                }
                // If a connection was accepted
                if (socket != null) {
                    // Do work to manage the connection (in a separate thread)
                    manageConnectedSocket(mContext, mHandler, socket, mUsers);
                    try {
                        mmServerSocket.close();
                    } catch (IOException e) {
                        Toast.makeText(mContext, "Could not close socket!", Toast.LENGTH_SHORT).show();
                    }
                    break;
                }
            }
        }

        /** Will cancel the listening socket, and cause the thread to finish */
        public void cancel() {
            try {
                mmServerSocket.close();
            } catch (IOException e) { }
        }
    }

    private static class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;
        private final Handler mHandler;
        private final PinInfo[] mUsers;

        public ConnectedThread(Context context, Handler handler, BluetoothSocket socket, PinInfo[] users) {
            mHandler = handler;
            mUsers = users;

            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the input and output streams, using temp objects because
            // member streams are final
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                Log.w("Knock Factor", "Could not create streams.");
            }

            saveMAC(context, socket.getRemoteDevice().getAddress());

            mHandler.obtainMessage(MESSAGE_CONNECT).sendToTarget();

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            byte[] buffer = new byte[1024];  // buffer store for the stream
            int bytes; // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs
            while (true) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);

                    byte[] message = Arrays.copyOf(buffer, bytes);
                    String contents = new String(message, "UTF-8");

                    // Send the obtained bytes to the UI activity
                    mHandler.obtainMessage(MESSAGE_READ, bytes, -1, buffer)
                            .sendToTarget();

                    for (PinInfo info : mUsers) {
                        if (info.user.toLowerCase().contains(contents.toLowerCase())) {
                            write(info.pin.getBytes());

                            Log.w("Knock Factor", "sending pin for " + info.user + " : " + info.pin);

                            return;
                        }
                    }

                    Log.w("Knock Factor", "user not found: " + contents);
                } catch (IOException e) {
                    break;
                }
            }
        }

        /* Call this from the main activity to send data to the remote device */
        public void write(byte[] bytes) {
            try {
                mmOutStream.write(bytes);
            } catch (IOException e) { }
        }

        /* Call this from the main activity to shutdown the connection */
        public void cancel() {
            try {
                mmSocket.close();

                // Send the obtained bytes to the UI activity
                mHandler.obtainMessage(MESSAGE_DISCONNECT)
                        .sendToTarget();
            } catch (IOException e) { }
        }
    }

    private static void saveMAC(Context context, String mac) {
        // We need an Editor object to make preference changes.
        // All objects are from android.context.Context
        SharedPreferences settings = context.getSharedPreferences(PREFS_NAME, 0);
        SharedPreferences.Editor editor = settings.edit();
        editor.putString(PREF_MAC, mac);

        // Commit the edits!
        editor.commit();
    }

    public static String getMAC(Context context) {
        SharedPreferences settings = context.getSharedPreferences(PREFS_NAME, 0);
        return settings.getString(PREF_MAC, "");
    }

    public static BluetoothDevice getPairedDevice(BluetoothAdapter bluetoothAdapter, String mac) {
        Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
        // If there are paired devices
        if (pairedDevices.size() > 0) {
            // Loop through paired devices
            for (BluetoothDevice device : pairedDevices) {
                // Add the name and address to an array adapter to show in a ListView
                if (device.getAddress().equals(mac)) {
                    return device;
                }
            }
        }

        return null;
    }
}
