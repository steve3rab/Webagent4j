# Contribution guide

The canonical repository contribution policy lives in `../CONTRIBUTING.md`. This page only records documentation-specific expectations.

For code changes that affect supported behavior:

- update useful public Javadoc;
- update the relevant domain guide;
- update `contracts.md` only when the rule is genuinely cross-module;
- update `security-model.md` for trust/security-boundary changes;
- update `limitations.md` when a limitation is added/removed;
- update `modules.md`/architecture ADRs for structural dependency changes;
- add migration/release notes for compatibility-impacting changes;
- add deterministic tests proving the changed contract.

Follow [documentation-governance.md](documentation-governance.md) rather than copying the same matrix into multiple guides.
