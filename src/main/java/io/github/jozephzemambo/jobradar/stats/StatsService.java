package io.github.jozephzemambo.jobradar.stats;

import io.github.jozephzemambo.jobradar.persistence.IngestRunEntity;
import io.github.jozephzemambo.jobradar.persistence.IngestRunRepository;
import io.github.jozephzemambo.jobradar.persistence.PostingRepository;
import io.github.jozephzemambo.jobradar.scoring.SkillDictionary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.PriorityQueue;
import io.github.jozephzemambo.jobradar.ingest.IngestCompletedEvent;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aggregates for {@code GET /api/stats}. Date arithmetic happens in Java so the queries stay portable.
 *
 * <p>Results are cached per {@code topN} and evicted when an ingest completes: the underlying data only changes
 * during ingest, and recomputing scans every open posting's skills. The one time-dependent field
 * ({@code newLast7Days}) can therefore be up to one crawl interval stale, which a daily crawl keeps to a day.
 */
@Service
@Transactional(readOnly = true)
public class StatsService {

    public static final String CACHE = "stats";

    private static final double SECONDS_PER_DAY = 86_400.0;

    private final PostingRepository postings;
    private final IngestRunRepository runs;
    private final SkillDictionary dictionary;
    private final Clock clock;

    public StatsService(PostingRepository postings, IngestRunRepository runs, SkillDictionary dictionary,
            Clock clock) {
        this.postings = postings;
        this.runs = runs;
        this.dictionary = dictionary;
        this.clock = clock;
    }

    @Cacheable(cacheNames = CACHE, key = "#topN")
    public StatsView stats(int topN) {
        if (topN < 1 || topN > 100) {
            throw new IllegalArgumentException("top must be between 1 and 100");
        }
        long open = postings.countByClosedAtIsNull() - postings.countByClosedAtIsNullAndDuplicateOfIdIsNotNull();
        Optional<IngestRunEntity> first = runs.findFirstByOrderByStartedAtAsc();
        Optional<IngestRunEntity> last = runs.findFirstByOrderByStartedAtDesc();
        Instant cutoff = first.map(IngestRunEntity::getStartedAt).orElse(Instant.EPOCH);

        return new StatsView(
                open,
                postings.countByClosedAtIsNotNull(),
                postings.countByClosedAtIsNullAndDuplicateOfIdIsNotNull(),
                toMap(postings.openCountsByAts()),
                toMap(postings.openCountsByWorkplace()),
                postings.openCountsByCompany(PageRequest.of(0, topN)).stream()
                        .map(r -> new StatsView.Count((String) r[0], ((Number) r[1]).longValue()))
                        .toList(),
                topSkills(topN, open),
                timeToClose(cutoff),
                first.isEmpty() ? 0 : postings.countFirstSeenAfter(clock.instant().minus(Duration.ofDays(7)), cutoff),
                new StatsView.Crawl(runs.count(), first.map(IngestRunEntity::getStartedAt).orElse(null),
                        last.map(IngestRunEntity::getStartedAt).orElse(null),
                        last.map(IngestRunEntity::getCompaniesRequested).orElse(null)));
    }

    @EventListener
    @CacheEvict(cacheNames = CACHE, allEntries = true)
    public void onIngestCompleted(IngestCompletedEvent event) {
        // Eviction is the whole job; the annotation does it.
    }

    /** Counts skill mentions in Java, then keeps the top N with a size-bounded min-heap: O(skills * log N). */
    List<StatsView.SkillDemand> topSkills(int topN, long openPostings) {
        Map<String, Long> counts = new HashMap<>();
        for (Object row : postings.openSkillLists()) {
            if (row instanceof Collection<?> skills) {
                for (Object skill : skills) {
                    counts.merge(skill.toString(), 1L, Long::sum);
                }
            }
        }
        Comparator<Map.Entry<String, Long>> byCount = Map.Entry.<String, Long>comparingByValue()
                .thenComparing(Map.Entry.comparingByKey(Comparator.reverseOrder()));
        PriorityQueue<Map.Entry<String, Long>> heap = new PriorityQueue<>(byCount);
        for (Map.Entry<String, Long> entry : counts.entrySet()) {
            heap.offer(entry);
            if (heap.size() > topN) {
                heap.poll(); // drop the smallest so only the top N remain
            }
        }
        List<Map.Entry<String, Long>> top = new ArrayList<>(heap);
        top.sort(byCount.reversed());
        return top.stream()
                .map(e -> new StatsView.SkillDemand(e.getKey(), dictionary.categoryOf(e.getKey()), e.getValue(),
                        openPostings == 0 ? 0.0 : round(e.getValue() / (double) openPostings)))
                .toList();
    }

    StatsView.TimeToClose timeToClose(Instant cutoff) {
        List<Double> days = new ArrayList<>();
        for (Object[] row : postings.closedLifetimesFirstSeenAfter(cutoff)) {
            Duration open = Duration.between((Instant) row[0], (Instant) row[1]);
            days.add(open.toSeconds() / SECONDS_PER_DAY);
        }
        if (days.isEmpty()) {
            return new StatsView.TimeToClose(0, null, null);
        }
        days.sort(null);
        return new StatsView.TimeToClose(days.size(), round(percentile(days, 0.5)), round(percentile(days, 0.75)));
    }

    /** Linear-interpolated percentile of a sorted list. */
    static double percentile(List<Double> sorted, double p) {
        double index = p * (sorted.size() - 1);
        int lower = (int) Math.floor(index);
        int upper = (int) Math.ceil(index);
        double fraction = index - lower;
        return sorted.get(lower) + (sorted.get(upper) - sorted.get(lower)) * fraction;
    }

    private static Map<String, Long> toMap(List<Object[]> rows) {
        Map<String, Long> map = new LinkedHashMap<>();
        for (Object[] row : rows) {
            map.put(row[0].toString(), ((Number) row[1]).longValue());
        }
        return map;
    }

    private static double round(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }
}
