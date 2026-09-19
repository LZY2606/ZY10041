// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.fixtures

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import org.junit.Assert.assertThrows

/**
 * Asserts that requesting an adapter for [InvalidSealedFixture.root] from [moshi] fails with the
 * shared error code and core message. Used identically by every runtime entry point.
 */
public fun assertInvalidSealedModel(moshi: Moshi, fixture: InvalidSealedFixture) {
  val error =
    assertThrows(fixture.name, IllegalStateException::class.java) { moshi.adapter(fixture.root) }
  assertThat(error).hasMessageThat().contains("MOSHIX_SEALED_${fixture.expectedCode.name}")
  assertThat(error).hasMessageThat().contains(fixture.messageFragment)
}

/**
 * Kotlin source mirrors of the compiled invalid-model fixtures in [INVALID_SEALED_FIXTURES], keyed
 * by [InvalidSealedFixture.name]. The codegen compilation test feeds these to a real KSP
 * compilation so that the exact same invalid models are checked at compile time.
 *
 * Keep these in sync with InvalidSealedFixtures.kt.
 */
public val INVALID_SEALED_FIXTURE_SOURCES: Map<String, String> =
  mapOf(
    "duplicateLabel" to
      """
      package test

      import com.squareup.moshi.JsonClass
      import dev.zacsweers.moshix.sealed.annotations.TypeLabel

      @JsonClass(generateAdapter = true, generator = "sealed:type")
      sealed class DuplicateLabels {
        @TypeLabel("a") class TypeA : DuplicateLabels()
        @TypeLabel("a") class TypeB : DuplicateLabels()
      }
      """,
    "duplicateAlternateLabel" to
      """
      package test

      import com.squareup.moshi.JsonClass
      import dev.zacsweers.moshix.sealed.annotations.TypeLabel

      @JsonClass(generateAdapter = true, generator = "sealed:type")
      sealed class DuplicateAlternateLabels {
        @TypeLabel("a", alternateLabels = ["aa"]) class TypeA : DuplicateAlternateLabels()
        @TypeLabel("b", alternateLabels = ["aa"]) class TypeB : DuplicateAlternateLabels()
      }
      """,
    "missingTypeLabel" to
      """
      package test

      import com.squareup.moshi.JsonClass
      import dev.zacsweers.moshix.sealed.annotations.TypeLabel

      @JsonClass(generateAdapter = true, generator = "sealed:type")
      sealed class MissingTypeLabel {
        @TypeLabel("a") class TypeA : MissingTypeLabel()
        class TypeB : MissingTypeLabel()
      }
      """,
    "genericSubtype" to
      """
      package test

      import com.squareup.moshi.JsonClass
      import dev.zacsweers.moshix.sealed.annotations.TypeLabel

      @JsonClass(generateAdapter = true, generator = "sealed:type")
      sealed class GenericSubtype<T> {
        @TypeLabel("a") class TypeA : GenericSubtype<String>()
        @TypeLabel("b") class TypeB<T> : GenericSubtype<T>()
      }
      """,
    "defaultNullAndDefaultObject" to
      """
      package test

      import com.squareup.moshi.JsonClass
      import dev.zacsweers.moshix.sealed.annotations.DefaultNull
      import dev.zacsweers.moshix.sealed.annotations.DefaultObject
      import dev.zacsweers.moshix.sealed.annotations.TypeLabel

      @DefaultNull
      @JsonClass(generateAdapter = true, generator = "sealed:type")
      sealed class DefaultNullAndDefaultObject {
        @TypeLabel("a") class TypeA : DefaultNullAndDefaultObject()
        @DefaultObject object TypeB : DefaultNullAndDefaultObject()
      }
      """,
    "defaultNullAndFallbackAdapter" to
      """
      package test

      import com.squareup.moshi.JsonAdapter
      import com.squareup.moshi.JsonClass
      import com.squareup.moshi.JsonReader
      import com.squareup.moshi.JsonWriter
      import dev.zacsweers.moshix.sealed.annotations.DefaultNull
      import dev.zacsweers.moshix.sealed.annotations.FallbackJsonAdapter
      import dev.zacsweers.moshix.sealed.annotations.TypeLabel

      class NoOpFallback : JsonAdapter<Any>() {
        override fun fromJson(reader: JsonReader): Any? = null
        override fun toJson(writer: JsonWriter, value: Any?) {}
      }

      @DefaultNull
      @FallbackJsonAdapter(NoOpFallback::class)
      @JsonClass(generateAdapter = true, generator = "sealed:type")
      sealed class DefaultNullAndFallbackAdapter {
        @TypeLabel("a") class TypeA : DefaultNullAndFallbackAdapter()
      }
      """,
    "twoDefaultObjects" to
      """
      package test

      import com.squareup.moshi.JsonClass
      import dev.zacsweers.moshix.sealed.annotations.DefaultObject

      @JsonClass(generateAdapter = true, generator = "sealed:type")
      sealed class TwoDefaultObjects {
        @DefaultObject object TypeA : TwoDefaultObjects()
        @DefaultObject object TypeB : TwoDefaultObjects()
      }
      """,
    "nonObjectDefaultObject" to
      """
      package test

      import com.squareup.moshi.JsonClass
      import dev.zacsweers.moshix.sealed.annotations.DefaultObject
      import dev.zacsweers.moshix.sealed.annotations.TypeLabel

      @JsonClass(generateAdapter = true, generator = "sealed:type")
      sealed class NonObjectDefaultObject {
        @TypeLabel("a") class TypeA : NonObjectDefaultObject()
        @DefaultObject class TypeB : NonObjectDefaultObject()
      }
      """,
    "redundantNestedSealed" to
      """
      package test

      import com.squareup.moshi.JsonClass
      import dev.zacsweers.moshix.sealed.annotations.TypeLabel

      @JsonClass(generateAdapter = true, generator = "sealed:type")
      sealed class RedundantNestedSealed {
        @JsonClass(generateAdapter = true, generator = "sealed:type")
        sealed class Nested : RedundantNestedSealed() {
          @TypeLabel("x") object X : Nested()
        }
      }
      """,
    "typeLabelWithSealedGenerator" to
      """
      package test

      import com.squareup.moshi.JsonClass
      import dev.zacsweers.moshix.sealed.annotations.TypeLabel

      @JsonClass(generateAdapter = true, generator = "sealed:type")
      sealed class TypeLabelWithSealedGenerator {
        @TypeLabel("a")
        @JsonClass(generateAdapter = true, generator = "sealed:other")
        class TypeA : TypeLabelWithSealedGenerator()
      }
      """,
    "nestedSealedSameLabel" to
      """
      package test

      import com.squareup.moshi.JsonClass
      import dev.zacsweers.moshix.sealed.annotations.NestedSealed

      @JsonClass(generateAdapter = true, generator = "sealed:type")
      sealed class OuterMessage {
        @NestedSealed
        @JsonClass(generateAdapter = true, generator = "sealed:type")
        sealed class BadBranch : OuterMessage()
      }
      """,
    "missingSealedSupertype" to
      """
      package test

      import com.squareup.moshi.JsonClass
      import dev.zacsweers.moshix.sealed.annotations.NestedSealed

      sealed class PlainParent {
        @NestedSealed
        @JsonClass(generateAdapter = true, generator = "sealed:sub")
        sealed class OrphanBranch : PlainParent()
      }
      """,
  )
