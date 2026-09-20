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
import kotlin.metadata.ClassKind
import kotlin.metadata.ClassName
import kotlin.metadata.KmClass
import kotlin.metadata.Modality
import kotlin.metadata.jvm.KotlinClassMetadata
import kotlin.metadata.jvm.Metadata
import kotlin.metadata.kind
import kotlin.metadata.modality

/** Classes annotated with this are eligible for this adapter. */
private val KOTLIN_METADATA = Metadata::class.java

public class MetadataMoshiSealedJsonAdapterFactory : JsonAdapter.Factory {
  override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
    if (annotations.isNotEmpty()) {
      return null
    }
    val rawType = type.rawType
    if (!rawType.isAnnotationPresent(KOTLIN_METADATA)) return null

    val jsonClass = rawType.getAnnotation(JsonClass::class.java) ?: return null
    val labelKey = jsonClass.labelKey() ?: return null
    val kmClass = checkNotNull(rawType.header()?.toKmClass())

    if (kmClass.modality != Modality.SEALED) {
      return null
    }

    val model =
      when (val result = SealedModelValidator.validate(rawType.toSealedDescription(kmClass))) {
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
        val subtype = entry.origin as Class<*>
        objectSubtypes[subtype] = subtype.objectInstance()
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
          entry.labels.fold(factory) { f, label -> f.withSubtype(entry.origin as Class<*>, label) }
        }
        .let { factory ->
          when (model.defaultStrategy) {
            DefaultStrategy.NONE -> factory
            DefaultStrategy.NULL -> factory.withDefaultValue(null)
            DefaultStrategy.DEFAULT_OBJECT ->
              factory.withDefaultValue((model.defaultObject!!.origin as Class<*>).objectInstance())
            DefaultStrategy.FALLBACK_ADAPTER -> factory.withFallbackJsonAdapter(fallbackAdapter)
          }
        }

    return polymorphicFactory.create(rawType, annotations, delegateMoshi)
  }
}

private fun Class<*>.header(): Metadata? {
  val metadata = getAnnotation(KOTLIN_METADATA) ?: return null
  return with(metadata) {
    Metadata(
      kind = kind,
      metadataVersion = metadataVersion,
      data1 = data1,
      data2 = data2,
      extraString = extraString,
      packageName = packageName,
      extraInt = extraInt,
    )
  }
}

private fun Metadata.toKmClass(): KmClass? {
  val classMetadata = KotlinClassMetadata.readLenient(this)
  if (classMetadata !is KotlinClassMetadata.Class) {
    return null
  }
  return classMetadata.kmClass
}

private fun JsonClass.labelKey(): String? =
  if (generator.startsWith("sealed:")) {
    generator.removePrefix("sealed:")
  } else {
    null
  }

private fun ClassName.toJavaClass(): Class<*> {
  return Class.forName(replace(".", "$").replace("/", "."))
}

private fun Class<*>.objectInstance(): Any {
  return getDeclaredField("INSTANCE").get(null)
}

private fun Class<*>.toSealedDescription(kmClass: KmClass? = null): SealedTypeDescription {
  val resolvedKmClass =
    kmClass
      ?: header()?.toKmClass()
      ?: error("Cannot decode Metadata for $this. Is it not a Kotlin class?")
  val hasNestedSealed = isAnnotationPresent(NestedSealed::class.java)
  return SealedTypeDescription(
    name = toString(),
    isSealed = resolvedKmClass.modality == Modality.SEALED,
    isObject = resolvedKmClass.kind == ClassKind.OBJECT,
    isGeneric = typeParameters.isNotEmpty(),
    labelKey = getAnnotation(JsonClass::class.java)?.labelKey(),
    typeLabel =
      getAnnotation(TypeLabel::class.java)?.let {
        TypeLabelInfo(it.label, it.alternateLabels.toList())
      },
    hasDefaultObject = isAnnotationPresent(DefaultObject::class.java),
    hasDefaultNull = isAnnotationPresent(DefaultNull::class.java),
    hasFallbackAdapter = isAnnotationPresent(FallbackJsonAdapter::class.java),
    hasNestedSealed = hasNestedSealed,
    parentLabelKey =
      if (hasNestedSealed) {
        val supertypes: List<Class<*>> = listOfNotNull(superclass, *interfaces)
        supertypes.firstNotNullOfOrNull { supertype ->
          supertype.getAnnotation(JsonClass::class.java)?.labelKey()
        }
      } else {
        null
      },
    subclasses =
      if (resolvedKmClass.modality == Modality.SEALED) {
        resolvedKmClass.sealedSubclasses.map { it.toJavaClass().toSealedDescription() }
      } else {
        emptyList()
      },
    origin = this,
  )
}
