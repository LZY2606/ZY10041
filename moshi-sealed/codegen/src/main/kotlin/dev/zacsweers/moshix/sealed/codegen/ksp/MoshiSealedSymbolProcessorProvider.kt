// Copyright (C) 2026 Zac Sweers
// SPDX-License-Identifier: Apache-2.0
package dev.zacsweers.moshix.sealed.codegen.ksp

import com.google.auto.service.AutoService
import com.google.devtools.ksp.getAllSuperTypes
import com.google.devtools.ksp.isVisibleFrom
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind.OBJECT
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.Modifier
import com.squareup.kotlinpoet.ANY
import com.squareup.kotlinpoet.AnnotationSpec
import com.squareup.kotlinpoet.CodeBlock
import com.squareup.kotlinpoet.TypeName
import com.squareup.kotlinpoet.asClassName
import com.squareup.kotlinpoet.ksp.addOriginatingKSFile
import com.squareup.kotlinpoet.ksp.toClassName
import com.squareup.kotlinpoet.ksp.writeTo
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonClass
import dev.zacsweers.moshix.sealed.codegen.ksp.MoshiSealedSymbolProcessorProvider.Companion.OPTION_GENERATED
import dev.zacsweers.moshix.sealed.runtime.internal.ObjectJsonAdapter
import dev.zacsweers.moshix.sealed.runtime.model.DefaultStrategy
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelResult.Invalid
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelResult.Valid
import dev.zacsweers.moshix.sealed.runtime.model.SealedModelValidator
import dev.zacsweers.moshix.sealed.runtime.model.SealedTypeDescription
import dev.zacsweers.moshix.sealed.runtime.model.TypeLabelInfo

@AutoService(SymbolProcessorProvider::class)
public class MoshiSealedSymbolProcessorProvider : SymbolProcessorProvider {
  public companion object {
    /**
     * This annotation processing argument can be specified to have a `@Generated` annotation
     * included in the generated code. It is not encouraged unless you need it for static analysis
     * reasons and not enabled by default.
     *
     * Note that this can only be one of the following values:
     * * `"javax.annotation.processing.Generated"` (JRE 9+)
     * * `"javax.annotation.Generated"` (JRE <9)
     *
     * We reuse Moshi's option for convenience so you don't have to declare multiple options.
     */
    public const val OPTION_GENERATED: String = "moshi.generated"
  }

  override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
    return MoshiSealedSymbolProcessor(environment)
  }
}

private class MoshiSealedSymbolProcessor(environment: SymbolProcessorEnvironment) :
  SymbolProcessor {

  private companion object {
    private val POSSIBLE_GENERATED_NAMES =
      setOf("javax.annotation.processing.Generated", "javax.annotation.Generated")

    private val JSON_CLASS_NAME = JsonClass::class.qualifiedName!!

    private val COMMON_SUPPRESS =
      arrayOf(
          // https://github.com/square/moshi/issues/1023
          "DEPRECATION",
          // Because we look it up reflectively
          "unused",
          // Because we include underscores
          "ClassName",
          // Because we generate redundant `out` variance for some generics and there's no way
          // for us to know when it's redundant.
          "REDUNDANT_PROJECTION",
          // Because we may generate redundant explicit types for local vars with default
          // values.
          // Example: 'var fooSet: Boolean = false'
          "RedundantExplicitType",
          // NameAllocator will just add underscores to differentiate names, which Kotlin
          // doesn't
          // like for stylistic reasons.
          "LocalVariableName",
          // KotlinPoet always generates explicit public modifiers for public members.
          "RedundantVisibilityModifier",
        )
        .let { suppressions ->
          AnnotationSpec.builder(Suppress::class)
            .addMember(suppressions.indices.joinToString { "%S" }, *suppressions)
            .build()
        }
  }

  private val codeGenerator = environment.codeGenerator
  private val logger = environment.logger
  private val generatedOption: String?
  private var hasInitErrors: Boolean = false

  init {
    generatedOption =
      environment.options[OPTION_GENERATED]?.also {
        if (it !in POSSIBLE_GENERATED_NAMES) {
          logger.error(
            "Invalid option value for $OPTION_GENERATED. Found $it, allowable values are $POSSIBLE_GENERATED_NAMES."
          )
          hasInitErrors = true
        }
      }
  }

  override fun process(resolver: Resolver): List<KSAnnotated> {
    if (hasInitErrors) return emptyList()
    val generatedAnnotation = generatedOption?.let {
      val annotationType =
        resolver.getClassDeclarationByName(resolver.getKSNameFromString(it))
          ?: run {
            logger.error("Generated annotation type doesn't exist: $it")
            return emptyList()
          }
      AnnotationSpec.builder(annotationType.toClassName())
        .addMember("value = [%S]", MoshiSealedSymbolProcessor::class.java.canonicalName)
        .addMember("comments = %S", "https://github.com/ZacSweers/moshi-sealed")
        .build()
    }

    val symbols = MoshiSealedSymbols(resolver)

    resolver.getSymbolsWithAnnotation(JSON_CLASS_NAME).forEach { type ->
      if (type !is KSClassDeclaration) {
        logger.error("@JsonClass is only applicable to classes!", type)
        return@forEach
      }

      val labelKey = type.findAnnotationWithType(symbols.jsonClass)?.labelKey() ?: return@forEach

      if (Modifier.SEALED !in type.modifiers) {
        logger.error("Must be a sealed class!", type)
        return@forEach
      }

      createType(type, labelKey, generatedAnnotation, symbols)
    }

    return emptyList()
  }

  private fun KSAnnotation.labelKey(checkGenerateAdapter: Boolean = true): String? {
    if (checkGenerateAdapter && !getMember<Boolean>("generateAdapter")) {
      return null
    }

    val generator = getMember<String>("generator")

    if (!generator.startsWith("sealed:")) {
      return null
    }

    return generator.removePrefix("sealed:")
  }

  /**
   * Translates this declaration into the backend-agnostic [SealedTypeDescription] consumed by
   * [SealedModelValidator]. Returns null if a KSP-specific parsing problem was found (already
   * logged).
   */
  private fun KSClassDeclaration.toSealedDescription(
    symbols: MoshiSealedSymbols,
    originatingKSFiles: MutableSet<KSFile>,
  ): SealedTypeDescription? {
    containingFile?.let(originatingKSFiles::add)
    val hasNestedSealed = hasAnnotation(symbols.nestedSealed)
    val typeLabelAnnotation = findAnnotationWithType(symbols.typeLabel)
    val typeLabel =
      typeLabelAnnotation?.let { annotation ->
        val label =
          annotation.arguments.find { it.name?.getShortName() == "label" }?.value as? String
            ?: run {
              logger.error("No label member for TypeLabel annotation!", annotation)
              return null
            }
        // https://github.com/google/ksp/issues/134
        @Suppress("UNCHECKED_CAST")
        val alternates =
          annotation.arguments.find { it.name?.getShortName() == "alternateLabels" }?.value
            as? List<String> // arrays are lists in KSP https://github.com/google/ksp/issues/135
          ?: emptyList() // ksp ignores undefined args
        TypeLabelInfo(label, alternates)
      }
    val isSealed = Modifier.SEALED in modifiers
    return SealedTypeDescription(
      name = qualifiedName?.asString() ?: toString(),
      isSealed = isSealed,
      isObject = classKind == OBJECT,
      isGeneric = typeParameters.isNotEmpty(),
      labelKey =
        findAnnotationWithType(symbols.jsonClass)?.labelKey(checkGenerateAdapter = false),
      typeLabel = typeLabel,
      hasDefaultObject = hasAnnotation(symbols.defaultObject),
      hasDefaultNull = hasAnnotation(symbols.defaultNull),
      hasFallbackAdapter = findAnnotationWithType(symbols.fallbackJsonAdapter) != null,
      hasNestedSealed = hasNestedSealed,
      parentLabelKey =
        if (hasNestedSealed) {
          getAllSuperTypes().firstNotNullOfOrNull { supertype ->
            // Weird that we need to check the classifier ourselves
            supertype.declaration.findAnnotationWithType(symbols.jsonClass)?.labelKey()
          }
        } else {
          null
        },
      subclasses =
        if (isSealed) {
          getSealedSubclasses()
            .toList()
            .map { it.toSealedDescription(symbols, originatingKSFiles) ?: return null }
        } else {
          emptyList()
        },
      origin = this,
    )
  }

  private fun createType(
    type: KSClassDeclaration,
    labelKey: String,
    generatedAnnotation: AnnotationSpec?,
    symbols: MoshiSealedSymbols,
  ) {
    val originatingKSFiles = mutableSetOf<KSFile>()
    val description =
      type.toSealedDescription(symbols, originatingKSFiles) ?: return

    // KSP-specific fallback adapter constructor checks run before shared model validation so
    // that adapter-shape problems are reported with the same priority as before.
    val fallbackAdapterAnnotation = type.findAnnotationWithType(symbols.fallbackJsonAdapter)
    var fallbackAdapterStrategy: FallbackStrategy.FallbackAdapter? = null
    if (fallbackAdapterAnnotation != null) {
      val adapterType = (fallbackAdapterAnnotation.arguments[0].value as KSType)
      // TODO can we check adapter type is valid? Compiler will check it for us
      val adapterDeclaration = adapterType.declaration as KSClassDeclaration
      val constructor = adapterDeclaration.primaryConstructor
      if (constructor?.isVisibleFrom(type) == false) {
        logger.error(
          "Fallback adapter type $adapterType and its primary constructor must be visible from $type",
          fallbackAdapterAnnotation,
        )
        return
      }
      val hasMoshiParam =
        when (constructor?.parameters?.size) {
          null,
          0 -> {
            // Nothing to do
            false
          }
          1 -> {
            // Check it's a Moshi parameter
            val moshiParam = constructor.parameters[0]
            // TODO can this be simpler?
            if (!symbols.moshi.isAssignableFrom(moshiParam.type.resolve())) {
              logger.error(
                "Fallback adapter type's primary constructor can only have a Moshi parameter",
                fallbackAdapterAnnotation,
              )
              return
            }
            true
          }
          else -> {
            logger.error(
              "Fallback adapter type's primary constructor can only have a Moshi parameter",
              fallbackAdapterAnnotation,
            )
            return
          }
        }
      fallbackAdapterStrategy =
        FallbackStrategy.FallbackAdapter(
          className = adapterType.toClassName(),
          hasMoshiParam = hasMoshiParam,
        )
    }

    val model =
      when (val result = SealedModelValidator.validate(description)) {
        is Invalid -> {
          for (error in result.errors) {
            logger.error(error.render(), error.origin as? KSNode)
          }
          return
        }
        is Valid -> result.model
      }

    var fallbackStrategy: FallbackStrategy? = null
    when (model.defaultStrategy) {
      DefaultStrategy.NULL -> fallbackStrategy = FallbackStrategy.Null
      DefaultStrategy.FALLBACK_ADAPTER -> fallbackStrategy = fallbackAdapterStrategy
      else -> {
        // NONE and DEFAULT_OBJECT need no fallback strategy here. DEFAULT_OBJECT is represented
        // as a Subtype.ObjectType below.
      }
    }

    val objectAdapters = mutableListOf<CodeBlock>()
    val sealedSubtypes =
      model.entries.mapTo(LinkedHashSet<Subtype>()) { entry ->
        val subtypeDeclaration = entry.origin as KSClassDeclaration
        val className = subtypeDeclaration.toClassName()
        if (entry.isObject) {
          objectAdapters.add(
            CodeBlock.of(
              ".add(%1T::class.java,·%2T(%1T))",
              className,
              ObjectJsonAdapter::class.asClassName(),
            )
          )
        }
        Subtype.ClassType(className, entry.labels)
      }
    model.defaultObject?.let { defaultObject ->
      sealedSubtypes += Subtype.ObjectType((defaultObject.origin as KSClassDeclaration).toClassName())
    }

    createType(
        targetType = type.toClassName(),
        isInternal = Modifier.INTERNAL in type.modifiers,
        labelKey = labelKey,
        fallbackStrategy = fallbackStrategy,
        generatedAnnotation = generatedAnnotation,
        subtypes = sealedSubtypes,
        objectAdapters = objectAdapters,
        errorLogger = { message -> logger.error(message, type) },
      ) {
        addAnnotation(COMMON_SUPPRESS)
        for (file in originatingKSFiles) {
          addOriginatingKSFile(file)
        }
      }
      ?.writeTo(codeGenerator, aggregating = true)
  }
}

internal sealed interface FallbackStrategy {
  fun statement(moshiParam: CodeBlock): CodeBlock

  data object Null : FallbackStrategy {
    override fun statement(moshiParam: CodeBlock) = CodeBlock.of(".withDefaultValue(null)")
  }

  class FallbackAdapter(val className: TypeName, val hasMoshiParam: Boolean) : FallbackStrategy {
    // TODO handle which moshi param comes in here
    override fun statement(moshiParam: CodeBlock): CodeBlock {
      val constructorParams =
        if (hasMoshiParam) {
          moshiParam
        } else {
          CodeBlock.of("")
        }
      return CodeBlock.of(
        ".withFallbackJsonAdapter(%T(%L)·as·%T<%T>)",
        className,
        constructorParams,
        JsonAdapter::class.asClassName(),
        ANY,
      )
    }
  }

  class DefaultObject(val className: TypeName) : FallbackStrategy {
    override fun statement(moshiParam: CodeBlock) = CodeBlock.of(".withDefaultValue(%T)", className)
  }
}
