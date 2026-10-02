package io.github.jozephzemambo.jobradar.source;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * The ATS failed to answer usefully: a non-2xx status other than 404, or a network error.
 * 429 and 5xx are retryable; other 4xx are not, because repeating a bad request won't fix it.
 */
public final class UpstreamException extends SourceException {

    /** Status code used when no HTTP response was received at all (timeout, connection reset). */
    public static final int NO_RESPONSE = -1;

    private final int statusCode;
    private final Duration retryAfter;

    public UpstreamException(URI uri, int statusCode, Duration retryAfter) {
        super("HTTP " + statusCode + " from " + uri, null);
        this.statusCode = statusCode;
        this.retryAfter = retryAfter;
    }

    public UpstreamException(URI uri, Throwable cause) {
        super("No response from " + uri + ": " + cause.getMessage(), cause);
        this.statusCode = NO_RESPONSE;
        this.retryAfter = null;
    }

    public int statusCode() {
        return statusCode;
    }

    /** Server-requested wait from a {@code Retry-After} header, if one was sent. */
    public Optional<Duration> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }

    @Override
    public boolean retryable() {
        return statusCode == NO_RESPONSE || statusCode == 429 || statusCode >= 500;
    }
}
