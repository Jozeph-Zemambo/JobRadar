package io.github.jozephzemambo.jobradar.source.workday;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.domain.WorkplaceType;
import io.github.jozephzemambo.jobradar.http.HttpFetcher;
import io.github.jozephzemambo.jobradar.normalize.HtmlText;
import io.github.jozephzemambo.jobradar.normalize.UrlCanonicalizer;
import io.github.jozephzemambo.jobradar.source.AbstractPagedAtsSource;
import java.net.URI;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import tools.jackson.databind.ObjectMapper;

/**
 * Reads Workday career sites through the public CXS JSON API.
 *
 * <p>Board token format: {@code tenant/wdN/site}, e.g. {@code workday/wd5/Workday} for
 * {@code https://workday.wd5.myworkdayjobs.com/Workday}. Each tenant is its own host, so each gets its own rate
 * limit bucket.
 */
public class WorkdaySource extends AbstractPagedAtsSource<WorkdayDtos.Page, WorkdayDtos.ListItem, WorkdayDtos.Detail> {

    /** Workday rejects page sizes above 20 with HTTP 400. */
    static final int PAGE_SIZE = 20;

    private static final Pattern LOCATION_COUNT = Pattern.compile("\\d+ Locations?");

    private final String baseUrlTemplate;

    /**
     * @param baseUrlTemplate origin with {@code {tenant}} and {@code {wd}} placeholders, e.g.
     *                        {@code https://{tenant}.{wd}.myworkdayjobs.com}; tests pass a plain local URL
     */
    public WorkdaySource(HttpFetcher http, ObjectMapper mapper, String baseUrlTemplate, int maxDetails) {
        super(http, mapper, WorkdayDtos.Page.class, WorkdayDtos.Detail.class, PAGE_SIZE, maxDetails);
        this.baseUrlTemplate = baseUrlTemplate.endsWith("/")
                ? baseUrlTemplate.substring(0, baseUrlTemplate.length() - 1)
                : baseUrlTemplate;
    }

    @Override
    public Ats ats() {
        return Ats.WORKDAY;
    }

    /** Parsed board token. */
    record Board(String tenant, String wd, String site) {
        static Board parse(String token) {
            String[] parts = token.split("/");
            if (parts.length != 3 || !parts[1].matches("wd\\d+")) {
                throw new IllegalArgumentException("Workday board token must be tenant/wdN/site, got: " + token);
            }
            return new Board(parts[0], parts[1], parts[2]);
        }
    }

    private String origin(Board board) {
        return baseUrlTemplate.replace("{tenant}", board.tenant()).replace("{wd}", board.wd());
    }

    private String cxsBase(Company company) {
        Board board = Board.parse(company.boardToken());
        return origin(board) + "/wday/cxs/" + board.tenant() + "/" + board.site();
    }

    @Override
    protected PageRequest pageRequest(Company company, int offset, int limit) {
        String body = "{\"appliedFacets\":{},\"limit\":" + limit + ",\"offset\":" + offset + ",\"searchText\":\"\"}";
        return new PageRequest(URI.create(cxsBase(company) + "/jobs"), body);
    }

    @Override
    protected List<WorkdayDtos.ListItem> items(WorkdayDtos.Page page) {
        return page.jobPostings();
    }

    @Override
    protected String itemId(WorkdayDtos.ListItem item) {
        return String.valueOf(item.externalPath());
    }

    @Override
    protected int total(WorkdayDtos.Page page) {
        return page.total() == null ? Integer.MAX_VALUE : page.total();
    }

    @Override
    protected URI detailUrl(Company company, WorkdayDtos.ListItem item) {
        return item.externalPath() == null ? null : URI.create(cxsBase(company) + item.externalPath());
    }

    @Override
    protected Posting toPosting(WorkdayDtos.ListItem item, WorkdayDtos.Detail detail, Company company) {
        Board board = Board.parse(company.boardToken());
        WorkdayDtos.Info info = detail == null ? null : detail.jobPostingInfo();

        List<String> locations = new ArrayList<>();
        if (info != null && info.location() != null) {
            locations.add(info.location());
            if (info.additionalLocations() != null) {
                locations.addAll(info.additionalLocations());
            }
        } else if (item.locationsText() != null && !LOCATION_COUNT.matcher(item.locationsText()).matches()) {
            locations.add(item.locationsText()); // "3 Locations" says nothing, so it's dropped
        }

        String url = info != null && info.externalUrl() != null
                ? info.externalUrl()
                : item.externalPath() == null ? null : origin(board) + "/" + board.site() + item.externalPath();
        return new Posting(
                Ats.WORKDAY,
                // Paths are unique per career site only, so the board token makes the id unique across tenants.
                item.externalPath() == null ? null : company.boardToken() + item.externalPath(),
                company.name(),
                info != null && info.title() != null ? info.title() : item.title(),
                locations,
                info == null ? WorkplaceType.UNKNOWN : workplace(info.remoteType()),
                null,
                url,
                url == null ? null : UrlCanonicalizer.canonicalize(url),
                info == null ? "" : HtmlText.toPlainText(info.jobDescription()),
                info == null ? null : startOfDay(info.startDate()),
                null);
    }

    /** Workday tenants label remote work in their own words: "Flex", "Fully Remote", "On-Site", ... */
    static WorkplaceType workplace(String remoteType) {
        if (remoteType == null) {
            return WorkplaceType.UNKNOWN;
        }
        String value = remoteType.toLowerCase(Locale.ROOT);
        if (value.contains("remote")) {
            return WorkplaceType.REMOTE;
        }
        if (value.contains("flex") || value.contains("hybrid")) {
            return WorkplaceType.HYBRID;
        }
        if (value.contains("site") || value.contains("office")) {
            return WorkplaceType.ONSITE;
        }
        return WorkplaceType.UNKNOWN;
    }

    private static java.time.Instant startOfDay(String isoDate) {
        if (isoDate == null) {
            return null;
        }
        try {
            return LocalDate.parse(isoDate).atStartOfDay(ZoneOffset.UTC).toInstant();
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
