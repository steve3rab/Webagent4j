package io.webagent4j.observation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NavigationObserverTest {

    @Test
    void retainsOwnedLinksCurrentItemAndOrientation() {
        Observation observation = ObserverTestSupport.observeRich();

        assertThat(observation.navigations())
                .singleElement()
                .satisfies(
                        navigation -> {
                            assertThat(navigation.name()).isEqualTo("Primary");
                            assertThat(navigation.links())
                                    .extracting(SemanticElement::name)
                                    .containsExactly("Help");
                            assertThat(navigation.currentItem()).isPresent();
                            assertThat(navigation.orientation())
                                    .isEqualTo(NavigationOrientation.HORIZONTAL);
                        });
    }
}
