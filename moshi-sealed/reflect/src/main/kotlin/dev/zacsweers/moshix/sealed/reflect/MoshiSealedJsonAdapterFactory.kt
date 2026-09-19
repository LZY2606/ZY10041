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
import dev.zacsweers.moshix.sealed.runtime.model.SealedClassProbe
import dev.zacsweers.moshix.sealed.runtime.model.SealedFallback
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelResult
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelValidator
import dev.zacsweers.moshix.sealed.runtime.model.TypeLabelData
import dev.zacsweers.moshix.sealed.runtime.model.sealedLabelKey
import java.lang.reflect.Type
import kotlin.reflect.KClass
import kotlin.reflect.full.findAnnotation
import kotlin.reflect.full.hasAnnotation

private val UNSET = Any()

public class MoshiSealedJsonAdapterFactory : JsonAdapter.Factory {
  override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
    if (annotations.isNotEmpty()) {
      return null
    }
    val rawType = type.rawType
    val rawTypeKotlin = rawType.kotlin

    val jsonClass = rawType.getAnnotation(JsonClass::class.java)
    if (jsonClass != null) {
      val labelKey = sealedLabelKey(jsonClass.generator) ?: return null
      if (!rawTypeKotlin.isSealed) {
        return null
      }

      val result = SealedModelValidator.validate(ReflectSealedClassProbe(rawTypeKotlin), labelKey)
      val model =
        when (result) {
          is SealedModelResult.Invalid -> {
            throw IllegalStateException(
              "Invalid sealed model for $rawType:\n" +
                result.errors.joinToString("\n") { it.render() }
            )
          }
          is SealedModelResult.Valid -> result.model
        }

      // Pull out the default instance as necessary
      // Possible cases:
      //   - No default (error if missing at runtime)
      //   - Null default
      //   - Object default
      //   - Fallback adapter
      var defaultObjectInstance: Any? = UNSET
      var fallbackAdapter: JsonAdapter<Any>? = null
      when (val fallback = model.fallback) {
        is SealedFallback.DefaultObject -> {
          defaultObjectInstance =
            checkNotNull(fallback.probe.objectInstance) {
              "Must be an object type to use as a @DefaultObject: ${fallback.probe.displayName}"
            }
        }
        SealedFallback.NullValue -> {
          defaultObjectInstance = null
        }
        SealedFallback.FallbackAdapter -> {
          val clazz =
            checkNotNull(rawType.getAnnotation(FallbackJsonAdapter::class.java)).value
          fallbackAdapter = moshi.fallbackAdapter(clazz.java)
        }
        SealedFallback.None -> {
          // No default
        }
      }

      val objectSubtypes = mutableMapOf<Class<*>, Any>()
      val labels = mutableMapOf<String, Class<*>>()
      for (labeledSubtype in model.labeledSubtypes) {
        val subtypeClass = labeledSubtype.probe.handle
        for (label in labeledSubtype.labels) {
          labels[label] = subtypeClass
        }
        if (labeledSubtype.isObject) {
          objectSubtypes[subtypeClass] = checkNotNull(labeledSubtype.probe.objectInstance)
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
        labels.entries
          .fold(seed) { factory, (label, subtype) -> factory.withSubtype(subtype, label) }
          .let { factory ->
            if (defaultObjectInstance !== UNSET) {
              factory.withDefaultValue(defaultObjectInstance)
            } else if (fallbackAdapter != null) {
              factory.withFallbackJsonAdapter(fallbackAdapter)
            } else {
              factory
            }
          }

      return polymorphicFactory.create(rawType, annotations, delegateMoshi)
    }

    return null
  }
}

/** A [SealedClassProbe] backed by Kotlin reflection. */
private class ReflectSealedClassProbe(private val kClass: KClass<*>) :
  SealedClassProbe<Class<*>> {
  override val handle: Class<*>
    get() = kClass.java

  override val identity: Any
    get() = kClass.java

  override val displayName: String
    get() = kClass.java.toString()

  override val isSealed: Boolean
    get() = kClass.isSealed

  override val isObject: Boolean
    get() = kClass.objectInstance != null

  override val objectInstance: Any?
    get() = kClass.objectInstance

  override val hasTypeParameters: Boolean
    get() = kClass.java.typeParameters.isNotEmpty()

  override val jsonClassLabelKey: String?
    get() = kClass.findAnnotation<JsonClass>()?.let { sealedLabelKey(it.generator) }

  override val hasNestedSealed: Boolean
    get() = kClass.hasAnnotation<NestedSealed>()

  override val hasDefaultNull: Boolean
    get() = kClass.hasAnnotation<DefaultNull>()

  override val hasDefaultObject: Boolean
    get() = kClass.java.isAnnotationPresent(DefaultObject::class.java)

  override val hasFallbackJsonAdapter: Boolean
    get() = kClass.java.isAnnotationPresent(FallbackJsonAdapter::class.java)

  override val typeLabel: TypeLabelData?
    get() =
      kClass.findAnnotation<TypeLabel>()?.let {
        TypeLabelData(it.label, it.alternateLabels.toList())
      }

  override val sealedSubtypes: List<SealedClassProbe<Class<*>>>
    get() = kClass.sealedSubclasses.map(::ReflectSealedClassProbe)

  override val supertypeLabelKeys: List<String>
    get() =
      kClass.supertypes.mapNotNull { supertype ->
        val jsonClass =
          (supertype.classifier as? KClass<*>)?.findAnnotation<JsonClass>()
            ?: supertype.findAnnotation()
        jsonClass?.let { sealedLabelKey(it.generator) }
      }
}
