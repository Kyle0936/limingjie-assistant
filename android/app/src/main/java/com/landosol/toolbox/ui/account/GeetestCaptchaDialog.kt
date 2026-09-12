package com.landosol.toolbox.ui.account

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.landosol.toolbox.account.AccountCaptchaState
import com.landosol.toolbox.protocol.bilibili.GeetestPageBuilder

@Composable
@SuppressLint("SetJavaScriptEnabled")
fun GeetestCaptchaDialog(
    state: AccountCaptchaState,
    isWorking: Boolean,
    onSolved: (String) -> Unit,
    onError: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val webView = remember(state.challenge.challenge) {
        WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            addJavascriptInterface(CaptchaBridge(this, onSolved, onError), BRIDGE_NAME)
            webViewClient = CaptchaWebViewClient(onError)
            loadDataWithBaseURL(
                SDK_BASE_URL,
                GeetestPageBuilder.build(state.challenge),
                "text/html",
                "UTF-8",
                null,
            )
        }
    }
    DisposableEffect(webView) {
        onDispose {
            webView.stopLoading()
            webView.removeJavascriptInterface(BRIDGE_NAME)
            webView.destroy()
        }
    }

    Dialog(
        onDismissRequest = { if (!isWorking) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("人工安全验证", style = MaterialTheme.typography.headlineSmall)
                Text("账号：${state.accountAlias}")
                Text(
                    "验证由 Geetest 官方组件加载，请手动完成。登录账号和密码不会传入此页面。",
                    style = MaterialTheme.typography.bodySmall,
                )
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                AndroidView(
                    factory = { webView },
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
                if (isWorking) {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.CenterHorizontally))
                }
                TextButton(
                    onClick = onDismiss,
                    enabled = !isWorking,
                    modifier = Modifier.align(Alignment.End),
                ) {
                    Text("取消验证")
                }
            }
        }
    }
}

private class CaptchaBridge(
    private val webView: WebView,
    private val onSolved: (String) -> Unit,
    private val onError: (String) -> Unit,
) {
    @JavascriptInterface
    fun onSolved(validate: String) {
        webView.post {
            if (validate.isBlank() || validate.length > MAX_TOKEN_LENGTH) {
                onError("验证组件返回了无效结果")
            } else {
                onSolved(validate)
            }
        }
    }

    @JavascriptInterface
    fun onError(message: String) {
        webView.post { onError(message.take(120)) }
    }

    private companion object {
        const val MAX_TOKEN_LENGTH = 4096
    }
}

private class CaptchaWebViewClient(
    private val onError: (String) -> Unit,
) : WebViewClient() {
    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url ?: return true
        if (url.scheme != "https") return true
        val host = url.host.orEmpty().lowercase()
        return host != SDK_HOST && host != "geetest.com" && !host.endsWith(".geetest.com")
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        if (url != null && !url.startsWith("https://")) onError("已阻止非安全验证页面")
    }

    override fun onReceivedError(
        view: WebView?,
        request: WebResourceRequest?,
        error: WebResourceError?,
    ) {
        if (request?.isForMainFrame == true) onError("验证页面加载失败，请检查网络后重试")
    }
}

private const val BRIDGE_NAME = "AndroidCaptcha"
private const val SDK_HOST = "line1-sdk-center-login-sh.biligame.net"
private const val SDK_BASE_URL = "https://$SDK_HOST/"
