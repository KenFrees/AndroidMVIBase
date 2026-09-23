---
name: android-mvi-base-maintainer
description: Use when designing, implementing, teaching, documenting, debugging, or reviewing AndroidMVIBase framework internals, public APIs, modules, build configuration, or sample-host code. 面向本框架的编写者和维护者，不用于仅在业务工程接入现有框架。
---

# 框架维护者工作流

## 适用边界

用于编写 AndroidMVIBase 本身，包括示例宿主维护。业务工程使用已有 API 时使用 [使用者 Skill](../android-mvi-base/SKILL.md)，不把本仓库的包名和学习节奏强加给接入方。

任务同时涉及业务接入与框架修改时，分别明确两部分的仓库、授权范围和产出；确认目标前只读，不因接入问题自行修改框架或切换工程。

## 阅读入口

先读 [AGENTS.md](../../AGENTS.md)、[项目进度](../../README.md)；编码与框架 Review 的统一依据是 [框架开发规范](../../docs/framework-development.md)。

按任务读取规范相关章节与对应模块 README，不复制规范正文：

| 任务 | 规范章节 | 模块资料 |
| --- | --- | --- |
| 模块/依赖/构建 | 1、2、7 | 根 README、版本目录、相关模块 Gradle |
| 公共封装/API | 2、3、4、7 | [common](../../common/README.md)、实际调用者 |
| 公共 UI 组件 | 2、3、4、6、7 | [UI 组件目录](../../ui/components/README.md)、实际调用者 |
| 网络/协程/结果 | 3、4、5、7 | [network](../../network/README.md)、[common](../../common/README.md) |
| 示例 UI/MVI | 2、3、5、8 | [app](../../app/README.md)、已落地状态链路 |
| 注释/学习/文档 | 6 | 被讲解的真实源码和模块 README |

## 实现与学习

1. 明确当前学习步骤：“这一步需要你学习：〔主题〕；先读〔文件〕；本步处理〔边界〕；验收是〔可检查结果〕。”
2. 跟踪调用方，展示最小用法，说明封装价值、职责分配、失败/取消语义。新公共接口或模块边界先完成设计对齐。
3. 按规范落实类名、包路径、可见性与 KDoc。允许在授权范围内改进 AppResult 等现有公共 API，说明兼容影响；不把“复用”理解成禁止演进。
4. 同步模块 README；新增公共 UI 组件时登记 UI 组件目录并提供独立用法说明。面向调用者的行为改变时同步使用者 Skill；验证当前变化。不得将未实现能力写成已存在。
5. 收尾说明产出、原因、证据和未覆盖项，给一个小练习。到学习节点停下，遵循用户已明确的连续执行范围。

## 框架 Review

只审查时不改文件。核对实际源码和调用者，不把 README 的旧问题清单自动认定为仍然存在。

按规范 REVIEW-01 检查封装正确性及调用方体验；区分必须、建议和待设计。每项发现给文件/行号、触发条件、影响与修复建议，必要时引用规则编号。补充本次验证范围与未知事项。

例如 review `safeApiCall`，沿调用者 → 返回类型 → catch 分支核对取消与错误语义，并检查 KDoc；不能只因类型叫 Result 就认为业务失败已经统一处理。

## 验证与范围

文档修改检查源码一致性和链接；代码与配置修改按受影响行为选择构建或测试。保留用户已有改动，不自动提交、推送或修改全局 Skills。不把本次完善规范扩展成历史代码的全仓整改。
