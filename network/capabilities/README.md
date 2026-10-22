# network 能力目录

按能力名称、用途或关键词查找；用法列有独立说明时提供链接，其余直接列出关键约束。只登记已实现能力，通用接入与详细说明见 [network README](../README.md)。

| 能力 | 适用场景与检索词 | 用法与约束 | 源码 |
| --- | --- | --- | --- |
| Retrofit / OkHttp 单例提供 | HTTP、接口请求、连接、Hilt 注入、网络对象复用 | 服务地址目前固定为演示服务，业务 API 与 Repository 由宿主声明 | [NetworkModule.kt](../src/main/java/com/ttxz/base/network/di/NetworkModule.kt) |
| Moshi JSON 转换 | JSON、序列化、反序列化、Kotlin 数据类、Converter | 已注册 KotlinJsonAdapterFactory 和 MoshiConverterFactory；引入 codegen 不代表所有 DTO 自动使用生成适配器 | [NetworkModule.kt](../src/main/java/com/ttxz/base/network/di/NetworkModule.kt) |
| `safeApiCall` 请求结果包装 | HTTP 异常、IO、解析失败、取消、`AppResult` | 取消继续抛出，不产生 Loading、不切线程，不自动检查业务码或 Response 的 HTTP 状态 | [NetworkCall.kt](../src/main/java/com/ttxz/base/network/NetworkCall.kt) |
| 请求日志与超时配置 | BODY、请求响应日志、连接超时、读取超时、写入超时 | 目前 BODY 全环境启用，三项超时各 30 秒，不等于完整请求总时限 | [NetworkModule.kt](../src/main/java/com/ttxz/base/network/di/NetworkModule.kt) |

新增或调整公共能力时，同步本表、使用说明和示例。Token 刷新、会话失效、统一业务码、业务重试和离线策略尚未封装；不将这些待设计事项登记为已支持。网络日志与 [logging 本地诊断日志](../../logging/capabilities/README.md) 是不同能力。
