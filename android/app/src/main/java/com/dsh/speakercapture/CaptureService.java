package com.dsh.speakercapture;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

import de.sciss.jump3r.mp3.BitStream;
import de.sciss.jump3r.mp3.GainAnalysis;
import de.sciss.jump3r.mp3.ID3Tag;
import de.sciss.jump3r.mp3.Lame;
import de.sciss.jump3r.mp3.LameGlobalFlags;
import de.sciss.jump3r.mp3.MPEGMode;
import de.sciss.jump3r.mp3.Presets;
import de.sciss.jump3r.mp3.Quantize;
import de.sciss.jump3r.mp3.QuantizePVT;
import de.sciss.jump3r.mp3.Reservoir;
import de.sciss.jump3r.mp3.Takehiro;
import de.sciss.jump3r.mp3.VBRTag;
import de.sciss.jump3r.mp3.Version;
import de.sciss.jump3r.mpg.Common;
import de.sciss.jump3r.mpg.Interface;
import de.sciss.jump3r.mpg.MPGLib;

/**
 * 前台服务:用 MediaProjection 的音频回放采集抓取本机播放的声音,
 * 实时写 WAV,同时用 jump3r(LAME 纯 Java 移植)编码 MP3;停止后把
 * 两个文件发布到 MediaStore 的 Music/SpeakerCapture/ 目录。
 */
public class CaptureService extends Service {

    static final String ACTION_START = "com.dsh.speakercapture.START";
    static final String ACTION_STOP = "com.dsh.speakercapture.STOP";
    static final String ACTION_STATUS = "com.dsh.speakercapture.STATUS";
    static final String EXTRA_RESULT_CODE = "resultCode";
    static final String EXTRA_RESULT_DATA = "resultData";
    static final String EXTRA_BITRATE = "bitrate";
    static final String EXTRA_WAV = "wav";
    static final String EXTRA_MP3 = "mp3";
    static final String EXTRA_MESSAGE = "message";
    static final String EXTRA_STATE = "state";
    static final String STATE_RECORDING = "recording";
    static final String STATE_STOPPED = "stopped";
    static final String STATE_ERROR = "error";

    private static final String TAG = "CaptureService";
    private static final String CHANNEL_ID = "capture";
    private static final int NOTIFICATION_ID = 1;
    private static final int SAMPLE_RATE = 44100;
    private static final int CHANNELS = 2;

    private MediaProjection projection;
    private AudioRecord record;
    private Thread worker;
    private volatile boolean running;
    private volatile boolean wantWav = true;
    private volatile boolean wantMp3 = true;
    private int bitrate = 192;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || ACTION_STOP.equals(intent.getAction())) {
            running = false;
            return START_NOT_STICKY;
        }
        bitrate = intent.getIntExtra(EXTRA_BITRATE, 192);
        wantWav = intent.getBooleanExtra(EXTRA_WAV, true);
        wantMp3 = intent.getBooleanExtra(EXTRA_MP3, true);
        try {
            startAsForeground();
            beginCapture(intent.getIntExtra(EXTRA_RESULT_CODE, 0),
                    (Intent) intent.getParcelableExtra(EXTRA_RESULT_DATA));
        } catch (Throwable e) {
            Log.e(TAG, "start failed", e);
            broadcast(STATE_ERROR, "启动失败:" + e);
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private void startAsForeground() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL_ID, "录制服务",
                NotificationManager.IMPORTANCE_LOW));
        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle(getString(R.string.app_name))
                .setContentText("正在采集本机播放的音频")
                .setOngoing(true)
                .build();
        startForeground(NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private void beginCapture(int resultCode, Intent resultData) {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        // targetSdk 34 要求:必须已进入 mediaProjection 前台服务后才能调用 getMediaProjection
        projection = mpm.getMediaProjection(resultCode, resultData);
        if (projection == null) {
            broadcast(STATE_ERROR, "未获得系统授权。");
            stopSelf();
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        projection.registerCallback(new MediaProjection.Callback() {
            @Override
            public void onStop() {
                running = false;
            }
        }, main);

        AudioPlaybackCaptureConfiguration config =
                new AudioPlaybackCaptureConfiguration.Builder(projection)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build();
        AudioFormat format = new AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_STEREO)
                .build();
        int minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_STEREO, AudioFormat.ENCODING_PCM_16BIT);
        record = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(Math.max(minBuf * 2, 32768))
                .setAudioPlaybackCaptureConfig(config)
                .build();

        running = true;
        worker = new Thread(this::captureLoop, "capture");
        worker.start();
        broadcast(STATE_RECORDING, "开始录制,播放任意音乐即可抓取…");
    }

    /** 按官方 Main 的接线路径装配 LAME 各模块(不含文件输入部分)。 */
    private static final class Mp3Encoder {
        final Lame lame;
        final LameGlobalFlags gfp;

        Mp3Encoder(int bitrate) {
            Lame lame = new Lame();
            GainAnalysis ga = new GainAnalysis();
            BitStream bs = new BitStream();
            Presets p = new Presets();
            QuantizePVT qupvt = new QuantizePVT();
            Quantize qu = new Quantize();
            VBRTag vbr = new VBRTag();
            Version ver = new Version();
            ID3Tag id3 = new ID3Tag();
            Reservoir rv = new Reservoir();
            Takehiro tak = new Takehiro();
            MPGLib mpg = new MPGLib();
            Interface intf = new Interface();
            Common common = new Common();

            lame.setModules(ga, bs, p, qupvt, qu, vbr, ver, id3, mpg);
            bs.setModules(ga, mpg, ver, vbr);
            id3.setModules(bs, ver);
            p.setModules(lame);
            qu.setModules(bs, rv, qupvt, tak);
            qupvt.setModules(tak, rv, lame.enc.psy);
            rv.setModules(bs);
            tak.setModules(qupvt);
            vbr.setModules(lame, bs, ver);
            mpg.setModules(intf, common);
            intf.setModules(vbr, common);

            gfp = lame.lame_init();
            // 必须在 lame_init_params 之前调用(C 前端 main.c 同样如此):
            // 初始化 tag_spec,否则自动写 ID3v2 头时会因 tag_spec 为 null 而 NPE
            id3.id3tag_init(gfp);
            gfp.in_samplerate = SAMPLE_RATE;
            gfp.num_channels = CHANNELS;
            gfp.brate = bitrate;
            gfp.quality = 2;
            gfp.mode = MPEGMode.JOINT_STEREO;
            lame.lame_init_params(gfp);
            this.lame = lame;
        }
    }

    private void captureLoop() {
        String stamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File wavTmp = wantWav ? new File(getCacheDir(), stamp + ".wav") : null;
        File mp3Tmp = wantMp3 ? new File(getCacheDir(), stamp + ".mp3part") : null;
        Lame lame = null;
        LameGlobalFlags gfp = null;
        FileOutputStream wavOut = null;
        FileOutputStream mp3Out = null;
        long pcmBytes = 0;
        try {
            if (wavTmp != null) {
                wavOut = new FileOutputStream(wavTmp);
                writeWavHeader(wavOut);
            }
            if (mp3Tmp != null) {
                Mp3Encoder encoder = new Mp3Encoder(bitrate);
                lame = encoder.lame;
                gfp = encoder.gfp;
                mp3Out = new FileOutputStream(mp3Tmp);
            }

            byte[] buf = new byte[8192];
            int[] left = new int[buf.length / 4];
            int[] right = new int[buf.length / 4];
            byte[] mp3buf = new byte[Lame.LAME_MAXMP3BUFFER];
            record.startRecording();
            while (running) {
                int n = record.read(buf, 0, buf.length);
                if (n <= 0) {
                    continue;
                }
                if (wavOut != null) {
                    wavOut.write(buf, 0, n);
                }
                pcmBytes += n;
                if (gfp != null) {
                    int frames = n / (CHANNELS * 2);
                    for (int i = 0; i < frames; i++) {
                        // lame_encode_buffer_int 采用 32bit int 采样约定(2^31 满幅),
                        // 因此 16bit PCM 需左移 16 位,否则编码结果为静音
                        int l = (short) ((buf[i * 4] & 0xFF) | (buf[i * 4 + 1] << 8));
                        int r = (short) ((buf[i * 4 + 2] & 0xFF) | (buf[i * 4 + 3] << 8));
                        left[i] = l << 16;
                        right[i] = r << 16;
                    }
                    int got = lame.lame_encode_buffer_int(
                            gfp, left, right, frames, mp3buf, 0, mp3buf.length);
                    if (got > 0) {
                        mp3Out.write(mp3buf, 0, got);
                    } else if (got < 0) {
                        throw new IOException("MP3 编码错误:" + got);
                    }
                }
            }
            // 收尾:补 WAV 头、冲出 MP3 尾帧
            if (wavOut != null) {
                patchWavHeader(wavOut, pcmBytes);
                wavOut.close();
                wavOut = null;
            }
            if (gfp != null) {
                int flushed = lame.lame_encode_flush(gfp, mp3buf, 0, mp3buf.length);
                if (flushed > 0) {
                    mp3Out.write(mp3buf, 0, flushed);
                }
                mp3Out.close();
                mp3Out = null;
                lame.lame_close(gfp);
            }
            String message = publishToMusic(wavTmp, mp3Tmp);
            broadcast(STATE_STOPPED, message);
        } catch (Throwable t) {
            Log.e(TAG, "capture failed", t);
            broadcast(STATE_ERROR, "录制出错:" + t);
        } finally {
            if (record != null) {
                try {
                    record.stop();
                } catch (Exception ignored) { }
                record.release();
                record = null;
            }
            try {
                if (wavOut != null) {
                    wavOut.close();
                }
                if (mp3Out != null) {
                    mp3Out.close();
                }
            } catch (IOException ignored) { }
            if (wavTmp != null && wavTmp.exists()) {
                wavTmp.delete();
            }
            if (mp3Tmp != null && mp3Tmp.exists()) {
                mp3Tmp.delete();
            }
        }
        stopSelf();
    }

    /** 把临时文件发布到 MediaStore 的 Music/SpeakerCapture/,返回提示语。 */
    private String publishToMusic(File wav, File mp3) throws IOException {
        StringBuilder sb = new StringBuilder("完成!已保存到 Music/SpeakerCapture/:");
        if (wav != null && wav.exists() && wav.length() > 44) {
            copyIntoMediaStore(wav, wav.getName(), "audio/wav");
            sb.append(' ').append(wav.getName());
        }
        if (mp3 != null && mp3.exists() && mp3.length() > 0) {
            copyIntoMediaStore(mp3, mp3.getName().replace(".mp3part", ".mp3"), "audio/mpeg");
            sb.append(' ').append(mp3.getName().replace(".mp3part", ".mp3"));
        }
        return sb.toString();
    }

    private void copyIntoMediaStore(File src, String displayName, String mime) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, mime);
        values.put(MediaStore.Audio.Media.RELATIVE_PATH,
                Environment.DIRECTORY_MUSIC + "/SpeakerCapture");
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);
        Uri uri = getContentResolver().insert(
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) {
            throw new IOException("MediaStore 写入失败");
        }
        try (InputStream in = new FileInputStream(src);
             OutputStream out = getContentResolver().openOutputStream(uri)) {
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) {
                out.write(b, 0, n);
            }
        }
        ContentValues done = new ContentValues();
        done.put(MediaStore.Audio.Media.IS_PENDING, 0);
        getContentResolver().update(uri, done, null, null);
    }

    private void writeWavHeader(FileOutputStream out) throws IOException {
        byte[] h = new byte[44];
        h[0] = 'R'; h[1] = 'I'; h[2] = 'F'; h[3] = 'F';
        h[8] = 'W'; h[9] = 'A'; h[10] = 'V'; h[11] = 'E';
        h[12] = 'f'; h[13] = 'm'; h[14] = 't'; h[15] = ' ';
        putLe(h, 16, 16);                             // fmt 块长
        h[20] = 1; h[21] = 0;                         // PCM
        putLe(h, 22, CHANNELS);
        putLe(h, 24, SAMPLE_RATE);
        putLe(h, 28, SAMPLE_RATE * CHANNELS * 2);     // 字节率
        h[32] = (byte) (CHANNELS * 2); h[33] = 0;     // 块对齐
        h[34] = 16; h[35] = 0;                        // 位深
        h[36] = 'd'; h[37] = 'a'; h[38] = 't'; h[39] = 'a';
        out.write(h);
    }

    private void patchWavHeader(FileOutputStream out, long dataBytes) throws IOException {
        byte[] b = new byte[4];
        putLe(b, 0, (int) (36 + dataBytes));
        out.getChannel().position(4);
        out.getChannel().write(ByteBuffer.wrap(b));
        putLe(b, 0, (int) dataBytes);
        out.getChannel().position(40);
        out.getChannel().write(ByteBuffer.wrap(b));
    }

    private void putLe(byte[] dst, int off, int v) {
        dst[off] = (byte) v;
        dst[off + 1] = (byte) (v >> 8);
        dst[off + 2] = (byte) (v >> 16);
        dst[off + 3] = (byte) (v >> 24);
    }

    private void broadcast(String state, String message) {
        Intent intent = new Intent(ACTION_STATUS).setPackage(getPackageName());
        intent.putExtra(EXTRA_STATE, state);
        intent.putExtra(EXTRA_MESSAGE, message);
        sendBroadcast(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (projection != null) {
            projection.stop();
            projection = null;
        }
        super.onDestroy();
    }
}
