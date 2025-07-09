package com.example.ytaudioserver;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PORT = 8080;
    
    private TextView statusTextView;
    private Button startButton;
    private Button stopButton;
    
    private ServerSocket serverSocket;
    private boolean isServerRunning = false;
    private ExecutorService threadPool;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        
        Log.d(TAG, "onCreate chiamato");
        showToast("App avviata");

        statusTextView = findViewById(R.id.statusTextView);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        
        threadPool = Executors.newCachedThreadPool();

        updateUI();

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "startButton cliccato");
                startServer();
            }
        });

        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.d(TAG, "stopButton cliccato");
                stopServer();
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        Log.d(TAG, "onResume chiamato");
        updateUI();
    }
    
    @Override
    protected void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy chiamato");
        stopServer();
        threadPool.shutdown();
    }

    private void startServer() {
        if (isServerRunning) {
            showToast("Il server è già in esecuzione");
            return;
        }
        
        try {
            Log.d(TAG, "Avvio del server sulla porta " + PORT);
            serverSocket = new ServerSocket(PORT);
            isServerRunning = true;
            
            showToast("Server avviato sulla porta " + PORT);
            updateUI();
            
            // Avvia un thread per accettare connessioni
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        while (isServerRunning && serverSocket != null && !serverSocket.isClosed()) {
                            try {
                                final Socket socket = serverSocket.accept();
                                Log.d(TAG, "Connessione accettata");
                                
                                // Gestisci la richiesta in un thread separato
                                threadPool.execute(new Runnable() {
                                    @Override
                                    public void run() {
                                        handleRequest(socket);
                                    }
                                });
                            } catch (IOException e) {
                                if (isServerRunning) {
                                    Log.e(TAG, "Errore nell'accettare la connessione", e);
                                }
                            }
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "Errore nel thread del server", e);
                        showToast("Errore nel thread del server: " + e.getMessage());
                    }
                }
            }).start();
            
        } catch (IOException e) {
            Log.e(TAG, "Errore nell'avvio del server", e);
            showToast("Errore nell'avvio del server: " + e.getMessage());
        }
    }

    private void stopServer() {
        if (!isServerRunning) {
            return;
        }
        
        isServerRunning = false;
        
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
            
            showToast("Server arrestato");
            updateUI();
        } catch (IOException e) {
            Log.e(TAG, "Errore nell'arresto del server", e);
            showToast("Errore nell'arresto del server: " + e.getMessage());
        }
    }
    
    private void handleRequest(Socket socket) {
        try {
            // Leggi la richiesta
            InputStream in = socket.getInputStream();
            byte[] buffer = new byte[8192];
            int read = in.read(buffer);
            String request = new String(buffer, 0, read);
            
            // Analizza la richiesta HTTP
            String[] lines = request.split("\r\n");
            String firstLine = lines[0];
            String[] parts = firstLine.split(" ");
            String method = parts[0];
            String path = parts[1];
            
            Log.d(TAG, "Richiesta ricevuta: " + method + " " + path);
            showToast("Richiesta: " + method + " " + path);
            
            // Estrai i parametri dalla query string
            Map<String, String> params = new HashMap<>();
            if (path.contains("?")) {
                String query = path.substring(path.indexOf("?") + 1);
                String[] queryParams = query.split("&");
                for (String param : queryParams) {
                    String[] keyValue = param.split("=");
                    if (keyValue.length == 2) {
                        params.put(keyValue[0], keyValue[1]);
                    }
                }
                path = path.substring(0, path.indexOf("?"));
            }
            
            // Crea una risposta semplice
            JSONObject responseJson = new JSONObject();
            try {
                responseJson.put("status", "success");
                responseJson.put("message", "Richiesta ricevuta correttamente");
                responseJson.put("method", method);
                responseJson.put("path", path);
                
                // Se c'è un ID video, aggiungiamolo alla risposta
                if (params.containsKey("id")) {
                    String videoId = params.get("id");
                    responseJson.put("videoId", videoId);
                    responseJson.put("title", "Video di test: " + videoId);
                    responseJson.put("thumbnail", "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg");
                    responseJson.put("audio_url", "https://example.com/audio/" + videoId + ".mp3");
                }
            } catch (JSONException e) {
                Log.e(TAG, "Errore nella creazione della risposta JSON", e);
            }
            
            String response = responseJson.toString();
            
            // Invia la risposta
            OutputStream out = socket.getOutputStream();
            String httpResponse = 
                    "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: " + response.length() + "\r\n" +
                    "Connection: close\r\n\r\n" +
                    response;
            out.write(httpResponse.getBytes(StandardCharsets.UTF_8));
            out.flush();
            
            // Chiudi la connessione
            socket.close();
            
            showToast("Risposta inviata");
            
        } catch (IOException e) {
            Log.e(TAG, "Errore nella gestione della richiesta", e);
            try {
                socket.close();
            } catch (IOException ex) {
                Log.e(TAG, "Errore nella chiusura del socket", ex);
            }
        }
    }

    private void updateUI() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                String ipAddress = getLocalIpAddress();
                
                if (ipAddress != null) {
                    statusTextView.setText(
                        "Server: " + (isServerRunning ? "ATTIVO" : "INATTIVO") + "\n" +
                        "URL: http://" + ipAddress + ":8080/ytinfo?id=VIDEO_ID\n\n" +
                        "Istruzioni:\n" +
                        "1. Avvia il server con il pulsante 'Avvia Server'\n" +
                        "2. Usa l'URL sopra in Kodular\n" +
                        "3. Sostituisci VIDEO_ID con l'ID del video YouTube"
                    );
                } else {
                    statusTextView.setText("Impossibile ottenere l'indirizzo IP. Sei connesso a una rete Wi-Fi?");
                }
                
                startButton.setEnabled(!isServerRunning);
                stopButton.setEnabled(isServerRunning);
            }
        });
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null) {
                return Formatter.formatIpAddress(wifiManager.getConnectionInfo().getIpAddress());
            }
        } catch (Exception e) {
            Log.e(TAG, "Errore nel recupero dell'indirizzo IP", e);
        }
        return null;
    }
    
    private void showToast(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }
}
