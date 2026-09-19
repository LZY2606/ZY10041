# Shared sealed model & validation

## Goal

moshi-sealed has three execution backends that all validate the same hierarchy rules
(label presence/uniqueness, default subtype exclusivity, object subtypes, nested sealed
structure):

- **codegen** (`:moshi-sealed:codegen`, KSP, compile time)
- **reflect** (`:moshi-sealed:reflect`, Kotlin reflection, runtime)
- **metadata-reflect** (`:moshi-sealed:metadata-reflect`, kotlin-metadata + Java reflection, runtime)

Previously each backend walked the sealed hierarchy and re-implemented every check with its
own control flow, error timing, and message wording. This change extracts one
backend-agnostic sealed model + validator into `:moshi-sealed:runtime` and makes all three
backends share it. Each backend is now only responsible for (a) exposing its symbols as a
probe, and (b) instantiating the adapter from the validated model.

## Architecture

New package `dev.zacsweers.moshix.sealed.runtime.model` in `:moshi-sealed:runtime`
(no new module; `runtime` is already a dependency of all three backends and has no
compiler-plugin dependencies):

- `SealedClassProbe<T>` — backend-agnostic, read-only view of one class: `isSealed`,
  `isObject`, `hasTypeParameters`, `jsonClassLabelKey`, annotation presence flags,
  `typeLabel`, `sealedSubtypes`, `supertypeLabelKeys`, plus a backend `handle`
  (`KSClassDeclaration` / `Class<*>`), a stable `identity` for deduplication, and a
  `displayName` for messages.
- `SealedModelValidator.validate(root, labelKey)` — the single implementation of all
  hierarchy rules. Returns `SealedModelResult`:
  - `Valid(ValidatedSealedModel(labelKey, labeledSubtypes, fallback))` — everything a
    backend needs to instantiate an adapter: ordered labeled subtypes (primary label +
    alternates, `isObject`) and a `SealedFallback` (`None` / `NullValue` /
    `DefaultObject(probe)` / `FallbackAdapter`).
  - `Invalid(errors)` — a list of `SealedError(code, message, nodes)`.
- `SealedError.render()` — canonical text `"MOSHIX_SEALED_<CODE>: <core message>"`, identical
  on every entry point for the same invalid model. Backends add their own context on top:
  codegen passes the offending `KSClassDeclaration` to `logger.error(...)` so the compiler
  attaches source locations; runtime backends throw `IllegalStateException` prefixed with
  `"Invalid sealed model for <rawType>:"` so the type context is preserved.

Backend probes:

- codegen: `KspSealedClassProbe` (KSP symbols)
- reflect: `ReflectSealedClassProbe` (`KClass`)
- metadata-reflect: `MetadataSealedClassProbe` (`Class` + `KmClass`)

Dependency direction is strictly `codegen|reflect|metadata-reflect → runtime`. No runtime
module depends on the compiler plugin, and nothing calls back into the generator
(reflectively or otherwise).

## Error codes

| Code | Core message |
| --- | --- |
| `MISSING_TYPE_LABEL` | `Sealed subtypes must be annotated with @TypeLabel to define their label: <type>` |
| `DUPLICATE_LABEL` | `Duplicate label '<label>' defined for <A> and <B>.` |
| `DUPLICATE_ALTERNATE_LABEL` | `Duplicate alternate label '<label>' defined for <A> and <B>.` |
| `GENERIC_SUBTYPE` | `Moshi-sealed subtypes cannot be generic: <type>` |
| `DEFAULT_OBJECT_NOT_OBJECT` | `Must be an object type to use as a @DefaultObject: <type>` |
| `CONFLICTING_DEFAULTS` | `Only one of @DefaultNull, @DefaultObject, and @FallbackJsonAdapter can be used at a time: <root>` |
| `MULTIPLE_DEFAULT_OBJECTS` | `Can only have one @DefaultObject: <A> and <B> are both annotated` |
| `REDUNDANT_SEALED_GENERATOR` | `Sealed subtype <type> is redundantly annotated with @JsonClass(generator = "sealed:<key>").` |
| `NESTED_SEALED_SAME_LABEL` | `@NestedSealed-annotated subtype <type> is inappropriately annotated with @JsonClass(generator = "sealed:<key>").` |
| `MISSING_SEALED_SUPERTYPE` | `No JsonClass-annotated sealed supertype found for <type>` |
| `TYPE_LABEL_WITH_SEALED_GENERATOR` | `Sealed subtype <type> is annotated with @JsonClass(generator = "sealed:...") and @TypeLabel.` |

## What stays backend-specific

- **Eligibility**: whether a type is handled at all (non-sealed, non-`sealed:` generator,
  `generateAdapter = false` at compile time, missing kotlin metadata) is still decided by
  each backend before validation, as before.
- **codegen only**: fallback-adapter constructor visibility/`Moshi`-parameter checks
  (these inspect code, not the sealed model) and source generation (`TypeCreation`,
  unchanged).
- **runtime only**: `ObjectJsonAdapter` registration, `@FallbackJsonAdapter` instantiation
  (`Util.fallbackAdapter`), and the `PolymorphicJsonAdapterFactory` wiring.

## Compatibility

- **Legal models**: JSON output is unchanged. The shared runtime checks
  (`LegalSealedModelChecks`) assert identical JSON for object subtypes, alternate labels,
  nested sealed, `@DefaultNull`, `@DefaultObject`, `@FallbackJsonAdapter`, data-class
  subtypes, and unknown-label failure on both runtime entry points; the pre-existing
  `:moshi-sealed:sample` tests (unchanged) also pass.
- **Generated source**: unchanged. The golden-file compilation tests (`smokeTest`,
  `objectAdapters`, `separateFiles`) assert the exact generated source and were not
  modified.
- **Proguard/R8**: no consumer-proguard files were touched
  (`git diff -- '**/resources/META-INF/**'` is empty).
- **Public API**: purely additive (`runtime.api` +163 lines, generated by
  `./gradlew :moshi-sealed:runtime:apiDump`); `reflect`, `metadata-reflect`, and `codegen`
  API dumps are unchanged (`apiCheck` passes).

### Intentional error-semantics unifications (invalid models only)

- All backends now emit the same code + core message (see table). Some message wordings
  changed (e.g. compile-time `"Missing @TypeLabel"` → the shared `MISSING_TYPE_LABEL`
  text; three different "conflicting defaults" wordings → one `CONFLICTING_DEFAULTS` text).
- `@DefaultObject` on a non-object type is now a compile-time error too
  (`DEFAULT_OBJECT_NOT_OBJECT`); previously codegen silently treated it as a regular
  subtype.
- The generic-subtype check now runs on labeled subtypes in all backends (previously the
  runtime backends additionally rejected generic *intermediate* sealed nodes directly
  under the root).
- A subtype reachable through two hierarchy paths (e.g. implementing both the root and a
  nested sealed intermediate) is now registered once in all backends. This also fixes a
  latent codegen bug where such a type was emitted into `.withSubtype(...)` twice.
- Compile time still reports **all** errors with source locations; runtime throws the
  full list in one `IllegalStateException`.

### Out of scope

`:moshi-sealed:java-sealed-reflect` is a pure-Java module (no Kotlin plugin/stdlib) and
keeps its own checks for now; migrating it would require either adding a Kotlin
dependency to that artifact or a Java port of the validator.

## Cross-entry fixtures

`moshi-sealed/runtime/src/testFixtures/.../fixtures/` defines the fixtures once:

- `InvalidSealedFixtures.kt` — 12 invalid models + expected `SealedErrorCode` + message
  fragment (`INVALID_SEALED_FIXTURES`): duplicate label, duplicate alternate label,
  missing label, generic subtype, three conflicting-default shapes, two default objects,
  non-object default object, redundant nested generator, `@TypeLabel` + sealed generator,
  nested sealed with the parent's label, orphaned `@NestedSealed`.
- `SealedModelFixtureSources.kt` — Kotlin source mirrors of the same models for the
  compilation test, plus `assertInvalidSealedModel(moshi, fixture)`.
- `LegalSealedFixtures.kt` — legal models (object subtypes, nested sealed, `@DefaultNull`,
  `@DefaultObject`, `@FallbackJsonAdapter`, data-class subtype) + shared JSON assertions
  including unknown-label behavior.

Consumers:

- `:moshi-sealed:codegen` — `SealedModelFixtureCompilationTest` runs each fixture source
  through a real KSP compilation (kotlin-compile-testing) and asserts
  `COMPILATION_ERROR` + shared code/message.
- `:moshi-sealed:reflect` — `ReflectLegalSealedModelTest` /
  `ReflectInvalidSealedModelTest`.
- `:moshi-sealed:metadata-reflect` — `MetadataReflectLegalSealedModelTest` /
  `MetadataReflectInvalidSealedModelTest`.
- `:moshi-sealed:runtime` — `SealedModelValidatorTest` unit-tests the validator directly
  with a fake probe.

## Duplication statistics

Metric: number of hardcoded label/default/duplicate check sites (message emission points)
per module, counted with:

```sh
PAT='Duplicate label|Duplicate alternate label|cannot be generic|Must be an object type|Only one of @DefaultNull|Only one of @DefaultObject|Cannot have both @DefaultNull|Can only have one @DefaultObject|redundantly annotated|inappropriately annotated|No JsonClass-annotated sealed supertype|Missing @TypeLabel|must be annotated with @TypeLabel|and @TypeLabel'
# before:  git show HEAD:<file> | grep -cE "$PAT"
# after:   grep -cE "$PAT" <file>
```

Raw output (run from the repo root at the commit before this change vs. the worktree):

```
== BEFORE (git HEAD) ==
codegen/.../MoshiSealedSymbolProcessorProvider.kt: 11
codegen/.../TypeCreation.kt: 1
reflect/.../MoshiSealedJsonAdapterFactory.kt: 11
metadata-reflect/.../MetadataMoshiSealedJsonAdapterFactory.kt: 11
java-sealed-reflect/.../JavaSealedJsonAdapterFactory.java: 7   (out of scope, unchanged)

== AFTER (worktree) ==
codegen/.../MoshiSealedSymbolProcessorProvider.kt: 0
codegen/.../TypeCreation.kt: 1          (unreachable invariant assert in source generation)
reflect/.../MoshiSealedJsonAdapterFactory.kt: 1          (instantiation-time checkNotNull)
metadata-reflect/.../MetadataMoshiSealedJsonAdapterFactory.kt: 1  (same)
runtime/.../model/SealedModelValidator.kt: 11            (the single shared copy)
java-sealed-reflect/...: 7           (out of scope, unchanged)
```

Summary:

- In-scope backends went from **3 independent implementations / 34 check sites** to
  **1 shared implementation / 11 check sites** (−67% implementations, −68% sites; the
  remaining 3 sites are instantiation-time invariant assertions, not rule reimplementations).
- File sizes (`wc -l`, same repro as above): codegen processor 488 → 316 lines,
  reflect factory 214 → 189, metadata-reflect factory 257 → 233; the shared core is
  325 new lines (`SealedModel.kt` 123 + `SealedModelValidator.kt` 202) plus 91 lines of
  KSP probe. Total across the three backends: 959 → 829 lines.

## Verification

From the repository root (no external services, env vars, or network needed):

```sh
./gradlew assemble                                              # prep
./gradlew :moshi-sealed:test :moshi-metadata-reflect:test       # acceptance
```

`:moshi-sealed:test` is an aggregate task (see `moshi-sealed/build.gradle.kts`) covering
`:moshi-sealed:codegen:test` (real KSP compilation tests),
`:moshi-sealed:reflect:test` and `:moshi-sealed:metadata-reflect:test` (the two runtime
entry points), plus `:moshi-sealed:runtime:test`, `:moshi-sealed:java-sealed-reflect:test`,
and `:moshi-sealed:sample:test`. Test names are printed via
`testLogging { events("PASSED", "FAILED", "SKIPPED") }` in the modules with new tests.

Environment used for the numbers above: macOS (Apple Silicon), Gradle wrapper in-repo,
JDK toolchain from `gradle/libs.versions.toml`; all counts are machine-independent
(`grep`/`wc` on source), and no performance claims are made.
