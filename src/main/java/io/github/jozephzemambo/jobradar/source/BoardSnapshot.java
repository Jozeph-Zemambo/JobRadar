package io.github.jozephzemambo.jobradar.source;

import io.github.jozephzemambo.jobradar.domain.Posting;
import java.util.List;

/**
 * What one fetch of a board returned, and whether it is the <em>whole</em> listing.
 *
 * <p>Completeness matters because absence is evidence: a stored posting missing from a complete snapshot is
 * marked closed. A snapshot is incomplete when the source knows it may have missed postings (an item it couldn't
 * parse, a listing that shifted while paging, a page-count guard), and then nothing is closed for that board on
 * this run. New and still-listed postings are stored either way.
 *
 * @param postings         postings read
 * @param complete         true if every posting currently on the board is in {@code postings}
 * @param incompleteReason why not, for logs and the ingest report; null when complete
 */
public record BoardSnapshot(List<Posting> postings, boolean complete, String incompleteReason) {

    public BoardSnapshot {
        postings = List.copyOf(postings);
        if (complete && incompleteReason != null) {
            throw new IllegalArgumentException("a complete snapshot has no incomplete reason");
        }
        if (!complete && (incompleteReason == null || incompleteReason.isBlank())) {
            throw new IllegalArgumentException("an incomplete snapshot needs a reason");
        }
    }

    public static BoardSnapshot complete(List<Posting> postings) {
        return new BoardSnapshot(postings, true, null);
    }

    public static BoardSnapshot incomplete(List<Posting> postings, String reason) {
        return new BoardSnapshot(postings, false, reason);
    }
}
