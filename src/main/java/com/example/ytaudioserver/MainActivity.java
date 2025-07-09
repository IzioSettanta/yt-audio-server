package com.example.ytaudioserver;

import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.text.format.Formatter;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {

    private TextView statusTextView;
    private Button startButton;
    private Button stopButton;
    private boolean isServerRunning = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusTextView = findViewById(R.id.statusTextView);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);

        updateUI();

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startServer();
            }
        });

        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopServer();
            }
        });
    }

    private void startServer() {
        Intent serviceIntent = new Intent(this, ServerService.class);
        startForegroundService(serviceIntent);
        isServerRunning = true;
        updateUI();
    }

    private void stopServer() {
        Intent serviceIntent = new Intent(this, ServerService.class);
        stopService(serviceIntent);
        isServerRunning = false;
        updateUI();
    }

    private void updateUI() {
        if (isServerRunning) {
            String ipAddress = getLocalIpAddress();
            statusTextView.setText("Server in esecuzione\n" +
                    "URL: http://" + ipAddress + ":8080/ytinfo?id=VIDEO_ID");
            startButton.setEnabled(false);
            stopButton.setEnabled(true);
        } else {
            statusTextView.setText("Server non avviato");
            startButton.setEnabled(true);
            stopButton.setEnabled(false);
        }
    }

    private String getLocalIpAddress() {
        WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(WIFI_SERVICE);
        return Formatter.formatIpAddress(wifiManager.getConnectionInfo().getIpAddress());
    }
}
