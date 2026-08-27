package io.webagent4j.policy.network;

/**
 * When, relative to the actual network request, a {@link NetworkPolicyContext} is being evaluated.
 */
public enum NetworkCheckPhase {

    /**
     * Before the request is sent. A {@code DENY} here means the request is never made - the strong,
     * preventive guarantee this framework can make for every {@link NetworkRequestKind#HTTP_FETCH}
     * request and for the first hop of a {@link NetworkRequestKind#BROWSER_NAVIGATION}.
     */
    PRE_REQUEST,

    /**
     * After a browser navigation already completed, checking the final URL the browser landed on.
     * This exists only because a browser's own internal redirect handling cannot be intercepted
     * mid-flight - unlike {@code HttpCrawler}, which checks every redirect hop at {@link
     * #PRE_REQUEST}. A {@code DENY} at this phase means the navigation already happened; it is
     * reported as a violation after the fact, never as a prevented request.
     */
    POST_REQUEST
}
