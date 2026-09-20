# Moshi-sealed 共享模型与校验设计说明

## 背景

MoshiX 的 sealed 支持有三个入口：KSP codegen（`moshi-sealed:codegen`）、kotlin-reflect
（`moshi-sealed:reflect`）和 metadata-reflect（`moshi-sealed:metadata-reflect`）。此前每个入口
各自实现 label 收集、default 冲突、重复 label、object 子类型和嵌套 sealed 的校验，错误时机与
文本逐渐分叉（例如 default 冲突在 codegen 有三种不同文案，metadata-reflect 与 reflect 的
`@DefaultObject` 冲突文案不一致）。

## 设计

```
┌──────────────────────────── moshi-sealed:runtime ───────────────────────────┐
│ dev.zacsweers.moshix.sealed.runtime.model                                   │
│   SealedTypeDescription   后端无关的 sealed 层级描述（含 opaque origin token） │
│   SealedModelValidator    唯一的规则实现，纯函数，无 I/O、无反射、无回调      │
│   SealedModel             校验通过的模型（labelKey/entries/defaultStrategy） │
│   SealedModelError(Code)  稳定错误码 + 核心消息，render() = "[CODE] message" │
└─────────────────────────────────────────────────────────────────────────────┘
        ▲                    ▲                          ▲
        │ 各自把类型表示翻译成 SealedTypeDescription，再把 SealedModel 实例化为 adapter
        │                    │                          │
  codegen (KSP)        reflect (KClass)        metadata-reflect (KmClass)
  KSNode→origin        KClass→origin           Class→origin
  logger.error(render, node)  → 附 source location
  throw IllegalStateException(render + 请求类型上下文)  → 两个运行时入口
```

- **单一规则来源**：label/缺 label/重复 label/重复 alternate/default 冲突/多 default/
  generic 子类型/嵌套 sealed 冗余 label/`@TypeLabel`+`@JsonClass` 共存等 11 条规则只在
  `SealedModelValidator` 中实现一次。
- **错误码与核心消息统一**：同一非法模型在所有能到达的入口产生相同的
  `[MOSHIX_SEALED_*]` 错误码与核心消息（`CrossEntryConsistencyTest` 逐字节断言两个运行时
  入口的异常消息相等）。
- **入口上下文保留**：codegen 通过 `origin`（KSNode）把错误挂回源码位置；运行时入口抛出
  时附加 `(while creating adapter for <type>)` 类型上下文。
- **依赖方向**：共享代码位于 `moshi-sealed:runtime`，codegen/reflect/metadata-reflect 本就
  依赖它；运行时模块不依赖 compiler plugin，也不通过反射回调生成器（`origin` 是不透明
  token，validator 从不检查它）。
- **后端特有检查留在后端**：fallback adapter 构造器可见性/参数检查（需要 KSP 类型系统）保留
  在 codegen，并在共享校验之前运行以保持原有错误优先级；非 sealed/非 JsonClass 的提前返回
  （运行时入口的"不适用"语义）也保留在各入口。

## 行为兼容性

- 合法模型的 JSON、`@Generated` 公共签名与生成源码不变：`smokeTest` 对生成源码做全文比对，
  `MessageTest`/`ObjectSerializationTest`/`SealedInterfaceMessageTest` 全部通过。
- 无源码级 proguard 文件，生成侧 proguard 输出路径不变（仅由 moshi codegen 生成）。
- 唯一的行为变化是**非法模型**的错误文案统一为 `[CODE] message` 形式，以及 codegen 对
  `@DefaultObject` 标注在非 object 类型上从静默忽略改为报错（与运行时入口一致）。

## 跨入口 fixture 与测试

`moshi-sealed:fixtures`（新模块）提供三个入口共用的 fixture 与断言
（`SealedFixtureExpectations`）：

| 用例 | fixture | 覆盖入口 |
| --- | --- | --- |
| 重复 label / 重复 alternate | `DuplicateLabels`, `DuplicateAlternateLabels` | validator 单测 + reflect + metadata-reflect + codegen compilation test |
| 缺 label | `MissingLabel` | 同上 |
| 多个 default / default 冲突 | `MultipleDefaultObjects`, `ConflictingDefaults` | 同上 |
| object 子类型 | `ValidMessage.Empty`, `ValidDefaultObject` | reflect + metadata-reflect + sample |
| generic 子类型 | `GenericSubtypes` | validator 单测 + reflect + metadata-reflect + codegen |
| 嵌套 sealed | `ValidMessage.Nested`, `RedundantNestedLabel` | 全部三个入口 |
| fallback / 未知 label | `ValidFallback`, `ValidDefaultNull`, `ValidMessage` | reflect + metadata-reflect + sample |

验证阶段（`./gradlew :moshi-sealed:test` 聚合）：

- `:moshi-sealed:codegen:test` — 真实 compilation test（kotlin-compile-testing + KSP），
  含 `missingTypeLabel`（断言 `BaseType.kt:<行>` 源码位置）与 `redundantNestedSealedLabel`。
- `:moshi-sealed:reflect:test` / `:moshi-sealed:metadata-reflect:test` — 两个运行时入口跑
  同一套 `SealedFixtureExpectations`。
- `:moshi-sealed:runtime:test` — `SealedModelValidatorTest`，逐条 pin 住 11 个错误码的
  完整 `[CODE] message` 文本。
- `:moshi-sealed:sample:test` — `CrossEntryConsistencyTest` 断言两个运行时入口错误消息
  逐字节相等。

## 重复量统计

口径：10 条共享规则（重复 label、重复 alternate、default 冲突、generic 子类型、
`@DefaultObject` 非 object、缺 label、冗余嵌套 label、`@NestedSealed` 冲突、缺 sealed 父类型）
在三个被统一入口（codegen、reflect、metadata-reflect）主源码中的检查点出现次数。

复现命令（仓库根目录）：

```sh
# before：HEAD 上三个后端文件中的检查点
for f in \
  moshi-sealed/codegen/src/main/kotlin/dev/zacsweers/moshix/sealed/codegen/ksp/MoshiSealedSymbolProcessorProvider.kt \
  moshi-sealed/reflect/src/main/kotlin/dev/zacsweers/moshix/sealed/reflect/MoshiSealedJsonAdapterFactory.kt \
  moshi-sealed/metadata-reflect/src/main/kotlin/dev/zacsweers/moshix/sealed/reflect/MetadataMoshiSealedJsonAdapterFactory.kt; do
  git show "HEAD:$f" | grep -c -E "Duplicate label|Duplicate alternate label|Only one of|cannot be generic|Must be an object type|Missing @TypeLabel|must be annotated with @TypeLabel|redundantly annotated|inappropriately annotated|No JsonClass-annotated sealed supertype"
done
# after：工作区同三个文件 + 共享 validator
grep -c -E "<同上 pattern>" <同上三个文件> \
  moshi-sealed/runtime/src/main/kotlin/dev/zacsweers/moshix/sealed/runtime/model/SealedModelValidator.kt
```

原始摘要（本次交付时实际输出）：

```
before: codegen=8, reflect=9, metadata-reflect=9  → 合计 26 处检查点（3 份实现）
after:  codegen=0, reflect=0, metadata-reflect=0, validator=10 → 合计 10 处（1 份实现）
```

- 检查点出现次数：26 → 10，减少 62%。
- 规则实现份数：3 → 1，减少 67%（均超过"至少减少一半"）。
- 注：`moshi-sealed:java-sealed-reflect` 是纯 Java sealed class 的独立功能，不在本次统一
  范围内，未改动。

## 验收

```sh
./gradlew assemble   # 准备阶段（不计入演示）
./gradlew :moshi-sealed:test :moshi-metadata-reflect:test   # 验收，退出码 0
```

`:moshi-sealed:test` 是新增的聚合任务（`moshi-sealed/build.gradle.kts`），串联
codegen/reflect/metadata-reflect/runtime/java-sealed-reflect/sample 全部测试；各测试任务
配置了 `testLogging { events("passed", "failed", "skipped") }`，新增用例名称（如
`ReflectSealedModelTest > invalidModels PASSED`）会直接显示。无需外部服务、环境变量或公网
（依赖在 `assemble` 阶段已解析进本地缓存）。

## 环境说明

本文档不含机器相关的性能结论。验收命令的耗时与机器/缓存状态相关；本次交付环境为
macOS（darwin, arm64）、Gradle 守护进程已预热，仅供参考，不作为任何性能指标。
