package io.webagent4j.browser;

import io.webagent4j.action.IActionBuilder;
import io.webagent4j.action.IActionContext;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.api.IFind;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.observation.Observation;
import io.webagent4j.observation.ObservationOptions;
import io.webagent4j.observation.spi.IObservationSource;

/**
 * Backend-neutral live browser page.
 *
 * <p>Page instances are not thread-safe. Returned observations are immutable snapshots.
 */
public interface IPage extends IActionContext, IObservationSource, AutoCloseable {

    /** Navigates to an absolute HTTP(S) URL. */
    void navigate(String url);

    /** Reloads the current document. */
    void reload();

    /** Navigates backward in session history when possible. */
    void goBack();

    /** Navigates forward in session history when possible. */
    void goForward();

    /** Returns the current document HTML. */
    String content();

    /** Captures a PNG screenshot in memory. */
    byte[] screenshot();

    /** Evaluates a JavaScript expression in page context and returns its backend-neutral result. */
    Object evaluate(String expression);

    /** Builds an immutable semantic snapshot of meaningful page content. */
    Observation observe();

    /** Builds an immutable semantic snapshot using explicit bounded observation options. */
    Observation observe(ObservationOptions options);

    /** Resolves an immutable semantic definition against the current page. */
    @Override
    default IElement resolve(LocatorDefinition definition) {
        return locate(definition).element();
    }

    /** Starts a semantic or selector-based element query. */
    IFind<IElement> find();

    /** Starts a query using an explicit immutable locator configuration. */
    IFind<IElement> find(LocatorConfig config);

    /** Resolves a programmatic locator definition and returns ranked diagnostics. */
    LocatorResult locate(LocatorDefinition definition);

    /** Resolves a definition using an explicit immutable locator configuration. */
    LocatorResult locate(LocatorDefinition definition, LocatorConfig config);

    /** Starts a single action plan with optional postconditions. */
    IActionBuilder action();

    /** Closes this page while keeping its owning browser open. */
    @Override
    void close();
}
