package com.birchmod.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal blocking HTTP GET helper. Always call from a background thread,
 * never from the Minecraft render thread.
 */
public final class HttpUtil {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpUtil() {
    }

    /**
     * Performs a GET request and returns the response body, or {@code null} on
     * any failure or non-200 status.
     */
    public static String get(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("User-Agent", "BirchOptimizer/0.2.0")
                    .GET()
                    .build();

            HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 ? response.body() : null;
        } catch (InterruptedException e) {
            // Restore the flag so the scheduler can shut the thread down.
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            return null;
        }
    }
}
