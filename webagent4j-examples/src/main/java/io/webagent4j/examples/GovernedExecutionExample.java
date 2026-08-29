package io.webagent4j.examples;

import io.webagent4j.action.ActionDecisionEntry;
import io.webagent4j.action.ActionResult;
import io.webagent4j.action.ActionType;
import io.webagent4j.action.policy.ActionPolicies;
import io.webagent4j.action.policy.IActionPolicy;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import io.webagent4j.policy.network.INetworkPolicy;
import io.webagent4j.policy.network.NetworkPolicies;

/**
 * Demonstrates governed execution on a single {@code NAVIGATE} action: an {@link IActionPolicy}
 * restricting which action types/idempotency this navigation may have, and an {@link
 * INetworkPolicy} restricting the destination's scheme/host and denying private/loopback addresses
 * - then prints the resulting {@link io.webagent4j.action.ActionDecisionTrace}.
 */
public final class GovernedExecutionExample {

    private GovernedExecutionExample() {}

    /** Navigates to the given URL under both an action policy and a network policy. */
    public static void main(String[] args) {
        String url = requireArgument(args, "page URL");
        String allowedHost = java.net.URI.create(url).getHost();

        IActionPolicy actionPolicy =
                ActionPolicies.allOf(
                        ActionPolicies.denyNonIdempotent(),
                        ActionPolicies.allowOnlyTypes(ActionType.CLICK, ActionType.NAVIGATE));

        INetworkPolicy networkPolicy =
                NetworkPolicies.builder()
                        .allowScheme("https")
                        .allowHost(allowedHost)
                        .denyLoopback()
                        .denyPrivateAddresses()
                        .denyLinkLocal()
                        .build();

        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                var page = browser.open("about:blank")) {
            ActionResult<Void> result =
                    page.action()
                            .navigate(url)
                            .policy(actionPolicy)
                            .networkPolicy(networkPolicy)
                            .execute();

            System.out.println("success=" + result.success() + " executed=" + result.executed());
            for (ActionDecisionEntry decision : result.decisionTrace().entries()) {
                System.out.println(
                        "  "
                                + decision.kind()
                                + "/"
                                + decision.phase()
                                + " -> "
                                + decision.outcome()
                                + " ("
                                + decision.reason().code()
                                + ")");
            }
        }
    }

    private static String requireArgument(String[] args, String description) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException(
                    "Expected " + description + " as the first argument");
        }
        return args[0];
    }
}
