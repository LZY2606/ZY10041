// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.runtime.model

import com.google.common.truth.Truth.assertThat
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelResult.Invalid
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelResult.Valid
import org.junit.Test

/**
 * Cross-entry fixtures for the shared sealed model rules. Every backend (codegen, reflect,
 * metadata-reflect) validates through [SealedModelValidator], so these cases pin the error codes
 * and core messages that all entry points must produce.
 */
class SealedModelValidatorTest {

  private fun leaf(
    name: String,
    label: String? = null,
    alternates: List<String> = emptyList(),
    isObject: Boolean = false,
    isGeneric: Boolean = false,
    labelKey: String? = null,
  ): SealedTypeDescription =
    SealedTypeDescription(
      name = name,
      isObject = isObject,
      isGeneric = isGeneric,
      labelKey = labelKey,
      typeLabel = label?.let { TypeLabelInfo(it, alternates) },
    )

  private fun validate(
    root: SealedTypeDescription
  ): SealedModelResult = SealedModelValidator.validate(root)

  private fun invalidErrors(result: SealedModelResult): List<SealedModelError> {
    assertThat(result).isInstanceOf(Invalid::class.java)
    return (result as Invalid).errors
  }

  @Test
  fun validModel() {
    val root =
      SealedTypeDescription(
        name = "Base",
        isSealed = true,
        labelKey = "type",
        subclasses =
          listOf(
            leaf("Base.A", label = "a", alternates = listOf("aa")),
            leaf("Base.B", label = "b", isObject = true),
            SealedTypeDescription(
              name = "Base.Nested",
              isSealed = true,
              subclasses = listOf(leaf("Base.Nested.C", label = "c")),
            ),
          ),
      )

    val result = validate(root)
    assertThat(result).isInstanceOf(Valid::class.java)
    val model = (result as Valid).model
    assertThat(model.labelKey).isEqualTo("type")
    assertThat(model.defaultStrategy).isEqualTo(DefaultStrategy.NONE)
    assertThat(model.entries.map { it.typeName })
      .containsExactly("Base.A", "Base.B", "Base.Nested.C")
      .inOrder()
    assertThat(model.entries.map { it.labels })
      .containsExactly(listOf("a", "aa"), listOf("b"), listOf("c"))
      .inOrder()
    assertThat(model.entries[1].isObject).isTrue()
  }

  @Test
  fun validNestedSealedBranchWithOwnLabel() {
    val root =
      SealedTypeDescription(
        name = "Base",
        isSealed = true,
        labelKey = "type",
        subclasses =
          listOf(
            SealedTypeDescription(
              name = "Base.Branch",
              isSealed = true,
              typeLabel = TypeLabelInfo("branch"),
              subclasses = listOf(leaf("Base.Branch.C", label = "c")),
            )
          ),
      )

    val result = validate(root)
    assertThat(result).isInstanceOf(Valid::class.java)
    val model = (result as Valid).model
    // The branch itself becomes the label target; its subclasses are not flattened in
    assertThat(model.entries.map { it.typeName }).containsExactly("Base.Branch")
    assertThat(model.entries[0].labels).containsExactly("branch")
  }

  @Test
  fun validDefaults() {
    fun rootWith(
      hasDefaultNull: Boolean = false,
      hasFallbackAdapter: Boolean = false,
      defaultObject: Boolean = false,
    ) =
      SealedTypeDescription(
        name = "Base",
        isSealed = true,
        labelKey = "type",
        hasDefaultNull = hasDefaultNull,
        hasFallbackAdapter = hasFallbackAdapter,
        subclasses =
          listOfNotNull(
            leaf("Base.A", label = "a"),
            if (defaultObject) {
              SealedTypeDescription(name = "Base.Default", isObject = true, hasDefaultObject = true)
            } else {
              null
            },
          ),
      )

    assertThat((validate(rootWith(hasDefaultNull = true)) as Valid).model.defaultStrategy)
      .isEqualTo(DefaultStrategy.NULL)
    assertThat((validate(rootWith(hasFallbackAdapter = true)) as Valid).model.defaultStrategy)
      .isEqualTo(DefaultStrategy.FALLBACK_ADAPTER)
    val withObject = (validate(rootWith(defaultObject = true)) as Valid).model
    assertThat(withObject.defaultStrategy).isEqualTo(DefaultStrategy.DEFAULT_OBJECT)
    assertThat(withObject.defaultObject!!.name).isEqualTo("Base.Default")
    // The default object is not a labeled entry
    assertThat(withObject.entries.map { it.typeName }).containsExactly("Base.A")
  }

  @Test
  fun duplicateLabel() {
    val root =
      SealedTypeDescription(
        name = "Base",
        isSealed = true,
        labelKey = "type",
        subclasses = listOf(leaf("Base.A", label = "a"), leaf("Base.B", label = "a")),
      )

    val errors = invalidErrors(validate(root))
    assertThat(errors.map { it.code }).containsExactly(SealedModelErrorCode.DUPLICATE_LABEL)
    assertThat(errors[0].render())
      .isEqualTo("[MOSHIX_SEALED_DUPLICATE_LABEL] Duplicate label 'a' defined for Base.B and Base.A.")
  }

  @Test
  fun duplicateAlternateLabel() {
    val root =
      SealedTypeDescription(
        name = "Base",
        isSealed = true,
        labelKey = "type",
        subclasses =
          listOf(
            leaf("Base.A", label = "a", alternates = listOf("x")),
            leaf("Base.B", label = "b", alternates = listOf("x")),
          ),
      )

    val errors = invalidErrors(validate(root))
    assertThat(errors.map { it.code })
      .containsExactly(SealedModelErrorCode.DUPLICATE_ALTERNATE_LABEL)
    assertThat(errors[0].render())
      .isEqualTo(
        "[MOSHIX_SEALED_DUPLICATE_ALTERNATE_LABEL] Duplicate alternate label 'x' defined for Base.B and Base.A."
      )
  }

  @Test
  fun missingTypeLabel() {
    val root =
      SealedTypeDescription(
        name = "Base",
        isSealed = true,
        labelKey = "type",
        subclasses = listOf(leaf("Base.A", label = "a"), leaf("Base.B")),
      )

    val errors = invalidErrors(validate(root))
    assertThat(errors.map { it.code }).containsExactly(SealedModelErrorCode.MISSING_TYPE_LABEL)
    assertThat(errors[0].render())
      .isEqualTo(
        "[MOSHIX_SEALED_MISSING_TYPE_LABEL] Sealed subtypes must be annotated with @TypeLabel to define their label: Base.B"
      )
  }

  @Test
  fun multipleDefaultObjects() {
    val root =
      SealedTypeDescription(
        name = "Base",
        isSealed = true,
        labelKey = "type",
        subclasses =
          listOf(
            SealedTypeDescription(name = "Base.A", isObject = true, hasDefaultObject = true),
            SealedTypeDescription(name = "Base.B", isObject = true, hasDefaultObject = true),
          ),
      )

    val errors = invalidErrors(validate(root))
    assertThat(errors.map { it.code })
      .containsExactly(SealedModelErrorCode.MULTIPLE_DEFAULT_OBJECTS)
    assertThat(errors[0].render())
      .isEqualTo(
        "[MOSHIX_SEALED_MULTIPLE_DEFAULT_OBJECTS] Can only have one @DefaultObject: Base.B and Base.A are both annotated"
      )
  }

  @Test
  fun conflictingDefaults() {
    fun root(hasNull: Boolean, hasFallback: Boolean, hasObject: Boolean) =
      SealedTypeDescription(
        name = "Base",
        isSealed = true,
        labelKey = "type",
        hasDefaultNull = hasNull,
        hasFallbackAdapter = hasFallback,
        subclasses =
          listOfNotNull(
            leaf("Base.A", label = "a"),
            if (hasObject) {
              SealedTypeDescription(name = "Base.B", isObject = true, hasDefaultObject = true)
            } else {
              null
            },
          ),
      )

    for ((hasNull, hasFallback, hasObject) in
      listOf(
        Triple(true, true, false),
        Triple(true, false, true),
        Triple(false, true, true),
      )) {
      val errors = invalidErrors(validate(root(hasNull, hasFallback, hasObject)))
      assertThat(errors.map { it.code })
        .containsExactly(SealedModelErrorCode.CONFLICTING_DEFAULTS)
      assertThat(errors[0].render())
        .startsWith(
          "[MOSHIX_SEALED_CONFLICTING_DEFAULTS] Only one of @DefaultNull, @DefaultObject, and @FallbackJsonAdapter can be used at a time:"
        )
    }
  }

  @Test
  fun defaultObjectNotObject() {
    val root =
      SealedTypeDescription(
        name = "Base",
        isSealed = true,
        labelKey = "type",
        subclasses =
          listOf(
            leaf("Base.A", label = "a"),
            SealedTypeDescription(name = "Base.B", hasDefaultObject = true),
          ),
      )

    val errors = invalidErrors(validate(root))
    assertThat(errors.map { it.code })
      .containsExactly(SealedModelErrorCode.DEFAULT_OBJECT_NOT_OBJECT)
    assertThat(errors[0].render())
      .isEqualTo(
        "[MOSHIX_SEALED_DEFAULT_OBJECT_NOT_OBJECT] Must be an object type to use as a @DefaultObject: Base.B"
      )
  }

  @Test
  fun genericSubtype() {
    val root =
      SealedTypeDescription(
        name = "Base",
        isSealed = true,
        labelKey = "type",
        subclasses =
          listOf(leaf("Base.A", label = "a"), leaf("Base.B", label = "b", isGeneric = true)),
      )

    val errors = invalidErrors(validate(root))
    assertThat(errors.map { it.code }).containsExactly(SealedModelErrorCode.GENERIC_SUBTYPE)
    assertThat(errors[0].render())
      .isEqualTo("[MOSHIX_SEALED_GENERIC_SUBTYPE] Moshi-sealed subtypes cannot be generic: Base.B")
  }

  @Test
  fun redundantNestedLabel() {
    val root =
      SealedTypeDescription(
        name = "Base",
        isSealed = true,
        labelKey = "type",
        subclasses =
          listOf(
            leaf("Base.A", label = "a"),
            SealedTypeDescription(
              name = "Base.B",
              isSealed = true,
              labelKey = "type",
              subclasses = listOf(leaf("Base.B.C", label = "c")),
            ),
          ),
      )

    val errors = invalidErrors(validate(root))
    assertThat(errors.map { it.code })
      .containsExactly(SealedModelErrorCode.REDUNDANT_NESTED_LABEL)
    assertThat(errors[0].render())
      .isEqualTo(
        "[MOSHIX_SEALED_REDUNDANT_NESTED_LABEL] Sealed subtype Base.B is redundantly annotated with @JsonClass(generator = \"sealed:type\")."
      )
  }

  @Test
  fun nestedSealedParentLabelConflict() {
    val root =
      SealedTypeDescription(
        name = "Base.Nested",
        isSealed = true,
        labelKey = "type",
        hasNestedSealed = true,
        parentLabelKey = "type",
        subclasses = listOf(leaf("Base.Nested.A", label = "a")),
      )

    val errors = invalidErrors(validate(root))
    assertThat(errors.map { it.code })
      .containsExactly(SealedModelErrorCode.NESTED_SEALED_PARENT_LABEL_CONFLICT)
    assertThat(errors[0].render())
      .isEqualTo(
        "[MOSHIX_SEALED_NESTED_SEALED_PARENT_LABEL_CONFLICT] @NestedSealed-annotated subtype Base.Nested is inappropriately annotated with @JsonClass(generator = \"sealed:type\")."
      )
  }

  @Test
  fun missingSealedParent() {
    val root =
      SealedTypeDescription(
        name = "Base.Nested",
        isSealed = true,
        labelKey = "type",
        hasNestedSealed = true,
        parentLabelKey = null,
        subclasses = listOf(leaf("Base.Nested.A", label = "a")),
      )

    val errors = invalidErrors(validate(root))
    assertThat(errors.map { it.code })
      .containsExactly(SealedModelErrorCode.MISSING_SEALED_PARENT)
    assertThat(errors[0].render())
      .isEqualTo(
        "[MOSHIX_SEALED_MISSING_SEALED_PARENT] No JsonClass-annotated sealed supertype found for Base.Nested"
      )
  }

  @Test
  fun typeLabelWithJsonClass() {
    val root =
      SealedTypeDescription(
        name = "Base",
        isSealed = true,
        labelKey = "type",
        subclasses =
          listOf(
            leaf("Base.A", label = "a"),
            leaf("Base.B", label = "b", labelKey = "other"),
          ),
      )

    val errors = invalidErrors(validate(root))
    assertThat(errors.map { it.code })
      .containsExactly(SealedModelErrorCode.TYPE_LABEL_WITH_JSON_CLASS)
    assertThat(errors[0].render())
      .isEqualTo(
        "[MOSHIX_SEALED_TYPE_LABEL_WITH_JSON_CLASS] Sealed subtype Base.B is annotated with @JsonClass(generator = \"sealed:other\") and @TypeLabel."
      )
  }
}
