package io.webagent4j.recording;

/**
 * Numeric schema version of the canonical JSON recording format produced and consumed by the
 * Recording V2 JSON codec.
 *
 * <p>Deliberately a separate version space from {@link RecordingSchemaVersion} (Recording V1's own
 * version enum), not an added constant on that enum: {@link WorkflowRecording} and {@link
 * WorkflowRecordingV2} are structurally unrelated root types (flat step list vs. execution tree),
 * so sharing one version enum across both would let a V1-shaped payload silently claim to be schema
 * number 2, or vice versa, without either decoder's own field-shape checks necessarily catching the
 * mislabeling. Keeping the two version spaces disjoint means a V1 decoder can never resolve a
 * number this enum defines, and this decoder can never resolve {@code 1} - each fails closed on the
 * other's version number exactly as it would on any other unrecognized one.
 *
 * <p>There is deliberately no fallback or best-effort decoding of an unrecognized version: {@link
 * #fromNumber(int)} throws {@link RecordingFormatException} rather than guessing at a compatible
 * shape, mirroring {@link RecordingSchemaVersion#fromNumber(int)} exactly.
 */
public enum RecordingSchemaVersionV2 {
    /** The only schema version this V2 format currently defines. */
    V2(2);

    private final int number;

    RecordingSchemaVersionV2(int number) {
        this.number = number;
    }

    /** Returns the numeric value written to and read from the {@code schemaVersion} JSON field. */
    public int number() {
        return number;
    }

    /**
     * Returns the version matching {@code number}.
     *
     * @throws RecordingFormatException if {@code number} does not match a known V2 schema version
     */
    public static RecordingSchemaVersionV2 fromNumber(int number) {
        for (RecordingSchemaVersionV2 version : values()) {
            if (version.number == number) {
                return version;
            }
        }
        throw new RecordingFormatException("unsupported schemaVersion");
    }
}
