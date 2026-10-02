package io.github.jozephzemambo.jobradar.config;

import io.github.jozephzemambo.jobradar.stats.StatsService;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.concurrent.ConcurrentMapCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * In-process cache for the stats endpoint. A plain ConcurrentMap is enough: entries are evicted on every ingest,
 * there is one instance, and the key space is tiny (one entry per {@code top} value requested).
 */
@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    CacheManager cacheManager() {
        return new ConcurrentMapCacheManager(StatsService.CACHE);
    }
}
