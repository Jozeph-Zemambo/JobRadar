package io.github.jozephzemambo.jobradar.source;

import java.net.URI;

/** The ATS answered 200 but the body wasn't the JSON shape we expect. Retrying won't change that. */
public final class MalformedResponseException extends SourceException {

    public MalformedResponseException(URI uri, Throwable cause) {
        super("Unparseable response from " + uri + ": " + cause.getMessage(), cause);
    }

    @Override
    public boolean retryable() {
        return false;
    }
}
