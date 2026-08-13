package io.webagent4j.observation;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.locator.api.ElementRole;
import org.junit.jupiter.api.Test;

class ElementCapabilityResolverTest {

    @Test
    void derivesCapabilitiesFromRoleTypeAndState() {
        Observation observation = ObserverTestSupport.observeRich();

        assertThat(observation.byRole(ElementRole.TEXTBOX).getFirst().capabilities())
                .contains(ElementCapability.TYPE, ElementCapability.CLEAR, ElementCapability.FOCUS);
        assertThat(observation.buttons())
                .filteredOn(button -> button.name().equals("Continue"))
                .singleElement()
                .satisfies(
                        button ->
                                assertThat(button.capabilities())
                                        .contains(
                                                ElementCapability.CLICK,
                                                ElementCapability.FOCUS,
                                                ElementCapability.SUBMIT));
        assertThat(observation.byRole(ElementRole.MAIN).getFirst().capabilities()).isEmpty();
    }
}
