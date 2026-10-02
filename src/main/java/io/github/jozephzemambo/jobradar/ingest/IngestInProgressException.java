package io.github.jozephzemambo.jobradar.ingest;

/** Thrown when an ingest is requested while another one is still running. Maps to HTTP 409. */
public class IngestInProgressException extends RuntimeException {

    public IngestInProgressException() {
        super("An ingest is already running");
    }
}
