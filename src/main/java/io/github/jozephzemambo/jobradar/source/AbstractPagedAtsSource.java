package io.github.jozephzemambo.jobradar.source;

import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.http.HttpFetcher;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Template for boards whose list endpoint is paginated and omits the description, so each posting needs a
 * second "detail" request (Workday, SmartRecruiters).
 *
 * <p>This is a sibling of {@link AbstractAtsSource}, not a subclass: the one-GET-per-board template doesn't fit,
 * which is the point of having {@link JobSource} as the interface and the templates as optional helpers.
 *
 * <ul>
 *   <li><b>Paging</b> always reads the <em>whole</em> list (stopping at a short page or at the total reported by
 *   the first page; Workday reports 0 on later pages). Truncating the list would make every posting past the cut
 *   look closed to {@code PostingStore}, corrupting time-to-close. {@link #MAX_LIST} is only a runaway guard.</li>
 *   <li><b>Details</b> are the expensive part (one request per posting), so only the first {@code maxDetails}
 *   items get one (SmartRecruiters lists newest first; Workday's default order is mostly, not strictly, by date).
 *   The rest keep list fields only, and {@code PostingEntity} never overwrites a stored description with an
 *   empty one, so a posting described on an earlier run stays described. Details
 *   are fetched concurrently on virtual threads; the shared per-host rate limiter still spaces them.</li>
 *   <li>A detail that 404s (posting closed between list and detail call) falls back to list-only fields. Any
 *   other detail failure fails the board, so a board is never stored half-described without anyone noticing.</li>
 * </ul>
 *
 * @param <P> page DTO
 * @param <I> list item DTO
 * @param <D> detail DTO
 */
public abstract class AbstractPagedAtsSource<P, I, D> implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(AbstractPagedAtsSource.class);

    /** Runaway guard on list size; far above any board seen (Workday itself caps search results at 2000). */
    static final int MAX_LIST = 10_000;

    protected final HttpFetcher http;
    private final ObjectMapper mapper;
    private final Class<P> pageType;
    private final Class<D> detailType;
    private final int pageSize;
    private final int maxDetails;

    /**
     * @param maxDetails how many postings per board get a detail request; 0 disables details entirely
     */
    protected AbstractPagedAtsSource(HttpFetcher http, ObjectMapper mapper, Class<P> pageType, Class<D> detailType,
            int pageSize, int maxDetails) {
        if (pageSize < 1 || maxDetails < 0) {
            throw new IllegalArgumentException("pageSize must be positive and maxDetails non-negative");
        }
        this.http = http;
        this.mapper = mapper;
        this.pageType = pageType;
        this.detailType = detailType;
        this.pageSize = pageSize;
        this.maxDetails = maxDetails;
    }

    @Override
    public final List<Posting> fetch(Company company) {
        List<I> items = listItems(company);
        List<D> details = details(company, items.subList(0, Math.min(maxDetails, items.size())));

        List<Posting> postings = new ArrayList<>(items.size());
        int skipped = 0;
        for (int i = 0; i < items.size(); i++) {
            try {
                Posting posting = toPosting(items.get(i), i < details.size() ? details.get(i) : null, company);
                if (posting != null) {
                    postings.add(posting);
                }
            } catch (IllegalArgumentException e) {
                skipped++;
            }
        }
        if (skipped > 0) {
            log.warn("Skipped {} malformed postings from {} ({})", skipped, company.boardToken(), ats());
        }
        return List.copyOf(postings);
    }

    private List<I> listItems(Company company) {
        List<I> items = new ArrayList<>();
        int total = Integer.MAX_VALUE;
        for (int offset = 0; items.size() < MAX_LIST; offset += pageSize) {
            PageRequest request = pageRequest(company, offset, pageSize);
            byte[] body = request.jsonBody() == null
                    ? http.get(request.uri())
                    : http.postJson(request.uri(), request.jsonBody());
            P page = read(body, pageType, request.uri());
            List<I> pageItems = page == null ? List.of() : items(page);
            if (offset == 0 && page != null) {
                total = total(page);
            }
            items.addAll(pageItems);
            if (pageItems.size() < pageSize || items.size() >= total) {
                break;
            }
        }
        if (items.size() >= MAX_LIST) {
            log.warn("{} ({}) listed {}+ postings; stopped paging at the guard", company.boardToken(), ats(), MAX_LIST);
        }
        return items;
    }

    private List<D> details(Company company, List<I> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<D>> futures = new ArrayList<>(items.size());
            for (I item : items) {
                futures.add(executor.submit(() -> detail(company, item)));
            }
            List<D> details = new ArrayList<>(items.size());
            for (Future<D> future : futures) {
                details.add(future.get());
            }
            return details;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted fetching details for " + company.boardToken(), e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }

    private D detail(Company company, I item) {
        URI uri = detailUrl(company, item);
        if (uri == null) {
            return null;
        }
        try {
            return read(http.get(uri), detailType, uri);
        } catch (BoardNotFoundException e) {
            return null; // closed between the list call and this one
        }
    }

    private <T> T read(byte[] body, Class<T> type, URI uri) {
        try {
            return mapper.readValue(body, type);
        } catch (JacksonException e) {
            throw new MalformedResponseException(uri, e);
        }
    }

    /** A page request: GET when {@code jsonBody} is null, POST otherwise. */
    public record PageRequest(URI uri, String jsonBody) {
    }

    protected abstract PageRequest pageRequest(Company company, int offset, int limit);

    protected abstract List<I> items(P page);

    /** Total postings as reported by the first page. */
    protected abstract int total(P page);

    /** Detail URL for one item, or null if the item can't have one. */
    protected abstract URI detailUrl(Company company, I item);

    /** Maps a list item and its detail ({@code null} if unavailable or disabled) to a posting, or null to drop. */
    protected abstract Posting toPosting(I item, D detail, Company company);
}
