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
import java.util.List;

/**
 * Backend-neutral live frame: a document boundary reached from a {@link IPage} or another {@code
 * IFrame} via {@link IPage#frame()}/{@link #frame()}, never a plain descendant DOM element.
 *
 * <p>An {@code IFrame} is a semantic handle, not a frozen snapshot: the frame criterion it was
 * resolved from is re-evaluated against live browser state on every operation, so a query built
 * from one {@code IFrame} instance keeps working correctly across frame navigation and frame
 * replacement (a removed-then-reinserted {@code <iframe>} with the same semantic identity), and
 * fails explicitly - never silently reusing stale content - once that identity can no longer be
 * resolved unambiguously. This mirrors the re-resolution guarantee {@link
 * io.webagent4j.locator.api.ILocator#reference()} already gives at the element level, extended to
 * the document boundary itself.
 *
 * <p>Frame instances are not thread-safe. Returned observations are immutable snapshots scoped only
 * to this frame's own document, never mixed with the main page or a sibling frame.
 */
public interface IFrame extends IActionContext, IObservationSource {

    /** Returns this frame's current document URL, resolved against live browser state. */
    @Override
    String url();

    /** Returns this frame's current document title, resolved against live browser state. */
    @Override
    String title();

    /**
     * Navigates this specific frame to an absolute HTTP(S) URL, replacing its document.
     *
     * <p>Uses the same URL-scheme validation as {@link IPage#navigate(String)}: {@code
     * javascript:}, {@code file:}, and {@code data:} URLs are rejected. The navigation is bounded
     * by the same configured navigation timeout used for top-level page navigation.
     */
    void navigate(String url);

    /** Builds an immutable semantic snapshot of this frame's own document content. */
    @Override
    Observation observe();

    /** Builds an immutable semantic snapshot using explicit bounded observation options. */
    Observation observe(ObservationOptions options);

    /** Resolves an immutable semantic definition against this frame's current document. */
    @Override
    default IElement resolve(LocatorDefinition definition) {
        return locate(definition).element();
    }

    /** Starts a semantic or selector-based element query scoped to this frame's own document. */
    IFind<IElement> find();

    /**
     * Starts a query constrained by an explicit, immutable semantic context, scoped to this frame's
     * own document.
     */
    default IFind<IElement> find(InteractionContext context) {
        return find().inContext(context);
    }

    /** Starts a query using an explicit immutable locator configuration. */
    IFind<IElement> find(LocatorConfig config);

    /** Resolves a programmatic locator definition against this frame and returns diagnostics. */
    LocatorResult locate(LocatorDefinition definition);

    /** Resolves a definition using an explicit immutable locator configuration. */
    LocatorResult locate(LocatorDefinition definition, LocatorConfig config);

    /**
     * Resolves {@code request}'s source against this frame's current document to one unambiguous
     * element and reads, converts, and validates its data - re-resolving this frame's own
     * pending-scope chain fresh on every poll exactly like {@link #locate} already does, so a frame
     * that disappears, is replaced, or becomes ambiguous mid-wait is caught the same way.
     *
     * <p>The default implementation reports that this backend does not support extraction; a
     * backend that does (the Playwright adapter) overrides it. Adding this default, rather than an
     * abstract method, keeps every existing {@link IFrame} implementation source-compatible.
     */
    default <T> ExtractionResult<T> extract(ExtractionRequest<T> request) {
        throw new UnsupportedOperationException("Extraction is not supported by this backend");
    }

    /**
     * Resolves every element {@code request}'s source matches inside this frame, and reads,
     * converts, and validates each one's data in DOM order.
     *
     * <p>The default implementation reports that this backend does not support extraction; a
     * backend that does (the Playwright adapter) overrides it. Adding this default, rather than an
     * abstract method, keeps every existing {@link IFrame} implementation source-compatible.
     */
    default <T> ExtractionResult<List<T>> extractList(ExtractionRequest<T> request) {
        throw new UnsupportedOperationException("Extraction is not supported by this backend");
    }

    /**
     * Resolves {@code source} to one accessible HTML table inside this frame and reads its
     * structure.
     *
     * <p>The default implementation reports that this backend does not support extraction; a
     * backend that does (the Playwright adapter) overrides it. Adding this default, rather than an
     * abstract method, keeps every existing {@link IFrame} implementation source-compatible.
     */
    default ExtractionResult<ExtractedTable> extractTable(LocatorDefinition source) {
        throw new UnsupportedOperationException("Extraction is not supported by this backend");
    }

    /** Starts a single action plan targeting elements inside this frame. */
    IActionBuilder action();

    /**
     * Starts a frame query scoped to the direct child frames of this frame's own document.
     *
     * <p>A frame found this way is resolved strictly inside this frame: it can never accidentally
     * match a same-named frame belonging to the main page or to a sibling frame.
     */
    IFrameLocator frame();
}
