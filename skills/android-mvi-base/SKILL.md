---
name: android-mvi-base
description: Use when integrating AndroidMVIBase into a business Android project, calling its existing APIs, or reviewing consumer-side usage. 面向框架使用者及其 AI，不用于维护框架内部实现或设计新公共模块。
---

# 框架使用者指南

## 面向谁

用于业务工程接入、调用和检查 AndroidMVIBase 的现有能力。编写或修改框架本身时使用 [维护者 Skill](../android-mvi-base-maintainer/SKILL.md)。

接入方遵守自己的 AGENTS、包名、架构与提交规则；不要求把业务代码命名为 com.ttxz.base，也不要求按本框架作者的学习步骤开发。不因接入需要就擅自修改框架源码。

## 确认版本与资料

先明确业务仓库、接入方式和实际使用的框架版本，再读对应版本的模块 README 与公开 API。当前框架尚未发布 Maven 坐标，没有自动初始化脚本；不得编造依赖坐标或声称可直接生产使用。

本文件的相对链接适用于保留仓库目录结构的情况。若仅复制 Skill 到业务工程，先由用户提供或从已知配置确认框架源码/文档根目录，再解析对应模块资料；找不到时说明缺失信息，不猜测磁盘路径、版本或全局安装状态。

| 使用内容 | 对应说明 |
| --- | --- |
| 结果分支与回调 | [common README](../../common/README.md) |
| Retrofit/Moshi/OkHttp、异常包装 | [network README](../../network/README.md) |
| Hilt 宿主与 Compose 示例入口 | [app README](../../app/README.md) |
| 已实现范围与当前限制 | [项目总览](../../README.md)中的当前进度和限制；学习路线不约束接入方 |

## 接入步骤与使用约束

1. 检查现有业务工程的技术栈、minSdk、Hilt 配置和依赖，确定哪些能力适合复用；不要直接覆盖业务工程的 Gradle 或 Application。
2. 当前同一 Gradle 工程使用模块依赖。消费者直接引用 AppResult 时声明 common；直接引用 Retrofit/Hilt 等类型时检查直接依赖，不假设 network 的 implementation 会导出编译 API。具体配置见模块说明。
3. 复用框架提供的网络对象和结果类型，在业务数据层增加 API/Repository。业务代码使用业务包名；页面经其 ViewModel/Repository 获取数据，不在 Composable 内创建 Retrofit/OkHttp。
4. 使用 AppResult 时完整区分 Success、Error、Loading；safeApiCall 不产生 Loading、不主动切线程、不自动解析业务失败码。onSuccess/onError 返回原结果，回调异常不会自动转换成 Error。
5. 若 Retrofit API 返回 Response<T>，明确检查 HTTP 成功状态，不将 Success<Response<T>> 直接等同于请求成功。
6. 使用框架前核对当前取消、日志和 Base URL 限制。缺陷或缺少配置入口阻碍接入时，报告证据及影响，提出框架侧改进需求；未经授权不自行补丁框架或修改另一个仓库。
7. 记录业务工程实际接入方式并验证相关调用。原框架 Debug 构建成功不等于当前业务工程已集成或设备已验证。

## 使用方式 Review

范围是消费者是否正确使用框架。按 业务页面 → ViewModel → Repository → API/框架入口 追踪，重点检查：

- 实际版本、可用能力、直接依赖和宿主配置是否匹配。
- 是否复用已有对象，是否存在页面直连网络或重复创建客户端。
- 结果、HTTP/业务失败、加载、取消和生命周期语义是否处理正确。
- 演示服务地址、日志和已知限制是否影响目标环境。
- 使用示例和接入说明是否与实际调用一致，关键场景是否经过验证。

发现框架内部问题可以只读追踪，输出框架文件依据与对接入方的影响，但不因此切换到维护者模式实施修改。

Review 先列问题，给文件/行号、触发条件、影响和建议，再列验证范围与未知事项。只读审查不直接修复；不拿维护者的内部命名、教学进度或未落地 API 作为业务代码的违规依据。

## 完成标准

说明业务侧用了哪些能力、如何调用、已验证什么、还受哪些限制。明确区分源码审查、构建、接口与设备验证；不自动提交、推送或改变业务工程之外的状态。
