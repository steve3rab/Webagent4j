package io.webagent4j.robustness;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.webagent4j.browser.IPage;
import io.webagent4j.observation.CompactTextObservationRenderer;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

final class RobustnessReportWriter {

    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

    private RobustnessReportWriter() {}

    static void write(List<ScenarioResult> results) throws IOException {
        Path target = targetDirectory();
        Files.createDirectories(target);
        RobustnessMetrics metrics = RobustnessMetrics.from(results);
        Files.writeString(
                target.resolve("robustness-report.md"),
                markdown(results, metrics),
                StandardCharsets.UTF_8);
        JSON.writerWithDefaultPrettyPrinter()
                .writeValue(
                        target.resolve("robustness-report.json").toFile(),
                        Map.of(
                                "schemaVersion",
                                1,
                                "metrics",
                                metrics,
                                "results",
                                results.stream().map(RobustnessReportWriter::jsonResult).toList()));
    }

    static void failureArtifacts(IPage page, ScenarioResult result) {
        Path directory =
                targetDirectory().resolve("robustness-artifacts").resolve(result.scenario().id());
        try {
            Files.createDirectories(directory);
            Files.writeString(
                    directory.resolve("diagnostics.txt"),
                    "Scenario: "
                            + result.scenario().id()
                            + System.lineSeparator()
                            + "Expected: "
                            + result.scenario().expectation()
                            + System.lineSeparator()
                            + "Actual: "
                            + result.status()
                            + System.lineSeparator()
                            + "Classification: "
                            + result.failureClassification()
                            + System.lineSeparator()
                            + result.diagnostics(),
                    StandardCharsets.UTF_8);
            Files.writeString(
                    directory.resolve("observation.txt"),
                    new CompactTextObservationRenderer().render(page.observe()),
                    StandardCharsets.UTF_8);
            Files.writeString(
                    directory.resolve("fixture.html"), page.content(), StandardCharsets.UTF_8);
            Files.write(directory.resolve("screenshot.png"), page.screenshot());
        } catch (RuntimeException | IOException artifactFailure) {
            try {
                Files.createDirectories(directory);
                Files.writeString(
                        directory.resolve("artifact-error.txt"),
                        artifactFailure.toString(),
                        StandardCharsets.UTF_8);
            } catch (IOException ignored) {
                // Test assertion remains the source of truth when diagnostics cannot be persisted.
            }
        }
    }

    private static Map<String, Object> jsonResult(ScenarioResult result) {
        Map<String, Object> value = new TreeMap<>();
        value.put("id", result.scenario().id());
        value.put("description", result.scenario().description());
        value.put("fixture", result.scenario().fixture());
        value.put("difficulty", result.scenario().difficulty());
        value.put("tags", result.scenario().tags());
        value.put("expected", result.scenario().expectation());
        value.put("actual", result.status());
        value.put("expectedTarget", result.scenario().expectedTarget());
        value.put("actualTarget", result.actualTarget());
        value.put("wrongTarget", result.wrongTarget());
        value.put("failureClassification", result.failureClassification());
        value.put("durationMillis", result.resolutionDuration().toMillis());
        value.put("passed", result.expectedOutcome() && !result.unexpectedException());
        return value;
    }

    private static String markdown(List<ScenarioResult> results, RobustnessMetrics metrics) {
        String line = System.lineSeparator();
        StringBuilder report =
                new StringBuilder("# WebAgent4J Robustness Report").append(line).append(line);
        report.append("| Metric | Value |").append(line);
        report.append("| --- | ---: |").append(line);
        metric(report, "Total scenarios", metrics.total());
        metric(report, "Exact resolutions", metrics.exactResolutions());
        metric(report, "Fuzzy resolutions", metrics.fuzzyResolutions());
        metric(report, "Ambiguous safely", metrics.ambiguousDetections());
        metric(report, "Unresolved safely", metrics.safeUnresolved());
        metric(report, "Not interactable safely", metrics.notInteractable());
        metric(report, "Timeouts", metrics.timeouts());
        metric(report, "Wrong target", metrics.wrongTargets());
        metric(report, "Action successes", metrics.actionSuccesses());
        metric(report, "Verification failures", metrics.verificationFailures());
        metric(report, "Dynamic re-resolution successes", metrics.dynamicReresolutionSuccesses());
        metric(report, "Unexpected exceptions", metrics.unexpectedExceptions());
        metric(report, "Mean resolution (ms)", metrics.meanResolutionMillis());
        metric(report, "Median resolution (ms)", metrics.medianResolutionMillis());
        metric(report, "P95 resolution (ms)", metrics.p95ResolutionMillis());
        metric(report, "Max resolution (ms)", metrics.maxResolutionMillis());
        report.append(line).append("## Results by difficulty").append(line).append(line);
        grouped(report, results, result -> result.scenario().difficulty().name());
        report.append(line).append("## Results by tag").append(line).append(line);
        Map<String, List<ScenarioResult>> byTag =
                results.stream()
                        .flatMap(
                                result ->
                                        result.scenario().tags().stream()
                                                .map(tag -> Map.entry(tag.name(), result)))
                        .collect(
                                Collectors.groupingBy(
                                        Map.Entry::getKey,
                                        TreeMap::new,
                                        Collectors.mapping(
                                                Map.Entry::getValue, Collectors.toList())));
        table(report, byTag);
        List<ScenarioResult> failures =
                results.stream()
                        .filter(result -> !result.expectedOutcome() || result.unexpectedException())
                        .sorted(Comparator.comparing(result -> result.scenario().id()))
                        .toList();
        report.append(line).append("## Failed scenarios").append(line).append(line);
        if (failures.isEmpty()) {
            report.append("None.").append(line);
        } else {
            failures.forEach(
                    result ->
                            report.append("- `")
                                    .append(result.scenario().id())
                                    .append("`: expected ")
                                    .append(result.scenario().expectation())
                                    .append(", got ")
                                    .append(result.status())
                                    .append(" [")
                                    .append(result.failureClassification())
                                    .append("]")
                                    .append(". Run: `./mvnw -Probustness -Dscenario=")
                                    .append(result.scenario().id())
                                    .append(" verify`")
                                    .append(line));
        }
        return report.toString();
    }

    private static void grouped(
            StringBuilder report,
            List<ScenarioResult> results,
            java.util.function.Function<ScenarioResult, String> classifier) {
        Map<String, List<ScenarioResult>> groups =
                results.stream()
                        .collect(
                                Collectors.groupingBy(
                                        classifier, TreeMap::new, Collectors.toList()));
        table(report, groups);
    }

    private static void table(StringBuilder report, Map<String, List<ScenarioResult>> groups) {
        String line = System.lineSeparator();
        report.append("| Group | Passed | Total |").append(line);
        report.append("| --- | ---: | ---: |").append(line);
        groups.forEach(
                (group, values) ->
                        report.append("| ")
                                .append(group)
                                .append(" | ")
                                .append(
                                        values.stream()
                                                .filter(ScenarioResult::expectedOutcome)
                                                .count())
                                .append(" | ")
                                .append(values.size())
                                .append(" |")
                                .append(line));
    }

    private static void metric(StringBuilder report, String name, long value) {
        report.append("| ")
                .append(name)
                .append(" | ")
                .append(value)
                .append(" |")
                .append(System.lineSeparator());
    }

    private static Path targetDirectory() {
        return Path.of(System.getProperty("basedir", "."), "target");
    }
}
