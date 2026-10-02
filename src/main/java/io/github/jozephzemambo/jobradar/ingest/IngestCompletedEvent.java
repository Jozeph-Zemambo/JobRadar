package io.github.jozephzemambo.jobradar.ingest;

/**
 * Published after every ingest that wrote to the database. Listeners (the stats cache) react to new data without
 * {@link IngestService} knowing they exist.
 *
 * @param runId id of the ingest_run row, or null if the ingest failed after writing some boards
 */
public record IngestCompletedEvent(Long runId) {
}
