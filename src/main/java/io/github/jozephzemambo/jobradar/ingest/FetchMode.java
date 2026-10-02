package io.github.jozephzemambo.jobradar.ingest;

/** How boards are fetched. All three exist so their wall times can be compared on the same input. */
public enum FetchMode {
    /** One board at a time on the calling thread. The baseline. */
    SEQUENTIAL,
    /** A fixed pool of platform (OS) threads: the pre-Java-21 way. */
    PLATFORM_POOL,
    /** One virtual thread per board. */
    VIRTUAL
}
