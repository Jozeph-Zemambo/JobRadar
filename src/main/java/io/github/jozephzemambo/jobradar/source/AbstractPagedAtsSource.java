package io.github.jozephzemambo.jobradar.source;

import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.http.HttpFetcher;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 *   look closed to {@code PostingStore}, corrupting time-to-close. If paging may have missed postings (the guard
 *   at {@link #MAX_LIST}, or the listing shifting between pages) the snapshot is flagged incomplete.</li>
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
    public final BoardSnapshot fetch(Company company) {
        Listing<I> listing = listItems(company);
        List<I> items = listing.items();
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
        String incomplete = listing.incompleteReason();
        if (skipped > 0) {
            log.warn("Skipped {} malformed postings from {} ({})", skipped, company.boardToken(), ats());
            incomplete = skipped + " postings could not be parsed";
        }
        return incomplete == null ? BoardSnapshot.complete(postings) : BoardSnapshot.incomplete(postings, incomplete);
    }

    /** The paged listing, deduplicated by item id, and why it may be incomplete (null if it isn't). */
    record Listing<I>(List<I> items, String incompleteReason) {
    }

    /**
     * Pages through the whole listing. Postings can be added or removed while we page, which shifts later pages:
     * an item shows up twice, or one slides past us unseen. A repeated id or a total that changes between pages
     * is the visible symptom, so either marks the listing incomplete rather than letting a missed posting look
     * closed.
     */
    private Listing<I> listItems(Company company) {
        Map<String, I> byId = new LinkedHashMap<>();
        String incomplete = null;
        int firstTotal = Integer.MAX_VALUE;
        int fetched = 0;
        for (int offset = 0; ; offset += pageSize) {
            if (fetched >= MAX_LIST) {
                log.warn("{} ({}) listed {}+ postings; stopped at the guard", company.boardToken(), ats(), MAX_LIST);
                incomplete = "stopped paging at " + MAX_LIST + " postings";
                break;
            }
            PageRequest request = pageRequest(company, offset, pageSize);
            byte[] body = request.jsonBody() == null
                    ? http.get(request.uri())
                    : http.postJson(request.uri(), request.jsonBody());
            P page = read(body, pageType, request.uri());
            List<I> pageItems = page == null ? null : items(page);
            if (pageItems == null) {
                throw new MalformedResponseException(request.uri(),
                        new IllegalStateException("no postings collection in page"));
            }
            int total = total(page);
            if (offset == 0) {
                firstTotal = total;
            } else if (total > 0 && total != firstTotal && incomplete == null) {
                // Workday reports 0 on later pages; any other change means the listing moved under us.
                incomplete = "board total changed while paging (" + firstTotal + " -> " + total + ")";
            }
            for (I item : pageItems) {
                if (byId.putIfAbsent(itemId(item), item) != null && incomplete == null) {
                    incomplete = "listing shifted while paging (a posting appeared on two pages)";
                }
            }
            fetched += pageItems.size();
            if (pageItems.size() < pageSize || fetched >= firstTotal) {
                break;
            }
        }
        return new Listing<>(new ArrayList<>(byId.values()), incomplete);
    }

    /**
     * Detail requests run concurrently on virtual threads. If the board's thread is interrupted, the pending
     * detail tasks are cancelled (interrupting their HTTP calls and rate-limit waits) instead of being waited for.
     */
    private List<D> details(Company company, List<I> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        List<Future<D>> futures = new ArrayList<>(items.size());
        try {
            for (I item : items) {
                futures.add(executor.submit(() -> detail(company, item)));
            }
            List<D> details = new ArrayList<>(items.size());
            for (Future<D> future : futures) {
                details.add(future.get());
            }
            return details;
        } catch (InterruptedException e) {
            futures.forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            throw new UpstreamException(URI.create("detail://" + company.boardToken()), e);
        } catch (ExecutionException e) {
            futures.forEach(f -> f.cancel(true));
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        } finally {
            executor.shutdownNow();
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

    /** The items on a page, or null if the page has no postings collection. */
    protected abstract List<I> items(P page);

    /** Total postings the page reports; Workday only fills this in on the first page. */
    protected abstract int total(P page);

    /** Stable id of a list item, used to spot a posting that appears on two pages. */
    protected abstract String itemId(I item);

    /** Detail URL for one item, or null if the item can't have one. */
    protected abstract URI detailUrl(Company company, I item);

    /** Maps a list item and its detail ({@code null} if unavailable or disabled) to a posting, or null to drop. */
    protected abstract Posting toPosting(I item, D detail, Company company);
}
