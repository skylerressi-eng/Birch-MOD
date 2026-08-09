package com.birchmod.util;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Minimal blocking HTTP GET helper with bounded retries. Always call from a
 * background thread, never from the Minecraft render thread.
 */
public final class HttpUtil {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final int MAX_ATTEMPTS = 3;
    private static final long BASE_BACKOFF_MS = 500L;

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private HttpUtil() {
    }

    /**
     * GET a URL, retrying transient failures with exponential backoff.
     *
     * @return the response body, or {@code null} if every attempt failed
     */
    public static String get(String url) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(TIMEOUT)
                        .header("User-Agent", "BirchOptimizer/1.0.0")
                        .GET()
                        .build();

                HttpResponse<String> response = CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();

                if (code == 200) {
                    return response.body();
                }
                // 4xx other than rate-limiting is a request problem: retrying
                // the same call will not fix it.
                if (code >= 400 && code < 500 && code != 429) {
                    return null;
                }
            } catch (InterruptedException e) {
                // Restore the flag so the scheduler can shut this thread down.
                Thread.currentThread().interrupt();
                return null;
            } catch (Exception ignored) {
                // Fall through to the backoff below.
            }

            if (attempt < MAX_ATTEMPTS && !sleep(BASE_BACKOFF_MS << (attempt - 1))) {
                return null;
            }
        }
        return null;
    }

    /** @return false if the wait was interrupted */
    private static boolean sleep(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
