package io.webagent4j.observation;

/** Important semantic relationship type; this is intentionally not a DOM edge model. */
public enum SemanticRelationshipType {
    LABELS,
    BELONGS_TO,
    CONTROLS,
    DESCRIBES,
    SUBMITS,
    NAVIGATES_TO,
    OWNS
}
