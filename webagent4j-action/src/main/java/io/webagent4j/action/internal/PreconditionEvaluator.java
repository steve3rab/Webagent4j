package io.webagent4j.action.internal;

import io.webagent4j.action.ActionType;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.verification.IVerification;
import io.webagent4j.verification.IVerificationContext;
import io.webagent4j.verification.VerificationResult;
import io.webagent4j.verification.VerificationType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** Evaluates implicit actionability and explicit conditions before any backend side effect. */
final class PreconditionEvaluator {

    List<VerificationResult> evaluate(
            ActionType type,
            IElement target,
            IVerificationContext context,
            List<IVerification> explicit) {
        List<VerificationResult> results = new ArrayList<>();
        if (target != null) {
            ElementState state = target.state();
            results.add(result(state.present(), VerificationType.ELEMENT_EXISTS, "Target exists"));
            if (requiresVisibility(type)) {
                results.add(
                        result(
                                state.visible(),
                                VerificationType.ELEMENT_VISIBLE,
                                "Target is visible"));
            }
            if (requiresEnabled(type)) {
                results.add(
                        result(
                                state.enabled(),
                                VerificationType.ELEMENT_ENABLED,
                                "Target is enabled"));
            }
            if (requiresEditable(type)) {
                results.add(
                        result(
                                state.editable(),
                                VerificationType.ELEMENT_EDITABLE,
                                "Target is editable"));
            }
            if (state.interactabilityKnown() && requiresClickability(type)) {
                results.add(
                        result(
                                state.clickable(),
                                VerificationType.CUSTOM,
                                "Target is interactable"));
            }
        }
        explicit.stream().map(value -> value.verify(context)).forEach(results::add);
        return List.copyOf(results);
    }

    private static VerificationResult result(
            boolean success, VerificationType type, String description) {
        return new VerificationResult(
                success,
                type,
                description,
                "true",
                Boolean.toString(success),
                Duration.ZERO,
                false);
    }

    private static boolean requiresVisibility(ActionType type) {
        return switch (type) {
            case CLICK,
                    DOUBLE_CLICK,
                    TYPE,
                    CLEAR,
                    SELECT,
                    CHECK,
                    UNCHECK,
                    FOCUS,
                    BLUR,
                    HOVER,
                    SCROLL,
                    SUBMIT,
                    PRESS_KEY,
                    UPLOAD,
                    DOWNLOAD ->
                    true;
            default -> false;
        };
    }

    private static boolean requiresEnabled(ActionType type) {
        return switch (type) {
            case CLICK,
                    DOUBLE_CLICK,
                    TYPE,
                    CLEAR,
                    SELECT,
                    CHECK,
                    UNCHECK,
                    SUBMIT,
                    PRESS_KEY,
                    UPLOAD,
                    DOWNLOAD ->
                    true;
            default -> false;
        };
    }

    private static boolean requiresEditable(ActionType type) {
        return type == ActionType.TYPE || type == ActionType.CLEAR;
    }

    private static boolean requiresClickability(ActionType type) {
        return type == ActionType.CLICK
                || type == ActionType.DOUBLE_CLICK
                || type == ActionType.DOWNLOAD;
    }
}
