package io.webagent4j.action;

/** Broad action side-effect category for future safety policies. */
public enum ActionSideEffect {
    NONE,
    LOCAL_PAGE_STATE,
    NAVIGATION,
    EXTERNAL_SIDE_EFFECT
}
