package io.webagent4j.workflow;

import java.util.List;
import java.util.Objects;

/**
 * Factory for the built-in {@link IWorkflowStep} kinds this module supports.
 *
 * <p>There is no generic {@code Runnable}/{@code Consumer<Map<String,Object>>} step here - every
 * step is either backed by the real action pipeline ({@link #action}), a single deterministic
 * literal assignment ({@link #assign}), a deterministic if/else branch ({@link #ifElse}, {@link
 * #ifThen}), or a bounded, deterministic loop ({@link #loop}) over more steps of these same kinds -
 * preserving type safety, structural validation, and secret provenance (see {@code
 * docs/workflow.md#steps}).
 */
public final class WorkflowSteps {

    private WorkflowSteps() {}

    /** An action step with no declared output variable. */
    public static <R> IWorkflowStep action(String stepId, IWorkflowActionFactory<R> factory) {
        Objects.requireNonNull(factory, "factory");
        return new ActionWorkflowStep<>(new WorkflowStepId(stepId), factory);
    }

    /**
     * An action step that publishes the underlying {@code ActionResult}'s value to {@code output}
     * on success.
     */
    public static <R> IWorkflowStep action(
            String stepId, IWorkflowActionFactory<R> factory, WorkflowVariable<R> output) {
        Objects.requireNonNull(factory, "factory");
        Objects.requireNonNull(output, "output");
        return new ActionWorkflowStep<>(new WorkflowStepId(stepId), factory, output);
    }

    /**
     * A step that deterministically assigns literal {@code value} to non-secret {@code variable}.
     *
     * <p>Secret literals are intentionally not supported: a secret value assigned here would live
     * permanently inside the immutable, reusable {@link Workflow} definition rather than being
     * supplied fresh per execution through {@link WorkflowInputs} - prefer a secret {@link
     * WorkflowInputs} input, or a secret action output, instead.
     *
     * @throws IllegalArgumentException if {@code variable} is {@link WorkflowVariable#secret()}, or
     *     {@code value} is null or not assignable to {@code variable}'s declared type
     */
    public static <T> IWorkflowStep assign(String stepId, WorkflowVariable<T> variable, T value) {
        Objects.requireNonNull(variable, "variable");
        if (variable.secret()) {
            throw new IllegalArgumentException(
                    "assign does not support secret variable '"
                            + variable.name()
                            + "' - secret literals would live permanently inside the workflow"
                            + " definition; supply it as a WorkflowInputs input instead");
        }
        variable.requireValid(value);
        return new AssignWorkflowStep<>(new WorkflowStepId(stepId), variable, value);
    }

    /**
     * A deterministic if/else step: {@link WorkflowEngine} evaluates {@code condition} exactly once
     * when this step is reached and then executes exactly one of {@code thenSteps} (if it evaluated
     * to {@code true}) or {@code elseSteps} (if {@code false}) - never both, never neither, and
     * never re-evaluates {@code condition} while running the selected branch. The branch that is
     * not selected produces zero step executions and zero backend side effects: it is never run for
     * validation, dry-run, or as a fallback (see {@code docs/workflow.md#branching}).
     *
     * <p>If {@code condition}'s evaluation itself fails (throws, or its {@code describe()} is
     * malformed), this step fails closed with {@link
     * WorkflowFailureType#CONDITION_EVALUATION_FAILED}: neither branch runs - a failed evaluation
     * is never treated as a {@code false} decision.
     *
     * <p>{@code thenSteps} and {@code elseSteps} may themselves contain {@code ifElse}/{@code
     * ifThen} steps: nested branching works the same way at every depth, up to {@link
     * Workflow#MAX_CONDITIONAL_NESTING_DEPTH} levels - a top-level conditional is depth 1, one
     * nested inside either of its branches is depth 2, and so on, with {@code thenSteps} and {@code
     * elseSteps} measured independently rather than summed. Every step ID across the whole workflow
     * - including inside every branch, at every nesting depth - must still be unique. Neither of
     * these is checked by this factory method itself, since it builds one step in isolation without
     * knowing where it will sit in a larger definition: {@link Workflow.Builder#build()} rejects a
     * duplicate ID or an excessive nesting depth once the whole tree is known.
     *
     * <p>Unlike {@link #action} and {@link #assign}, the returned step does not support {@link
     * IWorkflowStep#when} - see {@link ConditionalWorkflowStep}'s Javadoc for why.
     *
     * @throws IllegalArgumentException if {@code thenSteps} or {@code elseSteps} is empty
     */
    public static IWorkflowStep ifElse(
            String stepId,
            IWorkflowCondition condition,
            List<IWorkflowStep> thenSteps,
            List<IWorkflowStep> elseSteps) {
        Objects.requireNonNull(condition, "condition");
        requireNonEmptyBranch(thenSteps, "thenSteps");
        requireNonEmptyBranch(elseSteps, "elseSteps");
        return new ConditionalWorkflowStep(
                new WorkflowStepId(stepId), condition, thenSteps, elseSteps);
    }

    /**
     * Same as {@link #ifElse} without an else branch: a {@code false} decision is a no-op success
     * for this step - {@code thenSteps} never runs, and this step still counts as {@link
     * WorkflowStepStatus#SUCCEEDED}, not {@link WorkflowStepStatus#SKIPPED} (which is reserved for
     * a step's own optional {@link IWorkflowStep#when} guard evaluating false, a different
     * mechanism this step does not support - see {@link #ifElse}).
     *
     * @throws IllegalArgumentException if {@code thenSteps} is empty
     */
    public static IWorkflowStep ifThen(
            String stepId, IWorkflowCondition condition, List<IWorkflowStep> thenSteps) {
        Objects.requireNonNull(condition, "condition");
        requireNonEmptyBranch(thenSteps, "thenSteps");
        return new ConditionalWorkflowStep(new WorkflowStepId(stepId), condition, thenSteps, null);
    }

    private static void requireNonEmptyBranch(List<IWorkflowStep> steps, String paramName) {
        Objects.requireNonNull(steps, paramName);
        if (steps.isEmpty()) {
            throw new IllegalArgumentException(paramName + " must contain at least one step");
        }
    }

    /**
     * A bounded, deterministic loop: before each iteration attempt (up to {@code maxIterations} of
     * them), {@link WorkflowEngine} evaluates {@code continueCondition} exactly once; a {@code
     * true} result authorizes exactly that one iteration's {@code body} to run in full (never
     * re-evaluating the condition while the body runs), and a {@code false} result stops the loop
     * as a successful no-op for whatever iteration was being considered - the loop step itself
     * still counts as {@link WorkflowStepStatus#SUCCEEDED}. If {@code continueCondition} is still
     * {@code true} once {@code maxIterations} iterations have already run, the loop fails closed
     * with {@link WorkflowFailureType#LOOP_ITERATION_LIMIT_EXCEEDED} - reaching the bound while
     * continuation is still requested is never silently treated as a successful stop (see {@code
     * docs/workflow.md#bounded-loops}).
     *
     * <p>A failure inside {@code body} stops the whole workflow immediately, exactly like a failure
     * anywhere else: {@code WorkflowEngine} never retries the failed iteration, never retries the
     * continuation check, and never attempts a further iteration. A step inside {@code body} that
     * declares an output publishes it again on every iteration that reaches it - the value visible
     * after the loop is whichever iteration last published it - but that output is never treated as
     * <b>definitely</b> available to a later step's condition, exactly like a guarded producer's
     * output (see {@code docs/workflow.md#definition-validation}): the loop may run zero
     * iterations, so nothing it might produce can ever be statically guaranteed.
     *
     * <p>{@code body} may itself contain {@code ifElse}/{@code ifThen}/further {@code loop} steps,
     * up to {@link Workflow#MAX_CONTROL_FLOW_NESTING_DEPTH} combined levels of nesting - measured
     * and enforced exactly like conditional nesting, and independently of any other branch or loop
     * body's own depth. Every step ID across the whole workflow - including inside {@code body}, at
     * every nesting depth - must still be unique; neither this nesting bound nor that uniqueness is
     * checked by this factory method itself, since it builds one step in isolation without knowing
     * where it will sit in a larger definition - {@link Workflow.Builder#build()} rejects a
     * duplicate ID, an excessive nesting depth, or an out-of-range {@code maxIterations} once the
     * whole definition is known.
     *
     * <p>Unlike {@link #action} and {@link #assign}, the returned step does not support {@link
     * IWorkflowStep#when} - see {@link LoopWorkflowStep}'s Javadoc for why.
     *
     * @throws IllegalArgumentException if {@code body} is empty
     */
    public static IWorkflowStep loop(
            String stepId,
            IWorkflowCondition continueCondition,
            int maxIterations,
            List<IWorkflowStep> body) {
        Objects.requireNonNull(continueCondition, "continueCondition");
        requireNonEmptyBranch(body, "body");
        return new LoopWorkflowStep(
                new WorkflowStepId(stepId), continueCondition, maxIterations, body);
    }

    /**
     * A deterministic, strictly bounded parallel step - added in 1.3.0: {@link WorkflowEngine}
     * launches every one of {@code branches}, concurrently, on its own internal, bounded executor
     * (never a shared or unbounded one), waits for every branch to reach a terminal outcome, and
     * joins them in <b>branch-definition order</b> - never the order branches actually finished.
     * {@code branches} must declare between two and {@link Workflow#MAX_PARALLEL_BRANCHES} branches
     * (inclusive); {@link Workflow.Builder#build()} rejects an out-of-range count, an empty branch,
     * a branch nested deeper than {@link Workflow#MAX_CONTROL_FLOW_NESTING_DEPTH}, two branches
     * that would publish the same output name (even identically - unlike {@code ifElse}'s two
     * mutually exclusive branches, every {@code PARALLEL} branch genuinely runs, so two branches
     * racing to publish the same name is always a collision, never a safe redeclaration), and any
     * branch containing a step this framework cannot prove is read-only (see {@code
     * docs/workflow.md#parallel} and {@link IWorkflowActionFactory#isParallelSafe()}).
     *
     * <p>If any branch fails, the whole {@code PARALLEL} step fails: the reported failure is
     * whichever failed branch has the <b>lowest definition index</b> among every branch that failed
     * - never whichever branch happened to fail first in wall-clock time - and every branch
     * declared after it is represented as {@link WorkflowStepStatus#NOT_RUN}, regardless of what
     * that branch may have already computed in the background, since a {@code PARALLEL} branch is
     * never permitted to perform an observable side effect in the first place (see {@code
     * docs/workflow.md#parallel}). {@code WorkflowEngine} never retries a failed or cancelled
     * branch, never runs more branches concurrently than {@code branches} declares, and always
     * shuts down its internal executor - with no orphaned task and no leaked thread - before this
     * step's own result is produced, whether it succeeds, fails, or is interrupted.
     *
     * <p>A branch's own newly-declared outputs join the outer, guard-independent {@code declared}
     * set structurally (so a sibling step, or another branch, can never redeclare one of them), and
     * become <b>definite</b> for whatever structurally follows this step only when both this {@code
     * PARALLEL} step itself and that specific producing step are unguarded - unlike a loop body
     * (which may run zero iterations, so nothing in it is ever definite), every declared {@code
     * PARALLEL} branch unconditionally runs whenever the step itself is reached and its own guard
     * (if any) evaluates {@code true} (see {@code docs/workflow.md#definition-validation}).
     *
     * <p>Unlike {@link WorkflowSteps#ifElse}/{@link WorkflowSteps#ifThen}/{@link
     * WorkflowSteps#loop}, the returned step <em>does</em> support {@link IWorkflowStep#when}: a
     * {@code PARALLEL} step has no decision of its own to make, so an optional skip-guard works
     * exactly as it does for {@link #action}/{@link #assign} - a {@code false} guard skips the
     * whole step, launching zero branches.
     *
     * @throws IllegalArgumentException if {@code branches} or any one of its own branches is empty
     */
    public static IWorkflowStep parallel(String stepId, List<List<IWorkflowStep>> branches) {
        Objects.requireNonNull(branches, "branches");
        if (branches.isEmpty()) {
            throw new IllegalArgumentException("branches must contain at least one branch");
        }
        List<List<IWorkflowStep>> copied = new java.util.ArrayList<>(branches.size());
        for (List<IWorkflowStep> branch : branches) {
            requireNonEmptyBranch(branch, "branch");
            copied.add(List.copyOf(branch));
        }
        return new ParallelWorkflowStep(new WorkflowStepId(stepId), copied);
    }
}
