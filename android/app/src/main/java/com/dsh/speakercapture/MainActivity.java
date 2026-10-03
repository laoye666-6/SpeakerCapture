package com.dsh.speakercapture;

import android.Manifest;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

/**
 * 扬声器录音机(Android 版):通过 MediaProjection 采集本机正在播放的音频,
 * 保存为 WAV / MP3。Android 无 WASAPI 环回,系统声音必须经此授权采集。
 */
public class MainActivity extends Activity {

    private static final int REQ_PROJECTION = 1;
    private static final int REQ_NOTIFICATIONS = 2;

    private MediaProjectionManager projectionManager;
    private Spinner bitrateSpinner;
    private CheckBox wavCheck, mp3Check;
    private Button startBtn, stopBtn;
    private TextView status;

    private final BroadcastReceiver statusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String message = intent.getStringExtra(CaptureService.EXTRA_MESSAGE);
            if (message != null) {
                status.setText(message);
            }
            String state = intent.getStringExtra(CaptureService.EXTRA_STATE);
            if (CaptureService.STATE_STOPPED.equals(state)
                    || CaptureService.STATE_ERROR.equals(state)) {
                setRecordingUi(false);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        projectionManager =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);

        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    REQ_NOTIFICATIONS);
        }

        setContentView(buildUi());
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(CaptureService.ACTION_STATUS);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(statusReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        unregisterReceiver(statusReceiver);
    }

    private ScrollView buildUi() {
        float d = getResources().getDisplayMetrics().density;
        int pad = (int) (16 * d);

        TextView title = new TextView(this);
        title.setText("扬声器录音机\n抓取本机正在播放的声音 → WAV / MP3");
        title.setTextSize(18);
        title.setPadding(0, 0, 0, (int) (8 * d));

        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.HORIZONTAL);
        form.setGravity(Gravity.CENTER_VERTICAL);

        form.addView(labeled("比特率 kbps:", d));

        bitrateSpinner = new Spinner(this);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item,
                new String[]{"128", "192", "256", "320"});
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        bitrateSpinner.setAdapter(adapter);
        bitrateSpinner.setSelection(1);
        form.addView(bitrateSpinner);

        wavCheck = new CheckBox(this);
        wavCheck.setText("WAV");
        wavCheck.setChecked(true);
        form.addView(wavCheck);

        mp3Check = new CheckBox(this);
        mp3Check.setText("MP3");
        mp3Check.setChecked(true);
        form.addView(mp3Check);

        startBtn = new Button(this);
        startBtn.setText("开始录制");
        startBtn.setOnClickListener(v -> startCapture());

        stopBtn = new Button(this);
        stopBtn.setText("停止");
        stopBtn.setEnabled(false);
        stopBtn.setOnClickListener(v -> {
            startService(new Intent(this, CaptureService.class)
                    .setAction(CaptureService.ACTION_STOP));
            stopBtn.setEnabled(false);
            status.setText("正在收尾并保存…");
        });

        Button toneBtn = new Button(this);
        toneBtn.setText("播放测试音");
        toneBtn.setOnClickListener(v -> playTestTone());

        status = new TextView(this);
        status.setText("就绪。点「开始录制」并在系统弹窗中允许采集,然后播放任意音乐。\n"
                + "录制内容保存到 Music/SpeakerCapture/。");

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.addView(title);
        root.addView(form);
        root.addView(startBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(stopBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(toneBtn, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        return scroll;
    }

    private TextView labeled(String text, float d) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setPadding(0, 0, (int) (8 * d), 0);
        return tv;
    }

    private void setRecordingUi(boolean recording) {
        startBtn.setEnabled(!recording);
        stopBtn.setEnabled(recording);
        bitrateSpinner.setEnabled(!recording);
        wavCheck.setEnabled(!recording);
        mp3Check.setEnabled(!recording);
    }

    private void startCapture() {
        boolean wav = wavCheck.isChecked();
        boolean mp3 = mp3Check.isChecked();
        if (!wav && !mp3) {
            status.setText("请至少勾选一种输出格式(WAV 或 MP3)。");
            return;
        }
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQ_PROJECTION);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PROJECTION) {
            return;
        }
        if (resultCode != RESULT_OK || data == null) {
            status.setText("未授权音频采集,无法录制。");
            return;
        }
        Intent svc = new Intent(this, CaptureService.class);
        svc.putExtra(CaptureService.EXTRA_RESULT_CODE, resultCode);
        svc.putExtra(CaptureService.EXTRA_RESULT_DATA, data);
        svc.putExtra(CaptureService.EXTRA_BITRATE,
                Integer.parseInt(bitrateSpinner.getSelectedItem().toString()));
        svc.putExtra(CaptureService.EXTRA_WAV, wavCheck.isChecked());
        svc.putExtra(CaptureService.EXTRA_MP3, mp3Check.isChecked());
        startForegroundService(svc);
        setRecordingUi(true);
        status.setText("采集服务启动中…");
    }

    /** 播放 2 秒 440Hz 测试音,验证采集链路(自录自放不受播放采集限制)。 */
    private void playTestTone() {
        final int rate = 44100;
        final int frames = rate * 2;
        byte[] pcm = new byte[frames * 4];
        for (int i = 0; i < frames; i++) {
            short v = (short) (Math.sin(2 * Math.PI * 440 * i / rate) * 16000);
            byte lo = (byte) (v & 0xFF);
            byte hi = (byte) ((v >> 8) & 0xFF);
            pcm[i * 4] = lo;
            pcm[i * 4 + 1] = hi;
            pcm[i * 4 + 2] = lo;
            pcm[i * 4 + 3] = hi;
        }
        AudioTrack track = new AudioTrack(
                new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build(),
                new AudioFormat.Builder()
                        .setSampleRate(rate)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).build(),
                pcm.length, AudioTrack.MODE_STATIC, AudioManager.AUDIO_SESSION_ID_GENERATE);
        track.write(pcm, 0, pcm.length);
        track.setNotificationMarkerPosition(frames);
        track.setPlaybackPositionUpdateListener(new AudioTrack.OnPlaybackPositionUpdateListener() {
            @Override
            public void onMarkerReached(AudioTrack t) {
                t.release();
                runOnUiThread(() -> status.setText("测试音播放完毕。"));
            }

            @Override
            public void onPeriodicNotification(AudioTrack t) { }
        });
        track.play();
        status.setText("正在播放测试音(440Hz,2 秒)…");
    }
}
