package io.webagent4j.observation;

import java.util.List;
import java.util.Objects;

/** Compact immutable view of meaningful visible content rather than full document text. */
public record PageContent(
        List<HeadingObservation> headings,
        List<String> textBlocks,
        List<ListObservation> lists,
        List<TableObservation> tables) {

    /** Validates and copies content collections. */
    public PageContent {
        headings = List.copyOf(Objects.requireNonNull(headings, "headings"));
        textBlocks = List.copyOf(Objects.requireNonNull(textBlocks, "textBlocks"));
        lists = List.copyOf(Objects.requireNonNull(lists, "lists"));
        tables = List.copyOf(Objects.requireNonNull(tables, "tables"));
    }
}
