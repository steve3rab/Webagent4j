package io.webagent4j.browser;

/** Service-provider contract implemented by optional browser backend modules. */
public interface IBrowserProvider {

    /** Returns the stable provider identifier used by the public builder. */
    String id();

    /** Launches a browser using validated backend-neutral options. */
    IBrowser launch(BrowserOptions options);
}
