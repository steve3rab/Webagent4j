# Contribution guide

The canonical policy is [CONTRIBUTING.md](../CONTRIBUTING.md). Keep public APIs small, document their
contracts and thread-safety expectations, preserve immutable result values, and introduce an abstraction
only when a tested architectural problem needs it. Changes to module edges should update
[modules.md](modules.md) and, when structurally important, add a concise ADR.
