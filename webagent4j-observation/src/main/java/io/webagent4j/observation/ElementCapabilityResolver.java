package io.webagent4j.observation;

import io.webagent4j.locator.api.ElementRole;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/** Default deterministic resolver for reliable role, state, and type-based capabilities. */
public final class ElementCapabilityResolver implements IElementCapabilityResolver {

    @Override
    public Set<ElementCapability> resolve(SemanticElement element) {
        EnumSet<ElementCapability> result = EnumSet.noneOf(ElementCapability.class);
        if (!element.state().interaction().present() || !element.visible() || !element.enabled()) {
            return Collections.unmodifiableSet(result);
        }
        switch (element.role()) {
            case LINK, BUTTON, MENUITEM, TAB -> {
                result.add(ElementCapability.CLICK);
                result.add(ElementCapability.FOCUS);
            }
            case TEXTBOX, SEARCHBOX -> {
                if (element.state().editable()) {
                    result.add(ElementCapability.TYPE);
                    result.add(ElementCapability.CLEAR);
                }
                result.add(ElementCapability.FOCUS);
            }
            case CHECKBOX, SWITCH -> {
                result.add(ElementCapability.CLICK);
                result.add(ElementCapability.FOCUS);
                result.add(
                        element.state().checked()
                                ? ElementCapability.UNCHECK
                                : ElementCapability.CHECK);
            }
            case RADIO -> {
                result.add(ElementCapability.CLICK);
                result.add(ElementCapability.FOCUS);
                if (!element.state().checked()) {
                    result.add(ElementCapability.CHECK);
                }
            }
            case SELECT, OPTION, SLIDER -> {
                result.add(ElementCapability.SELECT);
                result.add(ElementCapability.FOCUS);
            }
            case SPINBUTTON -> {
                if (element.state().editable()) {
                    result.add(ElementCapability.TYPE);
                    result.add(ElementCapability.CLEAR);
                }
                result.add(ElementCapability.FOCUS);
            }
            default -> {
                // Static structures intentionally have no action capability.
            }
        }
        if (element.role() == ElementRole.BUTTON
                && "submit".equalsIgnoreCase(element.attributes().getOrDefault("type", ""))) {
            result.add(ElementCapability.SUBMIT);
        }
        element.state()
                .expanded()
                .ifPresent(
                        expanded ->
                                result.add(
                                        expanded
                                                ? ElementCapability.COLLAPSE
                                                : ElementCapability.EXPAND));
        return Collections.unmodifiableSet(result);
    }
}
