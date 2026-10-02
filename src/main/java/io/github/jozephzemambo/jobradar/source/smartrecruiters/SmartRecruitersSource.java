package io.github.jozephzemambo.jobradar.source.smartrecruiters;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.domain.WorkplaceType;
import io.github.jozephzemambo.jobradar.http.HttpFetcher;
import io.github.jozephzemambo.jobradar.normalize.HtmlText;
import io.github.jozephzemambo.jobradar.normalize.UrlCanonicalizer;
import io.github.jozephzemambo.jobradar.source.AbstractPagedAtsSource;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.ObjectMapper;

/** Reads SmartRecruiters company boards. Board token is the company identifier, e.g. {@code Canva}. */
public class SmartRecruitersSource extends AbstractPagedAtsSource<SmartRecruitersDtos.Page,
        SmartRecruitersDtos.ListItem, SmartRecruitersDtos.Detail> {

    static final int PAGE_SIZE = 100;
    static final String PUBLIC_BASE = "https://jobs.smartrecruiters.com/";

    private final String baseUrl;

    public SmartRecruitersSource(HttpFetcher http, ObjectMapper mapper, String baseUrl, int maxDetails) {
        super(http, mapper, SmartRecruitersDtos.Page.class, SmartRecruitersDtos.Detail.class, PAGE_SIZE, maxDetails);
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @Override
    public Ats ats() {
        return Ats.SMARTRECRUITERS;
    }

    @Override
    protected PageRequest pageRequest(Company company, int offset, int limit) {
        return new PageRequest(URI.create(baseUrl + "/v1/companies/" + company.boardToken()
                + "/postings?limit=" + limit + "&offset=" + offset), null);
    }

    @Override
    protected List<SmartRecruitersDtos.ListItem> items(SmartRecruitersDtos.Page page) {
        return page.content() == null ? List.of() : page.content();
    }

    @Override
    protected int total(SmartRecruitersDtos.Page page) {
        return page.totalFound() == null ? Integer.MAX_VALUE : page.totalFound();
    }

    @Override
    protected URI detailUrl(Company company, SmartRecruitersDtos.ListItem item) {
        return item.id() == null ? null
                : URI.create(baseUrl + "/v1/companies/" + company.boardToken() + "/postings/" + item.id());
    }

    @Override
    protected Posting toPosting(SmartRecruitersDtos.ListItem item, SmartRecruitersDtos.Detail detail,
            Company company) {
        String url = detail != null && detail.postingUrl() != null
                ? detail.postingUrl()
                : PUBLIC_BASE + company.boardToken() + "/" + item.id();
        String department = item.department() != null && item.department().label() != null
                ? item.department().label()
                : item.function() == null ? null : item.function().label();
        return new Posting(
                Ats.SMARTRECRUITERS,
                item.id(),
                company.name(),
                item.name(),
                location(item.location()),
                workplace(item.location()),
                department,
                url,
                UrlCanonicalizer.canonicalize(url),
                description(detail),
                parseInstant(item.releasedDate()),
                null);
    }

    /** "Sydney, , Australia" has an empty region; drop empty parts rather than store stray commas. */
    static List<String> location(SmartRecruitersDtos.Location location) {
        if (location == null) {
            return List.of();
        }
        String text = location.fullLocation() != null
                ? location.fullLocation()
                : String.join(",", Stream.of(location.city(), location.region(), location.country())
                        .map(s -> Objects.toString(s, "")).toList());
        String cleaned = Arrays.stream(text.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining(", "));
        return cleaned.isEmpty() ? List.of() : List.of(cleaned);
    }

    static WorkplaceType workplace(SmartRecruitersDtos.Location location) {
        if (location == null) {
            return WorkplaceType.UNKNOWN;
        }
        if (Boolean.TRUE.equals(location.remote())) {
            return WorkplaceType.REMOTE;
        }
        if (Boolean.TRUE.equals(location.hybrid())) {
            return WorkplaceType.HYBRID;
        }
        return location.remote() != null ? WorkplaceType.ONSITE : WorkplaceType.UNKNOWN;
    }

    private static String description(SmartRecruitersDtos.Detail detail) {
        if (detail == null || detail.jobAd() == null || detail.jobAd().sections() == null) {
            return "";
        }
        SmartRecruitersDtos.Sections s = detail.jobAd().sections();
        return Stream.of(s.jobDescription(), s.qualifications(), s.additionalInformation(), s.companyDescription())
                .filter(Objects::nonNull)
                .map(section -> HtmlText.toPlainText(section.text()))
                .filter(text -> !text.isBlank())
                .collect(Collectors.joining("\n"));
    }

    private static Instant parseInstant(String iso) {
        if (iso == null) {
            return null;
        }
        try {
            return Instant.parse(iso);
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
