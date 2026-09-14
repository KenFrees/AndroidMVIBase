# network：统一网络基础能力

## 职责与依赖

集中配置 OkHttp、Moshi 和 Retrofit，并把请求结果转换成 [common 的 AppResult](../common/README.md)。

这是 Android Library，命名空间 `com.ttxz.base.network`，minSdk 26。依赖 common、Retrofit、OkHttp、Moshi、Hilt 和协程；使用 KSP 处理 Hilt / Moshi 注解。没有依赖 app，也不负责页面状态和导航。

## 关键文件与现有能力

| 文件 | 内容 |
| --- | --- |
| [NetworkModule.kt](src/main/java/com/ttxz/base/network/di/NetworkModule.kt) | 在 Hilt SingletonComponent 中提供 Moshi、OkHttpClient、Retrofit 单例 |
| [NetworkCall.kt](src/main/java/com/ttxz/base/network/NetworkCall.kt) | `safeApiCall`：执行挂起函数并转换成功 / 异常结果 |
| [build.gradle.kts](build.gradle.kts) | 网络依赖和注解处理配置 |

当前配置：

- Base URL 固定为演示服务 `https://jsonplaceholder.typicode.com/`。
- 连接、读取、写入超时各 30 秒，不代表一次完整请求总耗时最多 30 秒。
- HttpLoggingInterceptor 使用 BODY 级别，当前所有构建类型都使用此级别。
- Moshi 注册 `KotlinJsonAdapterFactory`，Retrofit 使用 MoshiConverterFactory。
- 已引入 Moshi codegen，但没有业务 DTO 示例；不代表所有数据类都自动使用生成适配器。

## 请求与错误如何流转

建议调用方向：业务 Repository → Retrofit 接口 → OkHttp → 服务端 → Moshi 解析 → `safeApiCall` → AppResult。当前还没有业务 Repository 和完整页面调用示例。

`safeApiCall(block)` 当前行为：

| 情况 | 返回 |
| --- | --- |
| block 正常返回 | `Success(block 的返回值)` |
| `HttpException` | Error，code 为 HTTP 状态码，保留异常 |
| `IOException` | Error，code 为空，保留异常 |
| 其他 `Exception` | Error，code 为空，保留异常 |

该函数不发射 Loading，不切换 Dispatcher，不创建独立协程，也不实现业务重试。Retrofit `suspend` 接口适合放进 block；其他阻塞工作仍需调用方选择合适的执行环境。

如果接口返回 `Response<T>`，非 2xx 响应可能以 Response 正常返回，当前包装会得到 `Success<Response<T>>`；这时需要明确检查响应。不要将这个辅助函数理解成所有 HTTP / 业务失败的自动识别器。

## 如何接入

### 1. 依赖与宿主配置

使用本模块的消费者声明 `implementation(project(":network"))`。如果消费者源码使用 AppResult，还需直接依赖 common；如果直接引用 Retrofit、Hilt 等 API，也需声明相应依赖，network 的 `implementation` 不会把它们作为消费者的编译 API 导出。

宿主需要 `@HiltAndroidApp` Application、相应 Hilt/KSP 配置，以及 Manifest 中的 INTERNET 权限。本仓库 app 已配置，可参考 [app 说明](../app/README.md)。

### 2. 声明 API 并交给 Hilt

下面是拟议示例，**尚未作为源码加入工程**。这里只演示复用方法，不据此宣称已验证接口调用。

```kotlin
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import retrofit2.http.GET

data class PostDto(val id: Int, val title: String)

interface PostApi {
    @GET("posts")
    suspend fun getPosts(): List<PostDto>
}

@Module
@InstallIn(SingletonComponent::class)
object PostApiModule {
    @Provides
    fun providePostApi(retrofit: Retrofit): PostApi =
        retrofit.create(PostApi::class.java)
}
```

`Retrofit` 由现有 NetworkModule 提供，业务只增加自己的 API 绑定，不重新创建网络客户端。

### 3. 在数据层包装调用

```kotlin
import com.ttxz.base.common.result.AppResult
import com.ttxz.base.network.safeApiCall
import javax.inject.Inject

class PostRepository @Inject constructor(private val api: PostApi) {
    suspend fun getPosts(): AppResult<List<PostDto>> =
        safeApiCall { api.getPosts() }
}
```

这是用法示意，生产接入前先修正下述取消和日志问题。Repository 的模块位置、是否拆接口、DTO 转领域模型等，留到完整示例阶段按实际需求确定。

## 已知限制：接入正式业务前逐项完善

1. **协程取消被捕获**：当前 `catch (Exception)` 会捕获 CancellationException，取消可能被当成业务失败。下一步网络封装应优先处理取消传播，并加取消测试。
2. **日志没有区分环境**：BODY 可能记录请求 / 响应正文，正式环境应明确日志开关与敏感信息处理，不能直接照搬当前默认值。
3. **环境地址固定**：没有向宿主开放 Base URL 配置；不能直接拿演示地址作为正式服务。
4. **业务错误未统一**：HTTP 成功不代表服务端业务码成功；目前不解析业务 code/message，也不处理 Token、401 会话失效或刷新。
5. **没有完整测试链路**：没有 MockWebServer 测试和实际 API/页面验证；空响应、解析失败、超时、离线等需按契约补测。

## 这一步需要学习什么

建议先学 common，再阅读 NetworkModule，最后阅读 safeApiCall。

- OkHttp 负责什么？Retrofit 的接口代理负责什么？Moshi 在哪一步解析？
- `@Provides`、`@InstallIn`、`@Singleton` 如何决定网络对象的提供方式与范围？
- 为什么 HTTP 状态码、服务端业务码和协程取消应分别处理？
- 为什么单个请求结果不能代替页面 StateFlow？

练习：沿 `safeApiCall { api.getPosts() }` 画出成功、HTTP 异常、取消的路径；标出当前取消处理不符合预期的位置。

本步通过标准：能说明三种库的分工，能区分结果包装与请求调度，知道哪些能力还没实现。

## 如何验证修改

构建用 `:network:assembleDebug`，宿主集成用 `:app:assembleDebug`。修改异常处理时测试成功、HTTP、IO 和取消；修改日志/环境注入时分别核对 Debug/Release 行为。

构建成功只说明编译与打包通过，不能证明服务可达、错误契约正确或设备运行正常。公开 API 或配置变化时，同步修改本说明、宿主调用与项目 Skill 中相关规则。
