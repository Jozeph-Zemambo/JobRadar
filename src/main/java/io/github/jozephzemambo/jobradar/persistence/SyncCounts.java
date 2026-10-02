package io.github.jozephzemambo.jobradar.persistence;

/** What syncing one or more boards did to the stored postings. */
public record SyncCounts(int created, int updated, int reopened, int closed) {

    public static final SyncCounts ZERO = new SyncCounts(0, 0, 0, 0);

    public SyncCounts plus(SyncCounts other) {
        return new SyncCounts(created + other.created, updated + other.updated, reopened + other.reopened,
                closed + other.closed);
    }
}
