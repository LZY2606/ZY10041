// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.fixtures

import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import dev.zacsweers.moshix.sealed.annotations.DefaultNull
import dev.zacsweers.moshix.sealed.annotations.DefaultObject
import dev.zacsweers.moshix.sealed.annotations.FallbackJsonAdapter
import dev.zacsweers.moshix.sealed.annotations.NestedSealed
import dev.zacsweers.moshix.sealed.annotations.TypeLabel

/**
 * Cross-entry fixtures shared by the codegen, kotlin-reflect, and metadata-reflect test suites.
 * Valid models must behave identically (JSON, labels, fallbacks) on every entry point; invalid
 * models must fail with the same error code and core message.
 */

/** Valid model: labels, alternate labels, an object subtype, and a nested sealed branch. */
@JsonClass(generateAdapter = false, generator = "sealed:type")
public sealed interface ValidMessage {
  @TypeLabel("text", ["txt"]) public data class Text(val value: String) : ValidMessage

  @TypeLabel("empty") public data object Empty : ValidMessage

  @NestedSealed
  public sealed interface Nested : ValidMessage {
    @TypeLabel("nested_text") public data class NestedText(val value: String) : Nested
  }
}

/** Valid model: unknown labels decode to null. */
@DefaultNull
@JsonClass(generateAdapter = false, generator = "sealed:type")
public sealed interface ValidDefaultNull {
  @TypeLabel("a") public data class A(val value: String) : ValidDefaultNull
}

/** Valid model: unknown labels decode to a default object. */
@JsonClass(generateAdapter = false, generator = "sealed:type")
public sealed interface ValidDefaultObject {
  @TypeLabel("a") public data class A(val value: String) : ValidDefaultObject

  @DefaultObject public object Default : ValidDefaultObject
}

/** Fallback adapter for [ValidFallback]. */
public class ValidFallbackAdapter : JsonAdapter<ValidFallback>() {
  override fun fromJson(reader: JsonReader): ValidFallback {
    reader.beginObject()
    while (reader.hasNext()) {
      reader.skipName()
      reader.skipValue()
    }
    reader.endObject()
    return ValidFallback.Unknown
  }

  override fun toJson(writer: JsonWriter, value: ValidFallback?) {
    writer.beginObject().endObject()
  }
}

/** Valid model: unknown labels decode via a fallback adapter. */
@FallbackJsonAdapter(ValidFallbackAdapter::class)
@JsonClass(generateAdapter = false, generator = "sealed:type")
public sealed interface ValidFallback {
  @TypeLabel("a") public data class A(val value: String) : ValidFallback

  @TypeLabel("unknown") public object Unknown : ValidFallback
}
