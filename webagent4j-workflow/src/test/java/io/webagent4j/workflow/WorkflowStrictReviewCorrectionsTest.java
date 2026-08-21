package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.action.ActionFailureType;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Regression suite for the strict correction round of Phase 0.8: the closed step contract,
 * fail-closed optional-input mismatch, write-once inputs, unique input declarations, undeclared
 * inputs, deterministic ordering, defensive custom-condition handling, and cross-field secret
 * redaction.
 */
class WorkflowStrictReviewCorrectionsTest {

    private static final String SECRET_SENTINEL = "WA4J_SUPER_SECRET_982734";
    private static final WorkflowVariable<String> USERNAME =
            WorkflowVariable.publicValue("username", String.class);
    private static final WorkflowVariable<Boolean> FLAG =
            WorkflowVariable.publicValue("flag", Boolean.class);
    private static final WorkflowVariable<String> TOKEN = WorkflowVariable.secret("token");

    private final WorkflowEngine engine = new WorkflowEngine();

    // ---- STEP-API: the sealed step contract -------------------------------------------------

    @Test
    void stepApi001IWorkflowStepIsSealedAndPermitsOnlyAWorkflowStep() {
        // No runtime "external step causes ClassCastException" test is needed or even possible:
        // IWorkflowStep is sealed and permits only the package-private AWorkflowStep, so a class
        // implementing IWorkflowStep anywhere outside that exact permitted type - even in this
        // same package, even in this same file - is a compile-time error, not a runtime one. This
        // test documents that closure precisely via reflection.
        assertThat(IWorkflowStep.class.isSealed()).isTrue();
        Class<?>[] permitted = IWorkflowStep.class.getPermittedSubclasses();
        assertThat(permitted).extracting(Class::getSimpleName).containsExactly("AWorkflowStep");
    }

    // ---- INPUT-OPT: optional input fail-closed mismatch handling ----------------------------

    @Test
    void inputOpt001OptionalAbsentContinuesExecution() {
        Workflow workflow =
                Workflow.builder("wf")
                        .optionalInput(FLAG)
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice"))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
    }

    @Test
    void inputOpt002OptionalPresentWithCorrectDeclarationIsSeeded() {
        Workflow workflow =
                Workflow.builder("wf")
                        .optionalInput(FLAG)
                        .step(
                                WorkflowSteps.assign("s1", USERNAME, "alice")
                                        .when(WorkflowConditions.isTrue(FLAG)))
                        .build();

        WorkflowResult result =
                engine.execute(workflow, WorkflowInputs.builder().put(FLAG, true).build());

        assertThat(result.completed()).isTrue();
        assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.SUCCEEDED);
    }

    @Test
    void inputOpt003OptionalWrongTypeDeclarationFailsBeforeStepZero() {
        AtomicInteger factoryCalls = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .optionalInput(FLAG)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> {
                                            factoryCalls.incrementAndGet();
                                            return new FakePreparedAction<>(
                                                    ActionResults.success("ok"),
                                                    new AtomicInteger());
                                        }))
                        .build();
        WorkflowVariable<Integer> wrongType = WorkflowVariable.publicValue("flag", Integer.class);
        WorkflowInputs inputs = WorkflowInputs.builder().put(wrongType, 1).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.INPUT_TYPE_MISMATCH);
        assertThat(result.steps())
                .allSatisfy(
                        step -> assertThat(step.status()).isEqualTo(WorkflowStepStatus.NOT_RUN));
        assertThat(factoryCalls).hasValue(0);
    }

    @Test
    void inputOpt004OptionalSecretVsPublicMismatchFailsBeforeStepZero() {
        Workflow workflow =
                Workflow.builder("wf")
                        .optionalInput(TOKEN)
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice"))
                        .build();
        WorkflowVariable<String> publicToken = WorkflowVariable.publicValue("token", String.class);
        WorkflowInputs inputs = WorkflowInputs.builder().put(publicToken, "abc").build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.INPUT_TYPE_MISMATCH);
    }

    @Test
    void inputOpt004bOptionalPublicVsSecretMismatchFailsBeforeStepZero() {
        WorkflowVariable<String> publicName = WorkflowVariable.publicValue("name", String.class);
        Workflow workflow =
                Workflow.builder("wf")
                        .optionalInput(publicName)
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice"))
                        .build();
        WorkflowVariable<String> secretName = WorkflowVariable.secret("name");
        WorkflowInputs inputs = WorkflowInputs.builder().put(secretName, "abc").build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.INPUT_TYPE_MISMATCH);
    }

    // ---- INPUT-DUP: write-once inputs and unique declarations --------------------------------

    @Test
    void inputDup001SameWorkflowInputsVariableSuppliedTwiceIsRejected() {
        WorkflowInputs.Builder builder = WorkflowInputs.builder().put(USERNAME, "alice");

        assertThatThrownBy(() -> builder.put(USERNAME, "bob"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }

    @Test
    void inputDup002SameWorkflowInputsVariableSuppliedTwiceWithSameValueIsRejected() {
        WorkflowInputs.Builder builder = WorkflowInputs.builder().put(USERNAME, "alice");

        assertThatThrownBy(() -> builder.put(USERNAME, "alice"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inputDup003DuplicateRequiredInputDeclarationRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(USERNAME)
                        .requiredInput(USERNAME)
                        .step(WorkflowSteps.assign("s1", FLAG, true));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("username");
    }

    @Test
    void inputDup004DuplicateOptionalInputDeclarationRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .optionalInput(FLAG)
                        .optionalInput(FLAG)
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice"));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inputDup005RequiredAndOptionalDuplicateRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(USERNAME)
                        .optionalInput(USERNAME)
                        .step(WorkflowSteps.assign("s1", FLAG, true));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void inputDup006RequiredAndConflictingOptionalDuplicateRejected() {
        WorkflowVariable<Integer> conflicting =
                WorkflowVariable.publicValue("username", Integer.class);
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .requiredInput(USERNAME)
                        .optionalInput(conflicting)
                        .step(WorkflowSteps.assign("s1", FLAG, true));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    // ---- INPUT-EXTRA: undeclared inputs -------------------------------------------------------

    @Test
    void inputExtra001UndeclaredPublicInputFailsBeforeStepZero() {
        AtomicInteger factoryCalls = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> {
                                            factoryCalls.incrementAndGet();
                                            return new FakePreparedAction<>(
                                                    ActionResults.success("ok"),
                                                    new AtomicInteger());
                                        }))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(USERNAME, "alice").build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.UNDECLARED_INPUT);
        assertThat(factoryCalls).hasValue(0);
    }

    @Test
    void inputExtra002UndeclaredSecretInputFailsWithoutLeakingValue() {
        Workflow workflow =
                Workflow.builder("wf").step(WorkflowSteps.assign("s1", USERNAME, "alice")).build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(TOKEN, SECRET_SENTINEL).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.UNDECLARED_INPUT);
        assertThat(result.failure().orElseThrow().safeMessage())
                .doesNotContain(SECRET_SENTINEL)
                .contains("token");
        assertThat(result.toString()).doesNotContain(SECRET_SENTINEL);
    }

    @Test
    void inputExtra003AllDeclaredInputsSucceed() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(USERNAME)
                        .step(WorkflowSteps.assign("s1", FLAG, true))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(USERNAME, "alice").build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
    }

    // ---- ORDER: deterministic insertion/publication order -------------------------------------

    @Test
    void order001WorkflowInputsRenderingPreservesInsertionOrder() {
        WorkflowVariable<String> a = WorkflowVariable.publicValue("a", String.class);
        WorkflowVariable<String> b = WorkflowVariable.publicValue("b", String.class);
        WorkflowVariable<String> c = WorkflowVariable.publicValue("c", String.class);

        WorkflowInputs inputs =
                WorkflowInputs.builder().put(b, "1").put(a, "2").put(c, "3").build();

        String rendered = inputs.toString();
        assertThat(rendered.indexOf("b=")).isLessThan(rendered.indexOf("a="));
        assertThat(rendered.indexOf("a=")).isLessThan(rendered.indexOf("c="));
    }

    @Test
    void order002WorkflowOutputsRenderingPreservesPublicationOrder() {
        WorkflowVariable<String> out2 = WorkflowVariable.publicValue("out-2", String.class);
        WorkflowVariable<String> out1 = WorkflowVariable.publicValue("out-1", String.class);
        WorkflowVariable<String> out3 = WorkflowVariable.publicValue("out-3", String.class);
        Workflow workflow =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("step-out-2", out2, "b"))
                        .step(WorkflowSteps.assign("step-out-1", out1, "a"))
                        .step(WorkflowSteps.assign("step-out-3", out3, "c"))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        String rendered = result.toString();
        assertThat(rendered.indexOf("out-2=")).isLessThan(rendered.indexOf("out-1="));
        assertThat(rendered.indexOf("out-1=")).isLessThan(rendered.indexOf("out-3="));
    }

    // ---- COND-CUSTOM: defensive handling of trusted custom conditions -------------------------

    private static IWorkflowCondition throwingEvaluateCondition() {
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                throw new IllegalStateException("boom");
            }

            @Override
            public String describe() {
                return "throwingEvaluate";
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of();
            }
        };
    }

    @Test
    void condCustom001EvaluateThrowsBecomesStructuredFailure() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.assign("s1", USERNAME, "alice")
                                        .when(throwingEvaluateCondition()))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITION_EVALUATION_FAILED);
    }

    @Test
    void condCustom002DescribeThrowsAfterSuccessfulEvaluateBecomesStructuredFailure() {
        IWorkflowCondition condition =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        return true;
                    }

                    @Override
                    public String describe() {
                        throw new IllegalStateException("describe boom");
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        Workflow workflow =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice").when(condition))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITION_EVALUATION_FAILED);
        assertThat(result.failure().orElseThrow().underlyingTypeName())
                .contains(IllegalStateException.class.getName());
    }

    @Test
    void condCustom003NullDescriptionBecomesStructuredFailure() {
        IWorkflowCondition condition =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        return true;
                    }

                    @Override
                    public String describe() {
                        return null;
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        Workflow workflow =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice").when(condition))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.CONDITION_EVALUATION_FAILED);
    }

    @Test
    void condCustom004DescriptionContainingKnownSecretIsRedacted() {
        IWorkflowCondition condition =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        return true;
                    }

                    @Override
                    public String describe() {
                        return "token == " + SECRET_SENTINEL;
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(TOKEN)
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice").when(condition))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(TOKEN, SECRET_SENTINEL).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isTrue();
        String description = result.steps().get(0).condition().orElseThrow().description();
        assertThat(description).doesNotContain(SECRET_SENTINEL).contains("***");
    }

    @Test
    void condCustom005LongDescriptionIsBounded() {
        String hugeText = "x".repeat(10_000);
        IWorkflowCondition condition =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        return true;
                    }

                    @Override
                    public String describe() {
                        return hugeText;
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        Workflow workflow =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice").when(condition))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        String description = result.steps().get(0).condition().orElseThrow().description();
        assertThat(description.length()).isLessThan(hugeText.length());
    }

    @Test
    void condCustom006NullReferencedVariablesRejectedAtBuild() {
        IWorkflowCondition condition =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        return true;
                    }

                    @Override
                    public String describe() {
                        return "custom";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return null;
                    }
                };
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice").when(condition));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void condCustom007ReferencedVariablesContainingNullRejectedAtBuild() {
        IWorkflowCondition condition =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        return true;
                    }

                    @Override
                    public String describe() {
                        return "custom";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        java.util.Set<WorkflowVariable<?>> set = new java.util.HashSet<>();
                        set.add(null);
                        return set;
                    }
                };
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice").when(condition));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void condCustom008ReferencedVariablesThrowingRejectedAtBuild() {
        IWorkflowCondition condition =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        return true;
                    }

                    @Override
                    public String describe() {
                        return "custom";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        throw new IllegalStateException("boom");
                    }
                };
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice").when(condition));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void condCustom009EvaluateCalledExactlyOnce() {
        AtomicInteger evaluateCount = new AtomicInteger();
        IWorkflowCondition condition =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        evaluateCount.incrementAndGet();
                        return true;
                    }

                    @Override
                    public String describe() {
                        return "custom";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };
        Workflow workflow =
                Workflow.builder("wf")
                        .step(WorkflowSteps.assign("s1", USERNAME, "alice").when(condition))
                        .build();

        engine.execute(workflow, WorkflowInputs.empty());

        assertThat(evaluateCount).hasValue(1);
    }

    // ---- SEC-CROSS: cross-field secret redaction ----------------------------------------------

    @Test
    void secCross001PublicInputContainingSecretTextIsRedacted() {
        String secretValue = "hunter2";
        WorkflowVariable<String> comment = WorkflowVariable.publicValue("comment", String.class);
        WorkflowInputs inputs =
                WorkflowInputs.builder()
                        .put(TOKEN, secretValue)
                        .put(comment, "contains hunter2 inside")
                        .build();

        String rendered = inputs.toString();

        assertThat(rendered).doesNotContain(secretValue).contains("***");
    }

    @Test
    void secCross002PublicOutputContainingSecretTextIsRedacted() {
        String secretValue = "token123";
        WorkflowVariable<String> secretOut = WorkflowVariable.secret("secretOut");
        WorkflowVariable<String> publicOut =
                WorkflowVariable.publicValue("publicOut", String.class);
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                        "produce-secret",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(secretValue),
                                                        new AtomicInteger()),
                                        secretOut))
                        .step(
                                WorkflowSteps.action(
                                        "produce-public",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(
                                                                secretValue + "-suffix"),
                                                        new AtomicInteger()),
                                        publicOut))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(result.toString()).doesNotContain(secretValue).contains("***");
    }

    @Test
    void secCross003OverlappingSecretsInPublicFieldFullyRedacted() {
        WorkflowVariable<String> secretB = WorkflowVariable.secret("secretB");
        WorkflowVariable<String> comment = WorkflowVariable.publicValue("comment", String.class);
        WorkflowInputs inputs =
                WorkflowInputs.builder()
                        .put(TOKEN, "abc")
                        .put(secretB, "abcdef")
                        .put(comment, "abcdef and abc")
                        .build();

        String rendered = inputs.toString();

        assertThat(rendered).doesNotContain("abcdef").doesNotContain("***def");
    }

    // ---- SEC-OUTPUT / SEC-ACTION: secret participation in later diagnostics -------------------

    @Test
    void secOutput001SecretOutputProtectsLaterFailureRedaction() {
        String secretValue = "producedSecret987";
        WorkflowVariable<String> secretOut = WorkflowVariable.secret("secretOut");
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                        "produce",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.success(secretValue),
                                                        new AtomicInteger()),
                                        secretOut))
                        .step(
                                WorkflowSteps.action(
                                        "leaky",
                                        vars -> {
                                            throw new RuntimeException(
                                                    "unexpected " + vars.require(secretOut));
                                        }))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().safeMessage())
                .doesNotContain(secretValue)
                .contains("***");
    }

    @Test
    void secAction001ActionFailureMessageSecretIsRedacted() {
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(TOKEN)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.<String>failure(
                                                                ActionFailureType.BACKEND_FAILURE,
                                                                "credential "
                                                                        + vars.require(TOKEN)
                                                                        + " rejected"),
                                                        new AtomicInteger())))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(TOKEN, SECRET_SENTINEL).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().safeMessage())
                .doesNotContain(SECRET_SENTINEL)
                .contains("***");
    }

    // ---- SEC-MSG: bounding and null-safety -----------------------------------------------------

    @Test
    void secMsg001HugeFactoryExceptionMessageIsBounded() {
        String hugeMessage = "y".repeat(5_000);
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> {
                                            throw new IllegalStateException(hugeMessage);
                                        }))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.failure().orElseThrow().safeMessage().length())
                .isLessThan(hugeMessage.length());
    }

    @Test
    void secMsg002HugeActionFailureMessageIsBounded() {
        String hugeMessage = "z".repeat(5_000);
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars ->
                                                new FakePreparedAction<>(
                                                        ActionResults.<String>failure(
                                                                ActionFailureType.BACKEND_FAILURE,
                                                                hugeMessage),
                                                        new AtomicInteger())))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.failure().orElseThrow().safeMessage().length())
                .isLessThan(hugeMessage.length());
    }

    @Test
    void secMsg003SecretNearTruncationBoundaryIsFullyRedacted() {
        String prefix = "p".repeat(190);
        String message = prefix + SECRET_SENTINEL;
        Workflow workflow =
                Workflow.builder("wf")
                        .requiredInput(TOKEN)
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> {
                                            throw new RuntimeException(message);
                                        }))
                        .build();
        WorkflowInputs inputs = WorkflowInputs.builder().put(TOKEN, SECRET_SENTINEL).build();

        WorkflowResult result = engine.execute(workflow, inputs);

        assertThat(result.failure().orElseThrow().safeMessage()).doesNotContain(SECRET_SENTINEL);
    }

    @Test
    void secMsg004NullExceptionMessageGetsSafeDeterministicFallback() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> {
                                            throw new IllegalStateException((String) null);
                                        }))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().safeMessage()).isNotBlank();
    }

    // ---- THREAD / ACTION-EX: execution guarantees ----------------------------------------------

    @Test
    void thread001ConditionFactoryAndPreparedExecuteAllRunOnCallingThread() {
        long callingThreadId = Thread.currentThread().threadId();
        java.util.concurrent.atomic.AtomicLong conditionThread =
                new java.util.concurrent.atomic.AtomicLong(-1);
        java.util.concurrent.atomic.AtomicLong factoryThread =
                new java.util.concurrent.atomic.AtomicLong(-1);
        java.util.concurrent.atomic.AtomicLong executeThread =
                new java.util.concurrent.atomic.AtomicLong(-1);

        IWorkflowCondition condition =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        conditionThread.set(Thread.currentThread().threadId());
                        return true;
                    }

                    @Override
                    public String describe() {
                        return "threadCapture";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of();
                    }
                };

        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                                "s1",
                                                vars -> {
                                                    factoryThread.set(
                                                            Thread.currentThread().threadId());
                                                    return new RecordingPreparedAction<>(
                                                            () -> ActionResults.success("ok"),
                                                            executeThread);
                                                })
                                        .when(condition))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isTrue();
        assertThat(conditionThread).hasValue(callingThreadId);
        assertThat(factoryThread).hasValue(callingThreadId);
        assertThat(executeThread).hasValue(callingThreadId);
    }

    @Test
    void actionEx001PreparedExecuteRuntimeExceptionBecomesStructuredStepException() {
        AtomicInteger step1Calls = new AtomicInteger();
        AtomicInteger step2Calls = new AtomicInteger();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.action(
                                        "s1",
                                        vars -> {
                                            step1Calls.incrementAndGet();
                                            return new RecordingPreparedAction<String>(
                                                    () -> {
                                                        throw new RuntimeException(
                                                                "backend exploded");
                                                    },
                                                    null);
                                        }))
                        .step(
                                WorkflowSteps.action(
                                        "s2",
                                        vars -> {
                                            step2Calls.incrementAndGet();
                                            return new FakePreparedAction<>(
                                                    ActionResults.success("ok"),
                                                    new AtomicInteger());
                                        }))
                        .build();

        WorkflowResult result = engine.execute(workflow, WorkflowInputs.empty());

        assertThat(result.completed()).isFalse();
        assertThat(result.failure().orElseThrow().type())
                .isEqualTo(WorkflowFailureType.STEP_EXCEPTION);
        assertThat(result.steps().get(0).status()).isEqualTo(WorkflowStepStatus.FAILED);
        assertThat(result.steps().get(1).status()).isEqualTo(WorkflowStepStatus.NOT_RUN);
        assertThat(step1Calls).hasValue(1);
        assertThat(step2Calls).hasValue(0);
    }
}
