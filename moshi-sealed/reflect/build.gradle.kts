// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  alias(libs.plugins.kotlinJvm)
  alias(libs.plugins.mavenPublish)
}

dependencies {
  api(libs.moshi)
  implementation(project(":moshi-sealed:runtime"))
  implementation(libs.moshi.adapters)
  implementation(libs.kotlin.reflect)

  testImplementation(project(":moshi-sealed:fixtures"))
  testImplementation(libs.moshi.kotlin)
  testImplementation(libs.junit)
  testImplementation(libs.truth)
}

tasks.test {
  testLogging { events("passed", "failed", "skipped") }
}
