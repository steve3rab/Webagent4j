package io.webagent4j.locator;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;

/** Default Unicode-aware locator text normalizer. */
public final class DefaultTextNormalizer implements ITextNormalizer {

    @Override
    public String normalize(String value) {
        String unicode =
                Normalizer.normalize(Objects.requireNonNull(value, "value"), Normalizer.Form.NFKC)
                        .replace('\u00a0', ' ');
        return unicode.trim().replaceAll("\\s+", " ");
    }

    @Override
    public String normalizeCaseFolded(String value) {
        return normalize(value).toLowerCase(Locale.ROOT);
    }
}
