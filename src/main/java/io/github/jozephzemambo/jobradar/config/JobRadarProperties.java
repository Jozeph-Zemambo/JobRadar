package io.github.jozephzemambo.jobradar.config;

import io.github.jozephzemambo.jobradar.domain.Company;
import io.github.jozephzemambo.jobradar.scoring.Profile;
import io.github.jozephzemambo.jobradar.scoring.SkillDictionary;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything configurable under {@code jobradar.*}, bound once at startup into immutable records.
 * Constructor binding means a missing or mistyped value fails fast at boot instead of at first use.
 */
@ConfigurationProperties("jobradar")
public record JobRadarProperties(
        @DefaultValue Sources sources,
        @DefaultValue Http http,
        @DefaultValue Dedup dedup,
        List<Company> companies,
        List<SkillDictionary.Skill> skills,
        Profile profile) {

    public JobRadarProperties {
        companies = companies == null ? List.of() : List.copyOf(companies);
        skills = skills == null ? List.of() : List.copyOf(skills);
        profile = profile == null ? new Profile(null, null, null, null) : profile;
    }

    /**
     * Base URLs per ATS (overridden in tests and benchmarks to point at a local WireMock), and how many postings
     * per paged board (Workday, SmartRecruiters) get a detail request for their description.
     */
    public record Sources(
            @DefaultValue("https://boards-api.greenhouse.io") String greenhouseBaseUrl,
            @DefaultValue("https://api.lever.co") String leverBaseUrl,
            @DefaultValue("https://api.ashbyhq.com") String ashbyBaseUrl,
            @DefaultValue("https://{tenant}.{wd}.myworkdayjobs.com") String workdayBaseUrlTemplate,
            @DefaultValue("https://api.smartrecruiters.com") String smartRecruitersBaseUrl,
            @DefaultValue("50") int maxDetailsPerBoard) {
    }

    /**
     * Outbound HTTP politeness and resilience. None of the three APIs documents a rate limit, so the default is
     * deliberately conservative.
     */
    public record Http(
            @DefaultValue("JobRadar/0.1 (job-market analytics; +https://github.com/Jozeph-Zemambo/jobradar)") String userAgent,
            @DefaultValue("5s") Duration connectTimeout,
            @DefaultValue("30s") Duration requestTimeout,
            @DefaultValue("2.0") double requestsPerSecondPerHost,
            @DefaultValue("2") int burstPerHost,
            @DefaultValue("4") int maxAttempts,
            @DefaultValue("500ms") Duration initialBackoff,
            @DefaultValue("8s") Duration maxBackoff,
            @DefaultValue("16") int platformPoolSize) {
    }

    /** Fuzzy-dedup threshold on title token similarity, in (0, 1]. See bench/dedup for how it was chosen. */
    public record Dedup(@DefaultValue("0.8") double titleSimilarityThreshold) {
    }
}
