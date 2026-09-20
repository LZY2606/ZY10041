// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.reflect

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import com.squareup.moshi.Moshi
import com.squareup.moshi.adapters.PolymorphicJsonAdapterFactory
import com.squareup.moshi.rawType
import dev.zacsweers.moshix.sealed.annotations.DefaultNull
import dev.zacsweers.moshix.sealed.annotations.DefaultObject
import dev.zacsweers.moshix.sealed.annotations.FallbackJsonAdapter
import dev.zacsweers.moshix.sealed.annotations.NestedSealed
import dev.zacsweers.moshix.sealed.annotations.TypeLabel
import dev.zacsweers.moshix.sealed.runtime.internal.ObjectJsonAdapter
import dev.zacsweers.moshix.sealed.runtime.internal.Util.fallbackAdapter
import dev.zacsweers.moshix.sealed.runtime.model.DefaultStrategy
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelResult.Invalid
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelResult.Valid
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelValidator
import dev.zacsweers.moshix.sealed.runtime.model.SealedTypeDescription
import dev.zacsweers.moshix.sealed.runtime.model.TypeLabelInfo
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation

public class MoshiSealedJsonAdapterFactory : JsonAdapter.Factory {
  override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
    if (annotations.isNotEmpty()) {
      return null
    }
    val rawType = type.rawType
    val rawTypeKotlin = rawType.kotlin

    val jsonClass = rawType.getAnnotation(JsonClass::class.java) ?: return null
    val labelKey = jsonClass.labelKey() ?: return null
    if (!rawTypeKotlin.isSealed) {
      return null
    }

    val model =
      when (val result = SealedModelValidator.validate(rawTypeKotlin.toSealedDescription())) {
        is Invalid ->
          throw IllegalStateException(
            "${result.errors.first().render()} (while creating adapter for $rawType)"
          )
        is Valid -> result.model
      }

    // Resolve the fallback adapter reflectively, if any
    val fallbackAdapter: JsonAdapter<Any>? =
      if (model.defaultStrategy == DefaultStrategy.FALLBACK_ADAPTER) {
        val clazz = rawType.getAnnotation(FallbackJsonAdapter::class.java)!!.value
        moshi.fallbackAdapter(clazz.java)
      } else {
        null
      }

    val objectSubtypes = mutableMapOf<Class<*>, Any>()
    for (entry in model.entries) {
      if (entry.isObject) {
        @Suppress("UNCHECKED_CAST")
        val subtype = (entry.origin as KClass<Any>)
        objectSubtypes[subtype.java] = subtype.objectInstance!!
      }
    }

    val delegateMoshi =
      if (objectSubtypes.isEmpty()) {
        moshi
      } else {
        moshi
          .newBuilder()
          .apply {
            for ((subtype, instance) in objectSubtypes) {
              add(subtype, ObjectJsonAdapter(instance))
            }
          }
          .build()
      }

    @Suppress("UNCHECKED_CAST")
    val seed = PolymorphicJsonAdapterFactory.of(rawType as Class<Any>?, labelKey)
    val polymorphicFactory =
      model.entries
        .fold(seed) { factory, entry ->
          entry.labels.fold(factory) { f, label ->
            f.withSubtype((entry.origin as KClass<*>).java, label)
          }
        }
        .let { factory ->
          when (model.defaultStrategy) {
            DefaultStrategy.NONE -> factory
            DefaultStrategy.NULL -> factory.withDefaultValue(null)
            DefaultStrategy.DEFAULT_OBJECT ->
              factory.withDefaultValue((model.defaultObject!!.origin as KClass<*>).objectInstance)
            DefaultStrategy.FALLBACK_ADAPTER -> factory.withFallbackJsonAdapter(fallbackAdapter)
          }
        }

    return polymorphicFactory.create(rawType, annotations, delegateMoshi)
  }
}

private fun JsonClass.labelKey(): String? =
  if (generator.startsWith("sealed:")) {
    generator.removePrefix("sealed:")
  } else {
    null
  }

private fun KClass<*>.toSealedDescription(): SealedTypeDescription {
  val hasNestedSealed = java.isAnnotationPresent(NestedSealed::class.java)
  return SealedTypeDescription(
    name = java.toString(),
    isSealed = isSealed,
    isObject = objectInstance != null,
    isGeneric = typeParameters.isNotEmpty(),
    labelKey = findAnnotation<JsonClass>()?.labelKey(),
    typeLabel =
      findAnnotation<TypeLabel>()?.let { TypeLabelInfo(it.label, it.alternateLabels.toList()) },
    hasDefaultObject = java.isAnnotationPresent(DefaultObject::class.java),
    hasDefaultNull = annotations.any { it is DefaultNull },
    hasFallbackAdapter = java.isAnnotationPresent(FallbackJsonAdapter::class.java),
    hasNestedSealed = hasNestedSealed,
    parentLabelKey =
      if (hasNestedSealed) {
        supertypes.firstNotNullOfOrNull { supertype ->
          // Weird that we need to check the classifier ourselves
          val nestedJsonClass =
            (supertype.classifier as? KClass<*>)?.findAnnotation<JsonClass>()
              ?: supertype.findAnnotation()
          nestedJsonClass?.labelKey()
        }
      } else {
        null
      },
    subclasses = sealedSubclasses.map { it.toSealedDescription() },
    origin = this,
  )
}
