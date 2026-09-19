// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.codegen.ksp

import com.google.common.truth.Truth.assertThat
import com.tschuchort.compiletesting.KotlinCompilation
import com.tschuchort.compiletesting.KotlinCompilation.ExitCode
import com.tschuchort.compiletesting.SourceFile.Companion.kotlin
import com.tschuchort.compiletesting.configureKsp
import dev.zacsweers.moshix.sealed.fixtures.INVALID_SEALED_FIXTURES
import dev.zacsweers.moshix.sealed.fixtures.INVALID_SEALED_FIXTURE_SOURCES
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * Feeds the shared invalid-model fixture sources ([INVALID_SEALED_FIXTURE_SOURCES], mirrors of the
 * compiled fixtures used by the runtime entry points) through a real KSP compilation and checks
 * that the compiler reports the same error code and core message as the runtime backends.
 */
@RunWith(Parameterized::class)
class SealedModelFixtureCompilationTest(private val fixtureName: String) {

  companion object {
    @JvmStatic
    @Parameterized.Parameters(name = "{0}")
    fun data(): Collection<String> = INVALID_SEALED_FIXTURES.map { it.name }
  }

  @Test
  fun invalidModelReportsSharedError() {
    val fixture = INVALID_SEALED_FIXTURES.first { it.name == fixtureName }
    val source =
      checkNotNull(INVALID_SEALED_FIXTURE_SOURCES[fixtureName]) {
        "Missing source mirror for fixture $fixtureName"
      }
    val compilation =
      KotlinCompilation().apply {
        sources = listOf(kotlin("$fixtureName.kt", source))
        inheritClassPath = true
        configureKsp { symbolProcessorProviders += MoshiSealedSymbolProcessorProvider() }
        kotlincArguments += "-Xskip-prerelease-check"
      }
    val result = compilation.compile()
    assertThat(result.exitCode).isEqualTo(ExitCode.COMPILATION_ERROR)
    assertThat(result.messages).contains("MOSHIX_SEALED_${fixture.expectedCode.name}")
    assertThat(result.messages).contains(fixture.messageFragment)
  }
}
