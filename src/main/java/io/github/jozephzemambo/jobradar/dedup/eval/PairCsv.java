package io.github.jozephzemambo.jobradar.dedup.eval;

import io.github.jozephzemambo.jobradar.dedup.DedupCandidate;
import io.github.jozephzemambo.jobradar.domain.Ats;
import io.github.jozephzemambo.jobradar.domain.PostingKey;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Reads and writes the labeling file. The CSV is self-contained (every field the dedup rule looks at is in it), so
 * the evaluation can be re-run from the committed file without the database that produced it.
 */
public final class PairCsv {

    public static final List<String> HEADER = List.of("pair_id", "stratum", "weight", "title_similarity",
            "company", "a_key", "a_title", "a_locations", "a_department", "a_url", "a_snippet",
            "b_key", "b_title", "b_locations", "b_department", "b_url", "b_snippet", "label", "note");

    private static final String LOCATION_SEPARATOR = " | ";

    private PairCsv() {
    }

    /** One row: a pair, short description snippets for the labeler, and the label (null until labeled). */
    public record Row(String pairId, String stratum, double weight, double titleSimilarity, DedupCandidate a,
            String snippetA, DedupCandidate b, String snippetB, Boolean duplicate, String note) {
    }

    public static String write(List<Row> rows) {
        StringBuilder out = new StringBuilder(line(HEADER));
        for (Row r : rows) {
            out.append(line(List.of(r.pairId(), r.stratum(), String.format(Locale.ROOT, "%.4f", r.weight()),
                    String.format(Locale.ROOT, "%.4f", r.titleSimilarity()), r.a().company(),
                    key(r.a()), r.a().title(), String.join(LOCATION_SEPARATOR, r.a().locations()),
                    nullToEmpty(r.a().department()), r.a().canonicalUrl(), nullToEmpty(r.snippetA()),
                    key(r.b()), r.b().title(), String.join(LOCATION_SEPARATOR, r.b().locations()),
                    nullToEmpty(r.b().department()), r.b().canonicalUrl(), nullToEmpty(r.snippetB()),
                    r.duplicate() == null ? "" : r.duplicate() ? "dup" : "distinct", nullToEmpty(r.note()))));
        }
        return out.toString();
    }

    public static List<Row> read(String csv) {
        List<List<String>> records = parse(csv);
        if (records.isEmpty() || !records.getFirst().equals(HEADER)) {
            throw new IllegalArgumentException("Unexpected header: " + (records.isEmpty() ? "none" : records.getFirst()));
        }
        List<Row> rows = new ArrayList<>();
        for (List<String> f : records.subList(1, records.size())) {
            if (f.size() != HEADER.size()) {
                throw new IllegalArgumentException("Row has " + f.size() + " fields: " + f);
            }
            String company = f.get(4);
            rows.add(new Row(f.get(0), f.get(1), Double.parseDouble(f.get(2)), Double.parseDouble(f.get(3)),
                    candidate(f.get(5), company, f.get(6), f.get(7), f.get(8), f.get(9)), f.get(10),
                    candidate(f.get(11), company, f.get(12), f.get(13), f.get(14), f.get(15)), f.get(16),
                    label(f.get(17)), f.get(18)));
        }
        return rows;
    }

    private static Boolean label(String value) {
        return switch (value.strip().toLowerCase(Locale.ROOT)) {
            case "" -> null;
            case "dup" -> true;
            case "distinct" -> false;
            default -> throw new IllegalArgumentException("Label must be dup or distinct, got: " + value);
        };
    }

    private static DedupCandidate candidate(String key, String company, String title, String locations,
            String department, String url) {
        int colon = key.indexOf(':');
        PostingKey postingKey = new PostingKey(Ats.valueOf(key.substring(0, colon)), key.substring(colon + 1));
        List<String> locationList = locations.isEmpty() ? List.of() : Arrays.asList(locations.split(" \\| "));
        return new DedupCandidate(null, postingKey, company, title, locationList,
                department.isEmpty() ? null : department, url);
    }

    private static String key(DedupCandidate c) {
        return c.key().ats() + ":" + c.key().externalId();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String line(List<String> fields) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < fields.size(); i++) {
            if (i > 0) {
                line.append(',');
            }
            String field = fields.get(i);
            if (field.contains(",") || field.contains("\"") || field.contains("\n") || field.contains("\r")) {
                line.append('"').append(field.replace("\"", "\"\"")).append('"');
            } else {
                line.append(field);
            }
        }
        return line.append('\n').toString();
    }

    /** RFC 4180 parser: quoted fields may contain commas, doubled quotes and newlines. */
    static List<List<String>> parse(String csv) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < csv.length(); i++) {
            char ch = csv.charAt(i);
            if (quoted) {
                if (ch == '"' && i + 1 < csv.length() && csv.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (ch == '"') {
                    quoted = false;
                } else {
                    field.append(ch);
                }
            } else if (ch == '"') {
                quoted = true;
            } else if (ch == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (ch == '\n' || ch == '\r') {
                if (ch == '\r' && i + 1 < csv.length() && csv.charAt(i + 1) == '\n') {
                    i++;
                }
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
            } else {
                field.append(ch);
            }
        }
        if (field.length() > 0 || !record.isEmpty()) {
            record.add(field.toString());
            records.add(record);
        }
        return records;
    }
}
