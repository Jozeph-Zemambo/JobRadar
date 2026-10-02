package io.github.jozephzemambo.jobradar.http;

import io.github.jozephzemambo.jobradar.source.BoardNotFoundException;
import io.github.jozephzemambo.jobradar.source.UpstreamException;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Blocking GET that turns HTTP outcomes into typed exceptions. Blocking is deliberate: callers run on virtual
 * threads, where a blocked {@code send} parks the virtual thread and frees the carrier, so plain sequential code
 * scales without callbacks.
 */
public class HttpFetcher {

    private final HttpClient client;
    private final String userAgent;
    private final Duration requestTimeout;

    public HttpFetcher(HttpClient client, String userAgent, Duration requestTimeout) {
        this.client = client;
        this.userAgent = userAgent;
        this.requestTimeout = requestTimeout;
    }

    public byte[] get(URI uri) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .GET()
                .timeout(requestTimeout)
                .header("User-Agent", userAgent)
                .header("Accept", "application/json")
                .build();
        HttpResponse<byte[]> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new UpstreamException(uri, e);
        } catch (InterruptedException e) {
            // Restore the flag so whoever owns this thread can see it was interrupted.
            Thread.currentThread().interrupt();
            throw new UpstreamException(uri, e);
        }

        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return response.body();
        }
        if (status == 404) {
            throw new BoardNotFoundException(uri);
        }
        Duration retryAfter = response.headers().firstValue("Retry-After")
                .map(HttpFetcher::parseRetryAfter)
                .orElse(null);
        throw new UpstreamException(uri, status, retryAfter);
    }

    /** Retry-After in delta-seconds form. The HTTP-date form is rare on APIs and is ignored. */
    static Duration parseRetryAfter(String value) {
        try {
            long seconds = Long.parseLong(value.strip());
            return seconds >= 0 ? Duration.ofSeconds(seconds) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
