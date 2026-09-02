package io.webagent4j.action;

import io.webagent4j.dom.IElement;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Backend-neutral primitive operations used exclusively by the action pipeline. */
public interface IActionBackend {

    /** Performs a normal actionable click. */
    default void click(IElement element) {
        element.click();
    }

    /** Performs a normal actionable double click. */
    default void doubleClick(IElement element) {
        throw unsupported();
    }

    /** Replaces the current editable value. */
    default void fill(IElement element, String value) {
        throw unsupported();
    }

    /** Replaces the current editable value without exposing the secret to diagnostics. */
    default void fillSecret(IElement element, Secret value) {
        throw unsupported();
    }

    /**
     * Replaces the current editable value by dispatching one key event per character, distinct from
     * {@link #fill(IElement, String)}, which sets the value with no per-character key events.
     */
    default void typeSequentially(IElement element, String value) {
        throw unsupported();
    }

    /**
     * Same as {@link #typeSequentially(IElement, String)} without exposing the secret to
     * diagnostics.
     */
    default void typeSequentiallySecret(IElement element, Secret value) {
        throw unsupported();
    }

    /** Clears the current editable value. */
    default void clear(IElement element) {
        throw unsupported();
    }

    /** Selects one option. */
    default void select(IElement element, Selection selection) {
        throw unsupported();
    }

    /** Idempotently checks a checkbox or activates a radio. */
    default void check(IElement element) {
        throw unsupported();
    }

    /** Idempotently unchecks a checkbox. */
    default void uncheck(IElement element) {
        throw unsupported();
    }

    /** Focuses an element. */
    default void focus(IElement element) {
        throw unsupported();
    }

    /** Removes focus from an element. */
    default void blur(IElement element) {
        throw unsupported();
    }

    /** Hovers over an element. */
    default void hover(IElement element) {
        throw unsupported();
    }

    /** Scrolls an element into view. */
    default void scrollTo(IElement element) {
        throw unsupported();
    }

    /** Scrolls the page by pixel offsets. */
    default void scrollBy(int horizontal, int vertical) {
        throw unsupported();
    }

    /** Scrolls to the top of the page. */
    default void scrollTop() {
        throw unsupported();
    }

    /** Scrolls to the bottom of the page. */
    default void scrollBottom() {
        throw unsupported();
    }

    /** Submits a form without assuming a submit button. */
    default void submit(IElement form) {
        throw unsupported();
    }

    /** Presses a portable key on the page or focused element. */
    default void press(IElement element, KeyPress keyPress) {
        throw unsupported();
    }

    /** Navigates to an absolute HTTP(S) URL. */
    default void navigate(String url) {
        throw unsupported();
    }

    /** Reloads the current document. */
    default void reload() {
        throw unsupported();
    }

    /** Navigates backward in history. */
    default void goBack() {
        throw unsupported();
    }

    /** Navigates forward in history. */
    default void goForward() {
        throw unsupported();
    }

    /** Uploads validated regular files. */
    default void upload(IElement element, List<Path> files) {
        throw unsupported();
    }

    /** Downloads after one trigger action and saves according to collision policy. */
    default DownloadedFile download(
            IElement element, Path destination, DownloadCollisionPolicy collisionPolicy) {
        throw unsupported();
    }

    /** Delegates explicit waits to the established backend wait implementation. */
    default void waitFor(Duration duration) {
        throw unsupported();
    }

    private static UnsupportedOperationException unsupported() {
        return new UnsupportedOperationException("Action is not supported by this backend");
    }
}
