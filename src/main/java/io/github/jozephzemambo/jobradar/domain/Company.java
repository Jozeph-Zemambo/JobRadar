package io.github.jozephzemambo.jobradar.domain;

import java.util.Objects;

/**
 * A company's public job board on one ATS.
 *
 * @param name       display name, e.g. "Stripe"
 * @param ats        which ATS hosts the board
 * @param boardToken the board identifier in the ATS URL, e.g. "stripe"
 */
public record Company(String name, Ats ats, String boardToken) {

    public Company {
        Objects.requireNonNull(ats, "ats");
        if (boardToken == null || boardToken.isBlank()) {
            throw new IllegalArgumentException("boardToken must not be blank");
        }
        if (name == null || name.isBlank()) {
            name = boardToken;
        }
    }
}
