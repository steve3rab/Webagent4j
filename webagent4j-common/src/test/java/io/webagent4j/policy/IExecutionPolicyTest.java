package io.webagent4j.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IExecutionPolicyTest {

    private record Context(String value) {}

    @Test
    void lambdaImplementationEvaluatesContext() {
        IExecutionPolicy<Context> policy =
                context ->
                        context.value().equals("ok")
                                ? PolicyDecision.allow("ok")
                                : PolicyDecision.deny("not-ok");
        assertThat(policy.evaluate(new Context("ok")).isAllow()).isTrue();
        assertThat(policy.evaluate(new Context("other")).isDeny()).isTrue();
    }

    @Test
    void implementationMayThrowAndCallerMustTreatAsDenyItself() {
        IExecutionPolicy<Context> throwing =
                context -> {
                    throw new RuntimeException("boom");
                };
        assertThatThrownBy(() -> throwing.evaluate(new Context("x")))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("boom");
    }

    @Test
    void implementationMayReturnNullAndCallerMustTreatAsDenyItself() {
        IExecutionPolicy<Context> nullReturning = context -> null;
        assertThat(nullReturning.evaluate(new Context("x"))).isNull();
    }

    @Test
    void genericContextTypeIsPreserved() {
        IExecutionPolicy<String> policy = value -> PolicyDecision.allow("string-context");
        assertThat(policy.evaluate("anything").isAllow()).isTrue();
    }
}
