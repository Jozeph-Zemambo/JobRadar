package io.github.jozephzemambo.jobradar.source.ashby;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.domain.Posting;
import io.github.jozephzemambo.jobradar.domain.WorkplaceType;
import io.github.jozephzemambo.jobradar.http.HttpFetcher;
import io.github.jozephzemambo.jobradar.normalize.HtmlText;
import io.github.jozephzemambo.jobradar.normalize.UrlCanonicalizer;
import io.github.jozephzemambo.jobradar.source.AbstractAtsSource;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import tools.jackson.databind.ObjectMapper;

/** Reads Ashby job boards. */
public class AshbySource extends AbstractAtsSource<AshbyDtos.Board, AshbyDtos.Job> {

    public AshbySource(HttpFetcher http, ObjectMapper mapper, String baseUrl) {
        super(http, mapper, AshbyDtos.Board.class, baseUrl);
    }

    @Override
    public Ats ats() {
        return Ats.ASHBY;
    }

    @Override
    protected URI boardUrl(Company company) {
        return URI.create(baseUrl + "/posting-api/job-board/" + company.boardToken() + "?includeCompensation=true");
    }

    @Override
    protected List<AshbyDtos.Job> items(AshbyDtos.Board board) {
        return board.jobs();
    }

    @Override
    protected Posting toPosting(AshbyDtos.Job job, Company company) {
        if (Boolean.FALSE.equals(job.isListed())) {
            return null; // unlisted postings are reachable by link only; they aren't part of the public market
        }
        List<String> locations = new ArrayList<>();
        if (job.location() != null && !job.location().isBlank()) {
            locations.add(job.location());
        }
        for (AshbyDtos.SecondaryLocation secondary : orEmpty(job.secondaryLocations())) {
            if (secondary.location() != null && !secondary.location().isBlank()) {
                locations.add(secondary.location());
            }
        }
        WorkplaceType workplace = WorkplaceType.fromRaw(job.workplaceType());
        if (workplace == WorkplaceType.UNKNOWN && Boolean.TRUE.equals(job.isRemote())) {
            workplace = WorkplaceType.REMOTE;
        }
        String description = job.descriptionPlain() != null && !job.descriptionPlain().isBlank()
                ? job.descriptionPlain().strip()
                : HtmlText.toPlainText(job.descriptionHtml());
        String url = job.jobUrl();
        return new Posting(
                Ats.ASHBY,
                job.id(),
                company.name(),
                job.title(),
                locations,
                workplace,
                job.department() != null ? job.department() : job.team(),
                url,
                url == null ? null : UrlCanonicalizer.canonicalize(url),
                description,
                parseInstant(job.publishedAt()),
                job.compensation() == null ? null : job.compensation().compensationTierSummary());
    }
}
