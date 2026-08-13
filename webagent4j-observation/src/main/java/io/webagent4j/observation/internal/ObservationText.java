package io.webagent4j.observation.internal;

import java.text.Normalizer;
import java.util.Locale;

/** Internal deterministic text normalization and bounded retention helpers. */
final class ObservationText {

    private ObservationText() {}

    static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replace('\u00A0', ' ')
                .trim()
                .replaceAll("\\s+", " ");
    }

    static String key(String value) {
        return normalize(value).toLowerCase(Locale.ROOT);
    }

    static String bounded(String value, int maximum) {
        String normalized = normalize(value);
        if (normalized.length() <= maximum) {
            return normalized;
        }
        return normalized.substring(0, maximum);
    }
}
