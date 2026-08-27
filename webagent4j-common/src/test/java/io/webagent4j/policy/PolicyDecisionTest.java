package io.webagent4j.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PolicyDecisionTest {

    @Test
    void allowFactoryProducesAllowOutcome() {
        PolicyDecision decision = PolicyDecision.allow("action.allowed");
        assertThat(decision.outcome()).isEqualTo(PolicyOutcome.ALLOW);
        assertThat(decision.isAllow()).isTrue();
        assertThat(decision.isDeny()).isFalse();
        assertThat(decision.reason().code()).isEqualTo("action.allowed");
    }

    @Test
    void denyFactoryProducesDenyOutcome() {
        PolicyDecision decision = PolicyDecision.deny("action.denied");
        assertThat(decision.outcome()).isEqualTo(PolicyOutcome.DENY);
        assertThat(decision.isDeny()).isTrue();
        assertThat(decision.isAllow()).isFalse();
        assertThat(decision.reason().code()).isEqualTo("action.denied");
    }

    @Test
    void reasonObjectFactoriesMatchStringFactories() {
        assertThat(PolicyDecision.allow(PolicyReason.of("x"))).isEqualTo(PolicyDecision.allow("x"));
        assertThat(PolicyDecision.deny(PolicyReason.of("y"))).isEqualTo(PolicyDecision.deny("y"));
    }

    @Test
    void rejectsNullOutcome() {
        assertThatThrownBy(() -> new PolicyDecision(null, PolicyReason.of("x")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullReason() {
        assertThatThrownBy(() -> new PolicyDecision(PolicyOutcome.ALLOW, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void toStringRendersOnlyOutcomeAndReasonCode() {
        PolicyDecision decision = PolicyDecision.deny("secret.marker.SHOULD-NOT-LEAK-CONTEXT");
        String rendered = decision.toString();
        assertThat(rendered).contains("DENY").contains("secret.marker.SHOULD-NOT-LEAK-CONTEXT");
        // The rendering is exactly outcome + reason code, nothing else appended.
        assertThat(rendered)
                .isEqualTo(
                        "PolicyDecision[outcome=DENY, reason=secret.marker.SHOULD-NOT-LEAK-CONTEXT]");
    }

    @Test
    void equalDecisionsAreEqualAndHashConsistently() {
        PolicyDecision first = PolicyDecision.allow("x");
        PolicyDecision second = PolicyDecision.allow("x");
        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }
}
