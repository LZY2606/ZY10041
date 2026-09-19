// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.codegen.ksp

import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.symbol.ClassKind.OBJECT
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ksp.toClassName
import dev.zacsweers.moshix.sealed.runtime.model.SealedClassProbe
import dev.zacsweers.moshix.sealed.runtime.model.TypeLabelData
import dev.zacsweers.moshix.sealed.runtime.model.sealedLabelKey

/** A [SealedClassProbe] backed by KSP symbols. */
internal class KspSealedClassProbe(
  private val declaration: KSClassDeclaration,
  private val symbols: MoshiSealedSymbols,
) : SealedClassProbe<KSClassDeclaration> {
  override val handle: KSClassDeclaration
    get() = declaration

  override val identity: Any
    get() = declaration.toClassName()

  override val displayName: String
    get() = declaration.qualifiedName?.asString() ?: declaration.toString()

  override val isSealed: Boolean
    get() = Modifier.SEALED in declaration.modifiers

  override val isObject: Boolean
    get() = declaration.classKind == OBJECT

  override val objectInstance: Any?
    get() = null // Instances are not available at compile time

  override val hasTypeParameters: Boolean
    get() = declaration.typeParameters.isNotEmpty()

  override val jsonClassLabelKey: String?
    get() =
      declaration.findAnnotationWithType(symbols.jsonClass)?.labelKey(checkGenerateAdapter = false)

  override val hasNestedSealed: Boolean
    get() = declaration.hasAnnotation(symbols.nestedSealed)

  override val hasDefaultNull: Boolean
    get() = declaration.hasAnnotation(symbols.defaultNull)

  override val hasDefaultObject: Boolean
    get() = declaration.hasAnnotation(symbols.defaultObject)

  override val hasFallbackJsonAdapter: Boolean
    get() = declaration.findAnnotationWithType(symbols.fallbackJsonAdapter) != null

  override val typeLabel: TypeLabelData?
    get() =
      declaration.findAnnotationWithType(symbols.typeLabel)?.let { annotation ->
        val label =
          annotation.arguments.find { it.name?.getShortName() == "label" }?.value as? String
            ?: error("No label member for TypeLabel annotation!")
        @Suppress("UNCHECKED_CAST")
        val alternates =
          annotation.arguments.find { it.name?.getShortName() == "alternateLabels" }?.value
            as? List<String> // arrays are lists in KSP https://github.com/google/ksp/issues/135
          ?: emptyList() // ksp ignores undefined args
        TypeLabelData(label, alternates)
      }

  override val sealedSubtypes: List<SealedClassProbe<KSClassDeclaration>>
    get() = declaration.getSealedSubclasses().map { KspSealedClassProbe(it, symbols) }.toList()

  override val supertypeLabelKeys: List<String>
    get() =
      declaration
        .getAllSuperTypes()
        .mapNotNull { supertype ->
          supertype.declaration.findAnnotationWithType(symbols.jsonClass)?.labelKey(
            checkGenerateAdapter = false
          )
        }
        .toList()
}

internal fun KSAnnotation.labelKey(checkGenerateAdapter: Boolean = true): String? {
  if (checkGenerateAdapter && !getMember<Boolean>("generateAdapter")) {
    return null
  }
  return sealedLabelKey(getMember<String>("generator"))
}
