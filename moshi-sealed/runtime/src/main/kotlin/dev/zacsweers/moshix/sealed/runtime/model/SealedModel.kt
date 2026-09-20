// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.runtime.model

/**
 * Backend-agnostic description of a `@TypeLabel` annotation.
 *
 * @property label the primary label.
 * @property alternateLabels optional alternate labels accepted during decoding.
 */
public class TypeLabelInfo(
  public val label: String,
  public val alternateLabels: List<String> = emptyList(),
)

/**
 * Backend-agnostic description of one type in a sealed hierarchy. Each entry point (codegen via
 * KSP, kotlin-reflect, metadata-reflect) is responsible for translating its own type
 * representation into this description, after which [SealedModelValidator] applies the shared
 * validation rules.
 *
 * @property name display name of the type, used in error messages. Runtime backends should use
 *   `Class.toString()` so messages are identical across entry points.
 * @property isSealed whether this type is sealed.
 * @property isObject whether this type is a Kotlin `object`.
 * @property isGeneric whether this type declares type parameters.
 * @property labelKey the label key from `@JsonClass(generator = "sealed:...")`, or null.
 * @property typeLabel the `@TypeLabel` info, or null if the type is not annotated.
 * @property hasDefaultObject whether the type is annotated with `@DefaultObject`.
 * @property hasDefaultNull whether the type is annotated with `@DefaultNull`.
 * @property hasFallbackAdapter whether the type is annotated with `@FallbackJsonAdapter`.
 * @property hasNestedSealed whether the type is annotated with `@NestedSealed`.
 * @property parentLabelKey for `@NestedSealed` types, the label key of the nearest
 *   JsonClass-annotated sealed supertype, or null if none was found.
 * @property subclasses direct sealed subclasses of this type, empty for non-sealed types.
 * @property origin opaque backend token (e.g. a `KSNode`, `KClass`, or `Class`) carried through to
 *   errors and model entries so backends can map results back to their own representation. Never
 *   inspected by the validator.
 */
public class SealedTypeDescription(
  public val name: String,
  public val isSealed: Boolean = false,
  public val isObject: Boolean = false,
  public val isGeneric: Boolean = false,
  public val labelKey: String? = null,
  public val typeLabel: TypeLabelInfo? = null,
  public val hasDefaultObject: Boolean = false,
  public val hasDefaultNull: Boolean = false,
  public val hasFallbackAdapter: Boolean = false,
  public val hasNestedSealed: Boolean = false,
  public val parentLabelKey: String? = null,
  public val subclasses: List<SealedTypeDescription> = emptyList(),
  public val origin: Any? = null,
)

/** Stable error codes shared by all moshi-sealed entry points. */
public enum class SealedModelErrorCode(public val code: String) {
  MISSING_TYPE_LABEL("MOSHIX_SEALED_MISSING_TYPE_LABEL"),
  DUPLICATE_LABEL("MOSHIX_SEALED_DUPLICATE_LABEL"),
  DUPLICATE_ALTERNATE_LABEL("MOSHIX_SEALED_DUPLICATE_ALTERNATE_LABEL"),
  GENERIC_SUBTYPE("MOSHIX_SEALED_GENERIC_SUBTYPE"),
  TYPE_LABEL_WITH_JSON_CLASS("MOSHIX_SEALED_TYPE_LABEL_WITH_JSON_CLASS"),
  REDUNDANT_NESTED_LABEL("MOSHIX_SEALED_REDUNDANT_NESTED_LABEL"),
  NESTED_SEALED_PARENT_LABEL_CONFLICT("MOSHIX_SEALED_NESTED_SEALED_PARENT_LABEL_CONFLICT"),
  MISSING_SEALED_PARENT("MOSHIX_SEALED_MISSING_SEALED_PARENT"),
  DEFAULT_OBJECT_NOT_OBJECT("MOSHIX_SEALED_DEFAULT_OBJECT_NOT_OBJECT"),
  MULTIPLE_DEFAULT_OBJECTS("MOSHIX_SEALED_MULTIPLE_DEFAULT_OBJECTS"),
  CONFLICTING_DEFAULTS("MOSHIX_SEALED_CONFLICTING_DEFAULTS"),
}

/**
 * A single validation failure. [render] produces the canonical `[CODE] message` form that all
 * entry points share. Backends augment this with their own context: codegen attaches the [origin]
 * node for source locations, runtime backends throw it with the requesting type context.
 */
public class SealedModelError(
  public val code: SealedModelErrorCode,
  public val message: String,
  public val typeName: String,
  public val origin: Any?,
) {
  public fun render(): String = "[${code.code}] $message"

  override fun toString(): String = render()
}

/** How a validated sealed model handles unknown labels at runtime. */
public enum class DefaultStrategy {
  NONE,
  NULL,
  DEFAULT_OBJECT,
  FALLBACK_ADAPTER,
}

/**
 * A validated sealed model, ready for a backend to instantiate or generate an adapter from.
 *
 * @property labelKey the polymorphic label key.
 * @property entries labeled subtypes in declaration order (as provided by the backend).
 * @property defaultStrategy the fallback strategy for unknown labels.
 * @property defaultObject the `@DefaultObject` subtype description, present iff [defaultStrategy]
 *   is [DefaultStrategy.DEFAULT_OBJECT].
 */
public class SealedModel(
  public val labelKey: String,
  public val entries: List<LabelEntry>,
  public val defaultStrategy: DefaultStrategy,
  public val defaultObject: SealedTypeDescription? = null,
) {
  /**
   * A labeled subtype.
   *
   * @property typeName display name of the subtype.
   * @property labels the primary label followed by any alternate labels.
   * @property isObject whether this subtype is a Kotlin `object` and needs an instance adapter.
   * @property origin the backend token from the originating [SealedTypeDescription].
   */
  public class LabelEntry(
    public val typeName: String,
    public val labels: List<String>,
    public val isObject: Boolean,
    public val origin: Any?,
  )
}

/** Result of validating a [SealedTypeDescription] tree with [SealedModelValidator]. */
public sealed interface SealedModelResult {
  /** The model is valid and ready for adapter instantiation. */
  public class Valid(public val model: SealedModel) : SealedModelResult

  /** The model is invalid. [errors] are reported in deterministic walk order. */
  public class Invalid(public val errors: List<SealedModelError>) : SealedModelResult
}
