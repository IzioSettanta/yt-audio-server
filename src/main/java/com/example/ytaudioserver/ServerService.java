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
import org.nanohttpd.protocols.http.IHTTPSession;
import org.nanohttpd.protocols.http.NanoHTTPD;
import org.nanohttpd.protocols.http.response.Response;
import org.nanohttpd.protocols.http.response.Status;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamInfo;

import java.io.IOException;
import java.util.List;
import java.util.Map;

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
            NewPipe.init();
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

    // Server HTTP interno
    private class Server extends NanoHTTPD {
        
        public Server(int port) {
            super(port);
        }

        @Override
        public Response serve(IHTTPSession session) {
            String uri = session.getUri();
            Map<String, String> params = session.getParms();
            
            // Gestione richiesta /ytinfo
            if (uri.equals("/ytinfo")) {
                String videoId = params.get("id");
                if (videoId == null || videoId.isEmpty()) {
                    return newJsonResponse("{\"error\":\"Missing id parameter\"}");
                }
                
                try {
                    // Crea URL YouTube
                    String url = "https://www.youtube.com/watch?v=" + videoId;
                    
                    // Ottieni servizio YouTube (ID 0)
                    StreamingService youtubeService = NewPipe.getService(0);
                    
                    // Estrai informazioni
                    StreamInfo streamInfo = StreamInfo.getInfo(youtubeService, url);
                    
                    // Ottieni titolo e thumbnail
                    String title = streamInfo.getName();
                    String thumbnail = streamInfo.getThumbnailUrl();
                    
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
                    
                    return newJsonResponse(result.toString());
                    
                } catch (IOException | ExtractionException | JSONException e) {
                    Log.e(TAG, "Errore estrazione video", e);
                    return newJsonResponse("{\"error\":\"" + e.getMessage() + "\"}");
                }
            }
            
            // Homepage del server
            return newJsonResponse("{\"status\":\"YT Audio Server running\",\"endpoints\":[\"/ytinfo?id=VIDEO_ID\"]}");
        }
        
        private Response newJsonResponse(String json) {
            return Response.newFixedLengthResponse(
                    Status.OK,
                    "application/json",
                    json
            );
        }
    }
}
