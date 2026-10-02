package io.github.jozephzemambo.jobradar.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.Arrays;
import java.util.List;

/**
 * Stores a short list of strings in one column, newline-separated. Locations and skills are read with the
 * posting every time and never queried individually, so a join table would only add N+1 risk and write cost.
 */
@Converter
public class StringListConverter implements AttributeConverter<List<String>, String> {

    private static final String SEPARATOR = "\n";

    @Override
    public String convertToDatabaseColumn(List<String> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        return String.join(SEPARATOR, values.stream().map(v -> v.replace(SEPARATOR, " ")).toList());
    }

    @Override
    public List<String> convertToEntityAttribute(String column) {
        if (column == null || column.isEmpty()) {
            return List.of();
        }
        return Arrays.asList(column.split(SEPARATOR));
    }
}
