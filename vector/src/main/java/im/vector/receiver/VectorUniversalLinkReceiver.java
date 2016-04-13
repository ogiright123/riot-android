package im.vector.receiver;


import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import org.matrix.androidsdk.MXSession;
import org.matrix.androidsdk.data.Room;
import org.matrix.androidsdk.rest.callback.ApiCallback;
import org.matrix.androidsdk.rest.model.MatrixError;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

import im.vector.Matrix;
import im.vector.R;
import im.vector.VectorApp;
import im.vector.activity.CommonActivityUtils;
import im.vector.activity.LoginActivity;
import im.vector.activity.SplashActivity;
import im.vector.activity.VectorHomeActivity;
import im.vector.activity.VectorRoomActivity;

@SuppressLint("LongLogTag")
/**
 * An universal link receiver.
 */
public class VectorUniversalLinkReceiver extends BroadcastReceiver {
    private static final String LOG_TAG = "VectorUniversalLinkReceiver";

    public static final String BROADCAST_ACTION_UNIVERSAL_LINK = "im.vector.receiver.UNIVERSAL_LINK";
    public static final String BROADCAST_ACTION_UNIVERSAL_LINK_RESUME = "im.vector.receiver.UNIVERSAL_LINK_RESUME";

    // the universal link
    public static final String EXTRA_UNIVERSAL_LINK_URI = "EXTRA_UNIVERSAL_LINK_URI";
    // the flow id
    public static final String EXTRA_UNIVERSAL_LINK_FLOW_ID = "EXTRA_UNIVERSAL_LINK_FLOW_ID";
    // the sender identifier (XXX_SENDER_ID)
    public static final String EXTRA_UNIVERSAL_LINK_SENDER_ID = "EXTRA_UNIVERSAL_LINK_SENDER_ID";

    // sender activities
    public static final String HOME_SENDER_ID = VectorHomeActivity.class.getSimpleName();
    public static final String LOGIN_SENDER_ID = LoginActivity.class.getSimpleName();
    public static final String SPLASH_SENDER_ID = SplashActivity.class.getSimpleName();

    // the supported paths
    private static final String SUPPORTED_PATH_BETA = "/beta/";
    private static final String SUPPORTED_PATH_DEVELOP = "/develop/";
    private static final String SUPPORTED_PATH_APP = "/app/";
    private static final String SUPPORTED_PATH_STAGING = "/staging/";

    // index of each item in path
    private static final int OFFSET_FRAGMENT_ROOM_ID = 1;
    private static final int OFFSET_FRAGMENT_EVENT_ID = 2;
    private static final int FRAGMENT_MAX_SPLIT_SECTIONS = 3;

    // supported paths list
    private static final List<String> mSupportedVectorLinkPaths = Arrays.asList(SUPPORTED_PATH_BETA, SUPPORTED_PATH_DEVELOP, SUPPORTED_PATH_APP, SUPPORTED_PATH_STAGING);

    // the session
    private MXSession mSession;

    // the room id
    private String mRoomIdOrAlias;

    // the event id
    private String mEventId;

    public VectorUniversalLinkReceiver() {
    }


    @Override
    public void onReceive(final Context aContext, final Intent aIntent) {
        String action,uriString;
        Uri intentUri;

        Log.d(LOG_TAG, "## onReceive() IN");

        // get session
        mSession = Matrix.getInstance(aContext).getDefaultSession();

        // user is not yet logged in
        if (null == mSession) {
            Log.e(LOG_TAG, "## onReceive() Warning - Unable to proceed URL link: Session is null");

            // No user is logged => no session. Just forward request to the login activity
            Intent intent = new Intent(aContext, LoginActivity.class);
            intent.putExtra(EXTRA_UNIVERSAL_LINK_URI, aIntent.getData());
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            aContext.startActivity(intent);
            return;
        }

        // sanity check
        if (null != aIntent) {

            action = aIntent.getAction();
            uriString = aIntent.getDataString();
            boolean isSessionActive = mSession.isAlive();
            boolean isLoginStepDone = mSession.getDataHandler().isInitialSyncComplete();

            Log.d(LOG_TAG, "## onReceive() uri getDataString=" + uriString+"isSessionActive=" + isSessionActive + " isLoginStepDone=" + isLoginStepDone);

            if (TextUtils.equals(action, BROADCAST_ACTION_UNIVERSAL_LINK)){
                Log.d(LOG_TAG, "## onReceive() action = BROADCAST_ACTION_UNIVERSAL_LINK");
                intentUri = aIntent.getData();

            } else if(TextUtils.equals(action, BROADCAST_ACTION_UNIVERSAL_LINK_RESUME)){
                Log.d(LOG_TAG, "## onReceive() action = BROADCAST_ACTION_UNIVERSAL_LINK_RESUME");

                // A first BROADCAST_ACTION_UNIVERSAL_LINK has been received with a room alias that could not be translated to a room ID.
                // Translation has been asked to server, and the response is processed here.
                // ......................
                intentUri = aIntent.getParcelableExtra(EXTRA_UNIVERSAL_LINK_URI);
                // aIntent.getParcelableExtra(EXTRA_UNIVERSAL_LINK_SENDER_ID);
            } else {
                // unknown action (very unlikely)
                Log.e(LOG_TAG, "## onReceive() Unknown action received ("+action+") - unable to proceed URL link");
                return;
            }

            if (null != intentUri) {
                Log.d(LOG_TAG, "## onCreate() intentUri - host=" + intentUri.getHost() + " path=" + intentUri.getPath() + " queryParams=" + intentUri.getQuery());
                //intentUri.getEncodedSchemeSpecificPart() = //vector.im/beta/  intentUri.getSchemeSpecificPart() = //vector.im/beta/

                List<String> params = parseUniversalLink(intentUri);

                if (null != params) {

                    if(!isSessionActive) {
                        Log.w(LOG_TAG, "## onReceive() Warning: Session is not alive");
                    }

                    if(!isLoginStepDone){
                        Log.w(LOG_TAG, "## onReceive() Warning: Session is not complete - start Login Activity");

                        // Start the login activity and wait for BROADCAST_ACTION_UNIVERSAL_LINK_RESUME.
                        // Once the login process flow is complete, BROADCAST_ACTION_UNIVERSAL_LINK_RESUME is
                        // sent back to resume the URL link processing.
                        Intent intent = new Intent(aContext, LoginActivity.class);
                        intent.putExtra(EXTRA_UNIVERSAL_LINK_URI, aIntent.getData());
                        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        aContext.startActivity(intent);
                    } else {
                        mRoomIdOrAlias = params.get(OFFSET_FRAGMENT_ROOM_ID);

                        // Is there any event ID?
                        if (params.size() > 2) {
                            mEventId = params.get(OFFSET_FRAGMENT_EVENT_ID);
                        }

                        manageRoom(aContext);
                    }
                } else {
                    Log.e(LOG_TAG, "## onReceive() Path not supported: " + intentUri.getPath());
                }
            }
        }
    }

    /**
     * Manage the room presence.
     * Check the URL room ID format: if room ID is provided as an alias, we translate it
     * into its corresponding room ID.
     * @param aContext the context
     */
    private void manageRoom(final Context aContext) {

        if (mRoomIdOrAlias.startsWith("!"))  { // usual room Id format (not alias)
            Room room = mSession.getDataHandler().getRoom(mRoomIdOrAlias, false);

            if (null != room) {
                stopHomeActivitySpinner(aContext);
                openRoomActivity(aContext);
            } else {
                inviteToJoin(aContext);
            }
        } else { // room ID is provided as a room alias: get corresponding room ID

            // Start the home activity with the waiting view enabled, while the URL link
            // is processed in the receiver. The receiver, once the URL was parsed, will stop the waiting view.
            Intent intent = new Intent(aContext, VectorHomeActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(VectorHomeActivity.EXTRA_WAITING_VIEW_STATUS, VectorHomeActivity.WAITING_VIEW_START);
            aContext.startActivity(intent);

            mSession.getDataHandler().roomIdByAlias(mRoomIdOrAlias, new ApiCallback<String>() {
                @Override
                public void onSuccess(final String roomId) {
                    if (!TextUtils.isEmpty(roomId)) {
                        mRoomIdOrAlias = roomId;
                        manageRoom(aContext);
                    }
                }

                private void onError(String errorMessage) {
                    CommonActivityUtils.displayToast(aContext, errorMessage);
                    stopHomeActivitySpinner(aContext);
                }

                @Override
                public void onNetworkError(Exception e) {
                    onError(e.getLocalizedMessage());
                }

                @Override
                public void onMatrixError(MatrixError e) {
                    onError(e.getLocalizedMessage());
                }

                @Override
                public void onUnexpectedError(Exception e) {
                    onError(e.getLocalizedMessage());
                }
            });
        }
    }

    /**
     * Open the room activity with the dedicated parameters
     * @param context
     */
    private void openRoomActivity(Context context) {
        HashMap<String, Object> params = new HashMap<String, Object>();

        params.put(VectorRoomActivity.EXTRA_MATRIX_ID, mSession.getMyUserId());
        params.put(VectorRoomActivity.EXTRA_ROOM_ID, mRoomIdOrAlias);

        if (null != mEventId) {
            params.put(VectorRoomActivity.EXTRA_EVENT_ID, mEventId);
        }

        // clear the activity stack to home activity
        Intent intent = new Intent(context, VectorHomeActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);

        intent.putExtra(VectorHomeActivity.EXTRA_JUMP_TO_ROOM_PARAMS, params);
        context.startActivity(intent);
    }

    /**
     * Display an invitation dialog to join the room.
     * If there is no active activity, launch the home activity
     * @param aContext
     */
    private void inviteToJoin(final Context aContext) {
        final Activity currentActivity = VectorApp.getCurrentActivity();

        if (null != currentActivity) {
            currentActivity.runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    AlertDialog.Builder builder = new AlertDialog.Builder(currentActivity);

                    builder.setTitle(R.string.universal_link_join_alert_title);
                    builder.setMessage(R.string.universal_link_join_alert_body);

                    builder.setPositiveButton(R.string.yes, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Room room = mSession.getDataHandler().getRoom(mRoomIdOrAlias, true);

                            // try to join the room
                            room.join(new ApiCallback<Void>() {
                                @Override
                                public void onSuccess(Void info) {
                                    currentActivity.runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            manageRoom(aContext);
                                        }
                                    });
                                }

                                private void onError(String errorMessage) {
                                    CommonActivityUtils.displayToast(aContext, errorMessage);
                                    stopHomeActivitySpinner(aContext);
                                }

                                @Override
                                public void onNetworkError(Exception e) {
                                    onError(e.getLocalizedMessage());
                                }

                                @Override
                                public void onMatrixError(MatrixError e) {
                                    onError(e.getLocalizedMessage());
                                }

                                @Override
                                public void onUnexpectedError(Exception e) {
                                    onError(e.getLocalizedMessage());
                                }
                            });
                        }
                    });

                    builder.setNegativeButton(R.string.no, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            stopHomeActivitySpinner(aContext);
                        }
                    });

                    AlertDialog dialog = builder.create();
                    dialog.show();
                }
            });

        } else {
            // clear the activity stack to home activity
            Intent intent = new Intent(aContext, VectorHomeActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.putExtra(VectorHomeActivity.EXTRA_WAITING_VIEW_STATUS, VectorHomeActivity.WAITING_VIEW_START);
            aContext.startActivity(intent);

            final Timer wakeup = new Timer();

            wakeup.schedule(new TimerTask() {
                @Override
                public void run() {
                    wakeup.cancel();
                    inviteToJoin(aContext);
                }
            }, 200);
        }
    }

    /***
     * Tries to parse an universal link.
     * @param uri the uri to parse
     * @return the universal link items, null if the universal link is invalid
     */
    public static List<String> parseUniversalLink(Uri uri) {
        List<String> res = null;

        try {
            // sanity check
            if ((null == uri) || TextUtils.isEmpty(uri.getPath())) {
                Log.e(LOG_TAG, "## parseUniversalLink : null");
                return null;
            }

            if (!mSupportedVectorLinkPaths.contains(uri.getPath())) {
                Log.e(LOG_TAG, "## parseUniversalLink : not supported");
                return null;
            }

            String uriFragment;

            if (!TextUtils.equals(uri.getHost(), "vector.im")) {
                Log.e(LOG_TAG, "## parseUniversalLink : unsupported host");
                return null;
            }

            // remove the server part
            if (null != (uriFragment = uri.getFragment())) {
                uriFragment = uriFragment.substring(1); // get rid of first "/"
            } else {
                Log.e(LOG_TAG, "## parseUniversalLink : cannot extract path");
                return null;
            }

            String temp[] = uriFragment.split("/", FRAGMENT_MAX_SPLIT_SECTIONS); // limit to 3 for security concerns (stack overflow injection)

            if (temp.length < 2) {
                Log.e(LOG_TAG, "## parseUniversalLink : too short");
                return null;
            }

            if (!TextUtils.equals(temp[0], "room")) {
                Log.e(LOG_TAG, "## parseUniversalLink : not supported " + temp[0]);
                return null;
            }

            res = Arrays.asList(temp);
        } catch (Exception e) {
            Log.e(LOG_TAG, "## parseUniversalLink : crashes " + e.getLocalizedMessage());
        }

        return res;
    }

    private void stopHomeActivitySpinner(Context aContext){
        Intent myBroadcastIntent = new Intent(VectorHomeActivity.BROADCAST_ACTION_STOP_WAITING_VIEW);
        aContext.sendBroadcast(myBroadcastIntent);
    }
}

