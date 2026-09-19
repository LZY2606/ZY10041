// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
@file:Suppress("unused")

package dev.zacsweers.moshix.sealed.fixtures

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import dev.zacsweers.moshix.sealed.annotations.DefaultNull
import dev.zacsweers.moshix.sealed.annotations.DefaultObject
import dev.zacsweers.moshix.sealed.annotations.FallbackJsonAdapter
import dev.zacsweers.moshix.sealed.annotations.NestedSealed
import dev.zacsweers.moshix.sealed.annotations.TypeLabel
import org.junit.Assert.assertThrows

/** Object subtypes, alternate labels, and unknown-label failure without a fallback. */
@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class ObjectMessage {
  @TypeLabel("a") public object TypeA : ObjectMessage()

  @TypeLabel("b", alternateLabels = ["bb"]) public object TypeB : ObjectMessage()
}

/** A nested sealed hierarchy branching off the root's label key. */
@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class NestedMessage {
  @NestedSealed
  public sealed class Branch : NestedMessage() {
    @TypeLabel("impl") public object Impl : Branch()
  }

  @TypeLabel("simple") public object Simple : NestedMessage()
}

/** Unknown labels deserialize to null. */
@DefaultNull
@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class DefaultNullMessage {
  @TypeLabel("a") public object TypeA : DefaultNullMessage()
}

/** Unknown labels deserialize to a default object. */
@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class DefaultObjectMessage {
  @TypeLabel("a") public object TypeA : DefaultObjectMessage()

  @DefaultObject public object Unknown : DefaultObjectMessage()
}

/** Unknown labels are handled by a fallback adapter. */
@FallbackJsonAdapter(FallbackMessageAdapter::class)
@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class FallbackMessage {
  @TypeLabel("a") public object TypeA : FallbackMessage()
}

public class FallbackMessageAdapter : JsonAdapter<FallbackMessage>() {
  override fun fromJson(reader: JsonReader): FallbackMessage {
    reader.beginObject()
    while (reader.hasNext()) {
      reader.skipName()
      reader.skipValue()
    }
    reader.endObject()
    return FallbackMessage.TypeA
  }

  override fun toJson(writer: JsonWriter, value: FallbackMessage?) {
    writer.beginObject().endObject()
  }
}

/** A non-object (data class) subtype, exercising delegate adapters. */
@JsonClass(generateAdapter = true, generator = "sealed:type")
public sealed class ClassMessage {
  @TypeLabel("text") public data class Text(val value: String) : ClassMessage()
}

/**
 * Shared assertions for the legal models above. Every runtime entry point runs these against its
 * own [Moshi] instance to prove JSON behavior is identical across backends.
 */
public object LegalSealedModelChecks {

  public fun checkObjectMessage(moshi: Moshi) {
    val adapter = moshi.adapter(ObjectMessage::class.java)
    assertThat(adapter.toJson(ObjectMessage.TypeA)).isEqualTo("{\"type\":\"a\"}")
    assertThat(adapter.toJson(ObjectMessage.TypeB)).isEqualTo("{\"type\":\"b\"}")
    assertThat(adapter.fromJson("{\"type\":\"a\"}")).isSameInstanceAs(ObjectMessage.TypeA)
    assertThat(adapter.fromJson("{\"type\":\"b\"}")).isSameInstanceAs(ObjectMessage.TypeB)
    // Alternate labels are read but not written
    assertThat(adapter.fromJson("{\"type\":\"bb\"}")).isSameInstanceAs(ObjectMessage.TypeB)
  }

  public fun checkObjectMessageUnknownLabelFails(moshi: Moshi) {
    val adapter = moshi.adapter(ObjectMessage::class.java)
    val error = assertThrows(JsonDataException::class.java) { adapter.fromJson("{\"type\":\"c\"}") }
    assertThat(error).hasMessageThat().contains("but found 'c'")
  }

  public fun checkNestedMessage(moshi: Moshi) {
    val adapter = moshi.adapter(NestedMessage::class.java)
    assertThat(adapter.toJson(NestedMessage.Branch.Impl)).isEqualTo("{\"type\":\"impl\"}")
    assertThat(adapter.fromJson("{\"type\":\"impl\"}")).isSameInstanceAs(NestedMessage.Branch.Impl)
    assertThat(adapter.toJson(NestedMessage.Simple)).isEqualTo("{\"type\":\"simple\"}")
    assertThat(adapter.fromJson("{\"type\":\"simple\"}")).isSameInstanceAs(NestedMessage.Simple)
  }

  public fun checkDefaultNullMessage(moshi: Moshi) {
    val adapter = moshi.adapter(DefaultNullMessage::class.java)
    assertThat(adapter.fromJson("{\"type\":\"a\"}")).isSameInstanceAs(DefaultNullMessage.TypeA)
    assertThat(adapter.fromJson("{\"type\":\"unknown\"}")).isNull()
  }

  public fun checkDefaultObjectMessage(moshi: Moshi) {
    val adapter = moshi.adapter(DefaultObjectMessage::class.java)
    assertThat(adapter.fromJson("{\"type\":\"a\"}")).isSameInstanceAs(DefaultObjectMessage.TypeA)
    assertThat(adapter.fromJson("{\"type\":\"unknown\"}"))
      .isSameInstanceAs(DefaultObjectMessage.Unknown)
  }

  public fun checkFallbackMessage(moshi: Moshi) {
    val adapter = moshi.adapter(FallbackMessage::class.java)
    assertThat(adapter.fromJson("{\"type\":\"a\"}")).isSameInstanceAs(FallbackMessage.TypeA)
    // The fallback adapter handles the unknown label
    assertThat(adapter.fromJson("{\"type\":\"unknown\"}")).isSameInstanceAs(FallbackMessage.TypeA)
  }

  public fun checkClassMessage(moshi: Moshi) {
    val adapter = moshi.adapter(ClassMessage::class.java)
    val text = ClassMessage.Text("hello")
    assertThat(adapter.toJson(text)).isEqualTo("{\"type\":\"text\",\"value\":\"hello\"}")
    assertThat(adapter.fromJson("{\"type\":\"text\",\"value\":\"hello\"}")).isEqualTo(text)
  }
}
