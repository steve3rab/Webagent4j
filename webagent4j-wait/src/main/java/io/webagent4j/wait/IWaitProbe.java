package io.webagent4j.wait;

/**
 * A single, side-effect-free reading of some external condition.
 *
 * <p>{@link WaitEngine} treats a probe purely as a read: it may be invoked many times over the life
 * of one {@link WaitEngine#await(WaitBudget, WaitPolicy, IWaitProbe)} call, and must never perform
 * an action that would be wrong to repeat - clicking a button, submitting a form, mutating state.
 * Trigger a side effect once, outside the wait, then probe its read-only outcome.
 *
 * <p>The engine does not interpret exceptions on the domain's behalf: a probe that throws
 * propagates the exception immediately, ending the wait right there. This is deliberate - the
 * engine has no way to know whether a given exception means "not there yet, keep trying" or
 * "ambiguous", "backend crashed", "wrong scope". Only the domain-specific probe implementation can
 * tell {@link WaitSample#pending()} apart from a failure that must stop the wait outright; when it
 * can, it should catch that specific, well-understood condition and return {@link
 * WaitSample#pending()} instead of throwing.
 */
@FunctionalInterface
public interface IWaitProbe<T> {

    /** Takes one reading. */
    WaitSample<T> evaluate();
}
