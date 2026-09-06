package io.webagent4j.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Build-time validation matrix for {@link WorkflowStepType#PARALLEL}: branch-count bounds, nesting
 * depth, and cross-branch output-collision rejection. See {@code docs/workflow.md#parallel} and
 * {@link WorkflowParallelActionSafetyTest} for the fail-closed ACTION-in-PARALLEL rejection matrix.
 */
class WorkflowParallelBuilderTest {

    private static final WorkflowVariable<String> OUT_A =
            WorkflowVariable.publicValue("outA", String.class);
    private static final WorkflowVariable<String> OUT_B =
            WorkflowVariable.publicValue("outB", String.class);

    /** A deterministic, framework-owned literal assignment - always permitted inside PARALLEL. */
    private static IWorkflowStep publish(String id, WorkflowVariable<String> output) {
        return WorkflowSteps.assign(id, output, id + "-value");
    }

    // --- PAR-001: a minimal, valid two-branch parallel builds cleanly ----------------------

    @Test
    void par001TwoBranchesBuildCleanly() {
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(publish("a", OUT_A)),
                                                List.of(publish("b", OUT_B)))))
                        .build();

        assertThat(workflow.steps()).hasSize(1);
    }

    // --- PAR-002: fewer than MIN_PARALLEL_BRANCHES is rejected ------------------------------

    @Test
    void par002SingleBranchRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(WorkflowSteps.parallel("par", List.of(List.of(publish("a", OUT_A)))));

        assertThatThrownBy(builder::build)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("branches");

        WorkflowValidationReport report = builder.validate();
        assertThat(report.diagnostics())
                .anySatisfy(
                        d ->
                                assertThat(d.code())
                                        .isEqualTo(
                                                WorkflowValidationCode
                                                        .PARALLEL_INVALID_BRANCH_COUNT));
    }

    // --- PAR-003: more than MAX_PARALLEL_BRANCHES is rejected -------------------------------

    @Test
    void par003TooManyBranchesRejected() {
        List<List<IWorkflowStep>> branches = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            branches.add(List.of(assignFiller("b" + i)));
        }
        Workflow.Builder builder =
                Workflow.builder("wf").step(WorkflowSteps.parallel("par", branches));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.validate().diagnostics())
                .anySatisfy(
                        d ->
                                assertThat(d.code())
                                        .isEqualTo(
                                                WorkflowValidationCode
                                                        .PARALLEL_INVALID_BRANCH_COUNT));
    }

    private static IWorkflowStep assignFiller(String id) {
        return WorkflowSteps.assign(
                id, WorkflowVariable.publicValue(id + "Var", String.class), "x");
    }

    // --- PAR-004: an empty branch is rejected at the factory method itself ------------------

    @Test
    void par004EmptyBranchRejectedByFactory() {
        assertThatThrownBy(
                        () ->
                                WorkflowSteps.parallel(
                                        "par", List.of(List.of(publish("a", OUT_A)), List.of())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- PAR-009: ASSIGN is always safe, even nested inside a loop inside a branch ----------

    @Test
    void par009AssignNestedInsideLoopInsideBranchAccepted() {
        IWorkflowCondition falseOnce = falseConditionOnce();
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(
                                                        WorkflowSteps.loop(
                                                                "innerLoop",
                                                                falseOnce,
                                                                3,
                                                                List.of(
                                                                        WorkflowSteps.assign(
                                                                                "assignStep",
                                                                                WorkflowVariable
                                                                                        .publicValue(
                                                                                                "loopVar",
                                                                                                String
                                                                                                        .class),
                                                                                "x")))),
                                                List.of(publish("b", OUT_B)))))
                        .build();
        assertThat(workflow.steps()).hasSize(1);
    }

    // --- PAR-010: two branches declaring the same output name collide, even identically ----

    @Test
    void par010CrossBranchOutputCollisionRejected() {
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(publish("a", OUT_A)),
                                                List.of(publish("b", OUT_A)))));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.validate().diagnostics())
                .anySatisfy(
                        d ->
                                assertThat(d.code())
                                        .isEqualTo(WorkflowValidationCode.OUTPUT_COLLISION));
    }

    // --- PAR-011: PARALLEL nesting depth shares MAX_CONTROL_FLOW_NESTING_DEPTH --------------

    @Test
    void par011ExcessiveNestingDepthRejected() {
        List<IWorkflowStep> innermost = List.of(publish("leaf", OUT_A));
        IWorkflowStep nested =
                WorkflowSteps.parallel("p0", List.of(innermost, List.of(publish("b0", OUT_B))));
        for (int depth = 1; depth <= 65; depth++) {
            IWorkflowStep finalNested = nested;
            nested =
                    WorkflowSteps.parallel(
                            "p" + depth,
                            List.of(
                                    List.of(finalNested),
                                    List.of(publish("filler" + depth, OUT_B))));
        }
        Workflow.Builder builder = Workflow.builder("wf").step(nested);

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.validate().diagnostics())
                .anySatisfy(
                        d ->
                                assertThat(d.code())
                                        .isEqualTo(
                                                WorkflowValidationCode
                                                        .PARALLEL_NESTING_DEPTH_EXCEEDED));
    }

    // --- PAR-012: an output published by every unguarded branch is definite afterward ------

    @Test
    void par012UnguardedParallelOutputIsDefiniteAfterward() {
        IWorkflowCondition referencesOutA =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        return true;
                    }

                    @Override
                    public String describe() {
                        return "refA";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of(OUT_A);
                    }
                };
        Workflow workflow =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                        "par",
                                        List.of(
                                                List.of(publish("a", OUT_A)),
                                                List.of(publish("b", OUT_B)))))
                        .step(
                                WorkflowSteps.ifThen(
                                        "afterwards",
                                        referencesOutA,
                                        List.of(
                                                publish(
                                                        "c",
                                                        WorkflowVariable.publicValue(
                                                                "outC", String.class)))))
                        .build();
        assertThat(workflow.steps()).hasSize(2);
    }

    // --- PAR-013: a guarded PARALLEL step's outputs are never definite afterward ------------

    @Test
    void par013GuardedParallelOutputNeverDefinite() {
        IWorkflowCondition alwaysTrue = alwaysTrueCondition();
        IWorkflowCondition referencesOutA =
                new IWorkflowCondition() {
                    @Override
                    public boolean evaluate(IWorkflowVariables variables) {
                        return true;
                    }

                    @Override
                    public String describe() {
                        return "refA";
                    }

                    @Override
                    public Set<WorkflowVariable<?>> referencedVariables() {
                        return Set.of(OUT_A);
                    }
                };
        Workflow.Builder builder =
                Workflow.builder("wf")
                        .step(
                                WorkflowSteps.parallel(
                                                "par",
                                                List.of(
                                                        List.of(publish("a", OUT_A)),
                                                        List.of(publish("b", OUT_B))))
                                        .when(alwaysTrue))
                        .step(
                                WorkflowSteps.ifThen(
                                        "afterwards",
                                        referencesOutA,
                                        List.of(
                                                publish(
                                                        "c",
                                                        WorkflowVariable.publicValue(
                                                                "outC", String.class)))));

        assertThatThrownBy(builder::build).isInstanceOf(IllegalArgumentException.class);
        assertThat(builder.validate().diagnostics())
                .anySatisfy(
                        d ->
                                assertThat(d.code())
                                        .isEqualTo(
                                                WorkflowValidationCode
                                                        .OUTPUT_NOT_DEFINITELY_AVAILABLE));
    }

    // --- PAR-014: a PARALLEL step supports when(), unlike ifElse/loop -----------------------

    @Test
    void par014ParallelSupportsWhenGuard() {
        IWorkflowStep guarded =
                WorkflowSteps.parallel(
                                "par",
                                List.of(List.of(publish("a", OUT_A)), List.of(publish("b", OUT_B))))
                        .when(alwaysTrueCondition());
        assertThat(guarded.condition()).isPresent();
    }

    private static IWorkflowCondition alwaysTrueCondition() {
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return true;
            }

            @Override
            public String describe() {
                return "alwaysTrue";
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of();
            }
        };
    }

    private static IWorkflowCondition falseConditionOnce() {
        return new IWorkflowCondition() {
            @Override
            public boolean evaluate(IWorkflowVariables variables) {
                return false;
            }

            @Override
            public String describe() {
                return "alwaysFalse";
            }

            @Override
            public Set<WorkflowVariable<?>> referencedVariables() {
                return Set.of();
            }
        };
    }
}
