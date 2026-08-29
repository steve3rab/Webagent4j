package io.webagent4j.action.policy;

import io.webagent4j.action.ActionId;
import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionSideEffect;
import io.webagent4j.action.ActionType;
import java.util.Objects;

/**
 * Immutable, structured description of one action about to be authorized. This is the only thing an
 * {@link IActionPolicy} ever sees - not the resolved DOM element, not typed text, not a {@link
 * io.webagent4j.action.Secret}, not upload file contents, and not any native backend object.
 *
 * <p>A policy that needs to make a domain-specific judgment (for example, "never submit a form
 * whose target description looks like a payment form") must derive that judgment from {@link
 * #targetDescription()} and the other objective, structured fields here - this framework does not
 * infer business semantics (such as "destructive" from button text) on a policy's behalf.
 *
 * @param actionId the correlation identifier this action runs under, shared with the eventual
 *     {@code ActionResult}
 * @param actionType the kind of action being authorized
 * @param idempotency the action's declared idempotency
 * @param sideEffect the action's declared side-effect category
 * @param mode why this evaluation is happening - see {@link ActionPolicyMode}
 * @param targetDescription a safe, human-readable description of the action's target (role and
 *     accessible name, or {@code "page"} for a page-level action) - the same description already
 *     used in {@code ActionDiagnostics} and audit events, never raw DOM or locator internals
 */
public record ActionPolicyContext(
        ActionId actionId,
        ActionType actionType,
        ActionIdempotency idempotency,
        ActionSideEffect sideEffect,
        ActionPolicyMode mode,
        String targetDescription) {

    /** Validates that every field is present. */
    public ActionPolicyContext {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(actionType, "actionType");
        Objects.requireNonNull(idempotency, "idempotency");
        Objects.requireNonNull(sideEffect, "sideEffect");
        Objects.requireNonNull(mode, "mode");
        Objects.requireNonNull(targetDescription, "targetDescription");
    }
}
