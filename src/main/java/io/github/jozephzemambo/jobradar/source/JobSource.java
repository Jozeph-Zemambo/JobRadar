package io.github.jozephzemambo.jobradar.source;

import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.Company;

/**
 * Strategy for reading one ATS. Each implementation knows a single provider's URL scheme and JSON shape; the
 * rest of the system only ever sees normalized postings.
 *
 * <p>Adding a provider (Workday, SmartRecruiters, ...) is one new implementation plus one {@link Ats} constant.
 * {@link SourceRegistry} picks it up automatically through Spring's collection injection.
 */
public interface JobSource {

    /** The ATS this source reads. Must be unique across all registered sources. */
    Ats ats();

    /**
     * Fetches every currently listed posting on the company's board.
     *
     * @return the postings, flagged incomplete if the source knows it may have missed some (never silently partial)
     * @throws SourceException if the board can't be read at all
     */
    BoardSnapshot fetch(Company company);
}
