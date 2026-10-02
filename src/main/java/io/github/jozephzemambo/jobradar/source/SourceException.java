package io.github.jozephzemambo.jobradar.source;

/**
 * Anything that can go wrong reading one board. Sealed so callers can switch over every case and the compiler
 * tells us if a new failure kind is added without being handled.
 */
public abstract sealed class SourceException extends RuntimeException
        permits BoardNotFoundException, UpstreamException, MalformedResponseException {

    protected SourceException(String message, Throwable cause) {
        super(message, cause);
    }

    /** Whether trying the same request again could plausibly succeed. */
    public abstract boolean retryable();
}
