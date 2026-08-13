package io.webagent4j.observation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Deterministic SHA-256 fingerprint of relevant semantic data.
 *
 * <p>Observation id, timestamp, duration, local index, and backend handles are excluded. The
 * fingerprint indicates probable semantic equality, not cryptographic page identity.
 */
public record ObservationFingerprint(String value) {

    /** Validates a lowercase hexadecimal SHA-256 value. */
    public ObservationFingerprint {
        value = Objects.requireNonNull(value, "value");
        if (!value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("value must be a lowercase SHA-256 fingerprint");
        }
    }

    /** Computes a fingerprint from stable metadata and ordered semantic elements. */
    public static ObservationFingerprint compute(
            PageMetadata metadata,
            List<SemanticElement> elements,
            List<SemanticRelationship> relationships) {
        Objects.requireNonNull(metadata, "metadata");
        Objects.requireNonNull(elements, "elements");
        Objects.requireNonNull(relationships, "relationships");
        StringBuilder canonical = new StringBuilder();
        append(canonical, metadata.url());
        append(canonical, metadata.title());
        for (SemanticElement element : elements) {
            append(canonical, element.stableKey());
            append(canonical, element.role().name());
            append(canonical, element.accessibleName());
            append(canonical, element.text());
            append(canonical, Boolean.toString(element.visible()));
            append(canonical, Boolean.toString(element.enabled()));
            append(canonical, Boolean.toString(element.state().checked()));
            append(canonical, Boolean.toString(element.state().selected()));
            append(canonical, element.state().expanded().map(String::valueOf).orElse("unknown"));
            append(canonical, element.value().disposition().name());
            element.value().value().ifPresent(value -> append(canonical, value));
        }
        Map<SemanticElementId, String> semanticKeys = new LinkedHashMap<>();
        elements.forEach(element -> semanticKeys.put(element.id(), element.stableKey()));
        relationships.forEach(
                relationship -> {
                    append(
                            canonical,
                            semanticKeys.getOrDefault(relationship.source(), "unknown-source"));
                    append(canonical, relationship.type().name());
                    append(
                            canonical,
                            semanticKeys.getOrDefault(relationship.target(), "unknown-target"));
                });
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.toString().getBytes(StandardCharsets.UTF_8));
            return new ObservationFingerprint(HexFormat.of().formatHex(digest));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 must be available", impossible);
        }
    }

    private static void append(StringBuilder target, String value) {
        target.append(value.length()).append(':').append(value).append('|');
    }
}
