// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.reflect

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import dev.zacsweers.moshix.sealed.fixtures.SealedFixtureExpectations
import org.junit.Test

/** Runs the shared cross-entry fixtures against the kotlin-reflect entry point. */
class ReflectSealedModelTest {

  private val moshi: Moshi =
    Moshi.Builder()
      .add(MoshiSealedJsonAdapterFactory())
      .addLast(KotlinJsonAdapterFactory())
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
