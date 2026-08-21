package io.webagent4j.workflow;

import java.util.Optional;

/**
 * Structural definition of one ordered step within a {@link Workflow}.
 *
 * <p>This is a closed type: Phase 0.8 does not expose a custom workflow-step extension point. Every
 * instance is created through {@link WorkflowSteps}, and the interface is {@code sealed} so no
 * other implementation can exist anywhere - the compiler rejects one at the point it would be
 * declared, and the JVM's {@code PermittedSubclasses} check rejects one that somehow reached the
 * classpath as compiled bytecode. That closure is what lets {@link WorkflowEngine} safely treat
 * every step it receives from a {@link Workflow} definition as one it knows how to run, without a
 * runtime type check that could otherwise fail for a caller-supplied implementation.
 */
public sealed interface IWorkflowStep permits AWorkflowStep {

    /** Returns this step's unique identifier within its workflow. */
    WorkflowStepId id();

    /** Returns this step's guard condition, if any. */
    Optional<IWorkflowCondition> condition();

    /**
     * Returns a copy of this step guarded by {@code condition}: {@link WorkflowStepStatus#SKIPPED}
     * when it evaluates to {@code false} rather than executing.
     */
    IWorkflowStep when(IWorkflowCondition condition);
}
