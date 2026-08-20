package io.webagent4j.extraction.api;

/**
 * The resolved element exists, but the requested attribute is not present on it - distinct from the
 * source element itself being absent, which is reported by the locator layer instead.
 */
public final class ExtractionAttributeMissingException extends AExtractionException {

    private static final long serialVersionUID = 1L;

    private final String attributeName;

    /** Creates a failure naming the missing attribute. */
    public ExtractionAttributeMissingException(String attributeName) {
        super("Attribute \"" + attributeName + "\" is not present on the resolved element");
        this.attributeName = attributeName;
    }

    /** Returns the requested attribute's name. */
    public String attributeName() {
        return attributeName;
    }
}
