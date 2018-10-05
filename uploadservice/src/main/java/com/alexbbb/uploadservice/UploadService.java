package com.alexbbb.uploadservice;

import android.app.IntentService;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.PowerManager;
import android.support.v4.app.NotificationCompat.Builder;
import android.media.RingtoneManager;
import java.net.MalformedURLException;
import java.text.DecimalFormat;

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
    protected static final double FACTOR_CONVERT = 0.000001;

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

    public static final String UPLOAD_CHANNEL_ID = "com.alexbbb.uploadservice.UPLOAD";
    public static final String UPLOAD_CHANNEL_NAME = "UPLOAD SERVICE CHANNEL";

    private NotificationManager notificationManager;
    private Builder notification;
    private PowerManager.WakeLock wakeLock;
    private UploadNotificationConfig notificationConfig;
    private long lastProgressNotificationTime;
    private String contentText;
    private DecimalFormat decimalFormat;

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
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        assert pm != null;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);

        this.createNotificationChannelUploadService();
        this.decimalFormat = new DecimalFormat("#.0");
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

        final int percentsProgress = (int) (uploadedBytes * 100 / totalBytes);
//        final String totalMB = decimalFormat.format((int)totalBytes/FACTOR_CONVERT);
//        final String uploadedMB = decimalFormat.format((int)uploadedBytes/FACTOR_CONVERT);
        final double totalMB = (double)Math.round((int)totalBytes/FACTOR_CONVERT * 10d / 10d);
        final double uploadedMB = (double)Math.round((int)uploadedBytes/FACTOR_CONVERT * 10d / 10d);

        updateNotificationProgress((int)uploadedBytes, (int)totalBytes, percentsProgress, totalMB, uploadedMB);

        final Intent intent = new Intent(getActionBroadcast());
        intent.putExtra(UPLOAD_ID, uploadId);
        intent.putExtra(STATUS, STATUS_IN_PROGRESS);


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

    private void createNotificationChannelUploadService(){
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            NotificationChannel androidChannel = new NotificationChannel(
                    UPLOAD_CHANNEL_ID,
                    UPLOAD_CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_LOW);
            androidChannel.enableLights(true);
            androidChannel.enableVibration(false);
            androidChannel.setLightColor(Color.MAGENTA);
            androidChannel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
            androidChannel.setSound(null, null);

            notificationManager.createNotificationChannel(androidChannel);
        }

    }

    private void setSimpleNotificationBuilder(){
        this.contentText = notificationConfig.getMessage();
        this.createNotificationChannelUploadService();
        this.notification = new Builder(this, UPLOAD_CHANNEL_ID)
                .setSmallIcon(notificationConfig.getIconResourceID())
                .setChannelId(UPLOAD_CHANNEL_ID)
                .setWhen(System.currentTimeMillis())
                .setContentIntent(notificationConfig.getPendingIntent(this))
                .setContentTitle(notificationConfig.getTitle())
                .setContentText(this.contentText)
                .setPriority(PRIORITY_DEFAULT)
                .setAutoCancel(true)
                .setDefaults(0)
                .setOngoing(true);
    }

    private void createNotification() {
        this.setSimpleNotificationBuilder();
        startForeground(UPLOAD_NOTIFICATION_ID, this.notification.build());
    }

    private void updateNotificationProgress(int uploadedBytes, int totalBytes, int percentsProgress,
                                            double totalMB, double uploadedMB) {

        this.notification.setProgress(totalBytes, uploadedBytes, false);
        this.notification.setContentText(this.contentText +" "+percentsProgress+ "% | " + uploadedMB + "/" + totalMB);
        startForeground(UPLOAD_NOTIFICATION_ID, this.notification.build());
    }

    private void updateNotificationCompleted() {
        stopForeground(notificationConfig.isAutoClearOnSuccess());

        if (!notificationConfig.isAutoClearOnSuccess()) {
            this.notification.setProgress(0,0, false);
            this.notification.setOngoing(false);
            this.setRingtone();
            this.notificationManager.notify(UPLOAD_NOTIFICATION_ID_DONE, notification.build());
        }
    }

    private void updateNotificationError() {
        stopForeground(false);
        this.notification.setProgress(0,0,false);
        this.notification.setOngoing(false);
        this.setRingtone();
        notificationManager.notify(UPLOAD_NOTIFICATION_ID_DONE, notification.build());
    }

    private void setRingtone() {

        if(notificationConfig.isRingTone() && Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            notification.setSound(RingtoneManager.getActualDefaultRingtoneUri(this, RingtoneManager.TYPE_NOTIFICATION));
            notification.setOnlyAlertOnce(true);
        }
    }
}
