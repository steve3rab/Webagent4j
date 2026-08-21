package io.webagent4j.browser;

import io.webagent4j.action.IActionBuilder;
import io.webagent4j.action.IActionContext;
import io.webagent4j.dom.IElement;
import io.webagent4j.extraction.api.ExtractedTable;
import io.webagent4j.extraction.api.ExtractionRequest;
import io.webagent4j.extraction.api.ExtractionResult;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.api.IFind;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.observation.Observation;
import io.webagent4j.observation.ObservationOptions;
import io.webagent4j.observation.spi.IObservationSource;
import java.time.Duration;
import java.util.List;

/**
 * Backend-neutral live browser page.
 *
 * <p>Page instances are not thread-safe. Returned observations are immutable snapshots.
 */
public interface IPage extends IActionContext, IObservationSource, AutoCloseable {

    /** Navigates to an absolute HTTP(S) URL. */
    void navigate(String url);

    /**
     * Navigates to an absolute HTTP(S) URL, with {@code timeout} as an authoritative upper bound on
     * the navigation attempt itself.
     *
     * <p>Unlike {@link #navigate(String)}, which uses whatever navigation behavior/timeout this
     * backend defaults to, this overload exists specifically so a caller with its own deadline -
     * the browser crawler, bounding one navigation attempt to {@code
     * BrowserCrawlRequest#navigationTimeout()} - gets a real, honored bound rather than an
     * unenforced suggestion. A backend that cannot honor a caller-supplied timeout must say so
     * explicitly rather than silently falling back to its own default: the default implementation
     * here throws {@link UnsupportedOperationException} for exactly that reason. Silently ignoring
     * {@code timeout} would let a caller believe a bound is enforced when it is not - the timeout
     * equivalent of swallowing an error. The Playwright adapter overrides this method and maps
     * {@code timeout} to the native driver's own per-call navigation timeout option, so it is truly
     * enforced there.
     *
     * @throws IllegalArgumentException if {@code timeout} is {@code null}, zero, or negative
     * @throws UnsupportedOperationException if this backend cannot honor a caller-supplied
     *     navigation timeout
     */
    default void navigate(String url, Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        throw new UnsupportedOperationException(
                "This browser backend does not support caller-supplied navigation timeouts");
    }

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

    /**
     * Polls {@code expression} - a JavaScript expression evaluated repeatedly in page context -
     * using this backend's OWN native timeout-aware polling primitive, until it returns a
     * JavaScript truthy value or {@code timeout} elapses.
     *
     * <p>This exists specifically so a caller can bind an entire poll-until-satisfied-or-timeout
     * operation to one backend call that the backend itself bounds, rather than repeatedly calling
     * {@link #evaluate(String)} from a Java-side loop: a single {@link #evaluate(String)} call has
     * no timeout of its own, so if it happens to block indefinitely - for example a client-side
     * navigation destroying the execution context mid-evaluation - no amount of Java-side deadline
     * bookkeeping around it can recover, because control never returns to Java until (if ever) the
     * call itself completes. An implementation of this method must never have that gap: the backend
     * is responsible for both the repeated evaluation and enforcing {@code timeout} against it, the
     * same way {@link #navigate(String, Duration)} delegates timeout enforcement to the backend
     * rather than checking it from the Java side after the fact.
     *
     * <p>A backend that cannot honor a caller-supplied, natively-bounded polling wait must say so
     * explicitly rather than silently falling back to an unbounded {@link #evaluate(String)} loop:
     * the default implementation here throws {@link UnsupportedOperationException} for exactly that
     * reason. The Playwright adapter overrides this method and maps it directly onto the native
     * driver's own timeout-aware function-polling primitive.
     *
     * @return the expression's final truthy value
     * @throws IllegalArgumentException if {@code expression} is blank, or {@code timeout} is {@code
     *     null}, zero, or negative
     * @throws ConditionTimeoutException if {@code expression} never becomes truthy within {@code
     *     timeout}
     * @throws UnsupportedOperationException if this backend cannot honor a natively-bounded
     *     condition wait
     */
    default Object waitForCondition(String expression, Duration timeout) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("expression cannot be blank");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        throw new UnsupportedOperationException(
                "This browser backend does not support natively-bounded condition waits");
    }

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

    /**
     * Starts a query constrained by an explicit, immutable semantic context.
     *
     * <p>The context is resolved before the target lookup, so callers can disambiguate repeated
     * labels such as duplicate product-card actions without relying on fragile CSS selectors.
     * Contexts are re-resolved against the live DOM before execution when the backend supports it.
     */
    default IFind<IElement> find(InteractionContext context) {
        return find().inContext(context);
    }

    /** Starts a query using an explicit immutable locator configuration. */
    IFind<IElement> find(LocatorConfig config);

    /** Resolves a programmatic locator definition and returns ranked diagnostics. */
    LocatorResult locate(LocatorDefinition definition);

    /** Resolves a definition using an explicit immutable locator configuration. */
    LocatorResult locate(LocatorDefinition definition, LocatorConfig config);

    /**
     * Resolves {@code request}'s source against this page's current document to one unambiguous
     * element and reads, converts, and validates its data - reusing the exact same locator
     * resolution {@link #locate} does, not a second DOM search. See {@link ExtractionRequest} for
     * the full raw-&gt;convert-&gt;validate pipeline and {@link
     * io.webagent4j.extraction.api.AExtractionException} for the failure taxonomy: a not-found or
     * ambiguous source still raises the normal {@code LocatorNotFoundException}/{@code
     * AmbiguousLocatorException}, never silently reinterpreted or resolved to a best-ranked guess.
     *
     * <p>The default implementation reports that this backend does not support extraction; a
     * backend that does (the Playwright adapter) overrides it. Adding this default, rather than an
     * abstract method, keeps every existing {@link IPage} implementation source-compatible.
     */
    default <T> ExtractionResult<T> extract(ExtractionRequest<T> request) {
        throw new UnsupportedOperationException("Extraction is not supported by this backend");
    }

    /**
     * Resolves every element {@code request}'s source matches against this page's current document,
     * and reads, converts, and validates each one's data in DOM order.
     *
     * <p>The default implementation reports that this backend does not support extraction; a
     * backend that does (the Playwright adapter) overrides it. Adding this default, rather than an
     * abstract method, keeps every existing {@link IPage} implementation source-compatible.
     */
    default <T> ExtractionResult<List<T>> extractList(ExtractionRequest<T> request) {
        throw new UnsupportedOperationException("Extraction is not supported by this backend");
    }

    /**
     * Resolves {@code source} to one accessible HTML table on this page and reads its structure.
     *
     * <p>The default implementation reports that this backend does not support extraction; a
     * backend that does (the Playwright adapter) overrides it. Adding this default, rather than an
     * abstract method, keeps every existing {@link IPage} implementation source-compatible.
     */
    default ExtractionResult<ExtractedTable> extractTable(LocatorDefinition source) {
        throw new UnsupportedOperationException("Extraction is not supported by this backend");
    }

    /** Starts a single action plan with optional postconditions. */
    IActionBuilder action();

    /**
     * Starts a frame query scoped to this page's own top-level document.
     *
     * <p>A frame is a separate document/browsing context, never a plain descendant DOM element: see
     * {@link IFrame} for the full contract, including re-resolution, ambiguity, detachment, and
     * replacement semantics.
     */
    IFrameLocator frame();

    /** Closes this page while keeping its owning browser open. */
    @Override
    void close();
}
