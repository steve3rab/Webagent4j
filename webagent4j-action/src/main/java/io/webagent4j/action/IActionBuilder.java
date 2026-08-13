package io.webagent4j.action;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementReference;
import io.webagent4j.locator.api.IElementReference;

/** Fluent plan for one action followed by deterministic postcondition checks. */
public interface IActionBuilder {

    /** Selects a click command for this plan. */
    IActionBuilder click(IElement element);

    /**
     * Selects a click command that re-resolves its semantic target immediately before execution.
     */
    IActionBuilder click(IElementReference<IElement> reference);

    /**
     * Selects a portable semantic reference resolved against the action context before execution.
     */
    IActionBuilder click(ElementReference reference);

    /** Adds a postcondition requiring the resulting URL to contain the supplied fragment. */
    IActionBuilder expectUrlContains(String expectedFragment);

    /** Executes the selected command and postconditions, returning structured failure details. */
    ActionResult<Void> execute();
}
