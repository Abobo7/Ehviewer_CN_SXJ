package com.acsbendi.requestinspectorwebview

data class WebViewRequest(
    val type: WebViewRequestType,
    val url: String,
    val method: String,
    val body: String,
    val formParameters: Map<String, String>,
    val headers: Map<String, String>,
    val trace: String,
    val enctype: String?,
    val isForMainFrame: Boolean,
    val isRedirect: Boolean,
    val hasGesture: Boolean
)
