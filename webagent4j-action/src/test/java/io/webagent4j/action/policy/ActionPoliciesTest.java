package io.webagent4j.action.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionSideEffect;
import io.webagent4j.action.ActionType;
import io.webagent4j.policy.PolicyDecision;
import org.junit.jupiter.api.Test;

class ActionPoliciesTest {

    @Test
    void allowAllAlwaysAllows() {
        assertThat(ActionPolicies.allowAll().evaluate(context(ActionType.CLICK)).isAllow())
                .isTrue();
        assertThat(ActionPolicies.allowAll().evaluate(context(ActionType.NAVIGATE)).isAllow())
                .isTrue();
    }

    @Test
    void denyAllAlwaysDenies() {
        assertThat(ActionPolicies.denyAll().evaluate(context(ActionType.CLICK)).isDeny()).isTrue();
    }

    @Test
    void allowOnlyTypesDeniesEveryUnlistedType() {
        IActionPolicy policy = ActionPolicies.allowOnlyTypes(ActionType.CLICK, ActionType.HOVER);

        assertThat(policy.evaluate(context(ActionType.CLICK)).isAllow()).isTrue();
        assertThat(policy.evaluate(context(ActionType.HOVER)).isAllow()).isTrue();
        assertThat(policy.evaluate(context(ActionType.NAVIGATE)).isDeny()).isTrue();
        assertThat(policy.evaluate(context(ActionType.SUBMIT)).isDeny()).isTrue();
        assertThat(policy.evaluate(context(ActionType.UPLOAD)).isDeny()).isTrue();
        assertThat(policy.evaluate(context(ActionType.DOWNLOAD)).isDeny()).isTrue();
    }

    @Test
    void allowOnlyTypesRejectsAnEmptyAllowList() {
        assertThatThrownBy(ActionPolicies::allowOnlyTypes)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void denyTypesDeniesOnlyTheListedTypes() {
        IActionPolicy policy = ActionPolicies.denyTypes(ActionType.NAVIGATE, ActionType.SUBMIT);

        assertThat(policy.evaluate(context(ActionType.NAVIGATE)).isDeny()).isTrue();
        assertThat(policy.evaluate(context(ActionType.SUBMIT)).isDeny()).isTrue();
        assertThat(policy.evaluate(context(ActionType.CLICK)).isAllow()).isTrue();
    }

    @Test
    void allowOnlySideEffectsDeniesEveryUnlistedSideEffect() {
        IActionPolicy policy = ActionPolicies.allowOnlySideEffects(ActionSideEffect.NONE);

        assertThat(policy.evaluate(sideEffectContext(ActionSideEffect.NONE)).isAllow()).isTrue();
        assertThat(policy.evaluate(sideEffectContext(ActionSideEffect.NAVIGATION)).isDeny())
                .isTrue();
        assertThat(
                        policy.evaluate(sideEffectContext(ActionSideEffect.EXTERNAL_SIDE_EFFECT))
                                .isDeny())
                .isTrue();
    }

    @Test
    void allowOnlySideEffectsRejectsAnEmptyAllowList() {
        assertThatThrownBy(ActionPolicies::allowOnlySideEffects)
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void denySideEffectsDeniesOnlyTheListedCategories() {
        IActionPolicy policy =
                ActionPolicies.denySideEffects(ActionSideEffect.EXTERNAL_SIDE_EFFECT);

        assertThat(
                        policy.evaluate(sideEffectContext(ActionSideEffect.EXTERNAL_SIDE_EFFECT))
                                .isDeny())
                .isTrue();
        assertThat(policy.evaluate(sideEffectContext(ActionSideEffect.NONE)).isAllow()).isTrue();
    }

    @Test
    void denyNonIdempotentDeniesOnlyNonIdempotentActions() {
        IActionPolicy policy = ActionPolicies.denyNonIdempotent();

        assertThat(policy.evaluate(idempotencyContext(ActionIdempotency.NON_IDEMPOTENT)).isDeny())
                .isTrue();
        assertThat(policy.evaluate(idempotencyContext(ActionIdempotency.IDEMPOTENT)).isAllow())
                .isTrue();
        assertThat(
                        policy.evaluate(
                                        idempotencyContext(
                                                ActionIdempotency.CONDITIONALLY_IDEMPOTENT))
                                .isAllow())
                .isTrue();
    }

    @Test
    void allOfComposesInOrderAndShortCircuitsOnDeny() {
        IActionPolicy policy =
                ActionPolicies.allOf(
                        ActionPolicies.allowOnlyTypes(ActionType.CLICK, ActionType.NAVIGATE),
                        ActionPolicies.denyNonIdempotent());

        PolicyDecision navigateDecision = policy.evaluate(context(ActionType.NAVIGATE));
        assertThat(navigateDecision.isAllow()).isTrue();

        PolicyDecision submitDecision = policy.evaluate(context(ActionType.SUBMIT));
        assertThat(submitDecision.isDeny()).isTrue();
        assertThat(submitDecision.reason()).isEqualTo(ActionPolicyReasons.ACTION_TYPE_DENIED);
    }

    @Test
    void futureActionTypeNotYetInAnAllowListIsDeniedNotSilentlyAllowed() {
        IActionPolicy policy = ActionPolicies.allowOnlyTypes(ActionType.CLICK);
        for (ActionType type : ActionType.values()) {
            PolicyDecision decision = policy.evaluate(context(type));
            if (type == ActionType.CLICK) {
                assertThat(decision.isAllow()).as(type.name()).isTrue();
            } else {
                assertThat(decision.isDeny()).as(type.name()).isTrue();
            }
        }
    }

    private static ActionPolicyContext context(ActionType type) {
        return new ActionPolicyContext(
                ActionId.create(),
                type,
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.NONE,
                ActionPolicyMode.EXECUTE,
                "page");
    }

    private static ActionPolicyContext sideEffectContext(ActionSideEffect sideEffect) {
        return new ActionPolicyContext(
                ActionId.create(),
                ActionType.CLICK,
                ActionIdempotency.IDEMPOTENT,
                sideEffect,
                ActionPolicyMode.EXECUTE,
                "page");
    }

    private static ActionPolicyContext idempotencyContext(ActionIdempotency idempotency) {
        return new ActionPolicyContext(
                ActionId.create(),
                ActionType.CLICK,
                idempotency,
                ActionSideEffect.NONE,
                ActionPolicyMode.EXECUTE,
                "page");
    }
}
