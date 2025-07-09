package com.example.ytaudioserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

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

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ServerService extends Service {

    private static final String TAG = "ServerService";
    private static final int PORT = 8080;
    private static final String CHANNEL_ID = "YTAudioServerChannel";
    private static final int NOTIFICATION_ID = 1;

    private Server server;

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "Servizio creato");
        
        // Inizializza NewPipe
        try {
            NewPipe.init(new Downloader() {
                @Override
                public Response execute(Request request) throws IOException {
                    return null;
                }
            }, Localization.DEFAULT);
            
            Log.d(TAG, "NewPipe inizializzato");
        } catch (Exception e) {
            Log.e(TAG, "Errore inizializzazione NewPipe", e);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        createNotificationChannel();
        
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("YT Audio Server")
                .setContentText("Server in esecuzione su porta " + PORT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentIntent(pendingIntent)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        // Avvia il server
        try {
            server = new Server(PORT);
            server.start();
            Log.d(TAG, "Server avviato sulla porta " + PORT);
        } catch (IOException e) {
            Log.e(TAG, "Errore avvio server", e);
        }

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (server != null) {
            server.stop();
            Log.d(TAG, "Server fermato");
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    "YT Audio Server Channel",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    // Server HTTP semplificato
    private class Server {
        private final int port;
        private ServerSocket serverSocket;
        private boolean running = false;
        private ExecutorService threadPool = Executors.newCachedThreadPool();
        
        public Server(int port) {
            this.port = port;
        }
        
        public void start() throws IOException {
            serverSocket = new ServerSocket(port);
            running = true;
            
            new Thread(() -> {
                while (running) {
                    try {
                        final Socket socket = serverSocket.accept();
                        threadPool.execute(() -> handleRequest(socket));
                    } catch (IOException e) {
                        if (running) {
                            Log.e(TAG, "Errore accettazione connessione", e);
                        }
                    }
                }
            }).start();
        }
        
        public void stop() {
            running = false;
            try {
                if (serverSocket != null) {
                    serverSocket.close();
                }
            } catch (IOException e) {
                Log.e(TAG, "Errore chiusura server", e);
            }
            threadPool.shutdown();
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
                
                // Gestisci la richiesta
                String response;
                if (path.equals("/ytinfo") && method.equals("GET")) {
                    String videoId = params.get("id");
                    if (videoId == null || videoId.isEmpty()) {
                        response = "{\"error\":\"Missing id parameter\"}";
                    } else {
                        response = getVideoInfo(videoId);
                    }
                } else {
                    response = "{\"status\":\"YT Audio Server running\",\"endpoints\":[\"/ytinfo?id=VIDEO_ID\"]}";
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
                
            } catch (IOException e) {
                Log.e(TAG, "Errore gestione richiesta", e);
                try {
                    socket.close();
                } catch (IOException ignored) {}
            }
        }
        
        private String getVideoInfo(String videoId) {
            try {
                // Crea URL YouTube
                String url = "https://www.youtube.com/watch?v=" + videoId;
                
                // Ottieni servizio YouTube (ID 0)
                StreamingService youtubeService = NewPipe.getService(0);
                
                // Estrai informazioni
                StreamInfo streamInfo = StreamInfo.getInfo(youtubeService, url);
                
                // Ottieni titolo e thumbnail
                String title = streamInfo.getName();
                String thumbnail = "";
                if (streamInfo.getThumbnails() != null && !streamInfo.getThumbnails().isEmpty()) {
                    thumbnail = streamInfo.getThumbnails().get(0).getUrl();
                }
                
                // Ottieni URL audio
                String audioUrl = "";
                List<AudioStream> audioStreams = streamInfo.getAudioStreams();
                if (!audioStreams.isEmpty()) {
                    audioUrl = audioStreams.get(0).getUrl();
                }
                
                // Crea risposta JSON
                JSONObject result = new JSONObject();
                result.put("title", title);
                result.put("thumbnail", thumbnail);
                result.put("audio_url", audioUrl);
                
                return result.toString();
                
            } catch (IOException | ExtractionException | JSONException e) {
                Log.e(TAG, "Errore estrazione video", e);
                return "{\"error\":\"" + e.getMessage() + "\"}";
            }
        }
    }
}
