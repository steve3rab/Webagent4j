package io.webagent4j.policy;

import java.util.List;
import java.util.Objects;

/** Composes multiple {@link IExecutionPolicy} instances into one. */
public final class ExecutionPolicies {

    private ExecutionPolicies() {}

    /**
     * Composes {@code policies} into one policy that {@code ALLOW}s only if every one of them does,
     * evaluated in the exact order given.
     *
     * <p>Deterministic and short-circuiting: evaluation stops at the first {@code DENY} (that
     * policy's own reason is returned unchanged, never collapsed to a generic one) or the first
     * thrown exception (propagated to the caller unchanged, per {@link IExecutionPolicy}'s own
     * fail-closed contract - this method itself never catches anything). A later policy in the list
     * is never evaluated once an earlier one has denied or thrown.
     *
     * <p>{@code policies} is defensively copied at construction time: mutating the list passed in
     * afterward has no effect on the returned policy.
     *
     * @throws NullPointerException if {@code policies} or any element of it is {@code null}
     * @throws IllegalArgumentException if {@code policies} is empty - an empty composition has no
     *     honest single outcome, so it is rejected rather than silently treated as allow-all
     */
    public static <C> IExecutionPolicy<C> allOf(List<IExecutionPolicy<C>> policies) {
        Objects.requireNonNull(policies, "policies");
        List<IExecutionPolicy<C>> copy = List.copyOf(policies);
        if (copy.isEmpty()) {
            throw new IllegalArgumentException(
                    "allOf(...) requires at least one policy - an empty composition has no"
                            + " honest single outcome");
        }
        return context -> {
            PolicyDecision last = null;
            for (IExecutionPolicy<C> policy : copy) {
                last = policy.evaluate(context);
                if (last == null || last.isDeny()) {
                    return last;
                }
            }
            return last;
        };
    }

    /** Varargs convenience for {@link #allOf(List)}. */
    @SafeVarargs
    public static <C> IExecutionPolicy<C> allOf(IExecutionPolicy<C>... policies) {
        return allOf(List.of(policies));
    }
}
