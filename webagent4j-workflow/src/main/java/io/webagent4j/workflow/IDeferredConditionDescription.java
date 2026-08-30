package io.webagent4j.workflow;

/**
 * Package-private extension of {@link IWorkflowCondition}, implemented only by this module's own
 * built-in conditions ({@link WorkflowConditions}) - never by a caller-supplied condition, since
 * this type is not reachable outside this package.
 *
 * <p>A condition that implements this interface lets {@link WorkflowEngine} skip the eager,
 * unbounded rendering {@link IWorkflowCondition#describe()} would otherwise perform at evaluation
 * time, deferring it to {@link #describeFinal} instead - called once, at workflow finalization,
 * when the complete secret set is already known, so the potentially large rendered text is created,
 * redacted, and (by the caller, immediately - see below) bounded within that one short-lived call
 * rather than retained unbounded for the rest of the execution (see {@code WF-MEM-001}).
 *
 * <p>{@link #describeFinal} itself redacts but does not bound its own result: {@link
 * WorkflowEngine} applies exactly one final bound to whatever it returns, immediately after calling
 * it, the same way it always has for any condition description - so a composite built from several
 * {@link IDeferredConditionDescription} children (see {@link WorkflowConditions#not}/{@code
 * allOf}/{@code anyOf}) is bounded as a complete, composed whole rather than each leaf
 * independently, which could otherwise let the total text grow with the number of composed
 * conditions even though every leaf were itself individually bounded.
 *
 * <p>{@link #describeFinal} must never invoke caller-supplied code: {@link WorkflowEngine} calls it
 * outside the per-step defensive scaffolding that protects {@link IWorkflowCondition#evaluate} and
 * {@link IWorkflowCondition#describe}, so only a type built entirely from framework-owned logic
 * (rendering a public literal via {@link SafeRendering}, never invoking a custom {@code
 * describe()}) may safely implement this interface.
 */
interface IDeferredConditionDescription extends IWorkflowCondition {

    /**
     * Renders and redacts (against {@code finalRedactor}, built from the workflow's complete final
     * secret set) this condition's description - the same transformation {@link
     * IWorkflowCondition#describe()} plus {@link WorkflowEngine}'s existing redaction would
     * produce, just computed at finalization time instead of evaluation time, so no unbounded
     * intermediate rendering is ever retained. Deliberately does not bound the result itself - see
     * the class Javadoc.
     */
    String describeFinal(SecretRedactor finalRedactor);
}
