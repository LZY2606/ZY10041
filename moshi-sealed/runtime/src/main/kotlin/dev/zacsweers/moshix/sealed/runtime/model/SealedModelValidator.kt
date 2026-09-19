// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.runtime.model

import dev.zacsweers.moshix.sealed.runtime.model.SealedErrorCode.CONFLICTING_DEFAULTS
import dev.zacsweers.moshix.sealed.runtime.model.SealedErrorCode.DEFAULT_OBJECT_NOT_OBJECT
import dev.zacsweers.moshix.sealed.runtime.model.SealedErrorCode.DUPLICATE_ALTERNATE_LABEL
import dev.zacsweers.moshix.sealed.runtime.model.SealedErrorCode.DUPLICATE_LABEL
import dev.zacsweers.moshix.sealed.runtime.model.SealedErrorCode.GENERIC_SUBTYPE
import dev.zacsweers.moshix.sealed.runtime.model.SealedErrorCode.MISSING_SEALED_SUPERTYPE
import dev.zacsweers.moshix.sealed.runtime.model.SealedErrorCode.MISSING_TYPE_LABEL
import dev.zacsweers.moshix.sealed.runtime.model.SealedErrorCode.MULTIPLE_DEFAULT_OBJECTS
import dev.zacsweers.moshix.sealed.runtime.model.SealedErrorCode.NESTED_SEALED_SAME_LABEL
import dev.zacsweers.moshix.sealed.runtime.model.SealedErrorCode.REDUNDANT_SEALED_GENERATOR
import dev.zacsweers.moshix.sealed.runtime.model.SealedErrorCode.TYPE_LABEL_WITH_SEALED_GENERATOR

/**
 * The single source of truth for moshi-sealed hierarchy rules.
 *
 * Given a backend-agnostic [SealedClassProbe] tree, this walks the sealed hierarchy once and
 * produces either a [ValidatedSealedModel] that backends can instantiate adapters from, or a list
 * of [SealedError]s with stable codes and messages. Compile-time backends report every error with
 * source locations; runtime backends throw with type context. Both emit the same codes and core
 * messages for the same invalid model.
 */
public object SealedModelValidator {

  /**
   * Validates the sealed hierarchy rooted at [root] for the given [labelKey].
   *
   * Eligibility checks (is this a sealed class at all? does it opt in?) remain the responsibility
   * of each backend and must happen before calling this.
   */
  public fun <T> validate(root: SealedClassProbe<T>, labelKey: String): SealedModelResult<T> {
    val errors = mutableListOf<SealedError<T>>()

    // A @NestedSealed type must have a JsonClass-annotated sealed supertype with a different key.
    if (root.hasNestedSealed) {
      val parentLabelKeys = root.supertypeLabelKeys
      if (parentLabelKeys.isEmpty()) {
        errors +=
          SealedError(
            MISSING_SEALED_SUPERTYPE,
            "No JsonClass-annotated sealed supertype found for ${root.displayName}",
            listOf(root),
          )
      } else if (labelKey in parentLabelKeys) {
        errors +=
          SealedError(
            NESTED_SEALED_SAME_LABEL,
            "@NestedSealed-annotated subtype ${root.displayName} is inappropriately annotated " +
              "with @JsonClass(generator = \"sealed:$labelKey\").",
            listOf(root),
          )
      }
    }

    val hasDefaultNull = root.hasDefaultNull
    val hasFallbackAdapter = root.hasFallbackJsonAdapter
    if (hasDefaultNull && hasFallbackAdapter) {
      errors += conflictingDefaultsError(root, root)
    }

    val labels = LinkedHashMap<String, SealedClassProbe<T>>()
    val labeledSubtypes = mutableListOf<LabeledSubtype<T>>()
    val labeledIdentities = mutableSetOf<Any>()
    var defaultObject: SealedClassProbe<T>? = null

    fun conflictingDefaults(node: SealedClassProbe<T>) {
      errors += conflictingDefaultsError(root, node)
    }

    fun addLabeledSubtype(node: SealedClassProbe<T>, skipJsonClassCheck: Boolean) {
      val typeLabel = node.typeLabel
      if (typeLabel == null) {
        errors +=
          SealedError(
            MISSING_TYPE_LABEL,
            "Sealed subtypes must be annotated with @TypeLabel to define their label: " +
              node.displayName,
            listOf(node),
          )
        return
      }
      if (node.hasTypeParameters) {
        errors +=
          SealedError(
            GENERIC_SUBTYPE,
            "Moshi-sealed subtypes cannot be generic: ${node.displayName}",
            listOf(node),
          )
        return
      }
      if (!skipJsonClassCheck && node.jsonClassLabelKey != null) {
        errors +=
          SealedError(
            TYPE_LABEL_WITH_SEALED_GENERATOR,
            "Sealed subtype ${node.displayName} is annotated with " +
              "@JsonClass(generator = \"sealed:...\") and @TypeLabel.",
            listOf(node),
          )
        return
      }
      val allLabels = listOf(typeLabel.label) + typeLabel.alternateLabels
      for ((index, label) in allLabels.withIndex()) {
        val prev = labels.putIfAbsent(label, node)
        if (prev != null && prev.identity != node.identity) {
          errors +=
            SealedError(
              if (index == 0) DUPLICATE_LABEL else DUPLICATE_ALTERNATE_LABEL,
              if (index == 0) {
                "Duplicate label '$label' defined for ${node.displayName} and ${prev.displayName}."
              } else {
                "Duplicate alternate label '$label' defined for ${node.displayName} and " +
                  prev.displayName +
                  "."
              },
              listOf(node, prev),
            )
          return
        }
      }
      // The same type can be reachable through multiple paths in the hierarchy (e.g. a subtype of
      // both the root and a nested sealed intermediate). Only register it once.
      if (labeledIdentities.add(node.identity)) {
        labeledSubtypes += LabeledSubtype(node, allLabels)
      }
    }

    fun walk(node: SealedClassProbe<T>) {
      if (node.hasDefaultObject) {
        when {
          !node.isObject ->
            errors +=
              SealedError(
                DEFAULT_OBJECT_NOT_OBJECT,
                "Must be an object type to use as a @DefaultObject: ${node.displayName}",
                listOf(node),
              )
          hasDefaultNull || hasFallbackAdapter -> conflictingDefaults(node)
          defaultObject != null ->
            errors +=
              SealedError(
                MULTIPLE_DEFAULT_OBJECTS,
                "Can only have one @DefaultObject: ${node.displayName} and " +
                  "${defaultObject!!.displayName} are both annotated",
                listOf(node, defaultObject!!),
              )
          else -> defaultObject = node
        }
        return
      }
      if (node.isSealed) {
        val nestedLabelKey = node.jsonClassLabelKey
        if (nestedLabelKey != null && nestedLabelKey == labelKey) {
          errors +=
            SealedError(
              REDUNDANT_SEALED_GENERATOR,
              "Sealed subtype ${node.displayName} is redundantly annotated with " +
                "@JsonClass(generator = \"sealed:$nestedLabelKey\").",
              listOf(node),
            )
        }
        if (node.typeLabel != null) {
          // It's a different type, allow it to be used as a label and branch off from here.
          addLabeledSubtype(node, skipJsonClassCheck = true)
        } else {
          // Recurse, inheriting the top type
          node.sealedSubtypes.forEach(::walk)
        }
      } else {
        addLabeledSubtype(node, skipJsonClassCheck = node.isObject)
      }
    }

    root.sealedSubtypes.forEach(::walk)

    if (errors.isNotEmpty()) {
      return SealedModelResult.Invalid(errors)
    }

    val fallback =
      when {
        defaultObject != null -> SealedFallback.DefaultObject(defaultObject!!)
        hasDefaultNull -> SealedFallback.NullValue
        hasFallbackAdapter -> SealedFallback.FallbackAdapter
        else -> SealedFallback.None
      }
    return SealedModelResult.Valid(ValidatedSealedModel(labelKey, labeledSubtypes, fallback))
  }

  private fun <T> conflictingDefaultsError(
    root: SealedClassProbe<T>,
    node: SealedClassProbe<T>,
  ): SealedError<T> =
    SealedError(
      CONFLICTING_DEFAULTS,
      "Only one of @DefaultNull, @DefaultObject, and @FallbackJsonAdapter can be used at a " +
        "time: ${root.displayName}",
      listOf(node),
    )
}
