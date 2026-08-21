/**
 * Deterministic, sequential workflow orchestration over the existing action pipeline: typed
 * variables, masked secret inputs and outputs, small declarative conditions, and structured
 * fail-fast results.
 *
 * <p>{@link io.webagent4j.workflow.Workflow} is an immutable, reusable definition built through
 * {@link io.webagent4j.workflow.Workflow.Builder}; {@link io.webagent4j.workflow.WorkflowEngine}
 * executes it against explicit {@link io.webagent4j.workflow.WorkflowInputs} and returns a
 * structured {@link io.webagent4j.workflow.WorkflowResult}. Execution is strictly sequential,
 * always on the calling thread, fail-fast on the first failed step, and never retries a step. This
 * package intentionally does not implement loops, branching graphs, parallel execution,
 * persistence, scheduling, or any scripting/expression language - see {@code docs/workflow.md} for
 * the complete contract, secret-masking security boundary, and non-goals.
 */
package io.webagent4j.workflow;
