package io.github.jozephzemambo.jobradar.dedup;

import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.domain.PostingKey;
import java.util.List;

/**
 * The few fields deduplication looks at. Kept separate from {@link Posting} so dedup over thousands of stored
 * postings doesn't have to load their (large) descriptions.
 *
 * @param id           database id if stored, else null; lower ids were seen earlier and win as canonical
 * @param key          ATS + external id
 * @param company      company display name, used for blocking
 * @param title        title as published
 * @param locations    published locations
 * @param department   department or team, may be null
 * @param canonicalUrl canonicalized posting URL
 */
public record DedupCandidate(Long id, PostingKey key, String company, String title, List<String> locations,
        String department, String canonicalUrl) {

    public DedupCandidate {
        locations = locations == null ? List.of() : List.copyOf(locations);
    }

    public static DedupCandidate of(Posting posting) {
        return new DedupCandidate(null, posting.key(), posting.company(), posting.title(), posting.locations(),
                posting.department(), posting.canonicalUrl());
    }
}
