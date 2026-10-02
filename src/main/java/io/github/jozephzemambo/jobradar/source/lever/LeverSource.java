package io.github.jozephzemambo.jobradar.source.lever;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.domain.WorkplaceType;
import io.github.jozephzemambo.jobradar.http.HttpFetcher;
import io.github.jozephzemambo.jobradar.normalize.HtmlText;
import io.github.jozephzemambo.jobradar.normalize.UrlCanonicalizer;
import io.github.jozephzemambo.jobradar.source.AbstractAtsSource;
import java.net.URI;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import tools.jackson.databind.ObjectMapper;

/** Reads Lever job boards. */
public class LeverSource extends AbstractAtsSource<LeverDtos.Posting[], LeverDtos.Posting> {

    public LeverSource(HttpFetcher http, ObjectMapper mapper, String baseUrl) {
        super(http, mapper, LeverDtos.Posting[].class, baseUrl);
    }

    @Override
    public Ats ats() {
        return Ats.LEVER;
    }

    @Override
    protected URI boardUrl(Company company) {
        return URI.create(baseUrl + "/v0/postings/" + company.boardToken() + "?mode=json");
    }

    @Override
    protected List<LeverDtos.Posting> items(LeverDtos.Posting[] response) {
        return Arrays.asList(response);
    }

    @Override
    protected Posting toPosting(LeverDtos.Posting item, Company company) {
        LeverDtos.Categories categories = item.categories();
        List<String> locations = List.of();
        String department = null;
        if (categories != null) {
            if (categories.allLocations() != null && !categories.allLocations().isEmpty()) {
                locations = categories.allLocations();
            } else if (categories.location() != null) {
                locations = List.of(categories.location());
            }
            department = categories.department() != null ? categories.department() : categories.team();
        }
        String url = item.hostedUrl();
        return new Posting(
                Ats.LEVER,
                item.id(),
                company.name(),
                item.text(),
                locations,
                WorkplaceType.fromRaw(item.workplaceType()),
                department,
                url,
                url == null ? null : UrlCanonicalizer.canonicalize(url),
                description(item),
                item.createdAt() == null ? null : Instant.ofEpochMilli(item.createdAt()),
                compensation(item.salaryRange()));
    }

    /** Lever splits the description across a lead paragraph, titled HTML lists, and a closing paragraph. */
    private static String description(LeverDtos.Posting item) {
        Stream<String> sections = item.lists() == null ? Stream.empty() : item.lists().stream()
                .map(section -> Objects.toString(section.text(), "") + " " + HtmlText.toPlainText(section.content()));
        return Stream.of(Stream.of(item.descriptionPlain()), sections, Stream.of(item.additionalPlain()))
                .flatMap(s -> s)
                .filter(s -> s != null && !s.isBlank())
                .map(String::strip)
                .collect(Collectors.joining("\n"));
    }

    static String compensation(LeverDtos.SalaryRange range) {
        if (range == null || range.min() == null || range.max() == null) {
            return null;
        }
        return String.format("%s %,.0f–%,.0f %s",
                Objects.toString(range.currency(), ""), range.min(), range.max(),
                Objects.toString(range.interval(), "")).strip();
    }
}
