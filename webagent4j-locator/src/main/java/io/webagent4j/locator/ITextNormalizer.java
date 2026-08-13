package io.webagent4j.locator;

/** Shared text normalization contract used by exact and fuzzy comparisons. */
public interface ITextNormalizer {

    /** Normalizes Unicode, non-breaking spaces and repeated whitespace without removing accents. */
    String normalize(String value);

    /** Applies {@link #normalize(String)} and locale-independent case folding. */
    String normalizeCaseFolded(String value);
}
