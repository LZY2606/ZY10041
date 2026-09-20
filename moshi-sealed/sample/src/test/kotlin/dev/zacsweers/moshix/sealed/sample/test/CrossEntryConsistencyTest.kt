// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.sample.test

import com.google.common.truth.Truth.assertThat
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.zacsweers.moshix.sealed.fixtures.SealedFixtureExpectations
import dev.zacsweers.moshix.sealed.reflect.MetadataMoshiSealedJsonAdapterFactory
import dev.zacsweers.moshix.sealed.reflect.MoshiSealedJsonAdapterFactory
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Verifies that both runtime entry points (kotlin-reflect and metadata-reflect) produce
 * byte-identical error codes and messages for the same invalid models.
 */
class CrossEntryConsistencyTest {

  private val reflectMoshi: Moshi =
    Moshi.Builder()
      .add(MoshiSealedJsonAdapterFactory())
      .addLast(KotlinJsonAdapterFactory())
      .build()

  private val metadataMoshi: Moshi =
    Moshi.Builder()
      .add(MetadataMoshiSealedJsonAdapterFactory())
      .addLast(KotlinJsonAdapterFactory())
      .build()

  @Test
  fun invalidModelsProduceIdenticalErrorsAcrossRuntimeEntries() {
    for (case in SealedFixtureExpectations.INVALID_CASES) {
      val reflectError =
        assertThrows(IllegalStateException::class.java) { reflectMoshi.adapter(case.rootClass) }
      val metadataError =
        assertThrows(IllegalStateException::class.java) { metadataMoshi.adapter(case.rootClass) }
      assertThat(metadataError.message).isEqualTo(reflectError.message)
      assertThat(reflectError.message).startsWith("[${case.expectedCode}] ")
    }
  }

  @Test
  fun validModelsBehaveIdenticallyAcrossRuntimeEntries() {
    SealedFixtureExpectations.assertValidModelJson(reflectMoshi)
    SealedFixtureExpectations.assertValidModelJson(metadataMoshi)
    SealedFixtureExpectations.assertUnknownLabelBehavior(reflectMoshi)
    SealedFixtureExpectations.assertUnknownLabelBehavior(metadataMoshi)
  }
}
