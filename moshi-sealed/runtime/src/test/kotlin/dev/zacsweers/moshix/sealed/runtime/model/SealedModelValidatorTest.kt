// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.runtime.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** Unit tests for the backend-agnostic [SealedModelValidator] using a fake probe. */
class SealedModelValidatorTest {

  private class FakeProbe(
    override val displayName: String,
    override val isSealed: Boolean = false,
    override val isObject: Boolean = false,
    override val hasTypeParameters: Boolean = false,
    override val jsonClassLabelKey: String? = null,
    override val hasNestedSealed: Boolean = false,
    override val hasDefaultNull: Boolean = false,
    override val hasDefaultObject: Boolean = false,
    override val hasFallbackJsonAdapter: Boolean = false,
    override val typeLabel: TypeLabelData? = null,
    override val sealedSubtypes: List<FakeProbe> = emptyList(),
    override val supertypeLabelKeys: List<String> = emptyList(),
  ) : SealedClassProbe<FakeProbe> {
    override val handle: FakeProbe
      get() = this

    override val identity: Any
      get() = displayName

    override val objectInstance: Any?
      get() = if (isObject) this else null
  }

  private fun label(label: String, vararg alternates: String) =
    TypeLabelData(label, alternates.toList())

  @Test
  fun validModel() {
    val root =
      FakeProbe(
        "Root",
        isSealed = true,
        sealedSubtypes =
          listOf(
            FakeProbe("Root.A", typeLabel = label("a", "aa")),
            FakeProbe("Root.B", isObject = true, typeLabel = label("b")),
            FakeProbe(
              "Root.Nested",
              isSealed = true,
              sealedSubtypes = listOf(FakeProbe("Root.Nested.C", typeLabel = label("c"))),
            ),
          ),
      )
    val result = SealedModelValidator.validate(root, "type")
    assertThat(result).isInstanceOf(SealedModelResult.Valid::class.java)
    val model = (result as SealedModelResult.Valid).model
    assertThat(model.labelKey).isEqualTo("type")
    assertThat(model.labeledSubtypes.map { it.probe.displayName })
      .containsExactly("Root.A", "Root.B", "Root.Nested.C")
      .inOrder()
    assertThat(model.labeledSubtypes[0].labels).containsExactly("a", "aa").inOrder()
    assertThat(model.labeledSubtypes[1].isObject).isTrue()
    assertThat(model.fallback).isEqualTo(SealedFallback.None)
  }

  @Test
  fun duplicateLabelError() {
    val root =
      FakeProbe(
        "Root",
        isSealed = true,
        sealedSubtypes =
          listOf(
            FakeProbe("Root.A", typeLabel = label("a")),
            FakeProbe("Root.B", typeLabel = label("a")),
          ),
      )
    val result = SealedModelValidator.validate(root, "type")
    assertThat(result).isInstanceOf(SealedModelResult.Invalid::class.java)
    val errors = (result as SealedModelResult.Invalid).errors
    assertThat(errors).hasSize(1)
    assertThat(errors[0].code).isEqualTo(SealedErrorCode.DUPLICATE_LABEL)
    assertThat(errors[0].render())
      .isEqualTo("MOSHIX_SEALED_DUPLICATE_LABEL: Duplicate label 'a' defined for Root.B and Root.A.")
  }

  @Test
  fun conflictingDefaultsError() {
    val root =
      FakeProbe(
        "Root",
        isSealed = true,
        hasDefaultNull = true,
        sealedSubtypes = listOf(FakeProbe("Root.A", isObject = true, hasDefaultObject = true)),
      )
    val result = SealedModelValidator.validate(root, "type")
    assertThat(result).isInstanceOf(SealedModelResult.Invalid::class.java)
    val errors = (result as SealedModelResult.Invalid).errors
    assertThat(errors.map { it.code }).contains(SealedErrorCode.CONFLICTING_DEFAULTS)
  }

  @Test
  fun defaultObjectFallback() {
    val defaultObject = FakeProbe("Root.Unknown", isObject = true, hasDefaultObject = true)
    val root =
      FakeProbe(
        "Root",
        isSealed = true,
        sealedSubtypes = listOf(FakeProbe("Root.A", typeLabel = label("a")), defaultObject),
      )
    val result = SealedModelValidator.validate(root, "type")
    assertThat(result).isInstanceOf(SealedModelResult.Valid::class.java)
    val model = (result as SealedModelResult.Valid).model
    assertThat(model.fallback).isEqualTo(SealedFallback.DefaultObject(defaultObject))
    assertThat(model.labeledSubtypes.map { it.probe.displayName }).containsExactly("Root.A")
  }

  @Test
  fun nestedSealedSameLabelError() {
    val root =
      FakeProbe(
        "Root.Nested",
        isSealed = true,
        hasNestedSealed = true,
        supertypeLabelKeys = listOf("type"),
      )
    val result = SealedModelValidator.validate(root, "type")
    assertThat(result).isInstanceOf(SealedModelResult.Invalid::class.java)
    val errors = (result as SealedModelResult.Invalid).errors
    assertThat(errors.map { it.code }).containsExactly(SealedErrorCode.NESTED_SEALED_SAME_LABEL)
  }

  @Test
  fun sameTypeReachableTwiceIsNotADuplicate() {
    val shared = FakeProbe("Root.Real", typeLabel = label("real"))
    val root =
      FakeProbe(
        "Root",
        isSealed = true,
        sealedSubtypes =
          listOf(FakeProbe("Root.Branch", isSealed = true, sealedSubtypes = listOf(shared)), shared),
      )
    val result = SealedModelValidator.validate(root, "type")
    assertThat(result).isInstanceOf(SealedModelResult.Valid::class.java)
    val model = (result as SealedModelResult.Valid).model
    assertThat(model.labeledSubtypes.map { it.probe.displayName }).containsExactly("Root.Real")
  }
}
