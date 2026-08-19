package io.webagent4j.extraction.api;

import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.LocatorDefinition;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ExtractionResultTest {

    private static final ExtractionProvenance PROVENANCE =
            new ExtractionProvenance(
                    List.of("Page"),
                    LocatorDefinition.forRole(ElementRole.HEADING).named("Total"),
                    ExtractionReadType.TEXT,
                    Optional.empty());

    @Test
    void aSuccessfulResultCanNeverCarryANullValue() {
        assertThatNullPointerException()
                .isThrownBy(() -> new ExtractionResult<>(null, Optional.empty(), PROVENANCE));
    }
}
