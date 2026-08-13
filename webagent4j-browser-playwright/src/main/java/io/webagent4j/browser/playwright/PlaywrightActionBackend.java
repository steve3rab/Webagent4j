package io.webagent4j.browser.playwright;

import com.microsoft.playwright.Download;
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

/** Playwright implementation of backend-neutral Phase 4 action primitives. */
final class PlaywrightActionBackend implements IActionBackend {

    private final Page page;
    private final BrowserOptions options;

    PlaywrightActionBackend(Page page, BrowserOptions options) {
        this.page = Objects.requireNonNull(page, "page");
        this.options = Objects.requireNonNull(options, "options");
    }

    @Override
    public void click(IElement element) {
        locator(element).click();
    }

    @Override
    public void doubleClick(IElement element) {
        locator(element).dblclick();
    }

    @Override
    public void fill(IElement element, String value) {
        locator(element).fill(value);
    }

    @Override
    public void fillSecret(IElement element, Secret value) {
        value.use(
                secret -> {
                    locator(element).fill(secret);
                    return null;
                });
    }

    @Override
    public void clear(IElement element) {
        locator(element).clear();
    }

    @Override
    public void select(IElement element, Selection selection) {
        SelectOption option =
                switch (selection.type()) {
                    case VALUE -> new SelectOption().setValue(selection.value());
                    case LABEL -> new SelectOption().setLabel(selection.value());
                    case INDEX -> new SelectOption().setIndex(selection.index());
                };
        locator(element).selectOption(option);
    }

    @Override
    public void check(IElement element) {
        locator(element).check();
    }

    @Override
    public void uncheck(IElement element) {
        locator(element).uncheck();
    }

    @Override
    public void focus(IElement element) {
        locator(element).focus();
    }

    @Override
    public void blur(IElement element) {
        locator(element).blur();
    }

    @Override
    public void hover(IElement element) {
        locator(element).hover();
    }

    @Override
    public void scrollTo(IElement element) {
        locator(element).scrollIntoViewIfNeeded();
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
        locator(form)
                .evaluate(
                        "element => element.requestSubmit"
                                + " ? element.requestSubmit() : element.submit()");
    }

    @Override
    public void press(IElement element, KeyPress keyPress) {
        String key = key(keyPress);
        if (element == null) {
            page.keyboard().press(key);
        } else {
            locator(element).press(key);
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
        locator(element).setInputFiles(files.toArray(Path[]::new));
    }

    @Override
    public DownloadedFile download(
            IElement element, Path destination, DownloadCollisionPolicy collisionPolicy) {
        Download download = page.waitForDownload(() -> locator(element).click());
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

    private static Locator locator(IElement element) {
        if (element instanceof PlaywrightElement playwrightElement) {
            return playwrightElement.locator();
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
