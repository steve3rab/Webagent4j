package io.webagent4j.policy.network;

/** What kind of network request a {@link NetworkPolicyContext} is describing. */
public enum NetworkRequestKind {

    /**
     * A browser navigation - either a governed {@code NAVIGATE} action or a browser-crawler page
     * visit. The browser backend performs the actual HTTP traffic; WebAgent4J only observes the
     * requested URL and, separately, the final URL the browser ends up on.
     */
    BROWSER_NAVIGATION,

    /**
     * One HTTP round trip performed directly by {@code HttpCrawler} - the crawler's own {@code
     * IHttpFetcher}, never a browser backend.
     */
    HTTP_FETCH
}
