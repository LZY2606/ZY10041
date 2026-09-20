// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.fixtures

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.JsonDataException
import com.squareup.moshi.Moshi
import org.junit.Assert.assertThrows

/**
 * Shared behavioral expectations for the cross-entry fixtures. Every runtime entry point
 * (kotlin-reflect, metadata-reflect) runs these exact checks against its own [Moshi] instance so
 * that valid models behave identically and invalid models fail with identical error codes and
 * core messages.
 */
public object SealedFixtureExpectations {

  /** A case of an invalid model: its root class, expected error code, and core message parts. */
  public class InvalidCase(
    public val rootClass: Class<*>,
    public val expectedCode: String,
    public vararg val messageParts: String,
  )

  public val INVALID_CASES: List<InvalidCase> =
    listOf(
      InvalidCase(
        DuplicateLabels::class.java,
        "MOSHIX_SEALED_DUPLICATE_LABEL",
        "Duplicate label 'a' defined for",
        DuplicateLabels.A::class.java.toString(),
        DuplicateLabels.B::class.java.toString(),
      ),
      InvalidCase(
        DuplicateAlternateLabels::class.java,
        "MOSHIX_SEALED_DUPLICATE_ALTERNATE_LABEL",
        "Duplicate alternate label 'x' defined for",
        DuplicateAlternateLabels.A::class.java.toString(),
        DuplicateAlternateLabels.B::class.java.toString(),
      ),
      InvalidCase(
        MissingLabel::class.java,
        "MOSHIX_SEALED_MISSING_TYPE_LABEL",
        "Sealed subtypes must be annotated with @TypeLabel to define their label:",
        MissingLabel.B::class.java.toString(),
      ),
      InvalidCase(
        MultipleDefaultObjects::class.java,
        "MOSHIX_SEALED_MULTIPLE_DEFAULT_OBJECTS",
        "Can only have one @DefaultObject:",
        MultipleDefaultObjects.A::class.java.toString(),
        MultipleDefaultObjects.B::class.java.toString(),
      ),
      InvalidCase(
        ConflictingDefaults::class.java,
        "MOSHIX_SEALED_CONFLICTING_DEFAULTS",
        "Only one of @DefaultNull, @DefaultObject, and @FallbackJsonAdapter can be used at a time:",
      ),
      InvalidCase(
        DefaultObjectNotObject::class.java,
        "MOSHIX_SEALED_DEFAULT_OBJECT_NOT_OBJECT",
        "Must be an object type to use as a @DefaultObject:",
        DefaultObjectNotObject.B::class.java.toString(),
      ),
      InvalidCase(
        GenericSubtypes::class.java,
        "MOSHIX_SEALED_GENERIC_SUBTYPE",
        "Moshi-sealed subtypes cannot be generic:",
        GenericSubtypes.B::class.java.toString(),
      ),
      InvalidCase(
        RedundantNestedLabel::class.java,
        "MOSHIX_SEALED_REDUNDANT_NESTED_LABEL",
        "is redundantly annotated with @JsonClass(generator = \"sealed:type\")",
        RedundantNestedLabel.B::class.java.toString(),
      ),
    )

  /** Valid models: JSON shape, labels, alternate labels, object subtypes, nested sealed types. */
  public fun assertValidModelJson(moshi: Moshi) {
    val adapter = moshi.adapter(ValidMessage::class.java)

    // Labels and round trips
    assertThat(adapter.toJson(ValidMessage.Text("hello")))
      .isEqualTo("""{"type":"text","value":"hello"}""")
    assertThat(adapter.fromJson("""{"type":"text","value":"hello"}"""))
      .isEqualTo(ValidMessage.Text("hello"))

    // Alternate labels decode to the same subtype
    assertThat(adapter.fromJson("""{"type":"txt","value":"hi"}"""))
      .isEqualTo(ValidMessage.Text("hi"))

    // Object subtypes
    assertThat(adapter.toJson(ValidMessage.Empty)).isEqualTo("""{"type":"empty"}""")
    assertThat(adapter.fromJson("""{"type":"empty"}""")).isEqualTo(ValidMessage.Empty)

    // Nested sealed types flatten into the parent's labels
    assertThat(adapter.toJson(ValidMessage.Nested.NestedText("n")))
      .isEqualTo("""{"type":"nested_text","value":"n"}""")
    assertThat(adapter.fromJson("""{"type":"nested_text","value":"n"}"""))
      .isEqualTo(ValidMessage.Nested.NestedText("n"))
  }

  /** Unknown label handling: no default, null default, default object, fallback adapter. */
  public fun assertUnknownLabelBehavior(moshi: Moshi) {
    // No default: a JsonDataException naming the unknown label
    val adapter = moshi.adapter(ValidMessage::class.java)
    val error =
      assertThrows(JsonDataException::class.java) { adapter.fromJson("""{"type":"nope"}""") }
    assertThat(error).hasMessageThat().contains("nope")

    // Null default
    assertThat(
        moshi.adapter(ValidDefaultNull::class.java).fromJson("""{"type":"nope","value":"x"}""")
      )
      .isNull()

    // Default object
    assertThat(
        moshi.adapter(ValidDefaultObject::class.java).fromJson("""{"type":"nope","value":"x"}""")
      )
      .isEqualTo(ValidDefaultObject.Default)

    // Fallback adapter
    assertThat(moshi.adapter(ValidFallback::class.java).fromJson("""{"type":"nope","value":"x"}"""))
      .isEqualTo(ValidFallback.Unknown)
  }

  /** Invalid models fail with the shared error code and core message. */
  public fun assertInvalidModels(moshi: Moshi) {
    for (case in INVALID_CASES) {
      val error =
        assertThrows(IllegalStateException::class.java) { moshi.adapter(case.rootClass) }
      assertThat(error).hasMessageThat().startsWith("[${case.expectedCode}] ")
      for (part in case.messageParts) {
        assertThat(error).hasMessageThat().contains(part)
      }
    }
  }
}
