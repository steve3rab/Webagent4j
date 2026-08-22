package io.webagent4j.extraction.api;

import java.io.IOException;
import java.io.NotSerializableException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serial;
import java.util.Objects;

/**
 * A converted value failed an {@link IExtractionValidator} check. Distinct from {@link
 * ExtractionConversionException}: validation always runs after conversion has already succeeded.
 * The exception message never embeds the rejected value. The retained value may be {@code null}
 * when a validator is invoked directly outside {@link ExtractionRequest}'s non-null pipeline. Java
 * native serialization is explicitly unsupported because {@link #value()} is structured state.
 */
public final class ExtractionValidationException extends AExtractionException {

    private static final long serialVersionUID = 1L;

    private final transient Object value;
    private final String description;

    /** Creates a validation failure retaining the offending value and a human-readable rule. */
    public ExtractionValidationException(Object value, String description) {
        super("Extracted value failed validation");
        this.value = value;
        this.description = Objects.requireNonNull(description, "description");
        if (description.isBlank()) {
            throw new IllegalArgumentException("description cannot be blank");
        }
    }

    /**
     * Returns the value that failed validation, possibly {@code null} for a direct validator call.
     */
    public Object value() {
        return value;
    }

    /** Returns a human-readable description of the failed rule. */
    public String description() {
        return description;
    }

    @Serial
    private void writeObject(ObjectOutputStream ignored) throws IOException {
        throw new NotSerializableException(ExtractionValidationException.class.getName());
    }

    @Serial
    private void readObject(ObjectInputStream ignored) throws IOException, ClassNotFoundException {
        throw new NotSerializableException(ExtractionValidationException.class.getName());
    }
}
