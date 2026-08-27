package io.webagent4j.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ExecutionPoliciesTest {

    private record Context(String value) {}

    @Test
    void allowsOnlyWhenEveryPolicyAllows() {
        IExecutionPolicy<Context> composed =
                ExecutionPolicies.allOf(
                        List.of(
                                context -> PolicyDecision.allow("first"),
                                context -> PolicyDecision.allow("second")));

        assertThat(composed.evaluate(new Context("x")).isAllow()).isTrue();
    }

    @Test
    void stopsAtTheFirstDenyAndPreservesItsOwnReason() {
        List<String> evaluated = new ArrayList<>();
        IExecutionPolicy<Context> composed =
                ExecutionPolicies.allOf(
                        List.of(
                                context -> {
                                    evaluated.add("first");
                                    return PolicyDecision.allow("first");
                                },
                                context -> {
                                    evaluated.add("second");
                                    return PolicyDecision.deny("second.specific.reason");
                                },
                                context -> {
                                    evaluated.add("third");
                                    return PolicyDecision.allow("third");
                                }));

        PolicyDecision decision = composed.evaluate(new Context("x"));

        assertThat(decision.isDeny()).isTrue();
        assertThat(decision.reason().code()).isEqualTo("second.specific.reason");
        assertThat(evaluated).containsExactly("first", "second");
    }

    @Test
    void stopsAtTheFirstThrownExceptionAndPropagatesItUnchanged() {
        List<String> evaluated = new ArrayList<>();
        RuntimeException boom = new RuntimeException("policy backend unavailable");
        IExecutionPolicy<Context> composed =
                ExecutionPolicies.allOf(
                        List.of(
                                context -> {
                                    evaluated.add("first");
                                    return PolicyDecision.allow("first");
                                },
                                context -> {
                                    evaluated.add("second");
                                    throw boom;
                                },
                                context -> {
                                    evaluated.add("third");
                                    return PolicyDecision.allow("third");
                                }));

        assertThatThrownBy(() -> composed.evaluate(new Context("x"))).isSameAs(boom);
        assertThat(evaluated).containsExactly("first", "second");
    }

    @Test
    void aMisbehavingNullDecisionFailsClosedRatherThanContinuing() {
        List<String> evaluated = new ArrayList<>();
        IExecutionPolicy<Context> composed =
                ExecutionPolicies.allOf(
                        List.of(
                                context -> {
                                    evaluated.add("first");
                                    return null;
                                },
                                context -> {
                                    evaluated.add("second");
                                    return PolicyDecision.allow("second");
                                }));

        assertThat(composed.evaluate(new Context("x"))).isNull();
        assertThat(evaluated).containsExactly("first");
    }

    @Test
    void emptyCompositionThrowsRatherThanSilentlyAllowingEverything() {
        assertThatThrownBy(() -> ExecutionPolicies.<Context>allOf(List.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionPolicies.<Context>allOf())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsNullListAndNullElements() {
        assertThatThrownBy(
                        () ->
                                ExecutionPolicies.<Context>allOf(
                                        (List<IExecutionPolicy<Context>>) null))
                .isInstanceOf(NullPointerException.class);
        List<IExecutionPolicy<Context>> withNull = new ArrayList<>();
        withNull.add(context -> PolicyDecision.allow("x"));
        withNull.add(null);
        assertThatThrownBy(() -> ExecutionPolicies.allOf(withNull))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void laterMutationOfTheInputListHasNoEffectOnAnAlreadyBuiltComposition() {
        List<IExecutionPolicy<Context>> mutable = new ArrayList<>();
        mutable.add(context -> PolicyDecision.allow("first"));
        IExecutionPolicy<Context> composed = ExecutionPolicies.allOf(mutable);

        mutable.add(context -> PolicyDecision.deny("added-after-construction"));

        assertThat(composed.evaluate(new Context("x")).isAllow()).isTrue();
    }

    @Test
    void nestedCompositionIsDeterministic() {
        IExecutionPolicy<Context> inner =
                ExecutionPolicies.allOf(
                        List.of(
                                context -> PolicyDecision.allow("inner-first"),
                                context -> PolicyDecision.deny("inner-second")));
        IExecutionPolicy<Context> outer =
                ExecutionPolicies.allOf(
                        List.of(context -> PolicyDecision.allow("outer-first"), inner));

        PolicyDecision decision = outer.evaluate(new Context("x"));

        assertThat(decision.isDeny()).isTrue();
        assertThat(decision.reason().code()).isEqualTo("inner-second");
    }

    @Test
    void varargsFactoryIsEquivalentToListFactory() {
        IExecutionPolicy<Context> composed =
                ExecutionPolicies.allOf(
                        context -> PolicyDecision.allow("a"), context -> PolicyDecision.allow("b"));

        assertThat(composed.evaluate(new Context("x")).isAllow()).isTrue();
    }
}
