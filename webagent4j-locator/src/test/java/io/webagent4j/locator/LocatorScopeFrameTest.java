package io.webagent4j.locator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.Mockito.mock;

import io.webagent4j.dom.IElement;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link LocatorScope#frame(String)} starts a fresh document-boundary chain root rather than
 * an element-scoped descendant, and that the canonical constructor rejects a frame scope carrying
 * an element root - a cross-document scope combination that can never be valid, since a frame is a
 * separate browsing context rather than a descendant DOM node.
 */
class LocatorScopeFrameTest {

    @Test
    void frameFactoryCreatesARootlessFreshChain() {
        LocatorScope scope = LocatorScope.frame("Frame[name=\"checkout\"]");

        assertThat(scope.type()).isEqualTo(LocatorScopeType.FRAME);
        assertThat(scope.root()).isEmpty();
        assertThat(scope.path()).containsExactly("Frame[name=\"checkout\"]");
    }

    @Test
    void rejectsAFrameScopeCarryingAnElementRoot() {
        IElement element = mock(IElement.class);

        assertThatIllegalArgumentException()
                .isThrownBy(
                        () ->
                                new LocatorScope(
                                        LocatorScopeType.FRAME,
                                        Optional.of(element),
                                        List.of("Frame[any]")))
                .withMessageContaining("frame scope cannot have an element root");
    }
}
