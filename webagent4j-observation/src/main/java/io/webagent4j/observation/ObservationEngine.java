package io.webagent4j.observation;

import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.observation.internal.ContentObserver;
import io.webagent4j.observation.internal.DialogObserver;
import io.webagent4j.observation.internal.FormObserver;
import io.webagent4j.observation.internal.ImageObserver;
import io.webagent4j.observation.internal.InteractiveElementObserver;
import io.webagent4j.observation.internal.LandmarkObserver;
import io.webagent4j.observation.internal.ListObserver;
import io.webagent4j.observation.internal.NavigationObserver;
import io.webagent4j.observation.internal.ObservationBuilder;
import io.webagent4j.observation.internal.ObservedElements;
import io.webagent4j.observation.internal.PageMetadataObserver;
import io.webagent4j.observation.internal.SemanticTreeBuilder;
import io.webagent4j.observation.internal.TableObserver;
import io.webagent4j.observation.spi.IObservationSource;
import io.webagent4j.observation.spi.PageSnapshot;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default deterministic semantic observation engine.
 *
 * <p>The engine is immutable and retains no per-observation mutable state. Concurrent sharing is
 * safe only when the injected clock, identifier supplier, policies, factories, resolvers, and
 * listener are themselves safe for concurrent use. It orchestrates one backend batch snapshot and
 * focused semantic observers, and does not retain pages or live elements between calls.
 */
public final class ObservationEngine implements IObservationEngine {

    private static final Logger LOGGER = LoggerFactory.getLogger(ObservationEngine.class);

    private final Clock clock;
    private final Supplier<ObservationId> idSupplier;
    private final IObservationFilter filter;
    private final IObservationRedactionPolicy redactionPolicy;
    private final ILocatorDefinitionFactory locatorFactory;
    private final IElementCapabilityResolver capabilityResolver;
    private final IObservationEventListener eventListener;

    /** Creates an engine with a system clock, secure redaction, and no-op events. */
    public ObservationEngine() {
        this(
                Clock.systemUTC(),
                () -> new ObservationId(UUID.randomUUID().toString()),
                new SemanticObservationFilter(),
                new SecureObservationRedactionPolicy(),
                new SemanticLocatorDefinitionFactory(),
                new ElementCapabilityResolver(),
                IObservationEventListener.none());
    }

    /** Creates an engine with an injected deterministic clock. */
    public ObservationEngine(Clock clock) {
        this(
                clock,
                () -> new ObservationId(UUID.randomUUID().toString()),
                new SemanticObservationFilter(),
                new SecureObservationRedactionPolicy(),
                new SemanticLocatorDefinitionFactory(),
                new ElementCapabilityResolver(),
                IObservationEventListener.none());
    }

    /** Creates a fully injected immutable engine for applications and deterministic tests. */
    @SuppressWarnings("checkstyle:ParameterNumber")
    public ObservationEngine(
            Clock clock,
            Supplier<ObservationId> idSupplier,
            IObservationFilter filter,
            IObservationRedactionPolicy redactionPolicy,
            ILocatorDefinitionFactory locatorFactory,
            IElementCapabilityResolver capabilityResolver,
            IObservationEventListener eventListener) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idSupplier = Objects.requireNonNull(idSupplier, "idSupplier");
        this.filter = Objects.requireNonNull(filter, "filter");
        this.redactionPolicy = Objects.requireNonNull(redactionPolicy, "redactionPolicy");
        this.locatorFactory = Objects.requireNonNull(locatorFactory, "locatorFactory");
        this.capabilityResolver = Objects.requireNonNull(capabilityResolver, "capabilityResolver");
        this.eventListener = Objects.requireNonNull(eventListener, "eventListener");
    }

    @Override
    public Observation observe(IObservationSource source) {
        return observe(source, ObservationOptions.defaults());
    }

    @Override
    public Observation observe(IObservationSource source, ObservationOptions options) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(options, "options");
        ensureNotInterrupted();
        long startedNanos = System.nanoTime();
        eventListener.onEvent(
                new IObservationEvent.ObservationStarted(
                        clock.instant(), options.mode(), options.budget()));
        LOGGER.debug(
                "Semantic observation started mode={} maxElements={}",
                options.mode(),
                options.budget().maxElements());
        PageSnapshot snapshot;
        try {
            snapshot = source.captureObservation(options);
        } catch (RuntimeException failure) {
            ObservationBackendException wrapped =
                    new ObservationBackendException("Observation backend capture failed", failure);
            failed(wrapped);
            throw wrapped;
        }
        try {
            checkDeadline(startedNanos, options);
            Observation observation = transform(snapshot, options, startedNanos);
            Duration duration = elapsed(startedNanos);
            LOGGER.debug(
                    "Semantic observation completed url={} durationMs={} visited={} included={}"
                            + " truncated={} warnings={}",
                    safeUrl(snapshot.url()),
                    duration.toMillis(),
                    observation.statistics().elementsVisited(),
                    observation.statistics().elementsIncluded(),
                    observation.statistics().truncated(),
                    observation.warnings().size());
            LOGGER.trace(
                    "Semantic observation retained forms={} links={} buttons={} tables={}",
                    observation.forms().size(),
                    observation.links().size(),
                    observation.buttons().size(),
                    observation.tables().size());
            if (observation.statistics().truncated()) {
                eventListener.onEvent(
                        new IObservationEvent.ObservationTruncated(
                                clock.instant(),
                                observation.statistics().truncations().stream()
                                        .map(ObservationTruncation::type)
                                        .distinct()
                                        .toList()));
            }
            eventListener.onEvent(
                    new IObservationEvent.ObservationCompleted(
                            clock.instant(),
                            observation.id(),
                            duration,
                            observation.elements().size(),
                            observation.warnings().size()));
            return observation;
        } catch (ObservationException failure) {
            failed(failure);
            throw failure;
        } catch (RuntimeException failure) {
            ObservationException wrapped =
                    new ObservationException("Semantic observation transformation failed", failure);
            failed(wrapped);
            throw wrapped;
        }
    }

    private Observation transform(
            PageSnapshot snapshot, ObservationOptions options, long startedNanos) {
        PageMetadata metadata = new PageMetadataObserver().observe(snapshot, clock);
        InteractiveElementObserver interactiveObserver =
                new InteractiveElementObserver(
                        filter, redactionPolicy, locatorFactory, capabilityResolver);
        ObservedElements observed = interactiveObserver.observe(snapshot, options);
        checkDeadline(startedNanos, options);

        ContentObserver.ContentResult content = new ContentObserver().observe(observed.elements());
        List<LandmarkObservation> landmarks = new LandmarkObserver().observe(observed.elements());
        FormObserver.FormResult forms = new FormObserver().observe(observed);
        NavigationObserver.NavigationResult navigation = new NavigationObserver().observe(observed);
        TableObserver.TableResult tables = new TableObserver().observe(observed);
        ListObserver.ListResult lists = new ListObserver().observe(observed);
        List<ImageObservation> images = new ImageObserver().observe(observed);
        DialogObserver.DialogResult dialogs = new DialogObserver().observe(observed);
        SemanticTreeBuilder.TreeResult tree =
                new SemanticTreeBuilder().build(observed.elements(), options.budget().maxDepth());
        checkDeadline(startedNanos, options);

        List<SemanticRelationship> relationships = new ArrayList<>();
        relationships.addAll(forms.relationships());
        relationships.addAll(navigation.relationships());
        relationships.addAll(dialogs.relationships());
        relationships = List.copyOf(new LinkedHashSet<>(relationships));

        List<ObservationTruncation> truncations = new ArrayList<>();
        truncations.addAll(observed.truncations());
        truncations.addAll(forms.truncations());
        truncations.addAll(tables.truncations());
        truncations.addAll(lists.truncations());
        truncations.addAll(tree.truncations());

        List<ObservationWarning> warnings = new ArrayList<>(observed.warnings());
        warnings.addAll(content.warnings());
        if (snapshot.mutationDetected()) {
            warnings.add(
                    new ObservationWarning(
                            ObservationWarningType.CAPTURE_MUTATED,
                            "The semantic document changed during batch capture",
                            java.util.Optional.empty()));
        }
        snapshot.warnings()
                .forEach(
                        warning ->
                                warnings.add(
                                        new ObservationWarning(
                                                ObservationWarningType.BACKEND_WARNING,
                                                safeBackendWarning(warning),
                                                java.util.Optional.empty())));

        Duration duration = elapsed(startedNanos);
        ObservationStatistics statistics =
                new ObservationStatistics(
                        snapshot.elementsVisited(),
                        observed.elements().size(),
                        Math.max(0, snapshot.elementsVisited() - observed.elements().size()),
                        (int)
                                observed.elements().stream()
                                        .filter(element -> !element.capabilities().isEmpty())
                                        .count(),
                        forms.forms().size(),
                        (int)
                                observed.elements().stream()
                                        .filter(element -> element.role() == ElementRole.LINK)
                                        .count(),
                        (int)
                                observed.elements().stream()
                                        .filter(element -> element.role() == ElementRole.BUTTON)
                                        .count(),
                        tables.tables().size(),
                        duration,
                        truncations);
        PageContent pageContent =
                new PageContent(
                        content.headings(), content.textBlocks(), lists.lists(), tables.tables());
        return new ObservationBuilder(idSupplier.get(), metadata)
                .semantic(observed.elements(), landmarks, relationships, tree.tree())
                .formsAndNavigation(forms.forms(), navigation.navigations())
                .structuredContent(tables.tables(), lists.lists(), images, pageContent)
                .transientUi(dialogs)
                .diagnostics(statistics, warnings)
                .build();
    }

    private void failed(RuntimeException failure) {
        LOGGER.debug("Semantic observation failed type={}", failure.getClass().getSimpleName());
        eventListener.onEvent(
                new IObservationEvent.ObservationFailed(
                        clock.instant(), failure.getClass().getSimpleName()));
    }

    private static void checkDeadline(long startedNanos, ObservationOptions options) {
        ensureNotInterrupted();
        if (elapsed(startedNanos).compareTo(options.budget().timeout()) > 0) {
            throw new ObservationTimeoutException("Global observation timeout exceeded");
        }
    }

    private static void ensureNotInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new ObservationException("Observation interrupted");
        }
    }

    private static Duration elapsed(long startedNanos) {
        return Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos));
    }

    private static String safeUrl(String value) {
        try {
            URI uri = new URI(value);
            return new URI(
                            uri.getScheme(),
                            null,
                            uri.getHost(),
                            uri.getPort(),
                            uri.getPath(),
                            null,
                            null)
                    .toString();
        } catch (URISyntaxException invalid) {
            return "<unavailable>";
        }
    }

    private static String safeBackendWarning(String warning) {
        // Backend diagnostics are untrusted and may accidentally contain page values.
        return "Backend reported a non-fatal capture warning";
    }
}
