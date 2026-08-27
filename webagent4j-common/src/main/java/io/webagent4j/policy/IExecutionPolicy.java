package io.webagent4j.policy;

/**
 * A synchronous, in-process authorization gate: given an immutable context {@code C}, decide
 * whether the operation it describes may proceed.
 *
 * <p><strong>Contract:</strong>
 *
 * <ul>
 *   <li><strong>Synchronous only.</strong> Implementations must not block on network I/O, an MCP
 *       call, an LLM call, a database query, or any other remote round trip. Evaluation is expected
 *       to complete quickly and deterministically given its input.
 *   <li><strong>Fail closed.</strong> A thrown exception (checked or unchecked), a returned {@code
 *       null}, or any other failure to produce a decision must be treated by the caller as if this
 *       method returned {@link PolicyDecision#deny}. This interface itself does not catch or
 *       translate exceptions - callers that invoke a policy in a governed pipeline are responsible
 *       for wrapping evaluation so that any failure denies rather than silently allows.
 *   <li><strong>No hidden retry.</strong> An implementation must not retry the underlying decision
 *       itself; if it needs to consult something unreliable, that unreliability must be surfaced as
 *       an exception, not masked with a retry loop the caller cannot observe.
 *   <li><strong>Two outcomes only.</strong> {@link PolicyOutcome#ALLOW} or {@link
 *       PolicyOutcome#DENY} - there is no way to express "ask a human" or "not sure yet" through
 *       this contract.
 *   <li><strong>Untrusted, unsandboxed code.</strong> A caller-supplied implementation runs as
 *       ordinary Java code with no sandboxing. This framework guarantees it will not invoke a
 *       governed backend before a policy allows it, but it cannot prevent or undo side effects a
 *       malicious or buggy policy implementation performs itself during evaluation.
 * </ul>
 *
 * @param <C> the immutable context type this policy evaluates
 */
@FunctionalInterface
public interface IExecutionPolicy<C> {

    /**
     * Evaluates {@code context} and returns a terminal decision.
     *
     * @param context the immutable context describing the operation to authorize
     * @return the decision; never {@code null} for a well-behaved implementation, though callers
     *     must defend against a misbehaving one returning {@code null} regardless
     */
    PolicyDecision evaluate(C context);
}
