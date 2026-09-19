// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("unused")

package dev.zacsweers.moshix.sealed.fixtures

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import dev.zacsweers.moshix.sealed.annotations.DefaultNull
import dev.zacsweers.moshix.sealed.annotations.DefaultObject
import dev.zacsweers.moshix.sealed.annotations.FallbackJsonAdapter
import dev.zacsweers.moshix.sealed.annotations.NestedSealed
import dev.zacsweers.moshix.sealed.annotations.TypeLabel
import dev.zacsweers.moshix.sealed.runtime.model.SealedErrorCode

/**
 * One invalid sealed model plus the error every moshi-sealed backend must report for it.
 *
 * The same model is exercised through every reachable entry point: the KSP compilation test feeds
 * [SealedModelFixtureSources] (source mirrors of these classes) to a real compilation, while the
 * runtime entry points (reflect and metadata-reflect) load [root] directly. All of them must
 * surface [expectedCode] and [messageFragment].
 */
public class InvalidSealedFixture(
  public val name: String,
  public val root: Class<*>,
  public val expectedCode: SealedErrorCode,
  public val messageFragment: String,
) {
  override fun toString(): String = name
}

@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class DuplicateLabels {
  @TypeLabel("a") public class TypeA : DuplicateLabels()

  @TypeLabel("a") public class TypeB : DuplicateLabels()
}

@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class DuplicateAlternateLabels {
  @TypeLabel("a", alternateLabels = ["aa"]) public class TypeA : DuplicateAlternateLabels()

  @TypeLabel("b", alternateLabels = ["aa"]) public class TypeB : DuplicateAlternateLabels()
}

@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class MissingTypeLabel {
  @TypeLabel("a") public class TypeA : MissingTypeLabel()

  public class TypeB : MissingTypeLabel()
}

@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class GenericSubtype<T> {
  // This form is ok
  @TypeLabel("a") public class TypeA : GenericSubtype<String>()

  // This form is not ok
  @TypeLabel("b") public class TypeB<T> : GenericSubtype<T>()
}

@DefaultNull
@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class DefaultNullAndDefaultObject {
  @TypeLabel("a") public class TypeA : DefaultNullAndDefaultObject()

  @DefaultObject public object TypeB : DefaultNullAndDefaultObject()
}

public class NoOpFallback : JsonAdapter<Any>() {
  override fun fromJson(reader: JsonReader): Any? = null

  override fun toJson(writer: JsonWriter, value: Any?) {}
}

@DefaultNull
@FallbackJsonAdapter(NoOpFallback::class)
@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class DefaultNullAndFallbackAdapter {
  @TypeLabel("a") public class TypeA : DefaultNullAndFallbackAdapter()
}

@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class TwoDefaultObjects {
  @DefaultObject public object TypeA : TwoDefaultObjects()

  @DefaultObject public object TypeB : TwoDefaultObjects()
}

@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class NonObjectDefaultObject {
  @TypeLabel("a") public class TypeA : NonObjectDefaultObject()

  @DefaultObject public class TypeB : NonObjectDefaultObject()
}

@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class RedundantNestedSealed {
  @JsonClass(generateAdapter = true, generator = "sealed:type")
  public sealed class Nested : RedundantNestedSealed() {
    @TypeLabel("x") public object X : Nested()
  }
}

@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class TypeLabelWithSealedGenerator {
  @TypeLabel("a")
  @JsonClass(generateAdapter = true, generator = "sealed:other")
  public class TypeA : TypeLabelWithSealedGenerator()
}

@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class OuterMessage {
  @NestedSealed
  @JsonClass(generateAdapter = true, generator = "sealed:type")
  public sealed class BadBranch : OuterMessage()
}

public sealed class PlainParent {
  @NestedSealed
  @JsonClass(generateAdapter = true, generator = "sealed:sub")
  public sealed class OrphanBranch : PlainParent()
}

/** All invalid-model fixtures, shared by every backend's tests. */
public val INVALID_SEALED_FIXTURES: List<InvalidSealedFixture> =
  listOf(
    InvalidSealedFixture(
      "duplicateLabel",
      DuplicateLabels::class.java,
      SealedErrorCode.DUPLICATE_LABEL,
      "Duplicate label 'a' defined for",
    ),
    InvalidSealedFixture(
      "duplicateAlternateLabel",
      DuplicateAlternateLabels::class.java,
      SealedErrorCode.DUPLICATE_ALTERNATE_LABEL,
      "Duplicate alternate label 'aa' defined for",
    ),
    InvalidSealedFixture(
      "missingTypeLabel",
      MissingTypeLabel::class.java,
      SealedErrorCode.MISSING_TYPE_LABEL,
      "must be annotated with @TypeLabel",
    ),
    InvalidSealedFixture(
      "genericSubtype",
      GenericSubtype::class.java,
      SealedErrorCode.GENERIC_SUBTYPE,
      "Moshi-sealed subtypes cannot be generic",
    ),
    InvalidSealedFixture(
      "defaultNullAndDefaultObject",
      DefaultNullAndDefaultObject::class.java,
      SealedErrorCode.CONFLICTING_DEFAULTS,
      "Only one of @DefaultNull, @DefaultObject, and @FallbackJsonAdapter can be used at a time",
    ),
    InvalidSealedFixture(
      "defaultNullAndFallbackAdapter",
      DefaultNullAndFallbackAdapter::class.java,
      SealedErrorCode.CONFLICTING_DEFAULTS,
      "Only one of @DefaultNull, @DefaultObject, and @FallbackJsonAdapter can be used at a time",
    ),
    InvalidSealedFixture(
      "twoDefaultObjects",
      TwoDefaultObjects::class.java,
      SealedErrorCode.MULTIPLE_DEFAULT_OBJECTS,
      "Can only have one @DefaultObject",
    ),
    InvalidSealedFixture(
      "nonObjectDefaultObject",
      NonObjectDefaultObject::class.java,
      SealedErrorCode.DEFAULT_OBJECT_NOT_OBJECT,
      "Must be an object type to use as a @DefaultObject",
    ),
    InvalidSealedFixture(
      "redundantNestedSealed",
      RedundantNestedSealed::class.java,
      SealedErrorCode.REDUNDANT_SEALED_GENERATOR,
      "is redundantly annotated with @JsonClass(generator = \"sealed:type\")",
    ),
    InvalidSealedFixture(
      "typeLabelWithSealedGenerator",
      TypeLabelWithSealedGenerator::class.java,
      SealedErrorCode.TYPE_LABEL_WITH_SEALED_GENERATOR,
      "and @TypeLabel",
    ),
    InvalidSealedFixture(
      "nestedSealedSameLabel",
      OuterMessage.BadBranch::class.java,
      SealedErrorCode.NESTED_SEALED_SAME_LABEL,
      "is inappropriately annotated with @JsonClass(generator = \"sealed:type\")",
    ),
    InvalidSealedFixture(
      "missingSealedSupertype",
      PlainParent.OrphanBranch::class.java,
      SealedErrorCode.MISSING_SEALED_SUPERTYPE,
      "No JsonClass-annotated sealed supertype found for",
    ),
  )
