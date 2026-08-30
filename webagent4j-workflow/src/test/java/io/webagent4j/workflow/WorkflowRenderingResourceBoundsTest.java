package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * WF-MEM-001: proves {@link WorkflowConditions}' built-in conditions defer rendering an arbitrary
 * public comparison literal until the workflow's complete secret set is known ({@link
 * IDeferredConditionDescription}), rather than rendering it eagerly at evaluation time and
 * retaining the (potentially huge) unbounded text for the rest of execution. Confirms this is a
 * resource- <em>retention</em> fix, not a claim that WebAgent4J can bound what an arbitrary caller
 * {@code toString()} allocates while it runs, and that the non-negotiable {@code redact → bound}
 * ordering (never the reverse) is unchanged.
 *
 * <p>Fixtures use synthetic values in the low single-digit megabytes - large enough to be clearly
 * distinguishable from the final ~200-char bound, small enough not to slow down or destabilize CI.
 */
class WorkflowRenderingResourceBoundsTest {

    private static final WorkflowVariable<String> STATUS =
            WorkflowVariable.publicValue("status", String.class);
    private static final WorkflowVariable<Boolean> FLAG =
            WorkflowVariable.publicValue("flag", Boolean.class);
    private static final WorkflowVariable<String> USERNAME =
            WorkflowVariable.publicValue("username", String.class);

    private final WorkflowEngine engine = new WorkflowEngine();

    /** A value whose {@code toString()} counts invocations and returns {@code size} 'x' chars. */
    private static final class CountingHugeValue {
        private final int size;
        private final AtomicInteger toStringCalls = new AtomicInteger();

        CountingHugeValue(int size) {
            this.size = size;
        }

        int toStringCallCount() {
            return toStringCalls.get();
        }

        @Override
        public String toString() {
            toStringCalls.incrementAndGet();
            return "x".repeat(size);
        }
    }

    /** A value whose {@code toString()} always throws. */
    private static final class ThrowingValue {
        @Override
        public String toString() {
            throw new IllegalStateException("rendering boom");
        }
    }

    /** A value whose {@code toString()} returns the literal Java {@code null}. */
    private static final class NullToStringValue {
        @Override
        public String toString() {
            return null;
        }
    }

    private String conditionDescription(WorkflowResult result) {
        return result.steps().get(0).condition().orElseThrow().description();
    }

    // ---- WF-MEM-001: no unbounded retained diagnostic in workflow state ----

    @Test
    void wfMem001HugePublicValueDoesNotLeaveAnUnboundedRetainedDiagnostic() {
        CountingHugeValue huge = new CountingHugeValue(2_000_000);
        WorkflowVariable<CountingHugeValue> hugeVariable =
                WorkflowVariable.publicValue("hugeStatus", CountingHugeValue.class);
        IWorkflowCondition condition = WorkflowConditions.equals(hugeVariable, huge);
        // Structural: this is exactly the mechanism that removes eager unbounded retention - the
        // engine defers calling render() on `huge` until finalization instead of retaining an
        // already-rendered 2,000,000-char String for the whole execution.
        assertThat(condition).isInstanceOf(IDeferredConditionDescription.class);
        assertThat(huge.toStringCallCount()).isZero();

        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(hugeVariable)
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice").when(condition))
                        .build();
        WorkflowResult result =
                engine.execute(workflow, WorkflowInputs.builder().put(hugeVariable, huge).build());

        assertThat(result.completed()).isTrue();
        // Rendered exactly once, at finalization - never re-rendered, never rendered ahead of time.
        assertThat(huge.toStringCallCount()).isEqualTo(1);
        String description = conditionDescription(result);
        assertThat(description.length()).isLessThan(1_000);
    }

    @Test
    void wfMem001bComposedBuiltInConditionsStayBoundedAsAWholeNotPerLeaf() {
        // Regression: an earlier draft of this fix bounded each leaf condition's own rendering
        // independently, which let a composite of many built-in conditions grow unbounded overall
        // (N leaves x ~200 bounded chars each) even though every individual leaf was itself safely
        // bounded. The engine must bound the complete, composed description exactly once.
        WorkflowVariable<String> a = WorkflowVariable.publicValue("a", String.class);
        WorkflowVariable<String> b = WorkflowVariable.publicValue("b", String.class);
        WorkflowVariable<String> c = WorkflowVariable.publicValue("c", String.class);
        String huge = "q".repeat(100_000);
        IWorkflowCondition composed =
                WorkflowConditions.allOf(
                        WorkflowConditions.equals(a, huge),
                        WorkflowConditions.equals(b, huge),
                        WorkflowConditions.equals(c, huge));
        assertThat(composed).isInstanceOf(IDeferredConditionDescription.class);
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(a)
                        .requiredInput(b)
                        .requiredInput(c)
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice").when(composed))
                        .build();
        WorkflowInputs inputs =
                WorkflowInputs.builder()
                        .put(a, "not-matching")
                        .put(b, "not-matching")
                        .put(c, "not-matching")
                        .build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        String description = conditionDescription(result);
        // Well under one leaf's own bound multiplied by three children - the whole composite text
        // is
        // bounded to the same single ~200-char limit as any other diagnostic, not N times that.
        assertThat(description.length()).isLessThan(500);
        assertThat(description).contains("...(truncated)");
    }

    // ---- WF-MEM-002/003/004: a known secret is fully redacted regardless of position ----

    @Test
    void wfMem002SecretAtBeginningOfLargeTextIsFullyRedacted() {
        String secret = "WA4J_MEM_SECRET_AT_START_193847";
        WorkflowVariable<String> guard = WorkflowVariable.secret("guard");
        String literal = secret + "y".repeat(500_000);
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(guard)
                        .requiredInput(STATUS)
                        .step(
                                WorkflowSteps.assign("s1", USERNAME, "alice")
                                        .when(WorkflowConditions.equals(STATUS, literal)))
                        .build();
        WorkflowInputs inputs =
                WorkflowInputs.builder().put(guard, secret).put(STATUS, literal).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        assertThat(conditionDescription(result)).doesNotContain(secret);
    }

    @Test
    void wfMem003SecretInMiddleOfLargeTextIsFullyRedacted() {
        String secret = "WA4J_MEM_SECRET_IN_MIDDLE_284756";
        WorkflowVariable<String> guard = WorkflowVariable.secret("guard");
        String literal = "y".repeat(250_000) + secret + "y".repeat(250_000);
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(guard)
                        .requiredInput(STATUS)
                        .step(
                                WorkflowSteps.assign("s1", USERNAME, "alice")
                                        .when(WorkflowConditions.equals(STATUS, literal)))
                        .build();
        WorkflowInputs inputs =
                WorkflowInputs.builder().put(guard, secret).put(STATUS, literal).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        assertThat(conditionDescription(result)).doesNotContain(secret);
    }

    @Test
    void wfMem004SecretStraddlingTheFinalTruncationBoundaryIsFullyRedacted() {
        // The rendered text is "equals(status, " + 190 filler chars + the secret + ")" - the secret
        // straddles the ~200-char final bound. If bounding ever happened before redaction, only the
        // secret's prefix would survive truncation and its (now-incomplete) text would never match
        // during redaction, leaking a still-identifying partial fragment - exactly what render →
        // redact → bound (never the reverse) exists to prevent.
        String secret = "WA4J_MEM_BOUNDARY_SECRET_726354";
        WorkflowVariable<String> guard = WorkflowVariable.secret("guard");
        String literal = "y".repeat(190) + secret;
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(guard)
                        .requiredInput(STATUS)
                        .step(
                                WorkflowSteps.assign("s1", USERNAME, "alice")
                                        .when(WorkflowConditions.equals(STATUS, literal)))
                        .build();
        WorkflowInputs inputs =
                WorkflowInputs.builder().put(guard, secret).put(STATUS, literal).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        String description = conditionDescription(result);
        assertThat(description).doesNotContain(secret).doesNotContain("WA4J_MEM_BOUNDARY_SECRET");
    }

    // ---- WF-MEM-005: no chunked/windowed processing is used, so no chunk boundary exists to leak
    // across - proven by full redaction over an even larger span than WF-MEM-004 ----

    @Test
    void wfMem005SecretFarIntoALargeTextIsFullyRedactedNoChunkBoundaryToLeakAcross() {
        // This implementation never streams or windows redaction - SecretRedactor.redact runs once
        // over the complete rendered text (see SafeRendering's and SecretRedactor's class Javadoc)
        // -
        // so there is no internal chunk boundary a secret could straddle. This test proves full
        // redaction holds at an arbitrary large offset regardless.
        String secret = "WA4J_MEM_FAR_OFFSET_SECRET_54321";
        WorkflowVariable<String> guard = WorkflowVariable.secret("guard");
        String literal = "y".repeat(1_000_000) + secret + "y".repeat(1_000_000);
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(guard)
                        .requiredInput(STATUS)
                        .step(
                                WorkflowSteps.assign("s1", USERNAME, "alice")
                                        .when(WorkflowConditions.equals(STATUS, literal)))
                        .build();
        WorkflowInputs inputs =
                WorkflowInputs.builder().put(guard, secret).put(STATUS, literal).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        assertThat(conditionDescription(result)).doesNotContain(secret);
    }

    // ---- WF-MEM-006: multiple/overlapping known secrets are fully redacted before bounding ----

    @Test
    void wfMem006OverlappingSecretsInAConditionDescriptionAreFullyRedactedLongestFirst() {
        String shortValue = "abc";
        String longValue = "abcdef";
        WorkflowVariable<String> shortSecret = WorkflowVariable.secret("shortSecret");
        WorkflowVariable<String> longSecret = WorkflowVariable.secret("longSecret");
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(shortSecret)
                        .requiredInput(longSecret)
                        .requiredInput(STATUS)
                        .step(
                                WorkflowSteps.assign("s1", USERNAME, "alice")
                                        .when(
                                                WorkflowConditions.equals(
                                                        STATUS, "prefix " + longValue + " suffix")))
                        .build();
        WorkflowInputs inputs =
                WorkflowInputs.builder()
                        .put(shortSecret, shortValue)
                        .put(longSecret, longValue)
                        .put(STATUS, "not-matching")
                        .build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        String description = conditionDescription(result);
        // Matching the longer secret first means the shorter secret's text, which is a substring of
        // the longer one, can never leave a dangling, still-identifying "***def" remnant behind.
        assertThat(description).doesNotContain(longValue).doesNotContain("***def").contains("***");
    }

    // ---- WF-MEM-007: a huge non-secret diagnostic produces deterministic bounded output ----

    @Test
    void wfMem007HugeNonSecretDiagnosticProducesDeterministicBoundedOutput() {
        String literal = "z".repeat(3_000_000);
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(STATUS)
                        .step(
                                WorkflowSteps.assign("s1", USERNAME, "alice")
                                        .when(WorkflowConditions.equals(STATUS, literal)))
                        .build();

        WorkflowResult first =
                engine.execute(
                        workflow, WorkflowInputs.builder().put(STATUS, "not-matching").build());
        WorkflowResult second =
                engine.execute(
                        workflow, WorkflowInputs.builder().put(STATUS, "not-matching").build());

        assertThat(first.completed()).isTrue();
        String firstDescription = conditionDescription(first);
        String secondDescription = conditionDescription(second);
        assertThat(firstDescription).isEqualTo(secondDescription);
        assertThat(firstDescription.length()).isLessThan(1_000);
        assertThat(firstDescription).contains("...(truncated)");
    }

    // ---- WF-MEM-008: a throwing toString() produces the existing safe rendering-failed marker
    // ----

    @Test
    void wfMem008ThrowingToStringProducesTheSafeRenderingFailedMarker() {
        ThrowingValue throwing = new ThrowingValue();
        WorkflowVariable<ThrowingValue> objectStatus =
                WorkflowVariable.publicValue("objStatus", ThrowingValue.class);
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(objectStatus)
                        .step(
                                WorkflowSteps.assign("s1", USERNAME, "alice")
                                        .when(WorkflowConditions.equals(objectStatus, throwing)))
                        .build();
        WorkflowInputs inputs =
                WorkflowInputs.builder().put(objectStatus, new ThrowingValue()).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        String description = conditionDescription(result);
        assertThat(description).contains("<rendering-failed:IllegalStateException>");
        assertThat(description).doesNotContain("rendering boom");
    }

    // ---- WF-MEM-009: a null-returning toString() remains compatible ----

    @Test
    void wfMem009NullReturningToStringRemainsCompatible() {
        WorkflowVariable<NullToStringValue> objectStatus =
                WorkflowVariable.publicValue("objStatus2", NullToStringValue.class);
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(objectStatus)
                        .step(
                                WorkflowSteps.assign("s1", USERNAME, "alice")
                                        .when(
                                                WorkflowConditions.equals(
                                                        objectStatus, new NullToStringValue())))
                        .build();
        WorkflowInputs inputs =
                WorkflowInputs.builder().put(objectStatus, new NullToStringValue()).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        assertThat(conditionDescription(result)).contains("equals(objStatus2, null)");
    }

    // ---- WF-MEM-010: repeated execution does not accumulate retained rendered state ----

    @Test
    void wfMem010RepeatedExecutionDoesNotAccumulateRetainedRenderedState() {
        String literal = "w".repeat(1_000_000);
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(STATUS)
                        .step(
                                WorkflowSteps.assign("s1", USERNAME, "alice")
                                        .when(WorkflowConditions.equals(STATUS, literal)))
                        .build();

        for (int i = 0; i < 20; i++) {
            WorkflowResult result =
                    engine.execute(
                            workflow, WorkflowInputs.builder().put(STATUS, "run-" + i).build());
            assertThat(result.completed()).isTrue();
            assertThat(conditionDescription(result).length()).isLessThan(1_000);
        }
    }

    // ---- WF-MEM-011: a secret known only later still masks an earlier retained description ----

    @Test
    void wfMem011SecretKnownOnlyLaterStillMasksAnEarlierBuiltInConditionDescription() {
        String value = "WA4J_MEM_LATE_SECRET_918273";
        WorkflowVariable<String> lateSecret = WorkflowVariable.secret("lateSecret");
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(STATUS)
                        .step(
                                WorkflowSteps.assign("s1", FLAG, true)
                                        .when(WorkflowConditions.equals(STATUS, value)))
                        .step(
                                WorkflowSteps.action(
                                        "s2",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(value),
                                                        new AtomicInteger()),
                                        lateSecret))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(STATUS, value).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        assertThat(result.steps().get(0).condition().orElseThrow().outcome()).isTrue();
        String description = conditionDescription(result);
        assertThat(description).doesNotContain(value).contains("***");
    }

    // ---- WF-MEM-012: no secret marker anywhere in public rendering ----

    @Test
    void wfMem012NoSecretMarkerInAnyPublicRenderingOfAResourceHardenedDescription() {
        String secret = "WA4J_MEM_MARKER_SENTINEL_837465";
        WorkflowVariable<String> guard = WorkflowVariable.secret("guard");
        String literal = "y".repeat(190) + secret;
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(guard)
                        .requiredInput(STATUS)
                        .step(
                                WorkflowSteps.assign("s1", USERNAME, "alice")
                                        .when(WorkflowConditions.equals(STATUS, literal)))
                        .build();
        WorkflowInputs inputs =
                WorkflowInputs.builder().put(guard, secret).put(STATUS, literal).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        assertThat(conditionDescription(result)).doesNotContain(secret);
        assertThat(result.steps().get(0).toString()).doesNotContain(secret);
        assertThat(result.toString()).doesNotContain(secret);
    }
}
