package io.github.jozephzemambo.jobradar.ingest;

import io.github.jozephzemambo.jobradar.domain.Company;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * Shared fan-out for the concurrent strategies. Subclasses only choose the executor, so the virtual-thread vs.
 * platform-pool comparison measures the thread model and nothing else.
 */
public abstract class ExecutorFetchStrategy implements FetchStrategy {

    /** A fresh executor per run; it is closed (and joined) when the run finishes. */
    protected abstract ExecutorService newExecutor();

    @Override
    public final List<FetchOutcome> fetchAll(List<Company> companies, Function<Company, FetchOutcome> task) {
        List<Callable<FetchOutcome>> calls = new ArrayList<>(companies.size());
        for (Company company : companies) {
            calls.add(() -> task.apply(company));
        }
        // ExecutorService is AutoCloseable since Java 19: close() waits for submitted tasks to finish.
        try (ExecutorService executor = newExecutor()) {
            List<Future<FetchOutcome>> futures = executor.invokeAll(calls);
            List<FetchOutcome> outcomes = new ArrayList<>(futures.size());
            for (Future<FetchOutcome> future : futures) {
                outcomes.add(future.get());
            }
            return outcomes;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Ingest interrupted", e);
        } catch (ExecutionException e) {
            // The task contract says it never throws; reaching here is a bug, so fail loudly.
            throw new IllegalStateException("Fetch task threw instead of returning a Failure", e.getCause());
        }
    }
}
