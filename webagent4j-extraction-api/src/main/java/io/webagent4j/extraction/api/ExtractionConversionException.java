package io.webagent4j.extraction.api;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.Objects;

/**
 * A raw extracted string could not be deterministically converted to the requested target type.
 * Retains the raw value and target type so a caller can diagnose the failure without re-reading the
 * source. The exception message never embeds the raw value or the cause message. Java native
 * serialization is explicitly unsupported because the structured fields are authoritative.
 */
public final class ExtractionConversionException extends AExtractionException {

    private static final long serialVersionUID = 1L;

    private final String rawValue;
    private final transient Class<?> targetType;

    /** Creates a conversion failure retaining the raw value, target type, and underlying cause. */
    public ExtractionConversionException(String rawValue, Class<?> targetType, Throwable cause) {
        super(message(targetType), cause);
        this.rawValue = Objects.requireNonNull(rawValue, "rawValue");
        this.targetType = Objects.requireNonNull(targetType, "targetType");
    }

    /** Returns the raw string that failed to convert. */
    public String rawValue() {
        return rawValue;
    }

    /** Returns the type conversion was attempted against. */
    public Class<?> targetType() {
        return targetType;
    }

    private static String message(Class<?> targetType) {
        return "Extracted value could not be converted to "
                + Objects.requireNonNull(targetType, "targetType").getName();
    }

    @Serial
    private void writeObject(ObjectOutputStream ignored) throws IOException {
        throw new NotSerializableException(ExtractionConversionException.class.getName());
    }

    @Serial
    private void readObject(ObjectInputStream ignored) throws IOException, ClassNotFoundException {
        throw new NotSerializableException(ExtractionConversionException.class.getName());
    }
}
