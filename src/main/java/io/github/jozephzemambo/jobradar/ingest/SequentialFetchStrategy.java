package io.github.jozephzemambo.jobradar.ingest;

import io.github.jozephzemambo.jobradar.domain.Company;
import java.util.List;
import java.util.function.Function;
import org.springframework.stereotype.Component;

@Component
public class SequentialFetchStrategy implements FetchStrategy {

    @Override
    public FetchMode mode() {
        return FetchMode.SEQUENTIAL;
    }

    @Override
    public List<FetchOutcome> fetchAll(List<Company> companies, Function<Company, FetchOutcome> task) {
        return companies.stream().map(task).toList();
    }
}
