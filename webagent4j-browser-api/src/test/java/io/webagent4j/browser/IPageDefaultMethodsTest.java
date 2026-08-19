package io.webagent4j.browser;

import static org.assertj.core.api.Assertions.assertThat;

import io.webagent4j.action.IActionBuilder;
import io.webagent4j.dom.BoundingBox;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.LocatorConfig;
import io.webagent4j.locator.LocatorDiagnostics;
import io.webagent4j.locator.LocatorDiagnosticsLevel;
import io.webagent4j.locator.LocatorResolutionPolicy;
import io.webagent4j.locator.LocatorResult;
import io.webagent4j.locator.LocatorStrategyType;
import io.webagent4j.locator.api.ElementRole;
import io.webagent4j.locator.api.IFind;
import io.webagent4j.locator.api.ILocator;
import io.webagent4j.locator.api.ILocatorScope;
import io.webagent4j.locator.api.LocatorDefinition;
import io.webagent4j.observation.Observation;
import io.webagent4j.observation.ObservationOptions;
import io.webagent4j.observation.spi.PageSnapshot;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Proves {@link IPage}'s two default methods delegate exactly as documented - the same contract
 * {@link IFrame} mirrors, see {@link IFrameDefaultMethodsTest}: {@link
 * IPage#resolve(LocatorDefinition)} must call through to {@code locate(definition).element()}, and
 * {@link IPage#find(InteractionContext)} must call through to {@code find().inContext(context)}.
 */
class IPageDefaultMethodsTest {

    @Test
    void resolveDelegatesToLocateThenReturnsItsElement() {
        IElement expected = new StubElement();
        LocatorDefinition definition = LocatorDefinition.element().named("Sign in");
        RecordingPage page = new RecordingPage(expected);

        IElement resolved = page.resolve(definition);

        assertThat(resolved).isSameAs(expected);
        assertThat(page.lastLocateDefinition).isSameAs(definition);
    }

    @Test
    void findWithContextDelegatesToFindThenInContext() {
        RecordingPage page = new RecordingPage(new StubElement());
        InteractionContext context = InteractionContext.context().containingText("Shipping");

        IFind<IElement> result = page.find(context);

        assertThat(result).isSameAs(page.recordingFind);
        assertThat(page.recordingFind.lastScope).isSameAs(context);
    }

    /** Minimal {@link IPage} test double recording the calls its default methods make. */
    private static final class RecordingPage implements IPage {

        private final IElement element;
        private final RecordingFind recordingFind = new RecordingFind();
        private LocatorDefinition lastLocateDefinition;

        RecordingPage(IElement element) {
            this.element = element;
        }

        @Override
        public String url() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String title() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void navigate(String url) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void reload() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void goBack() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void goForward() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String content() {
            throw new UnsupportedOperationException();
        }

        @Override
        public byte[] screenshot() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Object evaluate(String expression) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Observation observe() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Observation observe(ObservationOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public PageSnapshot captureObservation(ObservationOptions options) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IFind<IElement> find() {
            return recordingFind;
        }

        @Override
        public IFind<IElement> find(LocatorConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public LocatorResult locate(LocatorDefinition definition) {
            lastLocateDefinition = definition;
            return result(definition, element);
        }

        @Override
        public LocatorResult locate(LocatorDefinition definition, LocatorConfig config) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IActionBuilder action() {
            throw new UnsupportedOperationException();
        }

        @Override
        public IFrameLocator frame() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
            throw new UnsupportedOperationException();
        }
    }

    /** Minimal {@link IFind} test double recording which scope {@code inContext} narrowed with. */
    private static final class RecordingFind implements IFind<IElement> {

        private ILocatorScope<IElement> lastScope;

        @Override
        public IFind<IElement> within(IElement scope) {
            throw new UnsupportedOperationException();
        }

        @Override
        public IFind<IElement> within(ILocatorScope<IElement> scope) {
            lastScope = scope;
            return this;
        }

        @Override
        public ILocator<IElement> element() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> role(ElementRole role) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> link() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> button() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> textbox() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> searchbox() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> checkbox() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> radio() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> select() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> option() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> heading() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> form() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> table() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> list() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> image() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> banner() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> navigation() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> main() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> search() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> region() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> complementary() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> contentInfo() {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> placeholder(String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> text(String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> title(String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> altText(String text) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> id(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> nameAttribute(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> attribute(String name, String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> testId(String value) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> css(String selector) {
            throw new UnsupportedOperationException();
        }

        @Override
        public ILocator<IElement> xpath(String expression) {
            throw new UnsupportedOperationException();
        }
    }

    /** Minimal {@link IElement} test double; only identity ever matters in this test. */
    private static final class StubElement implements IElement {

        @Override
        public ElementRole role() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String accessibleName() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String text() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String tagName() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Map<String, String> attributes() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean visible() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean enabled() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<BoundingBox> boundingBox() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void click() {
            throw new UnsupportedOperationException();
        }
    }

    private static LocatorResult result(LocatorDefinition definition, IElement element) {
        LocatorDiagnostics diagnostics =
                new LocatorDiagnostics(
                        definition,
                        LocatorResolutionPolicy.BALANCED,
                        LocatorDiagnosticsLevel.BASIC,
                        List.of("Page"),
                        List.of(),
                        List.of(),
                        1,
                        0,
                        0,
                        List.of(),
                        1,
                        0,
                        Optional.empty(),
                        Duration.ZERO,
                        false,
                        Set.of(),
                        Optional.empty(),
                        List.of());
        return new LocatorResult(
                definition,
                element,
                LocatorStrategyType.ACCESSIBLE_NAME,
                1.0,
                1.0,
                true,
                List.of(),
                diagnostics);
    }
}
