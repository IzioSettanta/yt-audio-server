package com.example.ytaudioserver;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.Map;

public class RealDownloader implements Downloader {
    @Override
    public Response execute(Request request) throws IOException {
        URL url = new URL(request.url());
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod(request.httpMethod());

        // Imposta gli headers
        for (Map.Entry<String, List<String>> entry : request.headers().entrySet()) {
            for (String value : entry.getValue()) {
                connection.addRequestProperty(entry.getKey(), value);
            }
        }

        // Leggi la risposta
        int responseCode = connection.getResponseCode();
        InputStream in = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();

        // Converti InputStream in String
        BufferedReader reader = new BufferedReader(new InputStreamReader(in));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();

        // Crea Response object
        return new Response(
                responseCode,
                connection.getResponseMessage(),
                connection.getHeaderFields(),
                response.toString(),
                url.toString()
        );
    }
}
