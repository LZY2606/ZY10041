// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.runtime.model

/**
 * Backend-agnostic view of one class in a sealed hierarchy.
 *
 * Each execution backend (KSP codegen, Kotlin reflection, metadata reflection) provides its own
 * implementation of this probe. [SealedModelValidator] then applies the shared moshi-sealed rules
 * to the probe tree without knowing anything about the underlying symbol or reflection API.
 *
 * @param T the backend-specific handle for a class (e.g. `KSClassDeclaration`, `Class<*>`).
 */
public interface SealedClassProbe<out T> {
  /** The backend-specific handle for this class. */
  public val handle: T

  /** A stable identity used to deduplicate types reachable through multiple paths. */
  public val identity: Any

  /** A human-readable name for this class, used in error messages. */
  public val displayName: String

  public val isSealed: Boolean
  public val isObject: Boolean

  /** The object instance if this is an object declaration and instances are available, else null. */
  public val objectInstance: Any?

  /** Whether this class declares its own type parameters. */
  public val hasTypeParameters: Boolean

  /** The label key from `@JsonClass(generator = "sealed:<key>")`, or null if absent. */
  public val jsonClassLabelKey: String?

  public val hasNestedSealed: Boolean
  public val hasDefaultNull: Boolean
  public val hasDefaultObject: Boolean
  public val hasFallbackJsonAdapter: Boolean

  /** The `@TypeLabel` data, or null if the annotation is absent. */
  public val typeLabel: TypeLabelData?

  /** Direct sealed subclasses of this class. Empty if this class is not sealed. */
  public val sealedSubtypes: List<SealedClassProbe<T>>

  /** Label keys of `@JsonClass(generator = "sealed:...")`-annotated supertypes. */
  public val supertypeLabelKeys: List<String>
}

/** Data extracted from a `@TypeLabel` annotation. */
public data class TypeLabelData(val label: String, val alternateLabels: List<String>)

/** Extracts the label key from a `@JsonClass` `generator` value, or null if it is not sealed. */
public fun sealedLabelKey(generator: String): String? =
  if (generator.startsWith("sealed:")) {
    generator.removePrefix("sealed:")
  } else {
    null
  }

/** Stable error codes shared by all moshi-sealed backends. */
public enum class SealedErrorCode {
  MISSING_TYPE_LABEL,
  DUPLICATE_LABEL,
  DUPLICATE_ALTERNATE_LABEL,
  GENERIC_SUBTYPE,
  DEFAULT_OBJECT_NOT_OBJECT,
  CONFLICTING_DEFAULTS,
  MULTIPLE_DEFAULT_OBJECTS,
  REDUNDANT_SEALED_GENERATOR,
  NESTED_SEALED_SAME_LABEL,
  MISSING_SEALED_SUPERTYPE,
  TYPE_LABEL_WITH_SEALED_GENERATOR,
}

/**
 * A single validation error. [render] produces the canonical `"<CODE>: <message>"` text that every
 * backend emits; backends add their own context on top (source locations at compile time, type
 * context at runtime).
 */
public data class SealedError<T>(
  val code: SealedErrorCode,
  val message: String,
  val nodes: List<SealedClassProbe<T>> = emptyList(),
) {
  public fun render(): String = "MOSHIX_SEALED_${code.name}: $message"
}

/** The result of validating a sealed hierarchy with [SealedModelValidator]. */
public sealed interface SealedModelResult<T> {
  public data class Valid<T>(val model: ValidatedSealedModel<T>) : SealedModelResult<T>

  public data class Invalid<T>(val errors: List<SealedError<T>>) : SealedModelResult<T>
}

/** A validated sealed model, ready for a backend to instantiate an adapter from. */
public data class ValidatedSealedModel<T>(
  val labelKey: String,
  val labeledSubtypes: List<LabeledSubtype<T>>,
  val fallback: SealedFallback<T>,
)

/** A subtype and all of its labels (primary label first, then alternates). */
public data class LabeledSubtype<T>(val probe: SealedClassProbe<T>, val labels: List<String>) {
  val isObject: Boolean
    get() = probe.isObject
}

/** The default/fallback strategy of a validated sealed model. */
public sealed interface SealedFallback<out T> {
  /** No fallback: unknown labels fail at runtime. */
  public data object None : SealedFallback<Nothing>

  /** `@DefaultNull`: unknown labels deserialize to null. */
  public data object NullValue : SealedFallback<Nothing>

  /** A `@DefaultObject` object subtype is the default value for unknown labels. */
  public data class DefaultObject<T>(val probe: SealedClassProbe<T>) : SealedFallback<T>

  /** A `@FallbackJsonAdapter` adapter handles unknown labels. Instantiation is backend-specific. */
  public data object FallbackAdapter : SealedFallback<Nothing>
}
