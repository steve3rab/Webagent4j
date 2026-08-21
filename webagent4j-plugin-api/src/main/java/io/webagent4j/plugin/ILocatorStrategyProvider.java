package io.webagent4j.plugin;

import io.webagent4j.locator.ILocatorStrategy;
import io.webagent4j.locator.LocatorStrategyType;
import java.util.List;

/**
 * Explicit {@link java.util.ServiceLoader} extension point for custom locator strategies.
 *
 * <p>Implementations are discovered only when {@link PluginLoader} is explicitly called. Provider
 * construction and runtime strategy execution are trusted in-process Java code and are not
 * sandboxed. Both methods must be deterministic and side-effect-free. Metadata must be
 * non-sensitive, returned lists and elements must be non-null, every strategy must report {@link
 * LocatorStrategyType#CUSTOM}, and every strategy ID must be stable.
 */
public interface ILocatorStrategyProvider {

    /** Returns deterministic, non-sensitive plugin metadata. */
    PluginDescriptor descriptor();

    /** Returns a deterministic non-empty list of custom locator strategies. */
    List<ILocatorStrategy> strategies();
}
