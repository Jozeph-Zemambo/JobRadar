package io.github.jozephzemambo.jobradar.ingest;

import io.github.jozephzemambo.jobradar.config.JobRadarProperties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** A fixed pool of OS threads. The honest baseline for virtual threads: concurrency, but with a size cap. */
@Component
public class PlatformPoolFetchStrategy extends ExecutorFetchStrategy {

    private final int poolSize;

    @Autowired
    public PlatformPoolFetchStrategy(JobRadarProperties props) {
        this(props.http().platformPoolSize());
    }

    public PlatformPoolFetchStrategy(int poolSize) {
        if (poolSize < 1) {
            throw new IllegalArgumentException("poolSize must be at least 1");
        }
        this.poolSize = poolSize;
    }

    @Override
    public FetchMode mode() {
        return FetchMode.PLATFORM_POOL;
    }

    @Override
    protected ExecutorService newExecutor() {
        return Executors.newFixedThreadPool(poolSize);
    }
}
