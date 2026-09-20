// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlinJvm)
}

dependencies {
  api(project(":moshi-sealed:runtime"))
  api(libs.moshi)
  // Fixtures ship shared assertions so every entry point tests the same expectations
  api(libs.truth)
  api(libs.junit)
}
