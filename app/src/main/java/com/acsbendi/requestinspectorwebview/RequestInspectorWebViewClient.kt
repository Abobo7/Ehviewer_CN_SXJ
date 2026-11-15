package com.acsbendi.requestinspectorwebview

import android.annotation.SuppressLint
import android.annotation.TargetApi
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi

@SuppressLint("SetJavaScriptEnabled")
open class RequestInspectorWebViewClient @JvmOverloads constructor(
    webView: WebView,
    private val options: RequestInspectorOptions = RequestInspectorOptions()
) : WebViewClient() {

    private val interceptionJavascriptInterface = RequestInspectorJavaScriptInterface(webView)

    init {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    final override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest
    ): WebResourceResponse? {
        return Api21RequestHelper.intercept(this, view, request, interceptionJavascriptInterface)
    }

    @Suppress("DEPRECATION")
    override fun shouldInterceptRequest(view: WebView, url: String): WebResourceResponse? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return super.shouldInterceptRequest(view, url)
        }
        val recorded = interceptionJavascriptInterface.findRecordedRequestForUrl(url)
        val webViewRequest = WebViewRequest(
            type = recorded?.type ?: WebViewRequestType.HTML,
            url = url,
            method = recorded?.method ?: "GET",
            body = recorded?.body ?: "",
            formParameters = recorded?.formParameters ?: emptyMap(),
            headers = recorded?.headers ?: emptyMap(),
            trace = recorded?.trace ?: "",
            enctype = recorded?.enctype,
            isForMainFrame = true,
            isRedirect = false,
            hasGesture = false
        )
        return shouldInterceptRequest(view, webViewRequest)
    }

    open fun shouldInterceptRequest(
        view: WebView,
        webViewRequest: WebViewRequest
    ): WebResourceResponse? {
        logWebViewRequest(webViewRequest)
        return null
    }

    protected fun logWebViewRequest(webViewRequest: WebViewRequest) {
        Log.i(LOG_TAG, "Sending request from WebView: $webViewRequest")
    }

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        Log.i(LOG_TAG, "Page started loading, enabling request inspection. URL: $url")
        RequestInspectorJavaScriptInterface.enabledRequestInspection(
            view,
            options.extraJavaScriptToInject
        )
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String) {
        RequestInspectorJavaScriptInterface.enabledRequestInspection(
            view,
            options.extraJavaScriptToInject
        )
        super.onPageFinished(view, url)
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private object Api21RequestHelper {
        fun intercept(
            client: RequestInspectorWebViewClient,
            view: WebView,
            request: WebResourceRequest,
            jsInterface: RequestInspectorJavaScriptInterface
        ): WebResourceResponse? {
            val url = request.url.toString()
            val recorded = jsInterface.findRecordedRequestForUrl(url)
            val mergedHeaders = HashMap<String, String>()
            request.requestHeaders?.let { mergedHeaders.putAll(it) }
            recorded?.headers?.let { mergedHeaders.putAll(it) }
            val isRedirect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                Api24RedirectHelper.isRedirect(request)
            } else {
                false
            }
            val webViewRequest = WebViewRequest(
                type = recorded?.type ?: WebViewRequestType.HTML,
                url = url,
                method = recorded?.method ?: request.method ?: "GET",
                body = recorded?.body ?: "",
                formParameters = recorded?.formParameters ?: emptyMap(),
                headers = mergedHeaders,
                trace = recorded?.trace ?: "",
                enctype = recorded?.enctype,
                isForMainFrame = request.isForMainFrame,
                isRedirect = isRedirect,
                hasGesture = request.hasGesture()
            )
            return client.shouldInterceptRequest(view, webViewRequest)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    private object Api24RedirectHelper {
        fun isRedirect(request: WebResourceRequest): Boolean = request.isRedirect
    }

    companion object {
        private const val LOG_TAG = "RequestInspectorWebView"
    }
}
