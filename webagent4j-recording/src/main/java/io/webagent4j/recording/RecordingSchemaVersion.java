package io.webagent4j.recording;

/**
 * Numeric schema version of the canonical JSON recording format produced and consumed by {@link
 * JsonWorkflowRecordingCodec}.
 *
 * <p>There is deliberately no fallback or best-effort decoding of an unrecognized version: {@link
 * #fromNumber(int)} throws {@link RecordingFormatException} rather than guessing at a compatible
 * shape, since silently misreading a future or foreign schema version could produce a recording
 * that looks valid but does not mean what its author intended.
 */
public enum RecordingSchemaVersion {
    /** The only schema version Phase 0.9-A defines. */
    V1(1);

    private final int number;

    RecordingSchemaVersion(int number) {
        this.number = number;
    }

    /** Returns the numeric value written to and read from the {@code schemaVersion} JSON field. */
    public int number() {
        return number;
    }

    /**
     * Returns the version matching {@code number}.
     *
     * @throws RecordingFormatException if {@code number} does not match a known schema version
     */
    public static RecordingSchemaVersion fromNumber(int number) {
        for (RecordingSchemaVersion version : values()) {
            if (version.number == number) {
                return version;
            }
        }
        throw new RecordingFormatException("unsupported schemaVersion");
    }
}
