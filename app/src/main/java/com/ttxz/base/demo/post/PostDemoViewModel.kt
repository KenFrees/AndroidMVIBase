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
    private val _uiState = MutableStateFlow<PostDemoUiState>(PostDemoUiState.Idle)
    val uiState = _uiState.asStateFlow()
    private val _effects = MutableSharedFlow<PostDemoEffect>(extraBufferCapacity = 1)
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
