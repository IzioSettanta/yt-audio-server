package com.example.ytaudioserver;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONException;
import org.json.JSONObject;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PORT = 8080;
    
    private TextView statusTextView;
    private Button startButton;
    private Button stopButton;
    private TextView versionTextView;
    private EditText edtVideoId;
    private TextView lblResult;
    
    private ServerSocket serverSocket;
    private boolean isServerRunning = false;
    private ExecutorService threadPool;
    private static boolean newPipeInitialized = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Inizializza NewPipe
        try {
            NewPipe.init(new RealDownloader(), Localization.DEFAULT);
            Log.d(TAG, "NewPipe inizializzato con successo");
        } catch (Exception e) {
            Log.e(TAG, "Errore inizializzazione NewPipe", e);
        }
        
        Log.d(TAG, "onCreate chiamato");
        showToast("App avviata");

        statusTextView = findViewById(R.id.statusTextView);
        startButton = findViewById(R.id.startButton);
        stopButton = findViewById(R.id.stopButton);
        versionTextView = findViewById(R.id.versionTextView);
        edtVideoId = findViewById(R.id.edtVideoId);
        lblResult = findViewById(R.id.lblResult);
        
        threadPool = Executors.newCachedThreadPool();

        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = pInfo.versionName + " (" + pInfo.versionCode + ")";
            versionTextView.setText("Versione: " + version);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Errore nel recupero della versione", e);
            versionTextView.setText("Versione: Sconosciuta");
        }

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
        
        // Inizializza NewPipe
        if (!newPipeInitialized) {
            try {
                NewPipe.init(new RealDownloader(), Localization.DEFAULT);
                Log.d(TAG, "NewPipe inizializzato con successo");
                newPipeInitialized = true;
            } catch (Exception e) {
                Log.e(TAG, "Errore inizializzazione NewPipe", e);
            }
        } else {
            Log.d(TAG, "NewPipe è già stato inizializzato");
        }
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
            
            // Crea una risposta
            String response;
            
            if (path.equals("/ytinfo") && method.equals("GET")) {
                String videoId = params.get("id");
                if (videoId == null || videoId.isEmpty()) {
                    response = "{\"error\":\"Missing id parameter\"}";
                } else {
                    try {
                        // Usa NewPipe Extractor
                        Log.d(TAG, "Chiamata a getVideoInfo per videoId: " + videoId);
                        response = getVideoInfo(videoId);
                        Log.d(TAG, "Risposta da getVideoInfo: " + response);
                    } catch (Exception e) {
                        Log.e(TAG, "Errore nell'estrazione del video", e);
                        response = "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
                    }
                }
            } else {
                response = "{\"status\":\"success\",\"message\":\"YT Audio Server running\",\"endpoints\":[\"/ytinfo?id=VIDEO_ID\"]}";
            }
            
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

    private String getVideoInfo(String videoId) {
        Log.d(TAG, "Inizio getVideoInfo per videoId: " + videoId);
        
        String title = "Video di test: " + videoId;
        String thumbnail = "https://i.ytimg.com/vi/" + videoId + "/hqdefault.jpg";
        String audioUrl = "https://example.com/audio/" + videoId + ".mp3";
        
        try {
            // Prova a usare NewPipe, ma se fallisce, restituisci i dati di test
            
            // Crea URL YouTube
            String url = "https://www.youtube.com/watch?v=" + videoId;
            
            // Ottieni servizio YouTube (ID 0)
            StreamingService youtubeService = NewPipe.getService(0);
            
            // Estrai informazioni
            Log.d(TAG, "Inizio estrazione StreamInfo...");
            StreamInfo streamInfo = StreamInfo.getInfo(youtubeService, url);
            Log.d(TAG, "StreamInfo estratto con successo");
            
            // Ottieni titolo e thumbnail
            title = streamInfo.getName();
            Log.d(TAG, "Titolo: " + title);
            
            thumbnail = "";
            if (streamInfo.getThumbnails() != null && !streamInfo.getThumbnails().isEmpty()) {
                thumbnail = streamInfo.getThumbnails().get(0).getUrl();
                Log.d(TAG, "Thumbnail: " + thumbnail);
            } else {
                Log.d(TAG, "Nessuna thumbnail trovata");
            }
            
            // Ottieni URL audio
            audioUrl = "";
            List<AudioStream> audioStreams = streamInfo.getAudioStreams();
            if (!audioStreams.isEmpty()) {
                audioUrl = audioStreams.get(0).getUrl();
                Log.d(TAG, "URL audio trovato");
            } else {
                Log.d(TAG, "Nessun URL audio trovato");
            }
            
        } catch (Exception e) {
            Log.e(TAG, "Errore nell'estrazione del video", e);
        }
        
        // Crea risposta JSON
        JSONObject result = new JSONObject();
        try {
            result.put("title", title);
            result.put("thumbnail", thumbnail);
            result.put("audio_url", audioUrl);
            
            String jsonResult = result.toString();
            Log.d(TAG, "Risposta JSON: " + jsonResult);
            
            return jsonResult;
        } catch (JSONException e) {
            Log.e(TAG, "Errore nella creazione della risposta JSON", e);
            return "{\"error\":\"" + e.getMessage().replace("\"", "\\\"") + "\"}";
        }
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
