// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.runtime.model

import dev.zacsweers.moshix.sealed.runtime.model.SealedModel.LabelEntry
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelErrorCode.CONFLICTING_DEFAULTS
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelErrorCode.DEFAULT_OBJECT_NOT_OBJECT
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelErrorCode.DUPLICATE_ALTERNATE_LABEL
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelErrorCode.DUPLICATE_LABEL
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelErrorCode.GENERIC_SUBTYPE
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelErrorCode.MISSING_SEALED_PARENT
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelErrorCode.MISSING_TYPE_LABEL
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelErrorCode.MULTIPLE_DEFAULT_OBJECTS
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelErrorCode.NESTED_SEALED_PARENT_LABEL_CONFLICT
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelErrorCode.REDUNDANT_NESTED_LABEL
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelErrorCode.TYPE_LABEL_WITH_JSON_CLASS
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelResult.Invalid
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelResult.Valid

/**
 * Validates a backend-agnostic [SealedTypeDescription] tree into a [SealedModel]. This is the
 * single implementation of moshi-sealed's structural rules (labels, defaults, object subtypes,
 * nested sealed types) shared by the codegen, kotlin-reflect, and metadata-reflect entry points.
 * It performs no I/O, no reflection, and never calls back into any backend.
 */
public object SealedModelValidator {

  /**
   * Validates [root], which must describe a sealed type with a non-null
   * [SealedTypeDescription.labelKey]. Returns all errors found in deterministic walk order.
   */
  public fun validate(root: SealedTypeDescription): SealedModelResult {
    val labelKey = requireNotNull(root.labelKey) { "Root type ${root.name} has no sealed label key." }
    val errors = mutableListOf<SealedModelError>()

    // If this is a nested sealed type of a moshi-sealed parent, defer to the parent
    if (root.hasNestedSealed) {
      val parentLabelKey = root.parentLabelKey
      if (parentLabelKey == null) {
        errors +=
          SealedModelError(
            MISSING_SEALED_PARENT,
            "No JsonClass-annotated sealed supertype found for ${root.name}",
            root.name,
            root.origin,
          )
      } else if (parentLabelKey == labelKey) {
        errors +=
          SealedModelError(
            NESTED_SEALED_PARENT_LABEL_CONFLICT,
            "@NestedSealed-annotated subtype ${root.name} is inappropriately annotated with " +
              "@JsonClass(generator = \"sealed:$labelKey\").",
            root.name,
            root.origin,
          )
      }
    }

    // Pull out the default strategy. Possible cases:
    //   - No default (error if missing at runtime)
    //   - Null default
    //   - Object default
    //   - Fallback adapter
    var defaultStrategy = DefaultStrategy.NONE
    var defaultObject: SealedTypeDescription? = null
    if (root.hasDefaultNull) {
      defaultStrategy = DefaultStrategy.NULL
    }
    if (root.hasFallbackAdapter) {
      if (defaultStrategy != DefaultStrategy.NONE) {
        errors +=
          SealedModelError(
            CONFLICTING_DEFAULTS,
            "Only one of @DefaultNull, @DefaultObject, and @FallbackJsonAdapter can be used at " +
              "a time: ${root.name}",
            root.name,
            root.origin,
          )
      }
      defaultStrategy = DefaultStrategy.FALLBACK_ADAPTER
    }
    for (subclass in root.subclasses) {
      if (!subclass.hasDefaultObject) continue
      if (!subclass.isObject) {
        errors +=
          SealedModelError(
            DEFAULT_OBJECT_NOT_OBJECT,
            "Must be an object type to use as a @DefaultObject: ${subclass.name}",
            subclass.name,
            subclass.origin,
          )
        continue
      }
      when (defaultStrategy) {
        DefaultStrategy.NONE -> {
          defaultStrategy = DefaultStrategy.DEFAULT_OBJECT
          defaultObject = subclass
        }
        DefaultStrategy.DEFAULT_OBJECT ->
          errors +=
            SealedModelError(
              MULTIPLE_DEFAULT_OBJECTS,
              "Can only have one @DefaultObject: ${subclass.name} and ${defaultObject!!.name} " +
                "are both annotated",
              subclass.name,
              subclass.origin,
            )
        else ->
          errors +=
            SealedModelError(
              CONFLICTING_DEFAULTS,
              "Only one of @DefaultNull, @DefaultObject, and @FallbackJsonAdapter can be used " +
                "at a time: ${subclass.name}",
              subclass.name,
              subclass.origin,
            )
      }
    }

    // Collect labels from the hierarchy
    val labels = mutableMapOf<String, SealedTypeDescription>()
    val entries = mutableListOf<LabelEntry>()
    for (subclass in root.subclasses) {
      if (subclass.hasDefaultObject) continue
      walkTypeLabels(subclass, labelKey, labels, entries, errors)
    }

    return if (errors.isEmpty()) {
      Valid(SealedModel(labelKey, entries, defaultStrategy, defaultObject))
    } else {
      Invalid(errors)
    }
  }

  private fun walkTypeLabels(
    subtype: SealedTypeDescription,
    labelKey: String,
    labels: MutableMap<String, SealedTypeDescription>,
    entries: MutableList<LabelEntry>,
    errors: MutableList<SealedModelError>,
  ) {
    // If it's sealed, check if it's inheriting from our existing type or a separate/new branching
    // off point.
    if (subtype.isSealed) {
      val nestedLabelKey = subtype.labelKey
      if (nestedLabelKey != null && nestedLabelKey == labelKey) {
        // Redundant case
        errors +=
          SealedModelError(
            REDUNDANT_NESTED_LABEL,
            "Sealed subtype ${subtype.name} is redundantly annotated with " +
              "@JsonClass(generator = \"sealed:$nestedLabelKey\").",
            subtype.name,
            subtype.origin,
          )
        return
      }

      if (subtype.typeLabel != null) {
        // It's a different type, allow it to be used as a label and branch off from here.
        addLabelKeyForType(subtype, labels, entries, errors, skipJsonClassCheck = true)
      } else {
        // Recurse, inheriting the top type
        for (nested in subtype.subclasses) {
          walkTypeLabels(nested, labelKey, labels, entries, errors)
        }
      }
    } else {
      addLabelKeyForType(subtype, labels, entries, errors, skipJsonClassCheck = subtype.isObject)
    }
  }

  private fun addLabelKeyForType(
    subtype: SealedTypeDescription,
    labels: MutableMap<String, SealedTypeDescription>,
    entries: MutableList<LabelEntry>,
    errors: MutableList<SealedModelError>,
    skipJsonClassCheck: Boolean,
  ) {
    // Regular subtype, read its label
    val typeLabel = subtype.typeLabel
    if (typeLabel == null) {
      errors +=
        SealedModelError(
          MISSING_TYPE_LABEL,
          "Sealed subtypes must be annotated with @TypeLabel to define their label: " +
            subtype.name,
          subtype.name,
          subtype.origin,
        )
      return
    }

    if (subtype.isGeneric) {
      errors +=
        SealedModelError(
          GENERIC_SUBTYPE,
          "Moshi-sealed subtypes cannot be generic: ${subtype.name}",
          subtype.name,
          subtype.origin,
        )
      return
    }

    val allLabels = mutableListOf<String>()
    labels.put(typeLabel.label, subtype)?.let { prev ->
      if (prev.name != subtype.name) {
        errors +=
          SealedModelError(
            DUPLICATE_LABEL,
            "Duplicate label '${typeLabel.label}' defined for ${subtype.name} and ${prev.name}.",
            subtype.name,
            subtype.origin,
          )
        return
      }
    }
    allLabels += typeLabel.label

    for (alternate in typeLabel.alternateLabels) {
      labels.put(alternate, subtype)?.let { prev ->
        if (prev.name != subtype.name) {
          errors +=
            SealedModelError(
              DUPLICATE_ALTERNATE_LABEL,
              "Duplicate alternate label '$alternate' defined for ${subtype.name} and " +
                "${prev.name}.",
              subtype.name,
              subtype.origin,
            )
          return
        }
      }
      allLabels += alternate
    }

    if (!skipJsonClassCheck && subtype.labelKey != null) {
      errors +=
        SealedModelError(
          TYPE_LABEL_WITH_JSON_CLASS,
          "Sealed subtype ${subtype.name} is annotated with " +
            "@JsonClass(generator = \"sealed:${subtype.labelKey}\") and @TypeLabel.",
          subtype.name,
          subtype.origin,
        )
      return
    }

    entries += LabelEntry(subtype.name, allLabels, subtype.isObject, subtype.origin)
  }
}
