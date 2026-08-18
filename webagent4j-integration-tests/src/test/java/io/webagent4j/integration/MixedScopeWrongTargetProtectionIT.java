package io.webagent4j.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.ActionFailureType;
import io.webagent4j.action.ActionResult;
import io.webagent4j.browser.InteractionContext;
import io.webagent4j.dom.IElement;
import org.junit.jupiter.api.Test;

/**
 * The most important test in this suite: proves that regrouping a mixed scope chain by kind -
 * applying every explicit element scope before any structured scope, regardless of declared order,
 * which is exactly what the previous (buggy) implementation did - would have silently clicked a
 * wrong-but-plausible target instead of failing safely.
 *
 * <p>The declared chain is {@code within(structured("Group")).within(explicit(#containerX))}: find
 * "Group" (searched from the page root, since nothing narrows it yet - there is only one "Group" on
 * the page, so this step alone succeeds), then override the scope to the literal {@code
 * #containerX} element. Per declared order, "Group"'s resolution has no further effect once the
 * explicit element scope is applied - the "Confirm" target must be searched directly inside {@code
 * #containerX}, which contains two same-named "Confirm" buttons (one direct, one nested inside
 * "Group"), so this must fail {@code TARGET_AMBIGUOUS}.
 *
 * <p>The previous buggy implementation applied every explicit element scope to the base context
 * eagerly, regardless of where it was declared relative to a structured scope, then resolved
 * structured scopes on top of that. For this exact chain that collapses to "search Group inside
 * containerX, then search Confirm inside Group" - which resolves uniquely to the button nested
 * inside "Group", silently clicking it. Independent proof comes from two separate server-side click
 * counters; both must stay at zero.
 */
class MixedScopeWrongTargetProtectionIT {

    @Test
    void regroupingScopesByKindNeverSilentlyClicksTheNestedButtonInstead() throws Exception {
        try (var support = Phase4TestSupport.start();
                var page = support.open("/actions/mixed-scope-wrong-target")) {
            IElement containerX = page.find().id("containerX").single();

            ActionResult<Void> result =
                    page.action()
                            .click(
                                    page.find(InteractionContext.context().containingText("Group"))
                                            .within(containerX)
                                            .button()
                                            .named("Confirm")
                                            .reference())
                            .execute();

            assertThat(result.success()).isFalse();
            assertThat(result.failure().orElseThrow().type())
                    .isEqualTo(ActionFailureType.TARGET_AMBIGUOUS);
            assertThat(support.clickCount("btn-direct")).isZero();
            assertThat(support.clickCount("btn-in-group")).isZero();
        }
    }
}
