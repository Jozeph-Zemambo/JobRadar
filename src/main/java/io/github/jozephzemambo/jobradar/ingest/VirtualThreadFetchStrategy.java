package io.github.jozephzemambo.jobradar.ingest;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.springframework.stereotype.Component;

/**
 * One virtual thread per board. No pool size to tune: the per-host rate limiter, not the thread count, is what
 * bounds outbound concurrency.
 */
@Component
public class VirtualThreadFetchStrategy extends ExecutorFetchStrategy {

    @Override
    public FetchMode mode() {
        return FetchMode.VIRTUAL;
    }

    @Override
    protected ExecutorService newExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
