package io.github.jozephzemambo.jobradar.source;

import java.net.URI;

/** The ATS returned 404: the company has no board there (often because it moved to another ATS). */
public final class BoardNotFoundException extends SourceException {

    public BoardNotFoundException(URI uri) {
        super("Board not found: " + uri, null);
    }

    @Override
    public boolean retryable() {
        return false;
    }
}
