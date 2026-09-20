// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.fixtures

import com.squareup.moshi.JsonClass
import dev.zacsweers.moshix.sealed.annotations.DefaultNull
import dev.zacsweers.moshix.sealed.annotations.DefaultObject
import dev.zacsweers.moshix.sealed.annotations.TypeLabel

/** Invalid: two subtypes declare the same label. */
@JsonClass(generateAdapter = false, generator = "sealed:type")
public sealed interface DuplicateLabels {
  @TypeLabel("a") public class A : DuplicateLabels

  @TypeLabel("a") public class B : DuplicateLabels
}

/** Invalid: two subtypes declare the same alternate label. */
@JsonClass(generateAdapter = false, generator = "sealed:type")
public sealed interface DuplicateAlternateLabels {
  @TypeLabel("a", ["x"]) public class A : DuplicateAlternateLabels

  @TypeLabel("b", ["x"]) public class B : DuplicateAlternateLabels
}

/** Invalid: a subtype is missing its @TypeLabel. */
@JsonClass(generateAdapter = false, generator = "sealed:type")
public sealed interface MissingLabel {
  @TypeLabel("a") public class A : MissingLabel

  public class B : MissingLabel
}

/** Invalid: two @DefaultObject subtypes. */
@JsonClass(generateAdapter = false, generator = "sealed:type")
public sealed interface MultipleDefaultObjects {
  @DefaultObject public object A : MultipleDefaultObjects

  @DefaultObject public object B : MultipleDefaultObjects
}

/** Invalid: @DefaultNull and @DefaultObject used together. */
@DefaultNull
@JsonClass(generateAdapter = false, generator = "sealed:type")
public sealed interface ConflictingDefaults {
  @TypeLabel("a") public class A : ConflictingDefaults

  @DefaultObject public object B : ConflictingDefaults
}

/** Invalid: @DefaultObject on a non-object type. */
@JsonClass(generateAdapter = false, generator = "sealed:type")
public sealed interface DefaultObjectNotObject {
  @TypeLabel("a") public class A : DefaultObjectNotObject

  @DefaultObject public class B : DefaultObjectNotObject
}

/** Invalid: generic subtype. */
@JsonClass(generateAdapter = false, generator = "sealed:type")
public sealed class GenericSubtypes<T> {
  @TypeLabel("a") public class A : GenericSubtypes<String>()

  @TypeLabel("b") public class B<T> : GenericSubtypes<T>()
}

/** Invalid: nested sealed subtype redundantly redeclares the parent's label key. */
@JsonClass(generateAdapter = false, generator = "sealed:type")
public sealed interface RedundantNestedLabel {
  @TypeLabel("a") public class A : RedundantNestedLabel

  @JsonClass(generateAdapter = false, generator = "sealed:type")
  public sealed interface B : RedundantNestedLabel {
    @TypeLabel("b") public class BImpl : B
  }
}
