package io.webagent4j.examples;

import io.webagent4j.action.DownloadCollisionPolicy;
import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import java.nio.file.Path;

/** Demonstrates a verified browser download with explicit collision handling. */
public final class DownloadActionExample {

    private DownloadActionExample() {}

    /** Downloads a report to the caller-supplied directory. */
    public static void main(String[] args) {
        if (args.length < 2 || args[0].isBlank() || args[1].isBlank()) {
            throw new IllegalArgumentException("Expected a page URL and destination directory");
        }
        try (IBrowser browser =
                WebAgent.browser().playwright().chromium().headless(true).launch()) {
            var page = browser.open(args[0]);
            var result =
                    page.action()
                            .download(
                                    page.find().link().named("Download report").single(),
                                    Path.of(args[1]),
                                    DownloadCollisionPolicy.RENAME)
                            .execute();
            System.out.println(result.throwIfFailed().value().savedPath());
        }
    }
}
