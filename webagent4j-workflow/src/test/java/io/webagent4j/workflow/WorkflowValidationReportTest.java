package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Structured Workflow Validation Report (VALID-001..020): {@link Workflow.Builder#validate()}
 * explains the exact same structural invariants {@link Workflow.Builder#build()} enforces, derived
 * from the exact same internal analysis - never a second, independently maintained rule set that
 * could diverge. See {@code docs/workflow.md#validation-report}.
 */
class WorkflowValidationReportTest {

    private static final WorkflowVariable<Boolean> FLAG =
            WorkflowVariable.publicValue("flag", Boolean.class);
    private static final WorkflowVariable<String> PRODUCED =
            WorkflowVariable.publicValue("produced", String.class);
    private static final WorkflowVariable<Integer> PRODUCED_INT =
            WorkflowVariable.publicValue("produced", Integer.class);
    private static final WorkflowVariable<String> SECRET_PRODUCED =
            WorkflowVariable.secret("produced");

    /**
     * A condition that counts every {@code evaluate()} call - must never be invoked by validate().
     */
    private static final class CountingCondition implements IWorkflowCondition {
        private final AtomicInteger evaluations;

        CountingCondition(AtomicInteger evaluations) {
            this.evaluations = evaluations;
        }

        @Override
        public boolean evaluate(IWorkflowVariables variables) {
            evaluations.incrementAndGet();
            return true;
        }

        @Override
        public String describe() {
            return "counting";
        }

        @Override
        public Set<WorkflowVariable<?>> referencedVariables() {
            return Set.of();
        }
    }

    /**
     * An action factory that counts every {@code prepare()} call - must never be invoked either.
     */
    private static final class CountingFactory<R> implements IWorkflowActionFactory<R> {
        private final AtomicInteger prepareCalls;
        private final R value;

        CountingFactory(AtomicInteger prepareCalls, R value) {
            this.prepareCalls = prepareCalls;
            this.value = value;
        }

        @Override
        public io.webagent4j.action.IPreparedAction<R> prepare(IWorkflowVariables variables) {
            prepareCalls.incrementAndGet();
            return new FakePreparedAction<>(ActionResults.success(value), new AtomicInteger());
        }
    }

    private static IWorkflowStep countingStep(String id, AtomicInteger prepareCalls) {
        return WorkflowSteps.action(id, new CountingFactory<>(prepareCalls, "v"));
    }

    private static IWorkflowStep producesActual(String id, WorkflowVariable<String> output) {
        return WorkflowSteps.action(
                id,
                variables ->
                        new FakePreparedAction<>(ActionResults.success("v"), new AtomicInteger()),
                output);
    }

    private static IWorkflowStep producesInt(String id, WorkflowVariable<Integer> output) {
        return WorkflowSteps.action(
                id,
                variables ->
                        new FakePreparedAction<>(ActionResults.success(1), new AtomicInteger()),
                output);
    }

    private static IWorkflowStep consumesViaCondition(String id, WorkflowVariable<?> required) {
        return WorkflowSteps.assign(
                        id, WorkflowVariable.publicValue(id + "-marker", Boolean.class), true)
                .when(WorkflowConditions.exists(required));
    }

    // --- VALID-001: sequential valid workflow --------------------------------------------------

    @Test
    void valid001SequentialValidWorkflowPasses() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(producesActual("a", PRODUCED))
                        .step(consumesViaCondition("b", PRODUCED));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isTrue();
        assertThat(report.diagnostics()).isEmpty();
        assertThat(report.stepCount()).isEqualTo(2);
        assertThat(builder.build()).isNotNull();
    }

    // --- VALID-002: duplicate step ID ------------------------------------------------------------

    @Test
    void valid002DuplicateStepIdProducesExactDiagnosticAndBuildRejects() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(producesActual("dup", PRODUCED))
                        .step(WorkflowSteps.assign("dup", FLAG, true));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isFalse();
        WorkflowValidationDiagnostic diagnostic = report.diagnostics().get(0);
        assertThat(diagnostic.code()).isEqualTo(WorkflowValidationCode.DUPLICATE_STEP_ID);
        assertThat(diagnostic.stepId()).contains(new WorkflowStepId("dup"));
        assertThat(diagnostic.message()).contains("dup");

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dup");
    }

    // --- VALID-003: condition references a never-declared variable ------------------------------

    @Test
    void valid003MissingRequiredVariableProducesExactDiagnostic() {
        Workflow.Builder builder = Workflow.builder("wf").step(consumesViaCondition("b", PRODUCED));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isFalse();
        WorkflowValidationDiagnostic diagnostic = report.diagnostics().get(0);
        assertThat(diagnostic.code())
                .isEqualTo(WorkflowValidationCode.OUTPUT_NOT_DEFINITELY_AVAILABLE);
        assertThat(diagnostic.variableName()).contains("produced");
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    // --- VALID-004: guarded producer, later required consumer -----------------------------------

    @Test
    void valid004GuardedProducerThenConsumerProducesNotDefinitelyAvailable() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                producesActual("producer", PRODUCED)
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .step(consumesViaCondition("consumer", PRODUCED));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isFalse();
        assertThat(report.diagnostics())
                .anySatisfy(
                        d -> {
                            assertThat(d.code())
                                    .isEqualTo(
                                            WorkflowValidationCode.OUTPUT_NOT_DEFINITELY_AVAILABLE);
                            assertThat(d.variableName()).contains("produced");
                        });
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    // --- VALID-005: ifElse, both branches produce compatible X - definite -----------------------

    @Test
    void valid005BothBranchesProduceCompatibleOutputIsDefiniteNoErrors() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(producesActual("then", PRODUCED)),
                                        List.of(producesActual("else", PRODUCED))))
                        .step(consumesViaCondition("consumer", PRODUCED));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isTrue();
        assertThat(report.diagnostics()).isEmpty();
        assertThat(builder.build()).isNotNull();
    }

    // --- VALID-006: only one branch produces X - not definite ------------------------------------

    @Test
    void valid006OnlyOneBranchProducesOutputIsNotDefinite() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(producesActual("then", PRODUCED)),
                                        List.of(WorkflowSteps.assign("noop", FLAG, false))))
                        .step(consumesViaCondition("consumer", PRODUCED));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isFalse();
        assertThat(report.diagnostics())
                .anySatisfy(
                        d ->
                                assertThat(d.code())
                                        .isEqualTo(
                                                WorkflowValidationCode
                                                        .OUTPUT_NOT_DEFINITELY_AVAILABLE));
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    // --- VALID-007: ifThen produces X - never definite afterward ---------------------------------

    @Test
    void valid007IfThenProducesOutputNeverDefiniteAfterward() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifThen(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(producesActual("then", PRODUCED))))
                        .step(consumesViaCondition("consumer", PRODUCED));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isFalse();
        assertThat(report.diagnostics())
                .anySatisfy(
                        d ->
                                assertThat(d.code())
                                        .isEqualTo(
                                                WorkflowValidationCode
                                                        .OUTPUT_NOT_DEFINITELY_AVAILABLE));
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    // --- VALID-008: branch output type mismatch
    // ---------------------------------------------------

    @Test
    void valid008BranchOutputTypeMismatchProducesExactDiagnostic() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(producesActual("then", PRODUCED)),
                                        List.of(producesInt("else", PRODUCED_INT))));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isFalse();
        assertThat(report.diagnostics())
                .anySatisfy(
                        d -> {
                            assertThat(d.code())
                                    .isEqualTo(WorkflowValidationCode.OUTPUT_TYPE_MISMATCH);
                            assertThat(d.variableName()).contains("produced");
                        });
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    // --- VALID-009: branch output secret/public mismatch
    // -------------------------------------------

    @Test
    void valid009BranchOutputSecretMismatchProducesExactDiagnostic() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(producesActual("then", PRODUCED)),
                                        List.of(
                                                WorkflowSteps.action(
                                                        "else",
                                                        vars ->
                                                                new FakePreparedAction<>(
                                                                        ActionResults.success("v"),
                                                                        new AtomicInteger()),
                                                        SECRET_PRODUCED))));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isFalse();
        assertThat(report.diagnostics())
                .anySatisfy(
                        d ->
                                assertThat(d.code())
                                        .isEqualTo(
                                                WorkflowValidationCode
                                                        .OUTPUT_SECRET_CLASSIFICATION_MISMATCH));
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    // --- VALID-010: max depth 64 - passes
    // ---------------------------------------------------------

    @Test
    void valid010MaximumNestingDepthPasses() {
        int max = Workflow.MAX_CONDITIONAL_NESTING_DEPTH;
        IWorkflowStep current = countingStep("leaf", new AtomicInteger());
        for (int level = max; level >= 1; level--) {
            current =
                    WorkflowSteps.ifThen(
                            "d-" + level,
                            new CountingCondition(new AtomicInteger()),
                            List.of(current));
        }
        Workflow.Builder builder = Workflow.builder("wf-max-depth").step(current);

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isTrue();
        assertThat(report.maximumObservedConditionalDepth()).isEqualTo(max);
        assertThat(builder.build()).isNotNull();
    }

    // --- VALID-011: depth 65 - controlled error, never StackOverflowError
    // -------------------------

    @Test
    void valid011ExceedingMaximumDepthProducesControlledErrorNoStackOverflow() {
        int overMax = Workflow.MAX_CONDITIONAL_NESTING_DEPTH + 1;
        IWorkflowStep current = countingStep("leaf", new AtomicInteger());
        for (int level = overMax; level >= 1; level--) {
            current =
                    WorkflowSteps.ifThen(
                            "d-" + level,
                            new CountingCondition(new AtomicInteger()),
                            List.of(current));
        }
        Workflow.Builder builder = Workflow.builder("wf-over-depth").step(current);

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isFalse();
        assertThat(report.diagnostics())
                .anySatisfy(
                        d ->
                                assertThat(d.code())
                                        .isEqualTo(
                                                WorkflowValidationCode.CONDITIONAL_DEPTH_EXCEEDED));
        assertThat(report.maximumObservedConditionalDepth())
                .isEqualTo(Workflow.MAX_CONDITIONAL_NESTING_DEPTH + 1);
        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    // --- VALID-012: repeated validation is logically identical
    // -------------------------------------

    @Test
    void valid012RepeatedValidationProducesLogicallyIdenticalReports() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(producesActual("then", PRODUCED)),
                                        List.of(producesActual("else", PRODUCED))));

        WorkflowValidationReport first = builder.validate();
        WorkflowValidationReport second = builder.validate();

        assertThat(first).isEqualTo(second);
    }

    // --- VALID-013: validation causes zero workflow side effects
    // -----------------------------------

    @Test
    void valid013ValidationCausesZeroConditionEvaluationsAndZeroActionInvocations() {
        AtomicInteger evaluations = new AtomicInteger();
        AtomicInteger prepareCalls = new AtomicInteger();
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        new CountingCondition(evaluations),
                                        List.of(countingStep("then", prepareCalls)),
                                        List.of(countingStep("else", prepareCalls))))
                        .step(countingStep("after", prepareCalls));

        builder.validate();

        assertThat(evaluations).hasValue(0);
        assertThat(prepareCalls).hasValue(0);
    }

    // --- VALID-014: secret metadata present, value absent
    // -------------------------------------------

    @Test
    void valid014SecretOutputMetadataPresentValueAbsent() {
        String sentinel = "WA4J_VALID_SENTINEL_204981";
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                        "a",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(sentinel),
                                                        new AtomicInteger()),
                                        SECRET_PRODUCED));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isTrue();
        WorkflowValidationOutput output = report.outputs().get(0);
        assertThat(output.variable().name()).isEqualTo("produced");
        assertThat(output.variable().secret()).isTrue();
        assertThat(report.toString()).doesNotContain(sentinel);
    }

    // --- VALID-015: diagnostic ordering is stable across independent errors
    // -----------------------

    @Test
    void valid015DiagnosticOrderingIsStableAcrossIndependentErrors() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("dup", FLAG, true))
                        .step(WorkflowSteps.assign("dup", FLAG, false))
                        .step(consumesViaCondition("consumer", PRODUCED));

        WorkflowValidationReport first = builder.validate();
        WorkflowValidationReport second = builder.validate();

        assertThat(first.diagnostics()).hasSizeGreaterThanOrEqualTo(2);
        assertThat(first.diagnostics().stream().map(WorkflowValidationDiagnostic::code).toList())
                .isEqualTo(
                        second.diagnostics().stream()
                                .map(WorkflowValidationDiagnostic::code)
                                .toList());
        assertThat(first.diagnostics().get(0).code())
                .isEqualTo(WorkflowValidationCode.DUPLICATE_STEP_ID);
    }

    // --- VALID-016: report immutability
    // ---------------------------------------------------------------

    @Test
    void valid016ReportStructuralImmutability() {
        Workflow.Builder builder = Workflow.builder("wf").step(consumesViaCondition("b", PRODUCED));

        WorkflowValidationReport report = builder.validate();

        assertThatThrownBy(() -> report.diagnostics().add(report.diagnostics().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> report.requiredInputs().add(FLAG))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(
                        () ->
                                report.outputs()
                                        .add(
                                                report.outputs().isEmpty()
                                                        ? null
                                                        : report.outputs().get(0)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // --- VALID-017: builder unaffected by validate()
    // ------------------------------------------------

    @Test
    void valid017ValidateDoesNotMutateBuilderOnValidDefinition() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(producesActual("a", PRODUCED))
                        .step(consumesViaCondition("b", PRODUCED));

        WorkflowValidationReport first = builder.validate();
        WorkflowValidationReport second = builder.validate();
        Workflow workflow = builder.build();

        assertThat(first).isEqualTo(second);
        assertThat(workflow).isNotNull();
    }

    // --- VALID-018: nested branching definite assignment, multiple levels
    // --------------------------

    @Test
    void valid018NestedBranchingDefiniteAssignmentAcrossMultipleLevels() {
        IWorkflowStep innerConditional =
                WorkflowSteps.ifElse(
                        "inner",
                        WorkflowConditions.isTrue(FLAG),
                        List.of(producesActual("inner-then", PRODUCED)),
                        List.of(producesActual("inner-else", PRODUCED)));
        Workflow.Builder validBuilder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "outer",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(innerConditional),
                                        List.of(producesActual("outer-else", PRODUCED))))
                        .step(consumesViaCondition("consumer", PRODUCED));

        assertThat(validBuilder.validate().valid()).isTrue();

        IWorkflowStep innerConditionalOneSided =
                WorkflowSteps.ifElse(
                        "inner",
                        WorkflowConditions.isTrue(FLAG),
                        List.of(
                                producesActual("inner-then", PRODUCED)
                                        .when(WorkflowConditions.isTrue(FLAG))),
                        List.of(producesActual("inner-else", PRODUCED)));
        Workflow.Builder invalidBuilder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "outer",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(innerConditionalOneSided),
                                        List.of(producesActual("outer-else", PRODUCED))))
                        .step(consumesViaCondition("consumer", PRODUCED));

        WorkflowValidationReport invalidReport = invalidBuilder.validate();
        assertThat(invalidReport.valid()).isFalse();
        assertThat(invalidReport.diagnostics())
                .anySatisfy(
                        d ->
                                assertThat(d.code())
                                        .isEqualTo(
                                                WorkflowValidationCode
                                                        .OUTPUT_NOT_DEFINITELY_AVAILABLE));
    }

    // --- VALID-019: guarded producer in a branch never becomes definite by mistake
    // -------------------

    @Test
    void valid019GuardedProducerInsideBranchNeverBecomesDefinite() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(
                                WorkflowSteps.ifElse(
                                        "branch",
                                        WorkflowConditions.isTrue(FLAG),
                                        List.of(
                                                producesActual("then", PRODUCED)
                                                        .when(WorkflowConditions.isTrue(FLAG))),
                                        List.of(producesActual("else", PRODUCED))))
                        .step(consumesViaCondition("consumer", PRODUCED));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isFalse();
        assertThat(report.outputs())
                .filteredOn(o -> o.variable().name().equals("produced"))
                .noneSatisfy(o -> assertThat(o.definitelyAvailable()).isTrue());
    }

    // --- VALID-020: resource bound - diagnostics truncated deterministically
    // -------------------------

    @Test
    void valid020DiagnosticsBoundedAndTruncatedFlagSetWhenExceeded() {
        Workflow.Builder builder = Workflow.builder("wf");
        for (int i = 0; i < 400; i++) {
            builder.step(WorkflowSteps.assign("dup-" + (i % 2), FLAG, true));
        }

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isFalse();
        assertThat(report.diagnostics().size()).isLessThanOrEqualTo(256);
        assertThat(report.diagnosticsTruncated()).isTrue();
    }

    // --- no backend object retention / no Throwable retention (structural review)
    // -------------------

    @Test
    void validationTypesNeverRetainABackendObjectOrThrowableType() {
        for (Class<?> type :
                List.of(
                        WorkflowValidationReport.class,
                        WorkflowValidationDiagnostic.class,
                        WorkflowValidationOutput.class)) {
            for (RecordComponent component : type.getRecordComponents()) {
                String typeName = component.getType().getName();
                assertThat(typeName)
                        .as(
                                "record component '%s' on %s",
                                component.getName(), type.getSimpleName())
                        .doesNotContain("browser")
                        .doesNotContain("Page")
                        .doesNotContain("Locator")
                        .doesNotContain("Element")
                        .doesNotContain("PreparedAction")
                        .doesNotContain("ActionBuilder")
                        .doesNotContain("Throwable")
                        .doesNotContain("Exception");
            }
        }
    }

    // --- valid workflow: metadata still available
    // -----------------------------------------------------

    @Test
    void validWorkflowExposesExplanatoryMetadata() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(FLAG)
                        .step(producesActual("a", PRODUCED))
                        .step(consumesViaCondition("b", PRODUCED));

        WorkflowValidationReport report = builder.validate();

        assertThat(report.valid()).isTrue();
        assertThat(report.requiredInputs()).containsExactly(FLAG);
        // "a" declares "produced"; "b" is an assign step that also declares its own marker output.
        assertThat(report.outputs()).hasSize(2);
        WorkflowValidationOutput producedOutput =
                report.outputs().stream()
                        .filter(o -> o.variable().name().equals("produced"))
                        .findFirst()
                        .orElseThrow();
        assertThat(producedOutput.producerStepId()).isEqualTo(new WorkflowStepId("a"));
        assertThat(producedOutput.definitelyAvailable()).isTrue();
        assertThat(report.stepCount()).isEqualTo(2);
        assertThat(report.conditionalCount()).isZero();
    }
}
