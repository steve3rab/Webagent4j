package io.webagent4j.observation;

import io.webagent4j.observation.spi.SnapshotElement;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Small deterministic default redaction policy for passwords, common secrets/tokens, and reliable
 * credit-card control hints.
 */
public final class SecureObservationRedactionPolicy implements IObservationRedactionPolicy {

    private static final Pattern SENSITIVE_HINT =
            Pattern.compile(
                    "(^|[^a-z])(password|passwd|secret|token|api[ _-]?key|credit[ _-]?card|"
                            + "card[ _-]?number|cc[ _-]?number|cvv|cvc)([^a-z]|$)");

    @Override
    public ObservedValue redact(SnapshotElement element, ObservationOptions options) {
        Objects.requireNonNull(element, "element");
        Objects.requireNonNull(options, "options");
        boolean sensitive = isSensitive(element);
        if (sensitive) {
            return ObservedValue.redacted(element.valuePresent());
        }
        if (!element.valuePresent()) {
            return ObservedValue.empty();
        }
        if (!options.includeInputValues()) {
            return ObservedValue.omitted(true);
        }
        return element.retainedValue()
                .map(ObservedValue::plain)
                .orElseGet(() -> ObservedValue.omitted(true));
    }

    /** Returns whether safe element metadata indicates a sensitive form control. */
    public boolean isSensitive(SnapshotElement element) {
        if (element.sensitive()
                || element.fieldType().orElse(InputFieldType.OTHER) == InputFieldType.PASSWORD) {
            return true;
        }
        String autocomplete = element.attributes().getOrDefault("autocomplete", "");
        if (autocomplete.equalsIgnoreCase("current-password")
                || autocomplete.equalsIgnoreCase("new-password")
                || autocomplete.equalsIgnoreCase("cc-number")
                || autocomplete.equalsIgnoreCase("cc-csc")) {
            return true;
        }
        String hints =
                String.join(
                                " ",
                                element.accessibleName(),
                                element.label(),
                                element.attributes().getOrDefault("name", ""),
                                element.attributes().getOrDefault("id", ""),
                                element.attributes().getOrDefault("type", ""))
                        .toLowerCase(Locale.ROOT);
        return SENSITIVE_HINT.matcher(hints).find();
    }
}
