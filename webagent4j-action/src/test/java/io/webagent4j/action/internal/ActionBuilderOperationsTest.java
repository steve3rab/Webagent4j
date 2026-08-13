package io.webagent4j.action.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.webagent4j.action.DownloadCollisionPolicy;
import io.webagent4j.action.DownloadedFile;
import io.webagent4j.action.IActionBackend;
import io.webagent4j.action.IActionContext;
import io.webagent4j.action.KeyModifier;
import io.webagent4j.action.KeyPress;
import io.webagent4j.action.PortableKey;
import io.webagent4j.action.Secret;
import io.webagent4j.dom.ElementState;
import io.webagent4j.dom.IElement;
import io.webagent4j.locator.api.ElementRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ActionBuilderOperationsTest {

    @Test
    void delegatesEverySupportedTargetPrimitiveThroughThePipeline() throws Exception {
        IActionBackend backend = mock(IActionBackend.class);
        IElement target = element(ElementRole.TEXTBOX, false);
        IElement checked = element(ElementRole.CHECKBOX, true);
        Path upload = Files.createTempFile("action-unit-upload-", ".txt");
        try {
            when(backend.download(any(), any(), any()))
                    .thenReturn(
                            new DownloadedFile(
                                    "file.txt", Path.of("file.txt"), 4, Optional.of("text/plain")));
            var builder = new DefaultActionBuilder(context(backend));

            assertSuccess(builder.doubleClick(() -> target).execute());
            assertSuccess(builder.type(target, "value").execute());
            assertSuccess(builder.type(() -> target, "value").execute());
            assertSuccess(builder.typeSecret(target, Secret.of("secret")).execute());
            assertSuccess(builder.clear(target).execute());
            assertSuccess(builder.selectByValue(target, "fr").execute());
            assertSuccess(builder.selectByLabel(target, "France").execute());
            assertSuccess(builder.selectByIndex(target, 0).execute());
            assertSuccess(builder.check(checked).execute());
            assertSuccess(builder.uncheck(target).execute());
            assertSuccess(builder.focus(target).execute());
            assertSuccess(builder.blur(target).execute());
            assertSuccess(builder.hover(target).execute());
            assertSuccess(builder.scrollTo(target).execute());
            assertSuccess(builder.submit(target).execute());
            assertSuccess(
                    builder.pressKey(target, KeyPress.of(PortableKey.A, KeyModifier.CONTROL))
                            .execute());
            assertSuccess(builder.upload(target, upload).execute());
            assertThat(
                            builder.download(
                                            target,
                                            Path.of("download.txt"),
                                            DownloadCollisionPolicy.RENAME)
                                    .execute()
                                    .value())
                    .isNotNull();
        } finally {
            Files.deleteIfExists(upload);
        }
    }

    @Test
    void delegatesEverySupportedPagePrimitiveThroughThePipeline() {
        var builder = new DefaultActionBuilder(context(mock(IActionBackend.class)));
        assertSuccess(builder.scrollBy(1, 2).execute());
        assertSuccess(builder.scrollTop().execute());
        assertSuccess(builder.scrollBottom().execute());
        assertSuccess(builder.pressKey(KeyPress.of(PortableKey.ENTER)).execute());
        assertSuccess(builder.navigate("https://example.test").execute());
        assertSuccess(builder.reload().execute());
        assertSuccess(builder.goBack().execute());
        assertSuccess(builder.goForward().execute());
        assertSuccess(builder.waitFor(Duration.ZERO).execute());
    }

    private static void assertSuccess(io.webagent4j.action.ActionResult<?> result) {
        assertThat(result.success()).isTrue();
    }

    private static IActionContext context(IActionBackend backend) {
        return new IActionContext() {
            @Override
            public String url() {
                return "https://example.test";
            }

            @Override
            public String title() {
                return "Example";
            }

            @Override
            public IActionBackend actionBackend() {
                return backend;
            }
        };
    }

    private static IElement element(ElementRole role, boolean checked) {
        IElement element = mock(IElement.class);
        when(element.role()).thenReturn(role);
        when(element.accessibleName()).thenReturn("Target");
        when(element.state())
                .thenReturn(
                        new ElementState(
                                true, true, true, true, false, checked, true, true, true, true,
                                false, true));
        return element;
    }
}
