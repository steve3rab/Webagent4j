package io.webagent4j.robustness;

import io.webagent4j.locator.LocatorResolutionStatus;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;

record RobustnessMetrics(
        int total,
        int exactResolutions,
        int fuzzyResolutions,
        int ambiguousDetections,
        int safeUnresolved,
        int notInteractable,
        int timeouts,
        int wrongTargets,
        int actionSuccesses,
        int verificationFailures,
        int dynamicReresolutionSuccesses,
        int unexpectedExceptions,
        long meanResolutionMillis,
        long medianResolutionMillis,
        long p95ResolutionMillis,
        long maxResolutionMillis) {

    static RobustnessMetrics from(List<ScenarioResult> results) {
        List<Long> timings =
                results.stream()
                        .map(ScenarioResult::resolutionDuration)
                        .map(Duration::toMillis)
                        .sorted(Comparator.naturalOrder())
                        .toList();
        return new RobustnessMetrics(
                results.size(),
                count(
                        results,
                        result ->
                                result.status() == LocatorResolutionStatus.RESOLVED
                                        && result.exact()),
                count(
                        results,
                        result ->
                                result.status() == LocatorResolutionStatus.RESOLVED
                                        && result.fuzzy()),
                count(results, result -> result.status() == LocatorResolutionStatus.AMBIGUOUS),
                count(results, result -> result.status() == LocatorResolutionStatus.UNRESOLVABLE),
                count(
                        results,
                        result -> result.status() == LocatorResolutionStatus.NOT_INTERACTABLE),
                count(results, result -> result.status() == LocatorResolutionStatus.TIMEOUT),
                count(results, ScenarioResult::wrongTarget),
                count(results, ScenarioResult::actionSucceeded),
                count(results, ScenarioResult::verificationFailed),
                count(results, ScenarioResult::dynamicReresolutionSucceeded),
                count(results, ScenarioResult::unexpectedException),
                timings.isEmpty()
                        ? 0
                        : Math.round(
                                timings.stream().mapToLong(Long::longValue).average().orElse(0)),
                percentile(timings, 0.50),
                percentile(timings, 0.95),
                timings.isEmpty() ? 0 : timings.get(timings.size() - 1));
    }

    private static int count(
            List<ScenarioResult> results, java.util.function.Predicate<ScenarioResult> predicate) {
        return Math.toIntExact(results.stream().filter(predicate).count());
    }

    private static long percentile(List<Long> values, double percentile) {
        if (values.isEmpty()) {
            return 0;
        }
        int index = Math.max(0, (int) Math.ceil(percentile * values.size()) - 1);
        return values.get(index);
    }
}
