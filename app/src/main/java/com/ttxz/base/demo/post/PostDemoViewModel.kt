package com.ttxz.base.demo.post

import androidx.lifecycle.viewModelScope
import com.ttxz.base.common.mvi.BaseViewModel
import com.ttxz.base.common.result.AppResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.net.ssl.SSLException
import kotlinx.coroutines.launch

/** 处理业务操作和请求结果；BaseViewModel 提供状态容器及临时效果流。 */
@HiltViewModel
class PostDemoViewModel @Inject constructor(
    private val repository: PostRepository,
) : BaseViewModel<PostDemoAction, PostDemoUiState, PostDemoEffect>(
    initialState = PostDemoUiState.Idle,
) {

    /** 由 UI 主线程调用；请求中忽略重复点击，ViewModel 清除时取消请求。 */
    override fun onAction(action: PostDemoAction) {
        when (action) {
            is PostDemoAction.LoadPost -> loadPost(action.id)
        }
    }

    private fun loadPost(id: Int) {
        if (currentState == PostDemoUiState.Loading) return
        updateUiState(PostDemoUiState.Loading)
        viewModelScope.launch {
            when (val result = repository.getPost(id)) {
                is AppResult.Success -> {
                    updateUiState(
                        PostDemoUiState.Content(
                            title = result.data.title,
                            body = result.data.body,
                        ),
                    )
                    sendEffect(PostDemoEffect.Success)
                }
                is AppResult.Error -> {
                    val error = PostDemoUiState.Error(
                        httpCode = result.code,
                        isCertificateError = result.throwable is SSLException,
                    )
                    updateUiState(error)
                    sendEffect(PostDemoEffect.Failure(error.httpCode, error.isCertificateError))
                }
                AppResult.Loading -> Unit
            }
        }
    }
}
