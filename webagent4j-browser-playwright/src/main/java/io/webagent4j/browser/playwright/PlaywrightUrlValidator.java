package io.webagent4j.browser.playwright;

import io.webagent4j.common.BrowserException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Shared absolute-HTTP(S)-only URL validation, used identically by top-level page navigation and
 * frame navigation: {@code javascript:}, {@code file:}, and {@code data:} URLs are rejected in both
 * cases, and neither surface broadens what the other accepts.
 */
final class PlaywrightUrlValidator {

    private static final Set<String> ALLOWED_SCHEMES = Set.of("http", "https");

    private PlaywrightUrlValidator() {
        // not instantiable
    }

    /** Validates that {@code url} is an absolute HTTP(S) URL, throwing otherwise. */
    static void requireAbsoluteHttp(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("url cannot be blank");
        }
        try {
            URI uri = new URI(url);
            if (!uri.isAbsolute()
                    || !ALLOWED_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("only absolute HTTP(S) URLs are supported");
            }
        } catch (URISyntaxException exception) {
            throw new BrowserException("Invalid navigation URL: " + url, exception);
        }
    }
}
