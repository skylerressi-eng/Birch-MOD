package com.birchmod.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Minimal blocking HTTP GET helper. Always call from a background thread,
 * never from the Minecraft main/render thread.
 */
public final class HttpUtil {

    private static final int TIMEOUT_MS = 10_000;

    private HttpUtil() {
    }

    /**
     * Performs a GET request and returns the response body, or {@code null} on failure.
     */
    public static String get(String urlString) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlString);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestProperty("User-Agent", "BirchOptimizer/0.1.0");

            int code = conn.getResponseCode();
            if (code != 200) {
                return null;
            }

            StringBuilder sb = new StringBuilder();
            try (BufferedReader in = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    sb.append(line);
                }
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }
}
