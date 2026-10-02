package io.github.jozephzemambo.jobradar.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * One-shot crawl for schedulers (cron, Windows Task Scheduler): with the {@code crawl} profile the app starts
 * without a web server, runs one full ingest, logs the report, and exits.
 *
 * <p>Exit code 0 if at least one board was read, 1 if every board failed or the ingest threw, so a scheduler can
 * alert on a broken crawl while tolerating one company's board being down.
 */
@Component
@Profile("crawl")
public class CrawlRunner implements ApplicationRunner, ExitCodeGenerator {

    private static final Logger log = LoggerFactory.getLogger(CrawlRunner.class);

    private final IngestService ingestService;
    private final ObjectMapper mapper;
    private int exitCode = 1;

    public CrawlRunner(IngestService ingestService, ObjectMapper mapper) {
        this.ingestService = ingestService;
        this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            IngestReport report = ingestService.ingest(IngestRequest.all());
            log.info("Crawl report: {}", mapper.writeValueAsString(report));
            if (!report.failures().isEmpty()) {
                log.warn("{} of {} boards failed: {}", report.failures().size(), report.companiesRequested(),
                        report.failures().stream().map(IngestReport.CompanyFailure::boardToken).toList());
            }
            exitCode = report.companiesSucceeded() > 0 ? 0 : 1;
        } catch (RuntimeException e) {
            log.error("Crawl failed", e);
            exitCode = 1;
        }
    }

    @Override
    public int getExitCode() {
        return exitCode;
    }
}
