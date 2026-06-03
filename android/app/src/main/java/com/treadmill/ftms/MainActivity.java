package com.treadmill.ftms;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private boolean running = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Auto-start the service on launch (needed for adb `am start` deployment)
        startForegroundService(new Intent(this, FtmsBridgeService.class));
        running = true;

        // Simple layout in code
        android.widget.LinearLayout layout = new android.widget.LinearLayout(this);
        layout.setOrientation(android.widget.LinearLayout.VERTICAL);
        layout.setPadding(40, 80, 40, 40);

        statusText = new TextView(this);
        statusText.setTextSize(18);
        statusText.setText("FTMS Bridge: Running");
        layout.addView(statusText);

        Button btn = new Button(this);
        btn.setText("Start FTMS Bridge");
        btn.setOnClickListener(v -> toggleService(btn));
        layout.addView(btn);

        TextView info = new TextView(this);
        info.setPadding(0, 40, 0, 0);
        info.setTextSize(14);
        info.setText("Bridges treadmill serial data to BLE FTMS\n" +
                "so FitShow can connect via Bluetooth.");
        layout.addView(info);

        setContentView(layout);
    }

    private void toggleService(Button btn) {
        Intent intent = new Intent(this, FtmsBridgeService.class);
        if (!running) {
            startForegroundService(intent);
            btn.setText("Stop FTMS Bridge");
            statusText.setText("FTMS Bridge: Running");
            running = true;
        } else {
            stopService(intent);
            btn.setText("Start FTMS Bridge");
            statusText.setText("FTMS Bridge: Stopped");
            running = false;
        }
    }
}
