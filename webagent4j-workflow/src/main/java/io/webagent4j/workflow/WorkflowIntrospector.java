package io.webagent4j.workflow;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Produces a {@link WorkflowIntrospectionReport} from an already-built, already-valid {@link
 * Workflow} - a static complexity and safety-surface summary, computed entirely from the
 * definition's own already-validated structure. See {@code
 * docs/workflow.md#static-workflow-introspection} for the full contract and its relationship to
 * {@link WorkflowValidationReport}, {@link WorkflowExecutionPlan}, and {@link
 * WorkflowExecutionTree}.
 *
 * <p>{@link #inspect(Workflow)} is strictly static: it never evaluates an {@link
 * IWorkflowCondition} (not even {@link IWorkflowCondition#referencedVariables()} - unlike {@code
 * Workflow.Builder#build()} /{@code validate()}, which must call that metadata method, this report
 * needs nothing a caller-supplied condition implementation could get wrong), never calls {@link
 * IWorkflowActionFactory#prepare}, never resolves or contacts a backend, browser, or network
 * destination, and never creates a thread, executor, or {@code Future} - a {@link
 * WorkflowStepType#PARALLEL} step's branches are inspected sequentially, in declaration order,
 * exactly like every other container step's contents. This class is stateless and safe to share
 * across threads: {@link #inspect(Workflow)} depends on nothing but its argument, and two calls
 * with the same {@code workflow} - and any two logically-equal, independently-built {@code
 * Workflow} instances - always produce {@link Object#equals equal} reports.
 *
 * <p>Because {@link Workflow.Builder#build()} is the only way to obtain a {@link Workflow}, every
 * definition this class can ever receive is already known to be structurally valid and already
 * within {@link Workflow#MAX_CONTROL_FLOW_NESTING_DEPTH}. This lets {@link #inspect(Workflow)} skip
 * every collision/diagnostic concern {@code Workflow.Builder}'s own analysis must handle (an output
 * name colliding with an existing input or an earlier step's output cannot occur in an
 * already-valid definition) while still computing definite assignment with the exact same
 * guard-aware rule documented on {@link Workflow} and on {@link
 * WorkflowIntrospectionOutput#definitelyAvailable()}: this is a deliberately independent, minimal
 * traversal - not a second call into {@code build()}/ {@code validate()} - since neither of those
 * internal analyses is reachable from outside {@link Workflow}.
 *
 * <p>The whole traversal, including {@link
 * WorkflowIntrospectionReport#maximumPotentialExecutionNodes()}'s worst-case computation, visits
 * each declared step exactly a small, fixed number of times - never proportional to any declared
 * {@code maxIterations} or branch count, never a physical unrolling. Pass 1's guard-aware definite
 * assignment (see {@link #inspectStep}) never copies the full accumulated {@code declared}/{@code
 * definite} state into a branch: {@link #inspectStep}/{@link #inspectBranch} return only the {@link
 * FlowDelta} - the variables a step or branch itself newly introduces relative to its own entry
 * point - which a caller merges into its own running sets in time proportional to that delta's own
 * size, not to how many steps already precede it in the traversal. Since {@code workflow} is
 * already known to be collision-free, a given output variable can appear in at most two deltas
 * across the whole definition (the {@code then}/{@code else} branches of the single {@link
 * WorkflowStepType#CONDITIONAL} that may deliberately declare it identically in both - see {@link
 * #inspectStep}), so the total size of every delta ever produced is {@code O(N)} in the number
 * {@code N} of steps the definition declares. {@link #inspect(Workflow)}'s total cost - traversal
 * plus every delta merge combined - is therefore {@code O(N)}, independent of how large those
 * steps' own declared bounds ({@code maxIterations}, branch counts) are, and never proportional to
 * how many steps already precede a given branch.
 */
public final class WorkflowIntrospector {

    /**
     * Inspects {@code workflow} and returns its static {@link WorkflowIntrospectionReport}. Never
     * throws for a valid {@code workflow} - every {@link Workflow} this method can receive is
     * already known to be structurally valid, since {@link Workflow.Builder#build()} is the only
     * way to obtain one.
     *
     * @throws NullPointerException if {@code workflow} is {@code null}
     */
    public WorkflowIntrospectionReport inspect(Workflow workflow) {
        Objects.requireNonNull(workflow, "workflow");

        Metrics metrics = new Metrics();
        Set<WorkflowVariable<?>> declared = new LinkedHashSet<>();
        Set<WorkflowVariable<?>> definite = new LinkedHashSet<>();
        for (IWorkflowStep step : workflow.steps()) {
            FlowDelta delta = inspectStep(step, 0, metrics);
            declared.addAll(delta.declared());
            definite.addAll(delta.definite());
        }

        List<WorkflowIntrospectionInput> inputs = new ArrayList<>();
        for (WorkflowVariable<?> variable : workflow.requiredInputs()) {
            inputs.add(toIntrospectionInput(variable, true));
        }
        for (WorkflowVariable<?> variable : workflow.optionalInputs()) {
            inputs.add(toIntrospectionInput(variable, false));
        }

        List<WorkflowIntrospectionOutput> outputs = new ArrayList<>(declared.size());
        int definitelyAvailableCount = 0;
        int secretOutputCount = 0;
        for (WorkflowVariable<?> variable : declared) {
            boolean isDefinite = definite.contains(variable);
            if (isDefinite) {
                definitelyAvailableCount++;
            }
            if (variable.secret()) {
                secretOutputCount++;
            }
            outputs.add(
                    new WorkflowIntrospectionOutput(
                            variable.name(),
                            variable.type().getSimpleName(),
                            variable.secret(),
                            isDefinite));
        }

        Potential potential = potentialOf(workflow.steps());
        boolean mayExceedBudget =
                potential.saturated()
                        || potential.value() > WorkflowEngine.MAX_EXECUTED_WORKFLOW_NODES;

        boolean containsSecrets =
                secretOutputCount > 0
                        || workflow.requiredInputs().stream().anyMatch(WorkflowVariable::secret)
                        || workflow.optionalInputs().stream().anyMatch(WorkflowVariable::secret);

        List<WorkflowStaticRiskIndicator> riskIndicators = new ArrayList<>();
        if (mayExceedBudget) {
            riskIndicators.add(WorkflowStaticRiskIndicator.MAY_EXCEED_RUNTIME_NODE_BUDGET);
        }
        if (metrics.loopCount > 0) {
            riskIndicators.add(WorkflowStaticRiskIndicator.CONTAINS_LOOPS);
        }
        if (metrics.parallelCount > 0) {
            riskIndicators.add(WorkflowStaticRiskIndicator.CONTAINS_PARALLELISM);
        }
        if (metrics.actionCount > 0) {
            riskIndicators.add(WorkflowStaticRiskIndicator.CONTAINS_ACTIONS);
        }
        if (secretOutputCount > 0) {
            riskIndicators.add(WorkflowStaticRiskIndicator.CONTAINS_SECRET_OUTPUTS);
        }

        int requiredInputCount = workflow.requiredInputs().size();
        int optionalInputCount = workflow.optionalInputs().size();

        return new WorkflowIntrospectionReport(
                workflow.id(),
                metrics.definitionNodeCount,
                metrics.maximumControlFlowDepth,
                metrics.conditionalCount,
                metrics.loopCount,
                metrics.parallelCount,
                metrics.actionCount,
                requiredInputCount + optionalInputCount,
                requiredInputCount,
                optionalInputCount,
                inputs,
                declared.size(),
                definitelyAvailableCount,
                secretOutputCount,
                outputs,
                metrics.maximumLoopIterations,
                metrics.maximumParallelBranches,
                metrics.totalParallelBranches,
                potential.value(),
                potential.saturated(),
                mayExceedBudget,
                metrics.actionCount > 0,
                metrics.loopCount > 0,
                metrics.parallelCount > 0,
                containsSecrets,
                List.copyOf(riskIndicators));
    }

    private static WorkflowIntrospectionInput toIntrospectionInput(
            WorkflowVariable<?> variable, boolean required) {
        return new WorkflowIntrospectionInput(
                variable.name(), variable.type().getSimpleName(), required, variable.secret());
    }

    // --- Pass 1: counts, control-flow depth, and guard-aware definite assignment -----------------

    /** Mutable accumulator for the single top-to-bottom traversal {@link #inspect} performs. */
    private static final class Metrics {
        private long definitionNodeCount;
        private int maximumControlFlowDepth;
        private long conditionalCount;
        private long loopCount;
        private long parallelCount;
        private long actionCount;
        private int maximumLoopIterations;
        private int maximumParallelBranches;
        private long totalParallelBranches;

        private void observeDepth(int depth) {
            if (depth > maximumControlFlowDepth) {
                maximumControlFlowDepth = depth;
            }
        }
    }

    /**
     * The variables one step or branch newly introduces relative to its own entry point - never a
     * copy of everything already declared/definite before it. {@code declared} may list the same
     * {@link WorkflowVariable} up to twice: once from each of a {@link
     * WorkflowStepType#CONDITIONAL}'s {@code then}/{@code else} branches, the one case {@code
     * workflow}'s own pre-validated collision-freedom allows the identical name to be
     * (deliberately) declared twice, since at runtime only one of the two branches ever runs (see
     * {@link #inspectStep}). Merging a delta into an enclosing {@link LinkedHashSet} is idempotent,
     * so this duplication never affects the final result - only how many list entries carry it on
     * the way there, which is why every delta size stays {@code O(1)} relative to the branch/step
     * that produced it rather than to how much of the definition precedes it.
     */
    private record FlowDelta(
            List<WorkflowVariable<?>> declared, Set<WorkflowVariable<?>> definite) {

        private static final FlowDelta EMPTY = new FlowDelta(List.of(), Set.of());
    }

    /**
     * Inspects one step and returns the {@link FlowDelta} it alone newly introduces, exactly like
     * {@code Workflow.Builder#validateStep} does for definite assignment - minus every collision
     * diagnostic, since {@code workflow} is already known to be free of them. Never reads or
     * mutates any outer accumulator: everything this method needs is already reachable from {@code
     * step} itself, its own branches, and the same guard-aware rule applied recursively.
     */
    private static FlowDelta inspectStep(
            IWorkflowStep step, int controlFlowDepth, Metrics metrics) {
        metrics.definitionNodeCount++;
        boolean guarded = step.condition().isPresent();
        // Safe: IWorkflowStep is sealed and permits only AWorkflowStep (same precedent as
        // WorkflowPlanner/WorkflowEngine).
        AWorkflowStep concreteStep = (AWorkflowStep) step;

        if (concreteStep instanceof ConditionalWorkflowStep conditional) {
            metrics.conditionalCount++;
            int nestedDepth = controlFlowDepth + 1;
            metrics.observeDepth(nestedDepth);
            FlowDelta thenDelta = inspectBranch(conditional.thenSteps(), nestedDepth, metrics);
            FlowDelta elseDelta =
                    inspectBranch(conditional.elseSteps().orElse(List.of()), nestedDepth, metrics);
            List<WorkflowVariable<?>> declaredOut =
                    new ArrayList<>(thenDelta.declared().size() + elseDelta.declared().size());
            declaredOut.addAll(thenDelta.declared());
            declaredOut.addAll(elseDelta.declared());
            // NOTE on guard-awareness here: unlike every other step type, a CONDITIONAL step's
            // inherited `condition` slot is never an optional when(...) skip-guard - it is the
            // mandatory branch selector (see ConditionalWorkflowStep's Javadoc), and
            // ConditionalWorkflowStep#withCondition unconditionally throws
            // UnsupportedOperationException, so `guarded` above is always true for this branch and
            // deliberately never consulted below - a "guarded conditional" cannot be constructed at
            // all (WorkflowSteps#ifThen/#ifElse never support when(...); only a leaf ACTION/ASSIGN
            // step or a PARALLEL step's condition slot is an actual optional guard). The
            // intersection
            // below is therefore unconditional: an output both branches newly and unconditionally
            // guarantee identically is definite; ifThen's implicit empty else branch means a
            // then-only output is never in the intersection, regardless.
            Set<WorkflowVariable<?>> definiteOut = new LinkedHashSet<>();
            for (WorkflowVariable<?> candidate : thenDelta.definite()) {
                if (elseDelta.definite().contains(candidate)) {
                    definiteOut.add(candidate);
                }
            }
            return new FlowDelta(declaredOut, definiteOut);
        }
        if (concreteStep instanceof LoopWorkflowStep loop) {
            metrics.loopCount++;
            int nestedDepth = controlFlowDepth + 1;
            metrics.observeDepth(nestedDepth);
            if (loop.maxIterations() > metrics.maximumLoopIterations) {
                metrics.maximumLoopIterations = loop.maxIterations();
            }
            FlowDelta bodyDelta = inspectBranch(loop.body(), nestedDepth, metrics);
            // A loop may run zero iterations, so nothing its body might produce is ever definite -
            // regardless of whether individual body steps are themselves guarded (see
            // Workflow.Builder#validateStep's own LOOP handling, and WorkflowSteps#loop's Javadoc).
            return new FlowDelta(bodyDelta.declared(), Set.of());
        }
        if (concreteStep instanceof ParallelWorkflowStep parallel) {
            metrics.parallelCount++;
            int nestedDepth = controlFlowDepth + 1;
            metrics.observeDepth(nestedDepth);
            List<List<IWorkflowStep>> branches = parallel.branches();
            if (branches.size() > metrics.maximumParallelBranches) {
                metrics.maximumParallelBranches = branches.size();
            }
            metrics.totalParallelBranches += branches.size();
            List<WorkflowVariable<?>> declaredOut = new ArrayList<>();
            Set<WorkflowVariable<?>> definiteOut = guarded ? Set.of() : new LinkedHashSet<>();
            for (List<IWorkflowStep> branch : branches) {
                FlowDelta branchDelta = inspectBranch(branch, nestedDepth, metrics);
                declaredOut.addAll(branchDelta.declared());
                if (!guarded) {
                    definiteOut.addAll(branchDelta.definite());
                }
            }
            return new FlowDelta(declaredOut, definiteOut);
        }
        if (concreteStep instanceof ActionWorkflowStep<?>) {
            metrics.actionCount++;
        }
        Optional<WorkflowVariable<?>> output = concreteStep.outputVariable();
        if (output.isEmpty()) {
            return FlowDelta.EMPTY;
        }
        WorkflowVariable<?> variable = output.get();
        return new FlowDelta(List.of(variable), guarded ? Set.of() : Set.of(variable));
    }

    /**
     * Inspects one branch's steps in isolation and returns only the {@link FlowDelta} the branch as
     * a whole newly introduces - mirroring {@code Workflow.Builder#validateBranch}, but never
     * copying the enclosing scope's already-accumulated {@code declared}/{@code definite} state:
     * each step's own delta is merged into a fresh, branch-local accumulator whose final size is
     * exactly what this branch itself declares, not what precedes it.
     */
    private static FlowDelta inspectBranch(
            List<IWorkflowStep> branchSteps, int controlFlowDepth, Metrics metrics) {
        List<WorkflowVariable<?>> declared = new ArrayList<>();
        Set<WorkflowVariable<?>> definite = new LinkedHashSet<>();
        for (IWorkflowStep step : branchSteps) {
            FlowDelta delta = inspectStep(step, controlFlowDepth, metrics);
            declared.addAll(delta.declared());
            definite.addAll(delta.definite());
        }
        return new FlowDelta(declared, definite);
    }

    // --- Pass 2: saturating worst-case executed-node potential --------------------------------

    /**
     * A non-negative {@code long} paired with whether it saturated at {@link Long#MAX_VALUE} rather
     * than reflecting the true, possibly larger, mathematical result - see {@link
     * WorkflowIntrospectionReport#maximumPotentialExecutionNodesSaturated()}.
     */
    private record Potential(long value, boolean saturated) {

        private static final Potential SATURATED = new Potential(Long.MAX_VALUE, true);

        private static Potential of(long value) {
            return new Potential(value, false);
        }

        private Potential plus(Potential other) {
            if (saturated || other.saturated) {
                return SATURATED;
            }
            try {
                return of(Math.addExact(value, other.value));
            } catch (ArithmeticException overflow) {
                return SATURATED;
            }
        }

        private Potential plusOne() {
            return plus(of(1));
        }

        private Potential times(long factor) {
            if (saturated) {
                return SATURATED;
            }
            try {
                return of(Math.multiplyExact(value, factor));
            } catch (ArithmeticException overflow) {
                return SATURATED;
            }
        }

        private static Potential worstOf(Potential a, Potential b) {
            if (a.saturated || b.saturated) {
                return SATURATED;
            }
            return a.value >= b.value ? a : b;
        }
    }

    /**
     * Returns the worst-case node potential of running {@code steps} in sequence: the sum of each
     * step's own potential, in order.
     */
    private static Potential potentialOf(List<IWorkflowStep> steps) {
        Potential total = Potential.of(0);
        for (IWorkflowStep step : steps) {
            total = total.plus(potentialOf(step));
        }
        return total;
    }

    /**
     * Returns one step's own worst-case node potential - the maximum number of flat {@code
     * WorkflowResult#steps()} entries a single execution reaching this step could produce for it
     * and everything it might run, given only its declared structural bounds:
     *
     * <ul>
     *   <li>a leaf ({@link WorkflowStepType#ACTION}/{@link WorkflowStepType#ASSIGN}) step always
     *       contributes exactly {@code 1} - its own entry;
     *   <li>a {@link WorkflowStepType#CONDITIONAL} step contributes {@code 1} (its own decision
     *       entry) plus whichever of its two branches has the larger potential - never the sum of
     *       both, since at runtime exactly one branch ever runs;
     *   <li>a {@link WorkflowStepType#LOOP} step contributes {@code 2} (its own wrapper entry, plus
     *       one final continuation-check entry that either stops the loop or discovers {@code
     *       LOOP_ITERATION_LIMIT_EXCEEDED}) plus {@code maxIterations} copies of ({@code 1}
     *       iteration decision entry plus the body's own potential) - the exact shape {@code
     *       WorkflowEngine}'s own bounded-iteration loop produces, never a plain {@code
     *       maxIterations * body} estimate;
     *   <li>a {@link WorkflowStepType#PARALLEL} step contributes {@code 1} (its own wrapper entry)
     *       plus, for every declared branch, {@code 1} (that branch's own {@link
     *       WorkflowStepType#PARALLEL_BRANCH} wrapper entry) plus that branch's own potential - the
     *       sum across every branch, never the maximum, since every declared branch genuinely runs.
     * </ul>
     *
     * Every addition and multiplication here uses {@link Potential}'s saturating arithmetic, so a
     * definition whose worst case would overflow a {@code long} - a deeply nested chain of loops
     * each declaring a large {@code maxIterations}, for example - reports {@link Long#MAX_VALUE}
     * with {@link WorkflowIntrospectionReport#maximumPotentialExecutionNodesSaturated()} set,
     * rather than wrapping around to a small or negative number.
     */
    private static Potential potentialOf(IWorkflowStep step) {
        // Safe: IWorkflowStep is sealed and permits only AWorkflowStep.
        AWorkflowStep concreteStep = (AWorkflowStep) step;
        if (concreteStep instanceof ConditionalWorkflowStep conditional) {
            Potential thenCost = potentialOf(conditional.thenSteps());
            Potential elseCost =
                    conditional
                            .elseSteps()
                            .map(WorkflowIntrospector::potentialOf)
                            .orElse(Potential.of(0));
            return Potential.worstOf(thenCost, elseCost).plusOne();
        }
        if (concreteStep instanceof LoopWorkflowStep loop) {
            Potential bodyCost = potentialOf(loop.body());
            Potential perIteration = bodyCost.plusOne();
            Potential everyIteration = perIteration.times(loop.maxIterations());
            return everyIteration.plusOne().plusOne();
        }
        if (concreteStep instanceof ParallelWorkflowStep parallel) {
            Potential total = Potential.of(0);
            for (List<IWorkflowStep> branch : parallel.branches()) {
                total = total.plus(potentialOf(branch).plusOne());
            }
            return total.plusOne();
        }
        return Potential.of(1);
    }
}
