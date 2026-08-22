# Plugins

Phase 0.9-B provides one deliberately small extension point: trusted Java service providers may
contribute custom locator strategies. Discovery is explicit, deterministic, immutable, and
fail-closed.

> Without an explicit call to `PluginLoader`, WebAgent4J loads zero plugins.

The default `new LocatorEngine()` path never scans the classpath and behaves exactly as it does
without plugin provider JARs present.

## Dependency

Applications that opt into discovery add `webagent4j-plugin-api`. The module depends only on
`webagent4j-locator`; locator and the other domain engines never depend on the plugin API.

```xml
<dependency>
  <groupId>io.webagent4j</groupId>
  <artifactId>webagent4j-plugin-api</artifactId>
</dependency>
```

Use the project BOM to manage the version, as described in the
[public API guide](public-api.md#choosing-modules).

## Architecture

```text
Application explicitly calls PluginLoader
                  |
                  v
ServiceLoader<ILocatorStrategyProvider>
                  |
                  v
       validated PluginRegistry
                  |
                  v
       LocatorStrategyRegistry
                  |
                  v
            LocatorEngine
```

`PluginLoader` is the only production discovery boundary. It has no static registry, cache,
watcher, reload mechanism, or hidden startup hook. Every call creates a new independent registry.

## Supported extension point

Providers implement only `ILocatorStrategyProvider`:

```java
public interface ILocatorStrategyProvider {
    PluginDescriptor descriptor();

    List<ILocatorStrategy> strategies();
}
```

Each provider must return one deterministic descriptor and a non-empty deterministic list. Every
strategy must report `LocatorStrategyType.CUSTOM`, have a stable non-blank ID, and use the existing
`phase()`, `priority()`, and `id()` ordering contract. Plugins cannot replace or override standard
strategies.

## Plugin metadata

`PluginDescriptor` contains only `PluginId` and `PluginVersion`.

- `PluginId` uses a lowercase ASCII identity of 1 to 128 characters matching
  `[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*`.
- `PluginVersion` is an opaque printable label of at most 64 characters. It describes the plugin;
  WebAgent4J does not interpret Maven versions, compare versions, negotiate compatibility, or solve
  dependencies.
- Both fields are diagnostic metadata and must never contain secrets.

A duplicate plugin ID is always a configuration error, even when the two versions differ.

## Registering a provider

For example, a provider can expose an application-defined `AccessibleHintStrategy`:

```java
package com.example.web;

import io.webagent4j.locator.ILocatorStrategy;
import io.webagent4j.plugin.ILocatorStrategyProvider;
import io.webagent4j.plugin.PluginDescriptor;
import io.webagent4j.plugin.PluginId;
import io.webagent4j.plugin.PluginVersion;
import java.util.List;

public final class AccessibleHintStrategyProvider implements ILocatorStrategyProvider {
    @Override
    public PluginDescriptor descriptor() {
        return new PluginDescriptor(
                new PluginId("com.example.accessible-hints"),
                new PluginVersion("1.0.0"));
    }

    @Override
    public List<ILocatorStrategy> strategies() {
        return List.of(new AccessibleHintStrategy());
    }
}
```

The provider JAR registers this service file:

```text
META-INF/services/io.webagent4j.plugin.ILocatorStrategyProvider
```

with the provider class name as its UTF-8 content:

```text
com.example.web.AccessibleHintStrategyProvider
```

The project does not use JPMS modules, so no separate module descriptor is required.

## Loading and using strategies

Use the thread context class loader explicitly through `load()`:

```java
PluginRegistry plugins = new PluginLoader().load();
LocatorEngine locator = new LocatorEngine(plugins.locatorStrategyRegistry());
```

Or supply exactly one caller-owned class loader:

```java
PluginRegistry plugins = new PluginLoader().load(applicationClassLoader);
```

`load(ClassLoader)` requires a non-null loader, searches only that loader, does not create a child
loader, does not fall back to other loaders, does not close the loader, and does not retain it in a
global cache. `load()` uses only `Thread.currentThread().getContextClassLoader()` and fails
explicitly if it is absent.

## Deterministic ordering

Service file or classpath order is not semantic order:

1. provider entries are sorted by fully qualified provider class name before construction;
2. providers are instantiated in that order;
3. `descriptor()` and `strategies()` are each called once;
4. plugin descriptors are exposed in `PluginId` order;
5. custom strategy execution uses `LocatorStrategyRegistry`: phase, priority descending, then ID.

The loader validates strategy metadata but never calls `supports(...)` or `discover(...)`. Those
callbacks run only when the explicitly configured `LocatorEngine` resolves a locator.

## Duplicate and invalid contribution handling

Loading either returns one complete `PluginRegistry` or throws `PluginLoadException`. There is no
partial success, first-wins policy, last-wins policy, automatic renaming, or version selection.

| Condition | Result |
| --- | --- |
| Duplicate `PluginId` | `DUPLICATE_PLUGIN_ID` |
| Duplicate custom strategy ID | `DUPLICATE_LOCATOR_STRATEGY_ID` |
| Custom ID collides with a standard strategy ID | `DUPLICATE_LOCATOR_STRATEGY_ID` |
| Provider contributes a standard strategy type | `INVALID_LOCATOR_STRATEGY` |
| Null, blank, empty, or otherwise invalid contribution | structured load failure |
| Malformed service declaration | `SERVICE_CONFIGURATION_ERROR` |

`PluginLoadFailure` exposes only a small category, optional non-sensitive identities, and a
framework-owned safe message. Translated exceptions have no raw provider exception as their public
cause, and arbitrary provider messages are never copied into the diagnostic.

## Trust and runtime failures

The plugin facility is not a sandbox. Calling `PluginLoader` opts into constructing and running
trusted third-party Java code in-process with normal JVM permissions. Provider constructors,
metadata callbacks, and strategy callbacks can perform arbitrary work. Review provider JARs exactly
as any other application dependency.

The loader catches `ServiceConfigurationError` at the service boundary and translates provider
`RuntimeException` failures during registration. It deliberately does not catch arbitrary JVM
`Error` values. After loading, runtime exceptions from `supports(...)` or `discover(...)` propagate
through the normal locator call; they are not silently converted to an empty result.

## Non-goals

Phase 0.9-B does not provide plugin lifecycle callbacks, dependency injection, configuration
schemas, permissions, isolation, a separate process, a plugin directory scanner, annotation
scanning, network downloads, hot reload, unloading, file watching, plugin dependency resolution,
recording plugins, workflow-step plugins, action plugins, crawler plugins, or browser-provider
plugins. Broader extension points require separate design work.

## Testing providers

Provider tests should use a real `META-INF/services` declaration and a caller-owned isolated test
class loader. Verify deterministic ordering, duplicate rejection, immutable registry snapshots, and
the default zero-plugin path. The WebAgent4J integration suite additionally runs a test-only provider
through `PluginLoader`, `PluginRegistry`, `LocatorStrategyRegistry`, `LocatorEngine`, and a real
Chromium page.
