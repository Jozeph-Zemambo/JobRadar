package io.github.jozephzemambo.jobradar.source.greenhouse;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.domain.WorkplaceType;
import io.github.jozephzemambo.jobradar.http.HttpFetcher;
import io.github.jozephzemambo.jobradar.normalize.HtmlText;
import io.github.jozephzemambo.jobradar.normalize.UrlCanonicalizer;
import io.github.jozephzemambo.jobradar.source.AbstractAtsSource;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import tools.jackson.databind.ObjectMapper;

/** Reads Greenhouse job boards. */
public class GreenhouseSource extends AbstractAtsSource<GreenhouseDtos.Board, GreenhouseDtos.Job> {

    public GreenhouseSource(HttpFetcher http, ObjectMapper mapper, String baseUrl) {
        super(http, mapper, GreenhouseDtos.Board.class, baseUrl);
    }

    @Override
    public Ats ats() {
        return Ats.GREENHOUSE;
    }

    @Override
    protected URI boardUrl(Company company) {
        return URI.create(baseUrl + "/v1/boards/" + company.boardToken() + "/jobs?content=true");
    }

    @Override
    protected List<GreenhouseDtos.Job> items(GreenhouseDtos.Board board) {
        return board.jobs();
    }

    @Override
    protected Posting toPosting(GreenhouseDtos.Job job, Company company) {
        String location = job.location() == null ? null : job.location().name();
        List<String> locations = location == null || location.isBlank() ? List.of() : List.of(location.strip());
        String department = orEmpty(job.departments()).stream()
                .map(GreenhouseDtos.Named::name)
                .filter(name -> name != null && !name.isBlank())
                .findFirst()
                .orElse(null);
        // Greenhouse has no workplace field; "Remote" in the location string is the only signal.
        WorkplaceType workplace = location != null && location.toLowerCase(Locale.ROOT).contains("remote")
                ? WorkplaceType.REMOTE
                : WorkplaceType.UNKNOWN;
        String url = job.absoluteUrl();
        return new Posting(
                Ats.GREENHOUSE,
                Long.toString(job.id()),
                company.name(),
                job.title(),
                locations,
                workplace,
                department,
                url,
                url == null ? null : UrlCanonicalizer.canonicalize(url),
                HtmlText.toPlainText(job.content()),
                parseInstant(job.firstPublished() != null ? job.firstPublished() : job.updatedAt()),
                null);
    }
}
