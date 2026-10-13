package com.ttxz.base.demo.post

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ttxz.base.common.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.net.ssl.SSLException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 接收页面操作，调用 Repository，并提供只读页面状态和一次性效果。 */
@HiltViewModel
class PostDemoViewModel @Inject constructor(
    private val repository: PostRepository,
) : ViewModel() {

    // 私有可变状态：仅供 ViewModel 内部更新 (_uiState.value = ...)
    private val _uiState = MutableStateFlow<PostDemoUiState>(PostDemoUiState.Idle)
    // 公开只读状态：用 asStateFlow() 将 MutableStateFlow 转换为只读 StateFlow，防止 UI 篡改状态，重绘不丢失
    val uiState = _uiState.asStateFlow()

    // 私有可变效果：仅供 ViewModel 内部触发 (_effects.tryEmit(...))
    private val _effects = MutableSharedFlow<PostDemoEffect>(extraBufferCapacity = 1)
    // 公开只读效果：用 asSharedFlow() 将 MutableSharedFlow 转换为只读 SharedFlow，防止 UI 篡改事件，用完即毁不重放
    val effects = _effects.asSharedFlow()

    /** 处理页面操作；请求中忽略重复点击，离开页面时协程随 ViewModel 取消。 */
    fun onAction(action: PostDemoAction) {
        when (action) {
            is PostDemoAction.LoadPost -> loadPost(action.id)
        }
    }

    private fun loadPost(id: Int) {
        if (_uiState.value == PostDemoUiState.Loading) return
        _uiState.value = PostDemoUiState.Loading
        viewModelScope.launch {
            when (val result = repository.getPost(id)) {
                is AppResult.Success -> {
                    _uiState.value = PostDemoUiState.Content(
                        title = result.data.title,
                        body = result.data.body,
                    )
                    _effects.tryEmit(PostDemoEffect.Success)
                }
                is AppResult.Error -> {
                    val error = PostDemoUiState.Error(
                        httpCode = result.code,
                        isCertificateError = result.throwable is SSLException,
                    )
                    _uiState.value = error
                    _effects.tryEmit(PostDemoEffect.Failure(error.httpCode, error.isCertificateError))
                }
                AppResult.Loading -> Unit
            }
        }
    }
}
