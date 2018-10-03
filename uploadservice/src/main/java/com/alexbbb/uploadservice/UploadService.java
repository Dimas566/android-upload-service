package com.alexbbb.uploadservice;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat.Builder;
import android.media.RingtoneManager;
import java.net.MalformedURLException;

import static android.support.v4.app.NotificationCompat.PRIORITY_DEFAULT;

/**
 * Service to upload files in background using HTTP POST with notification center progress
 * display.
 *
 * @author alexbbb (Aleksandar Gotev)
 * @author eliasnaur
 * @author cankov
 */
public class UploadService extends IntentService {

    private static final String SERVICE_NAME = UploadService.class.getName();
    private static final String TAG = "UploadService";

    private static final int UPLOAD_NOTIFICATION_ID = 1234; // Something unique
    private static final int UPLOAD_NOTIFICATION_ID_DONE = 1235; // Something unique

    public static String NAMESPACE = "com.alexbbb";

    private static final String ACTION_UPLOAD_SUFFIX = ".uploadservice.action.upload";
    protected static final String PARAM_NOTIFICATION_CONFIG = "notificationConfig";
    protected static final String PARAM_ID = "id";
    protected static final String PARAM_URL = "url";
    protected static final String PARAM_METHOD = "method";
    protected static final String PARAM_FILES = "files";
    protected static final String PARAM_FILE = "file";
    protected static final String PARAM_TYPE = "uploadType";

    protected static final String PARAM_REQUEST_HEADERS = "requestHeaders";
    protected static final String PARAM_REQUEST_PARAMETERS = "requestParameters";
    protected static final String PARAM_CUSTOM_USER_AGENT = "customUserAgent";
    protected static final String PARAM_MAX_RETRIES = "maxRetries";

    protected static final String UPLOAD_BINARY = "binary";
    protected static final String UPLOAD_MULTIPART = "multipart";

    /**
     * The minimum interval between progress reports in milliseconds.
     * If the upload Tasks report more frequently, we will throttle notifications.
     * We aim for 6 updates per second.
     */
    protected static final long PROGRESS_REPORT_INTERVAL = 166;

    private static final String BROADCAST_ACTION_SUFFIX = ".uploadservice.broadcast.status";
    public static final String UPLOAD_ID = "id";
    public static final String STATUS = "status";
    public static final int STATUS_IN_PROGRESS = 1;
    public static final int STATUS_COMPLETED = 2;
    public static final int STATUS_ERROR = 3;
    public static final String PROGRESS = "progress";
    public static final String PROGRESS_UPLOADED_BYTES = "progressUploadedBytes";
    public static final String PROGRESS_TOTAL_BYTES = "progressTotalBytes";
    public static final String ERROR_EXCEPTION = "errorException";
    public static final String SERVER_RESPONSE_CODE = "serverResponseCode";
    public static final String SERVER_RESPONSE_MESSAGE = "serverResponseMessage";

    public static final String DOWNLOAD_CHANNEL_ID = "com.alexbbb.uploadservice.UPLOAD";
    public static final String DOWNLOAD_CHANNEL_NAME = "UPLOAD SERVICE CHANNEL";

    private NotificationManager notificationManager;
    private Builder notification;
    private PowerManager.WakeLock wakeLock;
    private UploadNotificationConfig notificationConfig;
    private long lastProgressNotificationTime;

    private static HttpUploadTask currentTask;

    public static String getActionUpload() {
        return NAMESPACE + ACTION_UPLOAD_SUFFIX;
    }

    public static String getActionBroadcast() {
        return NAMESPACE + BROADCAST_ACTION_SUFFIX;
    }

    /**
     * Stops the currently active upload task.
     */
    public static void stopCurrentUpload() {
        if (currentTask != null) {
            currentTask.cancel();
        }
    }

    public UploadService() {
        super(SERVICE_NAME);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        notificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        //notification = new NotificationCompat.Builder(this);
        notification = new Builder(this, DOWNLOAD_CHANNEL_ID);
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        assert pm != null;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        this.createChannelUploadService();
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent != null) {
            final String action = intent.getAction();

            if (getActionUpload().equals(action)) {
                notificationConfig = intent.getParcelableExtra(PARAM_NOTIFICATION_CONFIG);

                String type = intent.getStringExtra(PARAM_TYPE);
                if (UPLOAD_MULTIPART.equals(type)) {
                    currentTask = new MultipartUploadTask(this, intent);
                } else if (UPLOAD_BINARY.equals(type)) {
                    currentTask = new BinaryUploadTask(this, intent);
                } else {
                    return;
                }

                lastProgressNotificationTime = 0;
                wakeLock.acquire();

                createNotification();

                currentTask.run();
            }
        }
    }

    /**
     * Start the background file upload service.
     * You can use the startUpload instance method of the HttpUploadRequest directly.
     * The method is here for backward compatibility.
     *
     * @deprecated As of 1.4, use startUpload() method on the request object
     * @throws IllegalArgumentException if one or more arguments passed are invalid
     * @throws MalformedURLException if the server URL is not valid
     */
    public static void startUpload(HttpUploadRequest request)
            throws IllegalArgumentException, MalformedURLException {
        request.startUpload();
    }

    void broadcastProgress(final String uploadId, final long uploadedBytes, final long totalBytes) {

        long currentTime = System.currentTimeMillis();
        if (currentTime < lastProgressNotificationTime + PROGRESS_REPORT_INTERVAL) {
            return;
        }

        lastProgressNotificationTime = currentTime;

        updateNotificationProgress((int)uploadedBytes, (int)totalBytes);

        final Intent intent = new Intent(getActionBroadcast());
        intent.putExtra(UPLOAD_ID, uploadId);
        intent.putExtra(STATUS, STATUS_IN_PROGRESS);

        final int percentsProgress = (int) (uploadedBytes * 100 / totalBytes);
        intent.putExtra(PROGRESS, percentsProgress);

        intent.putExtra(PROGRESS_UPLOADED_BYTES, uploadedBytes);
        intent.putExtra(PROGRESS_TOTAL_BYTES, totalBytes);
        sendBroadcast(intent);
    }

    void broadcastCompleted(final String uploadId, final int responseCode, final String responseMessage) {

        final String filteredMessage;
        if (responseMessage == null) {
            filteredMessage = "";
        } else {
            filteredMessage = responseMessage;
        }

        if (responseCode >= 200 && responseCode <= 299)
            updateNotificationCompleted();
        else
            updateNotificationError();

        final Intent intent = new Intent(getActionBroadcast());
        intent.putExtra(UPLOAD_ID, uploadId);
        intent.putExtra(STATUS, STATUS_COMPLETED);
        intent.putExtra(SERVER_RESPONSE_CODE, responseCode);
        intent.putExtra(SERVER_RESPONSE_MESSAGE, filteredMessage);
        sendBroadcast(intent);
        wakeLock.release();
    }

    void broadcastError(final String uploadId, final Exception exception) {

        updateNotificationError();

        final Intent intent = new Intent(getActionBroadcast());
        intent.setAction(getActionBroadcast());
        intent.putExtra(UPLOAD_ID, uploadId);
        intent.putExtra(STATUS, STATUS_ERROR);
        intent.putExtra(ERROR_EXCEPTION, exception);
        sendBroadcast(intent);
        wakeLock.release();
    }

    private void createChannelUploadService(){
        NotificationChannel androidChannel;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            androidChannel = new NotificationChannel(DOWNLOAD_CHANNEL_ID,
                    DOWNLOAD_CHANNEL_NAME, NotificationManager.IMPORTANCE_DEFAULT);
            androidChannel.enableLights(true);
            androidChannel.enableVibration(false);
            androidChannel.setLightColor(Color.MAGENTA);
            androidChannel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);

            notificationManager.createNotificationChannel(androidChannel);
        }

    }

    private void setSimpleNotificationBuilder(int totalBytes, int uploadedBytes, boolean indeterminate, boolean going){
        notification.setSmallIcon(notificationConfig.getIconResourceID())
                .setContentIntent(notificationConfig.getPendingIntent(this))
                .setContentTitle(notificationConfig.getTitle())
                .setContentText(notificationConfig.getMessage())
                .setPriority(PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setProgress(totalBytes, uploadedBytes, indeterminate)
                .setOngoing(going);
    }

    private void createNotification() {
        this.setSimpleNotificationBuilder(100, 0, true, true);
        startForeground(UPLOAD_NOTIFICATION_ID, this.notification.build());
    }

    private void updateNotificationProgress(int uploadedBytes, int totalBytes) {
        this.setSimpleNotificationBuilder(totalBytes, uploadedBytes, false, true);
        startForeground(UPLOAD_NOTIFICATION_ID, this.notification.build());

    }

    private void updateNotificationCompleted() {
        stopForeground(notificationConfig.isAutoClearOnSuccess());

        if (!notificationConfig.isAutoClearOnSuccess()) {
            this.setSimpleNotificationBuilder(0,0,false, false);
            this.setRingtone();
            this.notificationManager.notify(UPLOAD_NOTIFICATION_ID_DONE, notification.build());
        }
    }

    private void setRingtone() {

        if(notificationConfig.isRingTone()) {
            notification.setSound(RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION));
            notification.setOnlyAlertOnce(false);
        }

    }

    private void updateNotificationError() {
        stopForeground(false);
        this.setSimpleNotificationBuilder(0,0,false, false);
        this.setRingtone();
        notificationManager.notify(UPLOAD_NOTIFICATION_ID_DONE, notification.build());
    }
}
