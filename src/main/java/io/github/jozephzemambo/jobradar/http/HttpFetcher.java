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
 * Rate-limited, retried, blocking GET that turns HTTP outcomes into typed exceptions.
 *
 * <p>Blocking is deliberate: callers run on virtual threads, where a blocked {@code send} parks the virtual thread
 * and frees the carrier, so plain sequential code scales without callbacks. Every attempt, including retries,
 * goes through the rate limiter, so retries can't exceed the per-host budget.
 */
public class HttpFetcher {

    private final HttpClient client;
    private final String userAgent;
    private final Duration requestTimeout;
    private final RateLimiter rateLimiter;
    private final RetryPolicy retryPolicy;

    public HttpFetcher(HttpClient client, String userAgent, Duration requestTimeout, RateLimiter rateLimiter,
            RetryPolicy retryPolicy) {
        this.client = client;
        this.userAgent = userAgent;
        this.requestTimeout = requestTimeout;
        this.rateLimiter = rateLimiter;
        this.retryPolicy = retryPolicy;
    }

    public byte[] get(URI uri) {
        return retryPolicy.execute(() -> attempt(uri));
    }

    private byte[] attempt(URI uri) {
        try {
            rateLimiter.acquire(limiterKey(uri));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamException(uri, e);
        }
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

    /** Host plus port: three WireMock servers on localhost must count as three hosts, like the real ATSes. */
    static String limiterKey(URI uri) {
        return uri.getHost() + ":" + uri.getPort();
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
