package io.webagent4j.browser;

import io.webagent4j.common.LocatorException;
import io.webagent4j.locator.api.TextMatch;
import io.webagent4j.locator.api.TextMatchType;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, backend-neutral, conceptually serializable frame query.
 *
 * <p>A definition records intent only, mirroring {@link
 * io.webagent4j.locator.api.LocatorDefinition}: it performs no browser work and is safe to share
 * between threads. Every criterion is a hard, deterministic constraint - there is no scoring, no
 * DOM-order tie breaker, and no silent fallback between criteria. Zero matches is a typed "not
 * found" outcome; two or more equally valid matches is a typed "ambiguous" outcome. Neither outcome
 * is ever resolved by picking the first candidate.
 *
 * @param id exact {@code <iframe>} element id
 * @param name criterion against the HTML {@code name} attribute
 * @param title criterion against the {@code title} attribute
 * @param url criterion against the frame's current document URL - {@code EXACT}, {@code
 *     CASE_INSENSITIVE_EXACT}, {@code CONTAINS}, {@code STARTS_WITH}, {@code ENDS_WITH}, and {@code
 *     REGEX} are supported; {@code FUZZY} is rejected explicitly rather than silently treated as
 *     {@code CONTAINS} or any other mode
 * @param timeout resolution timeout override
 * @param stability required continuous stability duration before resolution succeeds
 */
public record FrameDefinition(
        Optional<String> id,
        Optional<TextMatch> name,
        Optional<TextMatch> title,
        Optional<TextMatch> url,
        Optional<Duration> timeout,
        Optional<Duration> stability) {

    /** Validates all immutable query components. */
    public FrameDefinition {
        id = normalizeOptional(id, "id");
        name = Objects.requireNonNull(name, "name");
        title = Objects.requireNonNull(title, "title");
        url = Objects.requireNonNull(url, "url");
        url.ifPresent(FrameDefinition::requireSupportedUrlMatchType);
        timeout = Objects.requireNonNull(timeout, "timeout");
        timeout.ifPresent(value -> requirePositive(value, "timeout"));
        stability = Objects.requireNonNull(stability, "stability");
        stability.ifPresent(value -> requirePositive(value, "stability"));
    }

    /** Creates an unconstrained frame query. */
    public static FrameDefinition frame() {
        return new FrameDefinition(
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    /** Returns a copy constrained to an exact element id. */
    public FrameDefinition withId(String value) {
        return new FrameDefinition(
                Optional.of(requireValue(value, "id")), name, title, url, timeout, stability);
    }

    /** Returns a copy constrained by an exact, case-insensitive HTML {@code name} attribute. */
    public FrameDefinition named(String value) {
        return new FrameDefinition(
                id,
                Optional.of(TextMatch.exactIgnoringCase(value)),
                title,
                url,
                timeout,
                stability);
    }

    /** Returns a copy constrained by an exact, case-insensitive {@code title} attribute. */
    public FrameDefinition withTitle(String value) {
        return new FrameDefinition(
                id, name, Optional.of(TextMatch.exactIgnoringCase(value)), url, timeout, stability);
    }

    /** Returns a copy constrained by the supplied URL criterion. */
    public FrameDefinition withUrl(TextMatch match) {
        return new FrameDefinition(
                id,
                name,
                title,
                Optional.of(Objects.requireNonNull(match, "match")),
                timeout,
                stability);
    }

    /** Returns a copy with a positive resolution timeout override. */
    public FrameDefinition withTimeout(Duration value) {
        Objects.requireNonNull(value, "timeout");
        requirePositive(value, "timeout");
        return new FrameDefinition(id, name, title, url, Optional.of(value), stability);
    }

    /**
     * Returns a copy requiring the resolved frame's identity to remain stable for {@code value}.
     */
    public FrameDefinition stableFor(Duration value) {
        Objects.requireNonNull(value, "stability");
        requirePositive(value, "stability");
        return new FrameDefinition(id, name, title, url, timeout, Optional.of(value));
    }

    /** Returns whether this definition constrains anything beyond "any frame". */
    public boolean unconstrained() {
        return id.isEmpty() && name.isEmpty() && title.isEmpty() && url.isEmpty();
    }

    private static Optional<String> normalizeOptional(Optional<String> value, String label) {
        return Objects.requireNonNull(value, label).map(item -> requireValue(item, label));
    }

    private static String requireValue(String value, String label) {
        Objects.requireNonNull(value, label);
        String normalized = value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(label + " cannot be blank");
        }
        return normalized;
    }

    private static void requirePositive(Duration value, String label) {
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(label + " must be positive");
        }
    }

    /**
     * Rejects a {@code FUZZY} URL criterion explicitly rather than letting it silently degrade into
     * {@code CONTAINS}, a regex, or any scoring behavior: frame URL matching only ever supports the
     * deterministic {@link TextMatchType} modes documented on {@link #url()}.
     */
    private static void requireSupportedUrlMatchType(TextMatch match) {
        if (match.type() == TextMatchType.FUZZY) {
            throw new LocatorException("Frame URL matching does not support FUZZY");
        }
    }
}
