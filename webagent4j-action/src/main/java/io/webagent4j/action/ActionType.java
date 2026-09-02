package io.webagent4j.action;

/**
 * Browser operations supported by the action engine.
 *
 * <p>Constants are appended only, never reordered or removed: {@link
 * io.webagent4j.action.ActionStage} carries the same discipline (see {@code
 * ActionStageOrdinalTest}) because Revapi's {@code java.field.enumConstantOrderChanged} check, and
 * this enum's own name-based (never ordinal-based) use in Recording V1 ({@code
 * JsonWorkflowRecordingCodec}), both depend on every existing constant's ordinal staying fixed
 * across releases.
 */
public enum ActionType {
    CLICK,
    DOUBLE_CLICK,
    TYPE,
    CLEAR,
    SELECT,
    CHECK,
    UNCHECK,
    FOCUS,
    BLUR,
    HOVER,
    SCROLL,
    SUBMIT,
    NAVIGATE,
    RELOAD,
    GO_BACK,
    GO_FORWARD,
    UPLOAD,
    DOWNLOAD,
    PRESS_KEY,
    WAIT,

    /**
     * Types characters sequentially into the target by dispatching one keyboard/input event per
     * character, distinct from {@link #TYPE} (which replaces the value via fill semantics, with no
     * per-character key events). This is not a guaranteed full-value replacement: the observable
     * result depends on the target's current value, selection, and caret position, and on any
     * application JavaScript handling those events - so repeating the same sequence is not
     * idempotent and can further modify the target (for example, appending to it) rather than
     * reproducing the same end state. Added in 1.2.0; see {@code docs/governed-execution.md}.
     */
    TYPE_SEQUENCE
}
