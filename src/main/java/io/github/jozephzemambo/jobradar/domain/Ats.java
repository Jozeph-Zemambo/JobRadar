package io.github.jozephzemambo.jobradar.domain;

/**
 * Applicant tracking systems JobRadar knows how to read.
 * Adding a new board provider means adding a constant here and one {@code JobSource} implementation.
 */
public enum Ats {
    GREENHOUSE,
    LEVER,
    ASHBY,
    WORKDAY,
    SMARTRECRUITERS
}
