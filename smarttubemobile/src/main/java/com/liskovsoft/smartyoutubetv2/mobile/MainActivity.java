package com.liskovsoft.smartyoutubetv2.mobile;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

public final class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView status = findViewById(R.id.mobile_status);
        Button verifyTouch = findViewById(R.id.verify_touch);
        verifyTouch.setOnClickListener(view -> {
            status.setText(R.string.touch_verified);
            verifyTouch.setEnabled(false);
        });
    }
}
