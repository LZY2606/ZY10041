// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.reflect

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.zacsweers.moshix.sealed.fixtures.INVALID_SEALED_FIXTURES
import dev.zacsweers.moshix.sealed.fixtures.InvalidSealedFixture
import dev.zacsweers.moshix.sealed.fixtures.LegalSealedModelChecks
import dev.zacsweers.moshix.sealed.fixtures.assertInvalidSealedModel
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Legal-model behavior of the metadata-reflect entry point, checked against the shared fixtures. */
class MetadataReflectLegalSealedModelTest {
  private val moshi: Moshi =
    Moshi.Builder()
      .add(MetadataMoshiSealedJsonAdapterFactory())
      .addLast(KotlinJsonAdapterFactory())
      .build()

  @Test
  fun objectMessage() = LegalSealedModelChecks.checkObjectMessage(moshi)

  @Test
  fun objectMessageUnknownLabelFails() =
    LegalSealedModelChecks.checkObjectMessageUnknownLabelFails(moshi)

  @Test
  fun nestedMessage() = LegalSealedModelChecks.checkNestedMessage(moshi)

  @Test
  fun defaultNullMessage() = LegalSealedModelChecks.checkDefaultNullMessage(moshi)

  @Test
  fun defaultObjectMessage() = LegalSealedModelChecks.checkDefaultObjectMessage(moshi)

  @Test
  fun fallbackMessage() = LegalSealedModelChecks.checkFallbackMessage(moshi)

  @Test
  fun classMessage() = LegalSealedModelChecks.checkClassMessage(moshi)
}

/** Every shared invalid-model fixture must fail with the shared error code and core message. */
@RunWith(Parameterized::class)
class MetadataReflectInvalidSealedModelTest(private val fixture: InvalidSealedFixture) {
  private val moshi: Moshi =
    Moshi.Builder()
      .add(MetadataMoshiSealedJsonAdapterFactory())
      .addLast(KotlinJsonAdapterFactory())
      .build()

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun data(): Collection<InvalidSealedFixture> = INVALID_SEALED_FIXTURES
  }

  @Test
  fun invalidModel() = assertInvalidSealedModel(moshi, fixture)
}
