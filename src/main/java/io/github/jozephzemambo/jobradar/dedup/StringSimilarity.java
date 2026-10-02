package io.github.jozephzemambo.jobradar.dedup;

/** Similarity of two strings in [0, 1], where 1 means identical for this measure's purposes. */
@FunctionalInterface
public interface StringSimilarity {

    double similarity(String a, String b);
}
