package io.github.jozephzemambo.jobradar.ingest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * In-process daily crawl for when JobRadar runs as a long-lived server. Off by default; enable with
 * {@code jobradar.schedule.enabled=true} and set {@code jobradar.schedule.cron}. For a machine that isn't always
 * on, prefer the one-shot {@code crawl} profile driven by the OS scheduler.
 */
@Component
@EnableScheduling
@ConditionalOnProperty(name = "jobradar.schedule.enabled", havingValue = "true")
public class ScheduledIngest {

    private static final Logger log = LoggerFactory.getLogger(ScheduledIngest.class);

    private final IngestService ingestService;

    public ScheduledIngest(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @Scheduled(cron = "${jobradar.schedule.cron:0 0 7 * * *}", zone = "${jobradar.schedule.zone:UTC}")
    public void crawl() {
        try {
            IngestReport report = ingestService.ingest(IngestRequest.all());
            log.info("Scheduled crawl: {} postings from {}/{} boards in {} ms", report.postingsFetched(),
                    report.companiesSucceeded(), report.companiesRequested(), report.wallMillis());
        } catch (IngestInProgressException e) {
            log.info("Skipping scheduled crawl: an ingest is already running");
        }
    }
}
