package io.webagent4j.action;

import io.webagent4j.dom.IElement;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Backend-neutral primitive operations used exclusively by the action pipeline. */
public interface IActionBackend {

    /** Performs a normal actionable click. */
    void click(IElement element);

    /** Performs a normal actionable double click. */
    void doubleClick(IElement element);

    /** Replaces the current editable value. */
    void fill(IElement element, String value);

    /** Replaces the current editable value without exposing the secret to diagnostics. */
    void fillSecret(IElement element, Secret value);

    /** Clears the current editable value. */
    void clear(IElement element);

    /** Selects one option. */
    void select(IElement element, Selection selection);

    /** Idempotently checks a checkbox or activates a radio. */
    void check(IElement element);

    /** Idempotently unchecks a checkbox. */
    void uncheck(IElement element);

    /** Focuses an element. */
    void focus(IElement element);

    /** Removes focus from an element. */
    void blur(IElement element);

    /** Hovers over an element. */
    void hover(IElement element);

    /** Scrolls an element into view. */
    void scrollTo(IElement element);

    /** Scrolls the page by pixel offsets. */
    void scrollBy(int horizontal, int vertical);

    /** Scrolls to the top of the page. */
    void scrollTop();

    /** Scrolls to the bottom of the page. */
    void scrollBottom();

    /** Submits a form without assuming a submit button. */
    void submit(IElement form);

    /** Presses a portable key on the page or focused element. */
    void press(IElement element, KeyPress keyPress);

    /** Navigates to an absolute HTTP(S) URL. */
    void navigate(String url);

    /** Reloads the current document. */
    void reload();

    /** Navigates backward in history. */
    void goBack();

    /** Navigates forward in history. */
    void goForward();

    /** Uploads validated regular files. */
    void upload(IElement element, List<Path> files);

    /** Downloads after one trigger action and saves according to collision policy. */
    DownloadedFile download(
            IElement element, Path destination, DownloadCollisionPolicy collisionPolicy);

    /** Delegates explicit waits to the established backend wait implementation. */
    void waitFor(Duration duration);
}
