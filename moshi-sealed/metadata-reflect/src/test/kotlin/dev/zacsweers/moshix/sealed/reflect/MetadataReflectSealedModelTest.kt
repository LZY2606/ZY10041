// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.reflect

import com.squareup.moshi.Moshi
import dev.zacsweers.moshix.reflect.MetadataKotlinJsonAdapterFactory
import dev.zacsweers.moshix.sealed.fixtures.SealedFixtureExpectations
import org.junit.Test

/** Runs the shared cross-entry fixtures against the metadata-reflect entry point. */
class MetadataReflectSealedModelTest {

  private val moshi: Moshi =
    Moshi.Builder()
      .add(MetadataMoshiSealedJsonAdapterFactory())
      .addLast(MetadataKotlinJsonAdapterFactory())
      .build()

  @Test
  fun validModelJson() {
    SealedFixtureExpectations.assertValidModelJson(moshi)
  }

  @Test
  fun unknownLabelBehavior() {
    SealedFixtureExpectations.assertUnknownLabelBehavior(moshi)
  }

  @Test
  fun invalidModels() {
    SealedFixtureExpectations.assertInvalidModels(moshi)
  }
}
