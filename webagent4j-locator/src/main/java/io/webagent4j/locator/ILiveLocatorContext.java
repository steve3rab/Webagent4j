package io.webagent4j.locator;

import java.util.Objects;

/**
 * Supplies the {@link LocatorContext} a resolution should search against, resolved fresh on demand
 * rather than captured once.
 *
 * <p>{@link LocatorEngine} calls {@link #resolve()} once per polling attempt of a logical wait, not
 * once for the whole wait: an implementation backed by a structured semantic scope (see {@code
 * PlaywrightScopeResolver} in {@code webagent4j-browser-playwright}) re-evaluates that scope
 * against the current DOM on every attempt, instead of freezing it into whatever concrete node it
 * resolved to on the first attempt. A structured scope that has become ambiguous, or that fails for
 * a genuine backend/runtime reason, must let {@link #resolve()} throw that failure unchanged; a
 * structured scope that is simply not currently present should throw a typed "not found" failure
 * (see {@code io.webagent4j.common.LocatorFailureClassifier}), which {@link LocatorEngine} treats
 * exactly like a momentarily-absent target rather than a terminal failure.
 *
 * <p>{@link #baseline()} is the stable, unscoped starting context - it carries the backend and
 * configuration a wait needs to size its budget and polling policy before it has resolved anything,
 * and never itself depends on live DOM state, so it is always safe to read once before a wait's
 * polling loop begins.
 */
public interface ILiveLocatorContext {

    /**
     * Returns the stable context available before any scope narrowing has been attempted. Used only
     * for the backend/configuration a wait needs up front (timeout, polling interval, ambiguity
     * margin); never used to search for a candidate directly.
     */
    LocatorContext baseline();

    /**
     * Resolves the current live context. Called once per polling attempt.
     *
     * @throws RuntimeException a typed "not found" failure for a scope that does not currently
     *     exist (treated as transient by callers waiting for candidates), or an ambiguous/backend
     *     failure that must propagate immediately
     */
    LocatorContext resolve();

    /**
     * Returns a live context whose {@link #resolve()} always returns the same already-resolved
     * value.
     */
    static ILiveLocatorContext fixed(LocatorContext context) {
        Objects.requireNonNull(context, "context");
        return new ILiveLocatorContext() {
            @Override
            public LocatorContext baseline() {
                return context;
            }

            @Override
            public LocatorContext resolve() {
                return context;
            }
        };
    }
}
