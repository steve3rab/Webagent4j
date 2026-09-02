package io.webagent4j.action;

import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementReference;
import io.webagent4j.locator.api.IElementReference;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/** Entry point for defining one immutable browser command before execution. */
public interface IActionBuilder {

    /** Selects a potentially non-idempotent click. */
    IPreparedAction<Void> click(IElement element);

    /** Selects a click using a dynamically re-resolved target. */
    IPreparedAction<Void> click(IElementReference<IElement> reference);

    /** Selects a click using a portable semantic reference. */
    IPreparedAction<Void> click(ElementReference reference);

    /** Selects a potentially non-idempotent double click. */
    IPreparedAction<Void> doubleClick(IElementReference<IElement> reference);

    /** Replaces an editable control value with plain text. */
    IPreparedAction<Void> type(IElement element, String value);

    /** Replaces a dynamically resolved editable control value with plain text. */
    IPreparedAction<Void> type(IElementReference<IElement> reference, String value);

    /** Replaces an editable control value without exposing the sensitive text. */
    IPreparedAction<Void> typeSecret(IElement element, Secret value);

    /**
     * Replaces an editable control's value by dispatching one key event per character, distinct
     * from {@link #type(IElement, String)}, which sets the value with no per-character key events.
     *
     * <p>Added in 1.2.0 as a {@code default} method (like {@link
     * IPreparedAction#policy(io.webagent4j.action.policy.IActionPolicy)}) so adding it never breaks
     * an existing {@link IActionBuilder} implementation: the default always throws {@link
     * UnsupportedOperationException}; only an implementation that actually supports it overrides
     * this method.
     */
    default IPreparedAction<Void> typeSequentially(IElement element, String value) {
        throw new UnsupportedOperationException(
                "typeSequentially(...) is not supported by this IActionBuilder implementation");
    }

    /**
     * Replaces a dynamically resolved editable control's value by dispatching one key event per
     * character, distinct from {@link #type(IElementReference, String)}. See {@link
     * #typeSequentially(IElement, String)} for why this is a {@code default} method.
     */
    default IPreparedAction<Void> typeSequentially(
            IElementReference<IElement> reference, String value) {
        throw new UnsupportedOperationException(
                "typeSequentially(...) is not supported by this IActionBuilder implementation");
    }

    /**
     * Same as {@link #typeSequentially(IElement, String)} without exposing the sensitive text. See
     * {@link #typeSequentially(IElement, String)} for why this is a {@code default} method.
     */
    default IPreparedAction<Void> typeSequentiallySecret(IElement element, Secret value) {
        throw new UnsupportedOperationException(
                "typeSequentiallySecret(...) is not supported by this IActionBuilder"
                        + " implementation");
    }

    /** Clears an editable control. */
    IPreparedAction<Void> clear(IElement element);

    /** Selects an option by submitted value. */
    IPreparedAction<Void> selectByValue(IElement element, String value);

    /** Selects an option by visible label. */
    IPreparedAction<Void> selectByLabel(IElement element, String label);

    /** Selects an option by zero-based index. */
    IPreparedAction<Void> selectByIndex(IElement element, int index);

    /** Idempotently checks a checkbox or activates a radio. */
    IPreparedAction<Void> check(IElement element);

    /** Idempotently unchecks a checkbox. */
    IPreparedAction<Void> uncheck(IElement element);

    /** Focuses an element. */
    IPreparedAction<Void> focus(IElement element);

    /** Removes focus from an element. */
    IPreparedAction<Void> blur(IElement element);

    /** Hovers over an element. */
    IPreparedAction<Void> hover(IElement element);

    /** Scrolls a target into the viewport. */
    IPreparedAction<Void> scrollTo(IElement element);

    /** Scrolls the page by pixel offsets. */
    IPreparedAction<Void> scrollBy(int horizontal, int vertical);

    /** Scrolls to the top of the document. */
    IPreparedAction<Void> scrollTop();

    /** Scrolls to the bottom of the document. */
    IPreparedAction<Void> scrollBottom();

    /** Submits a form without assuming a button implementation. */
    IPreparedAction<Void> submit(IElement form);

    /** Presses a portable key on the focused page element. */
    IPreparedAction<Void> pressKey(KeyPress keyPress);

    /** Presses a portable key on an explicit element. */
    IPreparedAction<Void> pressKey(IElement element, KeyPress keyPress);

    /** Navigates to an absolute HTTP(S) URL. */
    IPreparedAction<Void> navigate(String url);

    /** Reloads the current document. */
    IPreparedAction<Void> reload();

    /** Navigates backward in history. */
    IPreparedAction<Void> goBack();

    /** Navigates forward in history. */
    IPreparedAction<Void> goForward();

    /** Uploads one validated regular file. */
    IPreparedAction<Void> upload(IElement input, Path file);

    /** Uploads validated regular files. */
    IPreparedAction<Void> upload(IElement input, List<Path> files);

    /** Downloads into a destination using the safe default collision policy. */
    IPreparedAction<DownloadedFile> download(IElement trigger, Path destination);

    /** Downloads using an explicit collision policy. */
    IPreparedAction<DownloadedFile> download(
            IElement trigger, Path destination, DownloadCollisionPolicy collisionPolicy);

    /** Waits through the existing backend wait mechanism. */
    IPreparedAction<Void> waitFor(Duration duration);
}
