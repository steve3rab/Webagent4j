package io.webagent4j.integration.coverage;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Enforces a real aggregate line-coverage threshold for the Playwright adapter package ({@code
 * io.webagent4j.browser.playwright}), computed from the merged JaCoCo report produced by this
 * module's {@code report-aggregate} execution.
 *
 * <p>The Playwright adapter's per-module JaCoCo BUNDLE/LINE gate is intentionally skipped (see
 * {@code webagent4j-browser-playwright/pom.xml}): most of the adapter is only meaningfully
 * exercised through this module's browser-driven integration tests, not through the adapter's own
 * narrow, browser-free unit tests. Skipping that per-module gate without a replacement would
 * silently drop coverage protection for the whole adapter. The {@code jacoco-maven-plugin} "check"
 * goal has no built-in way to verify a cross-module aggregate - it only ever analyzes the current
 * module's own compiled classes, never a dependency's - so this class is the minimal deterministic
 * replacement: it reads the aggregate CSV this module already produces and re-applies the same
 * threshold to the real, combined number.
 *
 * <p>Bound via {@code exec-maven-plugin}'s {@code java} goal at the {@code verify} phase, after the
 * {@code report-aggregate} execution that produces the CSV this class reads.
 */
public final class PlaywrightCoverageGate {

    static final double MINIMUM_LINE_COVERAGE = 0.70;
    static final String TARGET_PACKAGE_PREFIX = "io.webagent4j.browser.playwright";
    private static final Path DEFAULT_CSV = Path.of("target/site/jacoco-aggregate/jacoco.csv");

    private PlaywrightCoverageGate() {
        // not instantiable
    }

    public static void main(String[] args) throws IOException {
        Path csv = args.length > 0 ? Path.of(args[0]) : DEFAULT_CSV;
        Result result = evaluate(csv);
        long total = result.lineCovered() + result.lineMissed();
        System.out.printf(
                "Playwright adapter aggregate LINE coverage: %d/%d (%.1f%%), minimum required"
                        + " %.0f%%%n",
                result.lineCovered(), total, result.ratio() * 100, MINIMUM_LINE_COVERAGE * 100);
        if (total == 0) {
            throw new IllegalStateException(
                    "No coverage data found for package '"
                            + TARGET_PACKAGE_PREFIX
                            + "' in "
                            + csv
                            + " - the aggregate report is missing, empty, or was generated before"
                            + " this module's tests ran.");
        }
        if (result.ratio() < MINIMUM_LINE_COVERAGE) {
            throw new IllegalStateException(
                    "Playwright adapter aggregate coverage gate failed: "
                            + String.format("%.1f%%", result.ratio() * 100)
                            + " is below the required "
                            + String.format("%.0f%%", MINIMUM_LINE_COVERAGE * 100)
                            + ".");
        }
    }

    static Result evaluate(Path csv) throws IOException {
        if (!Files.isRegularFile(csv)) {
            throw new IOException("JaCoCo aggregate CSV not found: " + csv);
        }
        long covered = 0;
        long missed = 0;
        try (BufferedReader reader = Files.newBufferedReader(csv)) {
            String header = reader.readLine();
            if (header == null) {
                throw new IOException("Empty JaCoCo aggregate CSV: " + csv);
            }
            List<String> columns = List.of(header.split(","));
            int packageIndex = columns.indexOf("PACKAGE");
            int lineMissedIndex = columns.indexOf("LINE_MISSED");
            int lineCoveredIndex = columns.indexOf("LINE_COVERED");
            if (packageIndex < 0 || lineMissedIndex < 0 || lineCoveredIndex < 0) {
                throw new IOException("Unexpected JaCoCo aggregate CSV header: " + header);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                String[] cells = line.split(",", -1);
                String packageName = cells[packageIndex];
                if (!packageName.equals(TARGET_PACKAGE_PREFIX)
                        && !packageName.startsWith(TARGET_PACKAGE_PREFIX + ".")) {
                    continue;
                }
                missed += Long.parseLong(cells[lineMissedIndex]);
                covered += Long.parseLong(cells[lineCoveredIndex]);
            }
        }
        return new Result(covered, missed);
    }

    record Result(long lineCovered, long lineMissed) {
        double ratio() {
            long total = lineCovered + lineMissed;
            return total == 0 ? 0.0 : (double) lineCovered / total;
        }
    }
}
