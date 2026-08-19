package io.webagent4j.examples;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.browser.IFrame;
import io.webagent4j.core.WebAgent;

/**
 * Demonstrates backend-neutral frame navigation: {@link IFrame#navigate(String)} replaces only that
 * frame's own document - the top-level page and any sibling frames are untouched - and normal
 * {@code find()} queries issued afterward search the new document.
 */
public final class FrameNavigationExample {

    private FrameNavigationExample() {}

    /**
     * Runs against a page containing an iframe named "content" and navigates it to a second
     * absolute HTTP(S) URL supplied as the second argument.
     */
    public static void main(String[] args) {
        String pageUrl = requireArgument(args, "page URL", 0);
        String targetUrl = requireArgument(args, "frame navigation URL", 1);
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                var page = browser.open(pageUrl)) {
            IFrame content = page.frame().named("content").single();

            content.navigate(targetUrl);

            System.out.println("Frame now at: " + content.url());
        }
    }

    private static String requireArgument(String[] args, String description, int index) {
        if (args.length <= index || args[index].isBlank()) {
            throw new IllegalArgumentException(
                    "Expected " + description + " as argument " + (index + 1));
        }
        return args[index];
    }
}
