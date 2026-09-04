package io.webagent4j.browser.playwright;

import com.microsoft.playwright.Download;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.SelectOption;
import io.webagent4j.action.DownloadCollisionPolicy;
import io.webagent4j.action.DownloadedFile;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.KeyModifier;
import io.webagent4j.action.KeyPress;
import io.webagent4j.action.Secret;
import io.webagent4j.action.Selection;
import io.webagent4j.browser.BrowserOptions;
import io.webagent4j.dom.IElement;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Playwright implementation of backend-neutral Phase 4 action primitives.
 *
 * <p>Every side-effecting method binds to the exact physical target governed execution just
 * verified, when one was verified: {@link PlaywrightElement#verifiedHandle()} exposes the same
 * {@link ElementHandle} identity verification captured and checked, so the native operation acts on
 * precisely that node - never on a second, independently re-resolved {@link Locator} lookup that
 * could silently observe a different physical node satisfying the same locator between the check
 * and the use. An element with no verified handle falls back to the ordinary, re-resolving {@link
 * Locator} path unchanged - see {@link #bindToVerifiedTarget}. In practice this only ever happens
 * for an ungoverned resolution: a governed resolution with no captured identity to verify fails
 * closed one layer up, in {@link PlaywrightElement#verifiedForExecution()}, so this class's methods
 * are never even invoked for it.
 */
final class PlaywrightActionBackend implements IActionBackend {

    private final Page page;
    private final BrowserOptions options;

    PlaywrightActionBackend(Page page, BrowserOptions options) {
        this.page = Objects.requireNonNull(page, "page");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public void click(IElement element) {
        bindToVerifiedTarget(element, ElementHandle::click, Locator::click);
    }

    @Override
    public void doubleClick(IElement element) {
        bindToVerifiedTarget(element, ElementHandle::dblclick, Locator::dblclick);
    }

    @Override
    public void fill(IElement element, String value) {
        bindToVerifiedTarget(element, handle -> handle.fill(value), locator -> locator.fill(value));
    }

    @Override
    public void fillSecret(IElement element, Secret value) {
        value.use(
                secret -> {
                    bindToVerifiedTarget(
                            element,
                            handle -> handle.fill(secret),
                            locator -> locator.fill(secret));
                    return null;
                });
    }

    @Override
    public void typeSequentially(IElement element, String value) {
        bindToVerifiedTarget(
                element, handle -> handle.type(value), locator -> locator.pressSequentially(value));
    }

    @Override
    public void typeSequentiallySecret(IElement element, Secret value) {
        value.use(
                secret -> {
                    bindToVerifiedTarget(
                            element,
                            handle -> handle.type(secret),
                            locator -> locator.pressSequentially(secret));
                    return null;
                });
    }

    @Override
    public void clear(IElement element) {
        // ElementHandle has no dedicated clear() operation, only Locator does - but Locator#clear
        // is itself implemented as a fill with an empty string at the protocol level, exactly what
        // ElementHandle#fill("") performs directly, so this is the same physical operation on the
        // exact verified node rather than a weaker substitute.
        bindToVerifiedTarget(element, handle -> handle.fill(""), Locator::clear);
    }

    @Override
    public void select(IElement element, Selection selection) {
        SelectOption option =
                switch (selection.type()) {
                    case VALUE -> new SelectOption().setValue(selection.value());
                    case LABEL -> new SelectOption().setLabel(selection.value());
                    case INDEX -> new SelectOption().setIndex(selection.index());
                };
        bindToVerifiedTarget(
                element,
                handle -> handle.selectOption(option),
                locator -> locator.selectOption(option));
    }

    @Override
    public void check(IElement element) {
        bindToVerifiedTarget(element, ElementHandle::check, Locator::check);
    }

    @Override
    public void uncheck(IElement element) {
        bindToVerifiedTarget(element, ElementHandle::uncheck, Locator::uncheck);
    }

    @Override
    public void focus(IElement element) {
        bindToVerifiedTarget(element, ElementHandle::focus, Locator::focus);
    }

    @Override
    public void blur(IElement element) {
        // ElementHandle has no dedicated blur() operation either; invoking the same native DOM
        // blur() directly on the exact verified node is the equivalent atomic operation.
        bindToVerifiedTarget(
                element, handle -> handle.evaluate("element => element.blur()"), Locator::blur);
    }

    @Override
    public void hover(IElement element) {
        bindToVerifiedTarget(element, ElementHandle::hover, Locator::hover);
    }

    @Override
    public void scrollTo(IElement element) {
        bindToVerifiedTarget(
                element, ElementHandle::scrollIntoViewIfNeeded, Locator::scrollIntoViewIfNeeded);
    }

    @Override
    public void scrollBy(int horizontal, int vertical) {
        page.evaluate("([x, y]) => window.scrollBy(x, y)", List.of(horizontal, vertical));
    }

    @Override
    public void scrollTop() {
        page.evaluate("() => window.scrollTo(0, 0)");
    }

    @Override
    public void scrollBottom() {
        page.evaluate("() => window.scrollTo(0, document.documentElement.scrollHeight)");
    }

    @Override
    public void submit(IElement form) {
        String script =
                "element => element.requestSubmit ? element.requestSubmit() : element.submit()";
        bindToVerifiedTarget(
                form, handle -> handle.evaluate(script), locator -> locator.evaluate(script));
    }

    @Override
    public void press(IElement element, KeyPress keyPress) {
        String key = key(keyPress);
        if (element == null) {
            page.keyboard().press(key);
        } else {
            bindToVerifiedTarget(
                    element, handle -> handle.press(key), locator -> locator.press(key));
        }
    }

    @Override
    public void navigate(String url) {
        page.navigate(
                url,
                new Page.NavigateOptions()
                        .setTimeout((double) options.timeouts().navigation().toMillis()));
    }

    @Override
    public void reload() {
        page.reload();
    }

    @Override
    public void goBack() {
        page.goBack();
    }

    @Override
    public void goForward() {
        page.goForward();
    }

    @Override
    public void upload(IElement element, List<Path> files) {
        Path[] filesArray = files.toArray(Path[]::new);
        bindToVerifiedTarget(
                element,
                handle -> handle.setInputFiles(filesArray),
                locator -> locator.setInputFiles(filesArray));
    }

    @Override
    public DownloadedFile download(
            IElement element, Path destination, DownloadCollisionPolicy collisionPolicy) {
        Download download =
                page.waitForDownload(
                        () -> bindToVerifiedTarget(element, ElementHandle::click, Locator::click));
        String suggested = download.suggestedFilename();
        Path requested =
                Files.isDirectory(destination) ? destination.resolve(suggested) : destination;
        Path saved = collisionSafePath(requested.toAbsolutePath().normalize(), collisionPolicy);
        try {
            Path parent = saved.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            if (collisionPolicy == DownloadCollisionPolicy.REPLACE) {
                Files.deleteIfExists(saved);
            }
            download.saveAs(saved);
            return new DownloadedFile(
                    suggested,
                    saved,
                    Files.size(saved),
                    Optional.ofNullable(Files.probeContentType(saved)));
        } catch (IOException exception) {
            throw new IllegalStateException("Download could not be saved", exception);
        }
    }

    @Override
    public void waitFor(Duration duration) {
        page.waitForTimeout(duration.toMillis());
    }

    /**
     * Performs one native operation against {@code element}, binding to its exact identity-verified
     * physical handle when {@link PlaywrightElement#verifiedHandle()} has one, and to the ordinary
     * re-resolving {@link Locator} otherwise.
     *
     * <p>This is the single mechanism every side-effecting method in this class routes through, so
     * a target policy-driven identity verification just reproved is never handed off to a second,
     * independent {@link Locator} resolution for the actual native call - the residual
     * time-of-check-to-time-of-use window a caller combining a boolean identity check with a
     * separate native call would otherwise reopen. An element that never captured a verified handle
     * takes the exact same {@link Locator} path this class always took, so an ungoverned action's
     * behavior and cost are completely unchanged. A governed resolution that had nothing to verify
     * an identity against (no captured identity) or that could not reprove it never reaches this
     * method at all - {@link PlaywrightElement#verifiedForExecution()} fails closed one layer up,
     * before the backend is ever invoked.
     */
    private static void bindToVerifiedTarget(
            IElement element,
            Consumer<ElementHandle> viaVerifiedHandle,
            Consumer<Locator> viaLocator) {
        PlaywrightElement target = asPlaywrightElement(element);
        Optional<ElementHandle> verifiedHandle = target.verifiedHandle();
        if (verifiedHandle.isPresent()) {
            viaVerifiedHandle.accept(verifiedHandle.get());
        } else {
            viaLocator.accept(target.locator());
        }
    }

    private static PlaywrightElement asPlaywrightElement(IElement element) {
        if (element instanceof PlaywrightElement playwrightElement) {
            return playwrightElement;
        }
        throw new IllegalArgumentException("Element does not belong to the Playwright backend");
    }

    private static String key(KeyPress keyPress) {
        List<String> parts = new ArrayList<>();
        keyPress.modifiers().stream()
                .sorted()
                .map(PlaywrightActionBackend::modifier)
                .forEach(parts::add);
        parts.add(
                switch (keyPress.key()) {
                    case ENTER -> "Enter";
                    case TAB -> "Tab";
                    case ESCAPE -> "Escape";
                    case SPACE -> " ";
                    case ARROW_UP -> "ArrowUp";
                    case ARROW_DOWN -> "ArrowDown";
                    case ARROW_LEFT -> "ArrowLeft";
                    case ARROW_RIGHT -> "ArrowRight";
                    case HOME -> "Home";
                    case END -> "End";
                    case PAGE_UP -> "PageUp";
                    case PAGE_DOWN -> "PageDown";
                    case BACKSPACE -> "Backspace";
                    case DELETE -> "Delete";
                    case A -> "A";
                    case C -> "C";
                    case V -> "V";
                });
        return String.join("+", parts);
    }

    private static String modifier(KeyModifier value) {
        return switch (value) {
            case CONTROL -> "Control";
            case ALT -> "Alt";
            case SHIFT -> "Shift";
            case META -> "Meta";
        };
    }

    private static Path collisionSafePath(Path requested, DownloadCollisionPolicy policy) {
        if (!Files.exists(requested)) {
            return requested;
        }
        if (policy == DownloadCollisionPolicy.FAIL) {
            throw new IllegalStateException("Download destination already exists");
        }
        if (policy == DownloadCollisionPolicy.REPLACE) {
            return requested;
        }
        String name = requested.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String suffix = dot > 0 ? name.substring(dot) : "";
        for (int index = 1; index < Integer.MAX_VALUE; index++) {
            Path candidate = requested.resolveSibling(stem + " (" + index + ")" + suffix);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No collision-free download path is available");
    }
}
