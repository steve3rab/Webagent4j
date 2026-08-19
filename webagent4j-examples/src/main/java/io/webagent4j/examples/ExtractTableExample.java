package io.webagent4j.examples;

import io.webagent4j.browser.IBrowser;
import io.webagent4j.core.WebAgent;
import io.webagent4j.extraction.api.ExtractedTable;
import io.webagent4j.extraction.api.ExtractionResult;
import io.webagent4j.locator.api.LocatorDefinition;

/**
 * Demonstrates structured table extraction: reads an accessible HTML table's headers and rows, then
 * accesses one cell both by column index and by header name.
 */
public final class ExtractTableExample {

    private ExtractTableExample() {}

    /** Runs against a page containing one {@code <table>}. */
    public static void main(String[] args) {
        String url = requireArgument(args, "page URL");
        try (IBrowser browser = WebAgent.browser().playwright().chromium().headless(true).launch();
                var page = browser.open(url)) {
            ExtractionResult<ExtractedTable> result =
                    page.extractTable(LocatorDefinition.css("table"));

            ExtractedTable table = result.value();
            System.out.println("Headers: " + table.headers());
            for (int row = 0; row < table.rows().size(); row++) {
                System.out.println("Row " + row + ": " + table.rows().get(row).cells());
            }
            table.cell(0, "Price").ifPresent(price -> System.out.println("First price: " + price));
        }
    }

    private static String requireArgument(String[] args, String description) {
        if (args.length == 0 || args[0].isBlank()) {
            throw new IllegalArgumentException(
                    "Expected " + description + " as the first argument");
        }
        return args[0];
    }
}
