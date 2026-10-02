package io.github.jozephzemambo.jobradar.ingest;

import java.util.List;

/**
 * What to ingest and how.
 *
 * @param companies board tokens to fetch; null or empty means every configured company
 * @param mode      fetch strategy; null means {@link FetchMode#VIRTUAL}
 */
public record IngestRequest(List<String> companies, FetchMode mode) {

    public IngestRequest {
        companies = companies == null ? List.of() : List.copyOf(companies);
        mode = mode == null ? FetchMode.VIRTUAL : mode;
    }

    public static IngestRequest all() {
        return new IngestRequest(null, null);
    }
}
