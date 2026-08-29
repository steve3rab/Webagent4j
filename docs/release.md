# Release procedure

This is the release-readiness runbook for WebAgent4J 1.0 and later. It separates “a tag exists” from “the exact code, artifacts, documentation, and publication were verified”.

## 1. Freeze the candidate

- Start from `main` only after all intended release work is merged through the repository's review policy.
- Record the exact candidate commit SHA.
- Ensure the working tree/repository state contains no unreviewed release-only patches.
- Confirm there are no unresolved P0/P1 correctness, security, resource-ownership, or documentation findings.

## 2. Version and compatibility review

Before tagging any release:

- replace development snapshot/candidate versions with the intended release version through the normal versioning workflow;
- verify all supported module versions align;
- verify BOM coverage matches the supported-artifact policy (including the intentional/current treatment of `webagent4j-common`);
- review deprecated APIs and migration notes;
- confirm Recording schema remains V1 unless a separately reviewed schema change is intended;
- confirm CLI compatibility policy for the release;
- update versioned documentation/Javadoc destinations.

## 3. Documentation gate

Review at least:

- `docs/index.md` and documentation authority rules;
- `getting-started.md` installation commands and release coordinates;
- `public-api.md`, `api-stability.md`, `modules.md`;
- `support-matrix.md` browser/OS qualification;
- `security-model.md` and repository `SECURITY.md` for identical security boundaries;
- domain guides and `limitations.md`;
- `migration-to-1.0.md` for the first 1.0 release;
- release notes/changelog outside `docs/`;
- generated Javadoc for all supported public API/SPI.

No current guide should claim an old development phase is still in progress. No release documentation should point only to moving `api/latest` when an immutable version-specific Javadoc should exist.

## 4. Exact-head verification

From a clean checkout of the candidate SHA:

```bash
./mvnw --batch-mode --no-transfer-progress clean verify
```

The release workflow's own `verify` job additionally runs the complete adversarial robustness corpus
once per qualified engine, failing closed on the first engine that does not pass with zero retry:

```bash
for browser in chromium firefox webkit; do
  ./mvnw --batch-mode --no-transfer-progress -Probustness -Drobustness.browser="$browser" verify
done
```

A release must not publish unless every qualified engine passes this way; see
[support-matrix.md](support-matrix.md#browser-and-robustness-qualification-by-operating-system) for the
current per-engine qualification scope. Run any release-specific packaging profile as required. Do
not add test retry as a release workaround.

Verify GitHub CI, CodeQL, and Dependency Review all correspond to the **same exact candidate SHA**. A green check on an older PR head does not certify a newer commit.

Immediately before GO, re-fetch the branch/tag head and compare the SHA again.

## 5. Artifact review

Inspect produced JAR/POM/source/Javadoc artifacts rather than assuming Maven packaging correctness from compilation alone.

Check:

- expected files/modules only;
- release version in filenames/POM metadata;
- sources and Javadoc attachments where the publication repository requires them;
- license/notice files preserved in source and redistributed/shaded artifacts as required by dependency licenses;
- shaded CLI contains no unexpected development metadata/secrets and starts successfully;
- checksums/signatures are present where the chosen repository requires them;
- generated Javadocs open and link correctly.

## 6. Repository publication

A GitHub Release and a Maven repository publication are separate outcomes. Do not document Maven Central availability until Central actually serves the coordinates.

If Maven Central is the selected distribution repository, verify all Central-required POM metadata, developer/SCM/license data, source/Javadoc artifacts, signatures/checksums, namespace ownership, and immutable publication rules with the current Central requirements before release. Publication infrastructure must be reviewed separately from this documentation.

## 7. Versioned documentation

For each release, publish immutable documentation/Javadoc paths, for example:

```text
/api/1.0.0/
/docs/1.0.0/   (when narrative docs are published as a site)
```

A moving `latest` alias may exist, but release notes must link to the immutable version path.

## 8. Post-publication verification

After publication:

- resolve the library from the public repository in a fresh consumer project;
- launch the primary supported browser with the published coordinates;
- run a minimal locator/action example;
- run CLI `version`/help from the published CLI artifact if distributed;
- open versioned Javadocs/docs;
- verify release notes point to the correct tag/SHA/artifacts;
- confirm no published artifact is accidentally a snapshot.

Published immutable artifacts are not replaced in place to fix a defect. Issue a new version and document the correction.

## External repository files that must be aligned

This `docs/` package cannot by itself make the repository release-ready. Before any final release, also align repository-root/build files such as:

- `README.md` release/status/dependency instructions;
- `SECURITY.md` with [security-model.md](security-model.md), especially current crawler/SSRF/robots/plugin boundaries;
- `CHANGELOG.md` with the final hardening implementation rather than an intermediate design;
- root/module POM release metadata, version, developer/signing/publication configuration;
- `Dockerfile`/packaging paths that hard-code a snapshot filename;
- GitHub Pages workflow so versioned Javadoc is retained;
- release workflow/publication credentials/configuration;
- branch/ruleset protection so documented merge gates are actually enforced.

A final release verdict is **NO-GO** until these external gates match the documentation.
