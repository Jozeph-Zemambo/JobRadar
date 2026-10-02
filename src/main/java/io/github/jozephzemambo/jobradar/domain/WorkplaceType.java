package io.github.jozephzemambo.jobradar.domain;

import java.util.Locale;

/** Where the work happens, normalized across the three ATS vocabularies. */
public enum WorkplaceType {
    ONSITE,
    HYBRID,
    REMOTE,
    UNKNOWN;

    /**
     * Maps raw values such as {@code "onsite"}, {@code "On-site"}, {@code "Hybrid"} or {@code "remote"}.
     * Anything unrecognized becomes {@link #UNKNOWN} rather than failing the whole board.
     */
    public static WorkplaceType fromRaw(String raw) {
        if (raw == null) {
            return UNKNOWN;
        }
        String key = raw.trim().toLowerCase(Locale.ROOT).replace("-", "").replace("_", "").replace(" ", "");
        return switch (key) {
            case "onsite", "inoffice", "office" -> ONSITE;
            case "hybrid" -> HYBRID;
            case "remote" -> REMOTE;
            default -> UNKNOWN;
        };
    }
}
