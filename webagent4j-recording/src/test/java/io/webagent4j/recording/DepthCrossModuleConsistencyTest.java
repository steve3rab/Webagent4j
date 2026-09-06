package io.webagent4j.recording;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.webagent4j.workflow.IWorkflowCondition;
import io.webagent4j.workflow.IWorkflowStep;
import io.webagent4j.workflow.IWorkflowVariables;
import io.webagent4j.workflow.Workflow;
import io.webagent4j.workflow.WorkflowEngine;
import io.webagent4j.workflow.WorkflowExecution;
import io.webagent4j.workflow.WorkflowExecutionPlan;
import io.webagent4j.workflow.WorkflowInputs;
import io.webagent4j.workflow.WorkflowPlanner;
import io.webagent4j.workflow.WorkflowSteps;
import io.webagent4j.workflow.WorkflowVariable;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * DEPTH-CROSS coverage: {@link RecordingV2PlanTreeValidator#MAX_TREE_DEPTH} and {@code
 * Workflow#MAX_CONTROL_FLOW_NESTING_DEPTH} are two independently-declared constants - one in this
 * module, one package-private inside {@code io.webagent4j.workflow} and therefore unreachable from
 * here at compile time - that this module's own Javadoc already documents as required to carry "the
 * same value" (see {@link RecordingV2PlanTreeValidator}'s class Javadoc). Nothing in the language
 * or the build enforces that by itself: a future change to either constant alone would silently
 * desynchronize them, in one of two directions - Recording V2 rejecting a genuinely valid,
 * maximally-deep real execution (its own ceiling too low), or Recording V2 accepting a plan/tree
 * shape deeper than any live {@code Workflow} could ever legitimately produce (its own ceiling too
 * high, a purely dormant looseness given {@link io.webagent4j.recording.replay.ReplayValidator}'s
 * own exact-plan-equality check against the live workflow would still reject a merely-deep-but-
 * unmatched plan).
 *
 * <p>Rather than exposing the internal, deliberately non-public-API bound as new public surface
 * purely to let one module's test suite assert equality with the other's, this suite instead proves
 * the two stay behaviorally synchronized end-to-end, through only public API plus this module's own
 * already-accessible {@link RecordingV2PlanTreeValidator#MAX_TREE_DEPTH}: a live {@link Workflow}
 * nested to exactly that depth must build, execute, and round-trip through {@link
 * WorkflowRecorderV2}/{@link JsonWorkflowRecordingV2Codec} cleanly (Recording's ceiling is not
 * lower than the live boundary), and one nested one level deeper must be rejected by {@code
 * Workflow.Builder#build()} itself (the live boundary is not higher than Recording's ceiling) -
 * closing the gap in both directions without needing to unhide either module's own internal bound.
 */
class DepthCrossModuleConsistencyTest {

    private static final int MAX = RecordingV2PlanTreeValidator.MAX_TREE_DEPTH;
    private static final WorkflowVariable<String> LEAF_OUTPUT =
            WorkflowVariable.publicValue("leafOutput", String.class);

    private static final IWorkflowCondition ALWAYS_TRUE =
            new IWorkflowCondition() {
                @Override
                public boolean evaluate(IWorkflowVariables variables) {
                    return true;
                }

                @Override
                public String describe() {
                    return "true";
                }

                @Override
                public Set<WorkflowVariable<?>> referencedVariables() {
                    return Set.of();
                }
            };

    /**
     * Iteratively builds a chain of {@code depth} nested {@code ifThen} steps wrapping {@code leaf}
     * at the bottom - built bottom-up with a plain loop, never recursion, so this helper's own
     * stack usage is O(1) regardless of {@code depth}, mirroring {@code
     * WorkflowConditionalNestingDepthTest}'s identical discipline in {@code webagent4j-workflow}.
     */
    private static IWorkflowStep nestedChain(String idPrefix, int depth, IWorkflowStep leaf) {
        IWorkflowStep current = leaf;
        for (int level = depth; level >= 1; level--) {
            current = WorkflowSteps.ifThen(idPrefix + "-" + level, ALWAYS_TRUE, List.of(current));
        }
        return current;
    }

    // --- DEPTH-CROSS-001/002: Recording's ceiling is not lower than the live boundary ---------

    @Test
    void depthCross001And002ExactlyRecordingsMaxTreeDepthBuildsExecutesAndRoundTrips() {
        IWorkflowStep root = nestedChain("d", MAX, WorkflowSteps.assign("leaf", LEAF_OUTPUT, "v"));
        Workflow workflow = Workflow.builder("wf-depth-cross-max").step(root).build();

        WorkflowExecutionPlan plan = WorkflowPlanner.plan(workflow);
        WorkflowExecution execution =
                new WorkflowEngine().executeWithTree(workflow, WorkflowInputs.empty());
        assertThat(execution.result().completed()).isTrue();

        WorkflowRecordingV2 recording =
                new WorkflowRecorderV2()
                        .record(
                                new RecordingId("rec-depth-cross-max"),
                                Instant.parse("2026-01-01T00:00:00Z"),
                                plan,
                                execution);

        JsonWorkflowRecordingV2Codec codec = new JsonWorkflowRecordingV2Codec();
        WorkflowRecordingV2 decoded = codec.decode(codec.encode(recording));
        assertThat(decoded).isEqualTo(recording);
    }

    // --- DEPTH-CROSS-003: the live boundary is not higher than Recording's ceiling -------------

    @Test
    void depthCross003OneMoreThanRecordingsMaxTreeDepthIsRejectedByTheLiveWorkflowItself() {
        IWorkflowStep root =
                nestedChain("d", MAX + 1, WorkflowSteps.assign("leaf", LEAF_OUTPUT, "v"));

        assertThatThrownBy(() -> Workflow.builder("wf-depth-cross-over").step(root).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
