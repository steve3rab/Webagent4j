package io.webagent4j.extraction.api;

/**
 * A raw extracted string could not be deterministically converted to the requested target type.
 * Retains the raw value and target type so a caller can diagnose the failure without re-reading the
 * source.
 */
public final class ExtractionConversionException extends AExtractionException {

    private static final long serialVersionUID = 1L;

    private final String rawValue;
    private final transient Class<?> targetType;

    /** Creates a conversion failure retaining the raw value, target type, and underlying cause. */
    public ExtractionConversionException(String rawValue, Class<?> targetType, Throwable cause) {
        super(
                "Could not convert \""
                        + rawValue
                        + "\" to "
                        + targetType.getName()
                        + ": "
                        + (cause == null ? "unknown reason" : cause.getMessage()),
                cause);
        this.rawValue = rawValue;
        this.targetType = targetType;
    }

    /** Returns the raw string that failed to convert. */
    public String rawValue() {
        return rawValue;
    }

    /** Returns the type conversion was attempted against. */
    public Class<?> targetType() {
        return targetType;
    }
}
