package io.github.jozephzemambo.jobradar.domain;

/** Natural key of a posting. Records give correct equals/hashCode, so this is safe as a map key. */
public record PostingKey(Ats ats, String externalId) {
}
