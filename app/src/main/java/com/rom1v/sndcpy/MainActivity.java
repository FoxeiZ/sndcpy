package com.rom1v.sndcpy;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;

public class MainActivity extends Activity {

    private static final int REQUEST_CODE_PERMISSION_AUDIO = 1;
    private static final int REQUEST_CODE_START_CAPTURE = 2;

    private CheckBox USE_ADB_CHECK;
    private CheckBox ENCODING_CHECK;
    private CheckBox HTTP_STREAMING_CHECK;
    private EditText ADDRESS_PORT_EDIT;
    private EditText BITRATE_EDIT;
    private EditText CHANNEL_EDIT;


    @SuppressLint("NonConstantResourceId")
    public void onCheckboxClick(View view) {
        CheckBox checkBox = (CheckBox) view;
        boolean isChecked = checkBox.isChecked();

        switch (view.getId()) {
            case R.id.encode_mp3:
                if (isChecked) {
                    if (!HTTP_STREAMING_CHECK.isChecked()) {
                        HTTP_STREAMING_CHECK.setChecked(true);
                    }
                    BITRATE_EDIT.setEnabled(true);
                } else {
                    BITRATE_EDIT.setEnabled(false);
                }

            case R.id.localhost_stream:
                if (isChecked) {
                    USE_ADB_CHECK.setEnabled(false);
                    ADDRESS_PORT_EDIT.setEnabled(true);
                } else if (view.getId() == R.id.localhost_stream){
                    ADDRESS_PORT_EDIT.setEnabled(false);
                }

                if (!ENCODING_CHECK.isChecked() && !HTTP_STREAMING_CHECK.isChecked()) {
                    USE_ADB_CHECK.setEnabled(true);
                }
                break;

            case R.id.use_adb:
                if (isChecked) {
                    ENCODING_CHECK.setEnabled(false);
                    HTTP_STREAMING_CHECK.setEnabled(false);
                } else {
                    ENCODING_CHECK.setEnabled(true);
                    HTTP_STREAMING_CHECK.setEnabled(true);
                }
                break;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            String[] permissions = {Manifest.permission.RECORD_AUDIO};
            requestPermissions(permissions, REQUEST_CODE_PERMISSION_AUDIO);
        }

        USE_ADB_CHECK = findViewById(R.id.use_adb);
        ENCODING_CHECK = findViewById(R.id.encode_mp3);
        HTTP_STREAMING_CHECK = findViewById(R.id.localhost_stream);

        ADDRESS_PORT_EDIT = findViewById(R.id.address_port);
        BITRATE_EDIT = findViewById(R.id.bitrate);
        CHANNEL_EDIT = findViewById(R.id.channel);

        USE_ADB_CHECK.setOnClickListener(this::onCheckboxClick);
        ENCODING_CHECK.setOnClickListener(this::onCheckboxClick);
        HTTP_STREAMING_CHECK.setOnClickListener(this::onCheckboxClick);

        Button startButton = (Button) findViewById(R.id.startbtn);
        startButton.setOnClickListener(v -> {
            MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
            Intent intent = mediaProjectionManager.createScreenCaptureIntent();
            startActivityForResult(intent, REQUEST_CODE_START_CAPTURE);
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_START_CAPTURE && resultCode == Activity.RESULT_OK) {
            data.putExtra("USE_ADB", USE_ADB_CHECK.isChecked());
            data.putExtra("ENCODING", ENCODING_CHECK.isChecked());
            data.putExtra("HTTP_STREAMING", HTTP_STREAMING_CHECK.isChecked());
            data.putExtra("ADDRESS_PORT", ADDRESS_PORT_EDIT.getText().toString());
            data.putExtra("BITRATE", BITRATE_EDIT.getText().toString());
            data.putExtra("CHANNEL", CHANNEL_EDIT.getText().toString());

            RecordService.start(this, data);
        }
        finish();
    }
}
