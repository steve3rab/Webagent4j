package io.webagent4j.locator;

import io.webagent4j.dom.BoundingBox;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

final class LocatorTestFixtures {

    private LocatorTestFixtures() {}

    static TestElement element(ElementRole role, String name) {
        return new TestElement(role, name, name, "button", Map.of(), true, true);
    }

    record TestElement(
            ElementRole role,
            String accessibleName,
            String text,
            String tagName,
            Map<String, String> attributes,
            boolean visible,
            boolean enabled)
            implements IElement {

        TestElement {
            attributes = Map.copyOf(attributes);
        }

        @Override
        public Optional<BoundingBox> boundingBox() {
            return Optional.of(new BoundingBox(0, 0, 10, 10));
        }

        @Override
        public ElementState state() {
            boolean readOnly = attributes.containsKey("readonly");
            boolean editable =
                    enabled
                            && !readOnly
                            && (tagName.equals("input")
                                    || tagName.equals("textarea")
                                    || attributes.containsKey("contenteditable"));
            return new ElementState(
                    true,
                    visible,
                    enabled,
                    editable,
                    readOnly,
                    attributes.containsKey("checked"),
                    attributes.containsKey("selected"),
                    attributes.containsKey("data-focused"),
                    !attributes.containsKey("data-outside-viewport"),
                    false,
                    attributes.containsKey("data-covered"),
                    false);
        }

        @Override
        public void click() {}
    }

    static final class FakeBackend implements ILocatorBackend {

        private final List<IElement> pageElements;
        private final List<IElement> scopedElements;
        private final List<LocatorBackendQuery> queries = new ArrayList<>();

        FakeBackend(List<IElement> pageElements) {
            this(pageElements, List.of());
        }

        FakeBackend(List<IElement> pageElements, List<IElement> scopedElements) {
            this.pageElements = List.copyOf(pageElements);
            this.scopedElements = List.copyOf(scopedElements);
        }

        @Override
        public LocatorBackendSearchResult find(
                LocatorBackendQuery query,
                LocatorScope scope,
                LocatorConfig config,
                Duration timeout,
                int candidateLimit) {
            queries.add(query);
            List<IElement> source = scope.root().isPresent() ? scopedElements : pageElements;
            List<LocatorBackendCandidate> result = new ArrayList<>();
            for (int index = 0; index < source.size(); index++) {
                IElement element = source.get(index);
                if (matchesBackendQuery(query, element)) {
                    result.add(new LocatorBackendCandidate("candidate-" + index, element, index));
                }
            }
            int count = result.size();
            List<LocatorBackendCandidate> bounded =
                    result.subList(0, Math.min(count, candidateLimit));
            return new LocatorBackendSearchResult(bounded, count, count > bounded.size());
        }

        List<LocatorBackendQuery> queries() {
            return List.copyOf(queries);
        }

        private static boolean matchesBackendQuery(LocatorBackendQuery query, IElement element) {
            return switch (query.strategy()) {
                case ID -> query.value().orElseThrow().equals(element.attributes().get("id"));
                case NAME_ATTRIBUTE ->
                        query.value().orElseThrow().equals(element.attributes().get("name"));
                case ATTRIBUTE ->
                        query.value()
                                .orElseThrow()
                                .equals(
                                        element.attributes()
                                                .get(query.attributeName().orElseThrow()));
                case TEST_ID ->
                        query.value()
                                .orElseThrow()
                                .equals(element.attributes().getOrDefault("data-testid", ""));
                default -> true;
            };
        }
    }
}
