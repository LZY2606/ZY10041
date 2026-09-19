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

private val UNSET = Any()

public class MetadataMoshiSealedJsonAdapterFactory : JsonAdapter.Factory {
  override fun create(type: Type, annotations: Set<Annotation>, moshi: Moshi): JsonAdapter<*>? {
    if (annotations.isNotEmpty()) {
      return null
    }
    val rawType = type.rawType
    if (!rawType.isAnnotationPresent(KOTLIN_METADATA)) return null

    rawType.getAnnotation(JsonClass::class.java)?.let { jsonClass ->
      val labelKey = sealedLabelKey(jsonClass.generator) ?: return null
      val kmClass = checkNotNull(rawType.header()?.toKmClass())

      if (kmClass.modality != Modality.SEALED) {
        return null
      }

      val result =
        SealedModelValidator.validate(MetadataSealedClassProbe(rawType, kmClass), labelKey)
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

/** A [SealedClassProbe] backed by `kotlin-metadata` and Java reflection. */
private class MetadataSealedClassProbe(
  private val clazz: Class<*>,
  private val kmClass: KmClass,
) : SealedClassProbe<Class<*>> {
  override val handle: Class<*>
    get() = clazz

  override val identity: Any
    get() = clazz

  override val displayName: String
    get() = clazz.toString()

  override val isSealed: Boolean
    get() = kmClass.modality == Modality.SEALED

  override val isObject: Boolean
    get() = kmClass.kind == ClassKind.OBJECT

  override val objectInstance: Any?
    get() = if (isObject) clazz.objectInstance() else null

  override val hasTypeParameters: Boolean
    get() = clazz.typeParameters.isNotEmpty()

  override val jsonClassLabelKey: String?
    get() = clazz.getAnnotation(JsonClass::class.java)?.let { sealedLabelKey(it.generator) }

  override val hasNestedSealed: Boolean
    get() = clazz.isAnnotationPresent(NestedSealed::class.java)

  override val hasDefaultNull: Boolean
    get() = clazz.isAnnotationPresent(DefaultNull::class.java)

  override val hasDefaultObject: Boolean
    get() = clazz.isAnnotationPresent(DefaultObject::class.java)

  override val hasFallbackJsonAdapter: Boolean
    get() = clazz.isAnnotationPresent(FallbackJsonAdapter::class.java)

  override val typeLabel: TypeLabelData?
    get() =
      clazz.getAnnotation(TypeLabel::class.java)?.let {
        TypeLabelData(it.label, it.alternateLabels.toList())
      }

  override val sealedSubtypes: List<SealedClassProbe<Class<*>>>
    get() =
      kmClass.sealedSubclasses.map { subclassName ->
        val subclass = subclassName.toJavaClass()
        MetadataSealedClassProbe(subclass, checkNotNull(subclass.header()?.toKmClass()))
      }

  override val supertypeLabelKeys: List<String>
    get() =
      listOfNotNull(clazz.superclass, *clazz.interfaces).mapNotNull { supertype ->
        supertype.getAnnotation(JsonClass::class.java)?.let { sealedLabelKey(it.generator) }
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

private fun ClassName.toJavaClass(): Class<*> {
  return Class.forName(replace(".", "$").replace("/", "."))
}

private fun Class<*>.objectInstance(): Any {
  return getDeclaredField("INSTANCE").get(null)
}
