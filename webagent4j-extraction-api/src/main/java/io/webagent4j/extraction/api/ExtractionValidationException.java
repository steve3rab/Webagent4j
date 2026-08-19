package io.webagent4j.extraction.api;

/**
 * A converted value failed an {@link IExtractionValidator} check. Distinct from {@link
 * ExtractionConversionException}: validation always runs after conversion has already succeeded.
 */
public final class ExtractionValidationException extends AExtractionException {

    private static final long serialVersionUID = 1L;

    private final transient Object value;
    private final String description;

    /** Creates a validation failure retaining the offending value and a human-readable rule. */
    public ExtractionValidationException(Object value, String description) {
        super("Value \"" + value + "\" failed validation: " + description);
        this.value = value;
        this.description = description;
    }

    /** Returns the value that failed validation. */
    public Object value() {
        return value;
    }

    /** Returns a human-readable description of the failed rule. */
    public String description() {
        return description;
    }
}
