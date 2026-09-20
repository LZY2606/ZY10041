// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
// Aggregate verification entry point for all moshi-sealed entry points (codegen, reflect,
// metadata-reflect) so `./gradlew :moshi-sealed:test` exercises every backend.
plugins {
  base
}

tasks.register("test") {
  group = LifecycleBasePlugin.VERIFICATION_GROUP
  description = "Runs all moshi-sealed tests across every entry point."
  dependsOn(
    ":moshi-sealed:codegen:test",
    ":moshi-sealed:reflect:test",
    ":moshi-sealed:metadata-reflect:test",
    ":moshi-sealed:runtime:test",
    ":moshi-sealed:java-sealed-reflect:test",
    ":moshi-sealed:sample:test",
  )
}
