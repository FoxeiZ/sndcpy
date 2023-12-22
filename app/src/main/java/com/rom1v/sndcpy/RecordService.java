package com.rom1v.sndcpy;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

public class RecordService extends Service {

    private static final String TAG = "sndcpy";
    private static final String CHANNEL_ID = "sndcpy";
    private static final int NOTIFICATION_ID = 1;

    private static final String ACTION_RECORD = "com.rom1v.sndcpy.RECORD";
    private static final String ACTION_STOP = "com.rom1v.sndcpy.STOP";
    private static final String EXTRA_MEDIA_PROJECTION_DATA = "mediaProjectionData";

    private static final int MSG_CONNECTION_ESTABLISHED = 1;

    private static final String SOCKET_NAME = "sndcpy";

    private static int SAMPLE_RATE = 48000;
    private static int CHANNELS = 2;
    private static int BITRATE = 128000;
    private static boolean USE_ADB = false;
    private static boolean ENCODING = false;
    private static boolean HTTP_STREAMING = false;
    private static String HOST = "0.0.0.0";
    private static int PORT = 4678;

    private final Handler handler = new ConnectionHandler(this);
    private MediaProjection mediaProjection;
    private Thread recorderThread;
    private boolean IS_RUNNING = true;

    private final static int BUFFER_MS = 15;
    private final static int BUFFER_SIZE = SAMPLE_RATE * CHANNELS * BUFFER_MS / 1000;


    private static final String OUTPUT_HEADERS = "HTTP/1.1 200 OK\r\n Content-Type: text/html\r\n\r\n";

    public static void start(Context context, Intent data) {
        String action = data.getAction();
        if (!ACTION_STOP.equals(action)) {
            USE_ADB = data.getBooleanExtra("USE_ADB", false);
            ENCODING = data.getBooleanExtra("ENCODING", false);
            HTTP_STREAMING = data.getBooleanExtra("HTTP_STREAMING", false);

            String ADDRESS_PORT = data.getStringExtra("ADDRESS_PORT");
            if (ADDRESS_PORT.length() == 0) {
                ADDRESS_PORT = "0.0.0.0:4678";
            }
            String[] ADDRESS_PORT_split = ADDRESS_PORT.split(":");
            if (ADDRESS_PORT_split.length < 2) {
                HOST = ADDRESS_PORT_split[0];
            } else {
                HOST = ADDRESS_PORT_split[0];
                PORT = Integer.parseInt(ADDRESS_PORT_split[1]);
            }

            if (!Objects.equals(data.getStringExtra("BITRATE"), "")) {
                BITRATE = Integer.parseInt(data.getStringExtra("BITRATE")) * 1000;
            }

            if (!Objects.equals(data.getStringExtra("CHANNEL"), "")) {
                CHANNELS = Integer.parseInt(data.getStringExtra("CHANNEL"));
            }
        }

        Intent intent = new Intent(context, RecordService.class);
        intent.setAction(ACTION_RECORD);
        intent.putExtra(EXTRA_MEDIA_PROJECTION_DATA, data);
        context.startForegroundService(intent);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        Notification notification = createNotification(false);

        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_NONE);
        getNotificationManager().createNotificationChannel(channel);

        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        if (isRunning()) {
            return START_NOT_STICKY;
        }

        Intent data = intent.getParcelableExtra(EXTRA_MEDIA_PROJECTION_DATA);
        MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        mediaProjection = mediaProjectionManager.getMediaProjection(Activity.RESULT_OK, data);
        if (mediaProjection != null) {
            startRecording();
        } else {
            Log.w(TAG, "Failed to capture audio");
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification createNotification(boolean established) {
        Notification.Builder notificationBuilder = new Notification.Builder(this, CHANNEL_ID);
        notificationBuilder.setContentTitle(getString(R.string.app_name));
        int textRes = established ? R.string.notification_forwarding : R.string.notification_waiting;
        notificationBuilder.setContentText(getText(textRes));
        notificationBuilder.setSubText(HOST + ":" + PORT);
        notificationBuilder.setSmallIcon(R.drawable.ic_album_black_24dp);
        notificationBuilder.addAction(createStopAction());
        return notificationBuilder.build();
    }


    private Intent createStopIntent() {
        Intent intent = new Intent(this, RecordService.class);
        intent.setAction(ACTION_STOP);
        return intent;
    }

    private Notification.Action createStopAction() {
        Intent stopIntent = createStopIntent();
        PendingIntent stopPendingIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
        Icon stopIcon = Icon.createWithResource(this, R.drawable.ic_close_24dp);
        String stopString = getString(R.string.action_stop);
        Notification.Action.Builder actionBuilder = new Notification.Action.Builder(stopIcon, stopString, stopPendingIntent);
        return actionBuilder.build();
    }

    private static Socket realSocketConnect() throws IOException {
        try (ServerSocket serverSocket = new ServerSocket(PORT, 1, InetAddress.getByName(HOST))) {
            return serverSocket.accept();
        }
    }

    private static LocalSocket connect() throws IOException {
        try (LocalServerSocket localServerSocket = new LocalServerSocket(SOCKET_NAME)) {
            return localServerSocket.accept();
        }
    }

    private static AudioPlaybackCaptureConfiguration createAudioPlaybackCaptureConfig(MediaProjection mediaProjection) {
        AudioPlaybackCaptureConfiguration.Builder confBuilder = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection);
        confBuilder.addMatchingUsage(AudioAttributes.USAGE_MEDIA);
        confBuilder.addMatchingUsage(AudioAttributes.USAGE_GAME);
        confBuilder.addMatchingUsage(AudioAttributes.USAGE_UNKNOWN);
        return confBuilder.build();
    }

    private static AudioFormat createAudioFormat() {
        AudioFormat.Builder builder = new AudioFormat.Builder();
        builder.setEncoding(AudioFormat.ENCODING_PCM_16BIT);
        builder.setSampleRate(SAMPLE_RATE);
        builder.setChannelMask(CHANNELS == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO);
        return builder.build();
    }

    private MediaCodec createEncodeAudio() throws IOException {
        MediaCodec encoder = MediaCodec.createEncoderByType("audio/mp4a-latm");
        MediaFormat format = MediaFormat.createAudioFormat("audio/mp4a-latm", SAMPLE_RATE, CHANNELS);

        format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
        format.setInteger(MediaFormat.KEY_SAMPLE_RATE, SAMPLE_RATE);
        format.setInteger(MediaFormat.KEY_CHANNEL_COUNT, CHANNELS);
        format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, BUFFER_SIZE);
        format.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        return encoder;
    }

    @SuppressLint("MissingPermission")
    private AudioRecord createAudioRecord(MediaProjection mediaProjection) {
        AudioRecord.Builder builder = new AudioRecord.Builder();
        builder.setAudioFormat(createAudioFormat());
        builder.setBufferSizeInBytes(1024 * 1024 * 10);
        builder.setAudioPlaybackCaptureConfig(createAudioPlaybackCaptureConfig(mediaProjection));
        return builder.build();
    }

    private boolean handleCodecInput(AudioRecord audioRecord,
                                     MediaCodec mediaCodec) throws IOException {
        byte[] audioRecordData = new byte[BUFFER_SIZE];
        int length = audioRecord.read(audioRecordData, 0, audioRecordData.length);
//
//        if (length != BUFFER_SIZE) {
//            return false;
//        }

        int codecInputBufferIndex = mediaCodec.dequeueInputBuffer(-1);

        if (codecInputBufferIndex >= 0) {
            ByteBuffer codecBuffer = mediaCodec.getInputBuffer(codecInputBufferIndex);
            codecBuffer.clear();
            codecBuffer.put(audioRecordData);
            mediaCodec.queueInputBuffer(codecInputBufferIndex, 0, length, 0, IS_RUNNING ? 0 : MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        }

        return true;
    }

    private void handleCodecOutput(MediaCodec mediaCodec,
                                   MediaCodec.BufferInfo bufferInfo,
                                   OutputStream outputStream)
            throws IOException {
        int codecOutputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);

        while (codecOutputBufferIndex != MediaCodec.INFO_TRY_AGAIN_LATER) {
            if (codecOutputBufferIndex >= 0) {
                ByteBuffer encoderOutputBuffer = mediaCodec.getOutputBuffer(codecOutputBufferIndex);

                encoderOutputBuffer.position(bufferInfo.offset);
                encoderOutputBuffer.limit(bufferInfo.offset + bufferInfo.size);

                if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != MediaCodec.BUFFER_FLAG_CODEC_CONFIG) {
                    byte[] header = createAdtsHeader(bufferInfo.size - bufferInfo.offset);
                    outputStream.write(header);

                    byte[] data = new byte[encoderOutputBuffer.remaining()];
                    encoderOutputBuffer.get(data);
                    outputStream.write(data);
                }

                encoderOutputBuffer.clear();

                mediaCodec.releaseOutputBuffer(codecOutputBufferIndex, false);
            }
            codecOutputBufferIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0);
        }
    }


    private byte[] createAdtsHeader(int length) {
        int frameLength = length + 7;
        byte[] adtsHeader = new byte[7];

        adtsHeader[0] = (byte) 0xFF; // Sync Word
        adtsHeader[1] = (byte) 0xF1; // MPEG-4, Layer (0), No CRC
        adtsHeader[2] = (byte) ((MediaCodecInfo.CodecProfileLevel.AACObjectLC - 1) << 6);
        adtsHeader[2] |= (((byte) 3) << 2);
        adtsHeader[2] |= (((byte) CHANNELS) >> 2);
        adtsHeader[3] = (byte) (((CHANNELS & 3) << 6) | ((frameLength >> 11) & 0x03));
        adtsHeader[4] = (byte) ((frameLength >> 3) & 0xFF);
        adtsHeader[5] = (byte) (((frameLength & 0x07) << 5) | 0x1f);
        adtsHeader[6] = (byte) 0xFC;

        return adtsHeader;
    }

    private Thread recordingADBNoEncode(AudioRecord recorder) throws IOException {
        recorderThread = new Thread(() -> {
            try (LocalSocket socket = connect()) {
                handler.sendEmptyMessage(MSG_CONNECTION_ESTABLISHED);
                socket.getOutputStream().write(OUTPUT_HEADERS.getBytes(StandardCharsets.UTF_8));
                recorder.startRecording();

                byte[] buf = new byte[BUFFER_SIZE];
                while (true) {
                    int r = recorder.read(buf, 0, buf.length);
                    socket.getOutputStream().write(buf, 0, r);
                }
            } catch (IOException e) {
                Log.e(TAG, e.toString());
            } finally {
                recorder.stop();
                stopSelf();
            }
        });
        return recorderThread;
    }

    private Thread recordingNoEncode(AudioRecord recorder) throws IOException {
        recorderThread = new Thread(() -> {
            try (Socket socket = realSocketConnect()) {
                handler.sendEmptyMessage(MSG_CONNECTION_ESTABLISHED);
                socket.getOutputStream().write(OUTPUT_HEADERS.getBytes(StandardCharsets.UTF_8));
                recorder.startRecording();

                byte[] buf = new byte[SAMPLE_RATE * CHANNELS * BUFFER_MS / 1000];
                while (true) {
                    int r = recorder.read(buf, 0, buf.length);
                    socket.getOutputStream().write(buf, 0, r);
                }
            } catch (IOException e) {
                Log.e(TAG, e.toString());
            } finally {
                recorder.stop();
                stopSelf();
            }
        });
        return recorderThread;
    }

    private Thread recordingEncode(AudioRecord recorder) throws IOException {
        final MediaCodec mediaCodec = createEncodeAudio();
        MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();

        recorderThread = new Thread(() -> {
            Socket socket = null;
            try {
                socket = realSocketConnect();
                socket.getOutputStream().write(OUTPUT_HEADERS.getBytes(StandardCharsets.UTF_8));
                handler.sendEmptyMessage(MSG_CONNECTION_ESTABLISHED);

                recorder.startRecording();
                mediaCodec.start();
                while (true) {
                    boolean success = handleCodecInput(recorder, mediaCodec);
                    if (success) {
                        handleCodecOutput(mediaCodec, bufferInfo, socket.getOutputStream());
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, e.toString());
            } finally {
                IS_RUNNING = false;
                try {
                    boolean success = handleCodecInput(recorder, mediaCodec);
                    if (success) {
                        assert socket != null;
                        handleCodecOutput(mediaCodec, bufferInfo, socket.getOutputStream());
                    }

                    recorder.stop();
                    mediaCodec.stop();

                    recorder.release();
                    mediaCodec.release();

                    if (socket != null) {
                        socket.close();
                    }
                } catch (IOException e) {
                    // ignore...
                }

                stopSelf();
            }
        });
        return recorderThread;
    }


    private void startRecording() {
        final AudioRecord recorder = createAudioRecord(mediaProjection);
        Thread audioThread = null;
        try {
            if (USE_ADB) {
                audioThread = recordingADBNoEncode(recorder);
            } else if (HTTP_STREAMING) {
                if (ENCODING) {
                    audioThread = recordingEncode(recorder);
                }
                else {
                    audioThread = recordingNoEncode(recorder);
                }
            }
        } catch (IOException e) {
            Log.e(TAG, e.toString());
        }
        if (audioThread != null) {
            audioThread.start();
        } else {
            stopSelf();
        }
    }

    private boolean isRunning() {
        return recorderThread != null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        stopForeground(true);
        if (recorderThread != null) {
            recorderThread.interrupt();
            recorderThread = null;
        }
    }

    private NotificationManager getNotificationManager() {
        return (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
    }

    private static final class ConnectionHandler extends Handler {

        private final RecordService service;

        ConnectionHandler(RecordService service) {
            this.service = service;
        }

        @Override
        public void handleMessage(Message message) {
            if (!service.isRunning()) {
                // if the VPN is not running anymore, ignore obsolete events
                return;
            }

            if (message.what == MSG_CONNECTION_ESTABLISHED) {
                Notification notification = service.createNotification(true);
                service.getNotificationManager().notify(NOTIFICATION_ID, notification);
            }
        }
    }
}