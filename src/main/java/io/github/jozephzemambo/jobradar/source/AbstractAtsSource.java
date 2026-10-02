package io.github.jozephzemambo.jobradar.source;

import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.http.HttpFetcher;
import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Template method shared by every JSON-over-HTTP ATS: build URL, GET, parse, map each item.
 * Subclasses supply only the provider-specific parts ({@link #boardUrl}, the response type, {@link #items},
 * {@link #toPosting}); HTTP, parsing errors and per-item fault isolation live here once.
 *
 * <p>This is why {@link JobSource} is an interface <em>and</em> this is an abstract class: the interface is the
 * contract callers depend on, the abstract class is shared implementation that a non-HTTP source could skip.
 *
 * @param <R> the provider's top-level response DTO
 * @param <I> the provider's per-posting DTO
 */
public abstract class AbstractAtsSource<R, I> implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(AbstractAtsSource.class);

    private final HttpFetcher http;
    private final ObjectMapper mapper;
    private final Class<R> responseType;
    protected final String baseUrl;

    protected AbstractAtsSource(HttpFetcher http, ObjectMapper mapper, Class<R> responseType, String baseUrl) {
        this.http = http;
        this.mapper = mapper;
        this.responseType = responseType;
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public final List<Posting> fetch(Company company) {
        URI uri = boardUrl(company);
        byte[] body = http.get(uri);
        R response;
        try {
            response = mapper.readValue(body, responseType);
        } catch (JacksonException e) {
            throw new MalformedResponseException(uri, e);
        }

        List<I> items = response == null ? List.of() : items(response);
        List<Posting> postings = new ArrayList<>(items.size());
        int skipped = 0;
        for (I item : items) {
            try {
                Posting posting = toPosting(item, company);
                if (posting != null) {
                    postings.add(posting);
                }
            } catch (IllegalArgumentException e) {
                // One malformed posting shouldn't cost us the other 700 on the board.
                skipped++;
            }
        }
        if (skipped > 0) {
            log.warn("Skipped {} malformed postings from {} ({})", skipped, company.boardToken(), ats());
        }
        return List.copyOf(postings);
    }

    /** Public board URL for the company. */
    protected abstract URI boardUrl(Company company);

    /** The posting items inside the top-level response. */
    protected abstract List<I> items(R response);

    /** Maps one provider item to a {@link Posting}, or returns null to deliberately drop it (e.g. unlisted). */
    protected abstract Posting toPosting(I item, Company company);

    /** Lenient ISO-8601 parse: a bad timestamp shouldn't drop the posting, the date just becomes unknown. */
    protected static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    protected static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }
}
