package io.github.jozephzemambo.jobradar.stats;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.github.jozephzemambo.jobradar.config.CacheConfig;
import io.github.jozephzemambo.jobradar.ingest.IngestCompletedEvent;
import io.github.jozephzemambo.jobradar.persistence.IngestRunRepository;
import io.github.jozephzemambo.jobradar.persistence.PostingRepository;
import io.github.jozephzemambo.jobradar.scoring.SkillDictionary;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

/** A minimal Spring context (no database) proving the cache is used and that an ingest event clears it. */
@SpringJUnitConfig(StatsCachingTest.Config.class)
class StatsCachingTest {

    @Configuration
    @Import({CacheConfig.class, StatsService.class})
    static class Config {
        @Bean
        PostingRepository postingRepository() {
            return mock(PostingRepository.class);
        }

        @Bean
        IngestRunRepository ingestRunRepository() {
            return mock(IngestRunRepository.class);
        }

        @Bean
        SkillDictionary skillDictionary() {
            return new SkillDictionary(List.of());
        }

        @Bean
        Clock clock() {
            return Clock.systemUTC();
        }
    }

    @Autowired
    StatsService stats;

    @Autowired
    PostingRepository postings;

    @Autowired
    IngestRunRepository runs;

    @Autowired
    ApplicationEventPublisher events;

    @BeforeEach
    void stubs() {
        Mockito.reset(postings, runs);
        when(runs.findFirstByOrderByStartedAtAsc()).thenReturn(Optional.empty());
        when(runs.findFirstByOrderByStartedAtDesc()).thenReturn(Optional.empty());
        when(postings.openCountsByCompany(any())).thenReturn(List.of());
    }

    @Test
    void secondCallIsServedFromCacheUntilAnIngestCompletes() {
        stats.stats(10);
        stats.stats(10);
        verify(postings, times(1)).openSkillLists();

        stats.stats(5); // different key
        verify(postings, times(2)).openSkillLists();

        events.publishEvent(new IngestCompletedEvent(1L));
        stats.stats(10);
        verify(postings, times(3)).openSkillLists();
    }
}
