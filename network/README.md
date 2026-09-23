# network：统一网络基础能力

## 职责与依赖

集中配置 OkHttp、Moshi 和 Retrofit，并把请求结果转换成 [common 的 AppResult](../common/README.md)。

这是 Android Library，命名空间 `com.ttxz.base.network`，minSdk 26。依赖 common、Retrofit、OkHttp、Moshi、Hilt 和协程；使用 KSP 处理 Hilt / Moshi 注解。没有依赖 app，也不负责页面状态和导航。

## 关键文件与现有能力

| 文件 | 内容 |
| --- | --- |
| [NetworkModule.kt](src/main/java/com/ttxz/base/network/di/NetworkModule.kt) | 在 Hilt SingletonComponent 中提供 Moshi、OkHttpClient、Retrofit 单例 |
| [NetworkCall.kt](src/main/java/com/ttxz/base/network/NetworkCall.kt) | `safeApiCall`：执行挂起函数、转换成功 / 错误结果并保留协程取消 |
| [build.gradle.kts](build.gradle.kts) | 网络依赖和注解处理配置 |

当前配置：

- Base URL 固定为演示服务 `https://jsonplaceholder.typicode.com/`。
- 连接、读取、写入超时各 30 秒，不代表一次完整请求总耗时最多 30 秒。
- HttpLoggingInterceptor 使用 BODY 级别，当前所有构建类型都使用此级别。
- Moshi 注册 `KotlinJsonAdapterFactory`，Retrofit 使用 MoshiConverterFactory。
- 已引入 Moshi codegen，但 app 的 PostDto 演示使用 KotlinJsonAdapterFactory；不代表所有数据类都自动使用生成适配器。

## 请求与错误如何流转

建议调用方向：业务 Repository → Retrofit 接口 → OkHttp → 服务端 → Moshi 解析 → `safeApiCall` → AppResult。实际演示位于 app 的 [demo/post](../app/src/main/java/com/ttxz/base/demo/post) 包，从首页“网络请求示例”入口进入。

`safeApiCall(block)` 当前行为：

| 情况 | 返回 |
| --- | --- |
| block 正常返回 | `Success(block 的返回值)` |
| `HttpException` | Error，code 为 HTTP 状态码，保留异常 |
| `IOException` | Error，code 为空，保留异常 |
| `CancellationException` | 继续抛出，调用方协程停止 |
| 其他 `Exception` | Error，code 为空，保留异常 |

该函数不发射 Loading，不切换 Dispatcher，不创建独立协程，也不实现业务重试。Retrofit `suspend` 接口适合放进 block；其他阻塞工作仍需调用方选择合适的执行环境。

如果接口返回 `Response<T>`，非 2xx 响应可能以 Response 正常返回，当前包装会得到 `Success<Response<T>>`；这时需要明确检查响应。不要将这个辅助函数理解成所有 HTTP / 业务失败的自动识别器。

## 如何接入

### 1. 依赖与宿主配置

使用本模块的消费者声明 `implementation(project(":network"))`。如果消费者源码使用 AppResult，还需直接依赖 common；如果直接引用 Retrofit、Hilt 等 API，也需声明相应依赖，network 的 `implementation` 不会把它们作为消费者的编译 API 导出。

宿主需要 `@HiltAndroidApp` Application、相应 Hilt/KSP 配置，以及 Manifest 中的 INTERNET 权限。本仓库 app 已配置，可参考 [app 说明](../app/README.md)。

### 2. 声明 API 并交给 Hilt

本仓库已经在 app 的共享数据包中实现 [PostApi.kt](../app/src/main/java/com/ttxz/base/data/remote/PostApi.kt)、[PostDto.kt](../app/src/main/java/com/ttxz/base/data/remote/PostDto.kt) 和 [PostApiModule.kt](../app/src/main/java/com/ttxz/base/data/remote/PostApiModule.kt)。简化后的调用方式如下：

```kotlin
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Path

data class PostDto(val userId: Int, val id: Int, val title: String, val body: String)

interface PostApi {
    @GET("posts/{id}")
    suspend fun getPost(@Path("id") id: Int): PostDto
}

@Module
@InstallIn(SingletonComponent::class)
object PostApiModule {
    @Provides
    fun providePostApi(retrofit: Retrofit): PostApi =
        retrofit.create(PostApi::class.java)
}
```

`Retrofit` 由现有 NetworkModule 提供，业务只增加自己的 API 绑定，不重新创建网络客户端。PostApi、PostDto 和绑定供 app 内多个功能使用，不放入通用 network 模块。app 因源码直接引用 Retrofit 类型而直接声明相应依赖。

### 3. 在数据层包装调用

```kotlin
import com.ttxz.base.common.result.AppResult
import com.ttxz.base.data.remote.PostApi
import com.ttxz.base.data.remote.PostDto
import com.ttxz.base.network.safeApiCall
import javax.inject.Inject

class PostRepository @Inject constructor(private val api: PostApi) {
    suspend fun getPost(id: Int): AppResult<PostDto> =
        safeApiCall { api.getPost(id) }
}
```

实际 [PostRepository.kt](../app/src/main/java/com/ttxz/base/demo/post/PostRepository.kt) 复用此方法；[PostDemoViewModel.kt](../app/src/main/java/com/ttxz/base/demo/post/PostDemoViewModel.kt) 接收页面 Action，将结果转换成 UiState，并发出一次性 Effect；[PostDemoScreen.kt](../app/src/main/java/com/ttxz/base/demo/post/PostDemoScreen.kt) 使用公共 LoadingDialog 与 ToastHost 展示加载和反馈，也可请求不存在的文章观察 HTTP 404。生产接入时仍需按业务决定 DTO 转换、错误文案和环境配置。

## 已知限制：接入正式业务前逐项完善

1. **日志没有区分环境**：BODY 可能记录请求 / 响应正文，正式环境应明确日志开关与敏感信息处理，不能直接照搬当前默认值。
2. **环境地址固定**：没有向宿主开放 Base URL 配置；JSONPlaceholder 是无认证的演示服务，不提供长期可用性保证，不能直接拿演示地址作为正式服务。
3. **业务错误未统一**：HTTP 成功不代表服务端业务码成功；目前不解析业务 code/message，也不处理 Token、401 会话失效或刷新。
4. **没有完整测试链路**：已有协程取消单测，但尚无 MockWebServer 的成功 / HTTP / IO 集成测试；空响应、解析失败、超时、离线等需按契约补测。

## 这一步需要学习什么

建议先学 common，再阅读 NetworkModule，最后阅读 safeApiCall。

- OkHttp 负责什么？Retrofit 的接口代理负责什么？Moshi 在哪一步解析？
- `@Provides`、`@InstallIn`、`@Singleton` 如何决定网络对象的提供方式与范围？
- 为什么 HTTP 状态码、服务端业务码和协程取消应分别处理？
- 为什么单个请求结果不能代替页面 StateFlow？

练习：沿 `safeApiCall { api.getPost(1) }` 画出成功、HTTP 异常、取消的路径；再在演示页分别请求文章和不存在的文章，观察界面分支。

本步通过标准：能说明三种库的分工，能区分结果包装与请求调度，知道哪些能力还没实现。

## 如何验证修改

构建用 `:network:assembleDebug`，宿主集成用 `:app:assembleDebug`；取消语义测试为 `:network:testDebugUnitTest`。修改异常处理时继续补测成功、HTTP、IO 和取消；修改日志/环境注入时分别核对 Debug/Release 行为。

构建成功只说明编译与打包通过，不能证明服务可达、错误契约正确或设备运行正常。公开 API 或配置变化时，同步修改本说明、宿主调用与项目 Skill 中相关规则。
