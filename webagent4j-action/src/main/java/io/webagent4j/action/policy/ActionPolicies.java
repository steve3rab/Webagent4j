package io.webagent4j.action.policy;

import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionSideEffect;
import io.webagent4j.action.ActionType;
import io.webagent4j.policy.ExecutionPolicies;
import io.webagent4j.policy.IExecutionPolicy;
import io.webagent4j.policy.PolicyDecision;
import java.util.EnumSet;
import java.util.Set;

/** Standard, declarative {@link IActionPolicy} implementations for common governance shapes. */
public final class ActionPolicies {

    private ActionPolicies() {}

    /** A policy that always {@code ALLOW}s. */
    public static IActionPolicy allowAll() {
        return context -> PolicyDecision.allow(ActionPolicyReasons.ALLOWED);
    }

    /** A policy that always {@code DENY}s. */
    public static IActionPolicy denyAll() {
        return context -> PolicyDecision.deny(ActionPolicyReasons.DENIED);
    }

    /**
     * A policy that {@code ALLOW}s only the given action types, denying every other one - an
     * allow-list, so a type never explicitly listed is always denied, never silently allowed by a
     * future addition to {@link ActionType}.
     *
     * @throws IllegalArgumentException if {@code types} is empty - an empty allow-list would deny
     *     every action, which is almost certainly not what a caller configuring an allow-list
     *     intended; use {@link #denyAll()} if that is genuinely the goal
     */
    public static IActionPolicy allowOnlyTypes(ActionType... types) {
        Set<ActionType> allowed = requireNonEmpty(types, ActionType.class, "types");
        return context ->
                allowed.contains(context.actionType())
                        ? PolicyDecision.allow(ActionPolicyReasons.ALLOWED)
                        : PolicyDecision.deny(ActionPolicyReasons.ACTION_TYPE_DENIED);
    }

    /** A policy that {@code DENY}s the given action types, allowing every other one. */
    public static IActionPolicy denyTypes(ActionType... types) {
        Set<ActionType> denied = EnumSet.noneOf(ActionType.class);
        java.util.Collections.addAll(denied, types);
        return context ->
                denied.contains(context.actionType())
                        ? PolicyDecision.deny(ActionPolicyReasons.ACTION_TYPE_DENIED)
                        : PolicyDecision.allow(ActionPolicyReasons.ALLOWED);
    }

    /**
     * A policy that {@code ALLOW}s only the given side-effect categories, denying every other one.
     *
     * @throws IllegalArgumentException if {@code sideEffects} is empty, for the same reason {@link
     *     #allowOnlyTypes} rejects an empty allow-list
     */
    public static IActionPolicy allowOnlySideEffects(ActionSideEffect... sideEffects) {
        Set<ActionSideEffect> allowed =
                requireNonEmpty(sideEffects, ActionSideEffect.class, "sideEffects");
        return context ->
                allowed.contains(context.sideEffect())
                        ? PolicyDecision.allow(ActionPolicyReasons.ALLOWED)
                        : PolicyDecision.deny(ActionPolicyReasons.SIDE_EFFECT_DENIED);
    }

    /** A policy that {@code DENY}s the given side-effect categories, allowing every other one. */
    public static IActionPolicy denySideEffects(ActionSideEffect... sideEffects) {
        Set<ActionSideEffect> denied = EnumSet.noneOf(ActionSideEffect.class);
        java.util.Collections.addAll(denied, sideEffects);
        return context ->
                denied.contains(context.sideEffect())
                        ? PolicyDecision.deny(ActionPolicyReasons.SIDE_EFFECT_DENIED)
                        : PolicyDecision.allow(ActionPolicyReasons.ALLOWED);
    }

    /** A policy that {@code DENY}s any action whose idempotency is {@code NON_IDEMPOTENT}. */
    public static IActionPolicy denyNonIdempotent() {
        return context ->
                context.idempotency() == ActionIdempotency.NON_IDEMPOTENT
                        ? PolicyDecision.deny(ActionPolicyReasons.NON_IDEMPOTENT_DENIED)
                        : PolicyDecision.allow(ActionPolicyReasons.ALLOWED);
    }

    /**
     * Composes {@code policies} into one that {@code ALLOW}s only if every one of them does - see
     * {@link ExecutionPolicies#allOf(java.util.List)} for the full ordering/short-circuit contract.
     */
    public static IActionPolicy allOf(IActionPolicy... policies) {
        IExecutionPolicy<ActionPolicyContext> composed =
                ExecutionPolicies.allOf(
                        java.util.List.<IExecutionPolicy<ActionPolicyContext>>of(policies));
        return composed::evaluate;
    }

    private static <E extends Enum<E>> Set<E> requireNonEmpty(
            E[] values, Class<E> type, String name) {
        java.util.Objects.requireNonNull(values, name);
        if (values.length == 0) {
            throw new IllegalArgumentException(name + " cannot be empty");
        }
        Set<E> set = EnumSet.noneOf(type);
        java.util.Collections.addAll(set, values);
        return set;
    }
}
