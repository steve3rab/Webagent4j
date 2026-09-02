package io.webagent4j.action.internal;

import io.webagent4j.action.ActionIdempotency;
import io.webagent4j.action.ActionSideEffect;
import io.webagent4j.action.ActionType;
import io.webagent4j.action.DownloadCollisionPolicy;
import io.webagent4j.action.DownloadedFile;
import io.webagent4j.action.IActionBuilder;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.IPreparedAction;
import io.webagent4j.action.KeyPress;
import io.webagent4j.action.Secret;
import io.webagent4j.action.Selection;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementReference;
import io.webagent4j.locator.api.IElementReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/** Internal action command factory; public only for cross-module page composition. */
public final class DefaultActionBuilder implements IActionBuilder {

    private final IActionContext context;

    /** Creates a command factory bound to one backend-neutral page context. */
    public DefaultActionBuilder(IActionContext context) {
        this.context = Objects.requireNonNull(context, "context");
    }

    @Override
    public IPreparedAction<Void> click(IElement element) {
        return click(fixed(element));
    }

    @Override
    public IPreparedAction<Void> click(IElementReference<IElement> reference) {
        return target(
                ActionType.CLICK,
                reference,
                ActionIdempotency.NON_IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                (backend, element) -> {
                    backend.click(element);
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> click(ElementReference reference) {
        return click(Objects.requireNonNull(reference, "reference").bind(context));
    }

    @Override
    public IPreparedAction<Void> doubleClick(IElementReference<IElement> reference) {
        return target(
                ActionType.DOUBLE_CLICK,
                reference,
                ActionIdempotency.NON_IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                (backend, element) -> {
                    backend.doubleClick(element);
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> type(IElement element, String value) {
        Objects.requireNonNull(value, "value");
        return this.<Void>target(
                ActionType.TYPE,
                fixed(element),
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                (backend, target) -> {
                    backend.fill(target, value);
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> type(IElementReference<IElement> reference, String value) {
        Objects.requireNonNull(value, "value");
        return target(
                ActionType.TYPE,
                reference,
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                (backend, target) -> {
                    backend.fill(target, value);
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> typeSecret(IElement element, Secret value) {
        Objects.requireNonNull(value, "value");
        return this.<Void>target(
                        ActionType.TYPE,
                        fixed(element),
                        ActionIdempotency.IDEMPOTENT,
                        ActionSideEffect.LOCAL_PAGE_STATE,
                        (backend, target) -> {
                            backend.fillSecret(target, value);
                            return null;
                        })
                .sensitive();
    }

    @Override
    public IPreparedAction<Void> typeSequentially(IElement element, String value) {
        Objects.requireNonNull(value, "value");
        return this.<Void>target(
                ActionType.TYPE_SEQUENCE,
                fixed(element),
                ActionIdempotency.NON_IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                (backend, target) -> {
                    backend.typeSequentially(target, value);
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> typeSequentially(
            IElementReference<IElement> reference, String value) {
        Objects.requireNonNull(value, "value");
        return target(
                ActionType.TYPE_SEQUENCE,
                reference,
                ActionIdempotency.NON_IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                (backend, target) -> {
                    backend.typeSequentially(target, value);
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> typeSequentiallySecret(IElement element, Secret value) {
        Objects.requireNonNull(value, "value");
        return this.<Void>target(
                        ActionType.TYPE_SEQUENCE,
                        fixed(element),
                        ActionIdempotency.NON_IDEMPOTENT,
                        ActionSideEffect.LOCAL_PAGE_STATE,
                        (backend, target) -> {
                            backend.typeSequentiallySecret(target, value);
                            return null;
                        })
                .sensitive();
    }

    @Override
    public IPreparedAction<Void> clear(IElement element) {
        return target(
                ActionType.CLEAR,
                fixed(element),
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                (backend, target) -> {
                    backend.clear(target);
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> selectByValue(IElement element, String value) {
        return select(element, Selection.byValue(value));
    }

    @Override
    public IPreparedAction<Void> selectByLabel(IElement element, String label) {
        return select(element, Selection.byLabel(label));
    }

    @Override
    public IPreparedAction<Void> selectByIndex(IElement element, int index) {
        return select(element, Selection.byIndex(index));
    }

    @Override
    public IPreparedAction<Void> check(IElement element) {
        return target(
                ActionType.CHECK,
                fixed(element),
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                (backend, target) -> {
                    backend.check(target);
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> uncheck(IElement element) {
        return target(
                ActionType.UNCHECK,
                fixed(element),
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                (backend, target) -> {
                    backend.uncheck(target);
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> focus(IElement element) {
        return targetVoid(ActionType.FOCUS, element, (backend, target) -> backend.focus(target));
    }

    @Override
    public IPreparedAction<Void> blur(IElement element) {
        return targetVoid(ActionType.BLUR, element, (backend, target) -> backend.blur(target));
    }

    @Override
    public IPreparedAction<Void> hover(IElement element) {
        return targetVoid(ActionType.HOVER, element, (backend, target) -> backend.hover(target));
    }

    @Override
    public IPreparedAction<Void> scrollTo(IElement element) {
        return targetVoid(
                ActionType.SCROLL, element, (backend, target) -> backend.scrollTo(target));
    }

    @Override
    public IPreparedAction<Void> scrollBy(int horizontal, int vertical) {
        return page(
                ActionType.SCROLL,
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                backend -> {
                    backend.scrollBy(horizontal, vertical);
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> scrollTop() {
        return page(
                ActionType.SCROLL,
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                backend -> {
                    backend.scrollTop();
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> scrollBottom() {
        return page(
                ActionType.SCROLL,
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                backend -> {
                    backend.scrollBottom();
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> submit(IElement form) {
        return target(
                ActionType.SUBMIT,
                fixed(form),
                ActionIdempotency.NON_IDEMPOTENT,
                ActionSideEffect.EXTERNAL_SIDE_EFFECT,
                (backend, target) -> {
                    backend.submit(target);
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> pressKey(KeyPress keyPress) {
        Objects.requireNonNull(keyPress, "keyPress");
        return page(
                ActionType.PRESS_KEY,
                ActionIdempotency.NON_IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                backend -> {
                    backend.press(null, keyPress);
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> pressKey(IElement element, KeyPress keyPress) {
        Objects.requireNonNull(keyPress, "keyPress");
        return target(
                ActionType.PRESS_KEY,
                fixed(element),
                ActionIdempotency.NON_IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                (backend, target) -> {
                    backend.press(target, keyPress);
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> navigate(String url) {
        Objects.requireNonNull(url, "url");
        return new DefaultPreparedAction<>(
                context,
                new ActionCommand<>(
                        ActionType.NAVIGATE,
                        ActionIdempotency.CONDITIONALLY_IDEMPOTENT,
                        ActionSideEffect.NAVIGATION,
                        null,
                        null,
                        backend -> {
                            backend.navigate(url);
                            return null;
                        },
                        java.util.Optional.of(url)));
    }

    @Override
    public IPreparedAction<Void> reload() {
        return page(
                ActionType.RELOAD,
                ActionIdempotency.CONDITIONALLY_IDEMPOTENT,
                ActionSideEffect.NAVIGATION,
                backend -> {
                    backend.reload();
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> goBack() {
        return page(
                ActionType.GO_BACK,
                ActionIdempotency.NON_IDEMPOTENT,
                ActionSideEffect.NAVIGATION,
                backend -> {
                    backend.goBack();
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> goForward() {
        return page(
                ActionType.GO_FORWARD,
                ActionIdempotency.NON_IDEMPOTENT,
                ActionSideEffect.NAVIGATION,
                backend -> {
                    backend.goForward();
                    return null;
                });
    }

    @Override
    public IPreparedAction<Void> upload(IElement input, Path file) {
        return upload(input, List.of(file));
    }

    @Override
    public IPreparedAction<Void> upload(IElement input, List<Path> files) {
        List<Path> validated = validateFiles(files);
        return target(
                ActionType.UPLOAD,
                fixed(input),
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                (backend, target) -> {
                    backend.upload(target, validated);
                    return null;
                });
    }

    @Override
    public IPreparedAction<DownloadedFile> download(IElement trigger, Path destination) {
        return download(trigger, destination, DownloadCollisionPolicy.FAIL);
    }

    @Override
    public IPreparedAction<DownloadedFile> download(
            IElement trigger, Path destination, DownloadCollisionPolicy collisionPolicy) {
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(collisionPolicy, "collisionPolicy");
        return target(
                ActionType.DOWNLOAD,
                fixed(trigger),
                ActionIdempotency.NON_IDEMPOTENT,
                ActionSideEffect.EXTERNAL_SIDE_EFFECT,
                (backend, target) -> backend.download(target, destination, collisionPolicy));
    }

    @Override
    public IPreparedAction<Void> waitFor(Duration duration) {
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
        return page(
                ActionType.WAIT,
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.NONE,
                backend -> {
                    backend.waitFor(duration);
                    return null;
                });
    }

    private IPreparedAction<Void> select(IElement element, Selection selection) {
        return target(
                ActionType.SELECT,
                fixed(element),
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                (backend, target) -> {
                    backend.select(target, selection);
                    return null;
                });
    }

    private IPreparedAction<Void> targetVoid(
            ActionType type, IElement element, ITargetVoidOperation operation) {
        return target(
                type,
                fixed(element),
                ActionIdempotency.IDEMPOTENT,
                ActionSideEffect.LOCAL_PAGE_STATE,
                (backend, target) -> {
                    operation.execute(backend, target);
                    return null;
                });
    }

    private <R> DefaultPreparedAction<R> target(
            ActionType type,
            IElementReference<IElement> target,
            ActionIdempotency idempotency,
            ActionSideEffect sideEffect,
            ITargetOperation<R> operation) {
        return new DefaultPreparedAction<>(
                context,
                new ActionCommand<>(
                        type,
                        idempotency,
                        sideEffect,
                        Objects.requireNonNull(target, "target"),
                        operation,
                        null,
                        java.util.Optional.empty()));
    }

    private <R> DefaultPreparedAction<R> page(
            ActionType type,
            ActionIdempotency idempotency,
            ActionSideEffect sideEffect,
            IPageOperation<R> operation) {
        return new DefaultPreparedAction<>(
                context,
                new ActionCommand<>(
                        type,
                        idempotency,
                        sideEffect,
                        null,
                        null,
                        operation,
                        java.util.Optional.empty()));
    }

    private static IElementReference<IElement> fixed(IElement element) {
        IElement value = Objects.requireNonNull(element, "element");
        return () -> value;
    }

    private static List<Path> validateFiles(List<Path> files) {
        List<Path> result = List.copyOf(Objects.requireNonNull(files, "files"));
        if (result.isEmpty()) {
            throw new IllegalArgumentException("files cannot be empty");
        }
        for (Path file : result) {
            Objects.requireNonNull(file, "file");
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new IllegalArgumentException("upload file must be a readable regular file");
            }
        }
        return result;
    }
}
