// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
plugins {
  base
}

// Aggregate entry point so `./gradlew :moshi-sealed:test` runs every moshi-sealed backend's
// tests (codegen compilation tests plus all runtime entry points) from the repo root.
tasks.register("test") {
  group = "verification"
  description = "Runs all moshi-sealed subproject tests."
  dependsOn(
    ":moshi-sealed:codegen:test",
    ":moshi-sealed:java-sealed-reflect:test",
    ":moshi-sealed:metadata-reflect:test",
    ":moshi-sealed:reflect:test",
    ":moshi-sealed:runtime:test",
    ":moshi-sealed:sample:test",
  )
}
