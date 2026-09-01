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

    /** No status at all: the request never reached a server. */
    public static final int NO_RESPONSE = 0;

    /**
     * What came back, including why nothing did.
     *
     * A bare body-or-null cannot tell a rejected API key from a typo in a
     * username from the wifi being off, and every one of those was reported to
     * the player as "api error". Since the only thing they can do about it is
     * whatever the cause suggests, the cause has to survive the trip.
     */
    public record Response(int status, String body) {
        public boolean ok() {
            return status == 200 && body != null;
        }

        /** The key was missing, rejected, or not allowed to ask this. */
        public boolean unauthorised() {
            return status == 401 || status == 403;
        }

        public boolean notFound() {
            return status == 404;
        }

        public boolean rateLimited() {
            return status == 429;
        }

        /** Nothing answered: no network, DNS failure, or a timeout. */
        public boolean unreachable() {
            return status == NO_RESPONSE;
        }
    }

    private HttpUtil() {
    }

    /**
     * GET a URL, retrying transient failures with exponential backoff.
     *
     * @return the response body, or {@code null} if every attempt failed
     */
    public static String get(String url) {
        return fetch(url, null, null).body();
    }

    /**
     * GET a URL, reporting what happened rather than only what came back.
     *
     * @param headerName  optional extra request header, e.g. an API key header
     * @param headerValue its value; both are ignored unless both are given
     */
    public static Response fetch(String url, String headerName, String headerValue) {
        int lastStatus = NO_RESPONSE;

        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                HttpRequest.Builder builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(TIMEOUT)
                        .header("User-Agent", "BirchOptimizer/1.0.0")
                        .GET();
                if (headerName != null && headerValue != null && !headerValue.isBlank()) {
                    builder.header(headerName, headerValue);
                }

                HttpResponse<String> response =
                        CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int code = response.statusCode();
                lastStatus = code;

                if (code == 200) {
                    return new Response(code, response.body());
                }
                // 4xx other than rate-limiting is a request problem: retrying
                // the same call will not fix it.
                if (code >= 400 && code < 500 && code != 429) {
                    return new Response(code, null);
                }
            } catch (InterruptedException e) {
                // Restore the flag so the scheduler can shut this thread down.
                Thread.currentThread().interrupt();
                return new Response(lastStatus, null);
            } catch (Exception ignored) {
                // Fall through to the backoff below.
            }

            if (attempt < MAX_ATTEMPTS && !sleep(BASE_BACKOFF_MS << (attempt - 1))) {
                return new Response(lastStatus, null);
            }
        }
        return new Response(lastStatus, null);
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
