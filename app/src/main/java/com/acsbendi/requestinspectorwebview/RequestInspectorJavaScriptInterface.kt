package com.acsbendi.requestinspectorwebview

import android.annotation.SuppressLint
import android.os.Build
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale

@SuppressLint("JavascriptInterface")
class RequestInspectorJavaScriptInterface(webView: WebView) {

    data class RecordedRequest(
        val type: WebViewRequestType,
        val url: String,
        val method: String,
        val body: String,
        val formParameters: Map<String, String>,
        val headers: Map<String, String>,
        val trace: String,
        val enctype: String?
    )

    private val recordedRequests = ArrayList<RecordedRequest>()

    init {
        webView.addJavascriptInterface(this, INTERFACE_NAME)
    }

    @Synchronized
    fun findRecordedRequestForUrl(url: String): RecordedRequest? {
        val exactIdx = recordedRequests.indexOfFirst { it.url == url }
        if (exactIdx >= 0) {
            return recordedRequests.removeAt(exactIdx)
        }
        val partialIdx = recordedRequests.indexOfFirst { url.contains(it.url) || it.url.contains(url) }
        if (partialIdx >= 0) {
            return recordedRequests.removeAt(partialIdx)
        }
        return null
    }

    @Synchronized
    private fun addRecordedRequest(request: RecordedRequest) {
        recordedRequests.add(request)
    }

    @JavascriptInterface
    fun recordFormSubmission(
        url: String,
        method: String,
        formParameterList: String,
        headers: String,
        trace: String,
        enctype: String?
    ) {
        try {
            val formArray = JSONArray(formParameterList)
            val headerMap = getHeadersAsMap(headers).toMutableMap()
            val effectiveEnctype = enctype ?: "application/x-www-form-urlencoded"
            val body = when (effectiveEnctype) {
                "application/x-www-form-urlencoded" -> {
                    headerMap["content-type"] = effectiveEnctype
                    getUrlEncodedFormBody(formArray)
                }
                "multipart/form-data" -> {
                    headerMap["content-type"] = "multipart/form-data; boundary=$MULTIPART_FORM_BOUNDARY"
                    getMultiPartFormBody(formArray)
                }
                "text/plain" -> {
                    headerMap["content-type"] = effectiveEnctype
                    getPlainTextFormBody(formArray)
                }
                else -> {
                    Log.e(LOG_TAG, "Incorrect encoding received from JavaScript: $effectiveEnctype")
                    ""
                }
            }
            addRecordedRequest(
                RecordedRequest(
                    type = WebViewRequestType.FORM,
                    url = url,
                    method = method.uppercase(Locale.US),
                    body = body,
                    formParameters = getFormParametersAsMap(formArray),
                    headers = headerMap,
                    trace = trace,
                    enctype = effectiveEnctype
                )
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to record form submission", e)
        }
    }

    @JavascriptInterface
    fun recordXhr(url: String, method: String, body: String, headers: String, trace: String) {
        addRecordedRequest(
            RecordedRequest(
                type = WebViewRequestType.XML_HTTP,
                url = url,
                method = method.uppercase(Locale.US),
                body = body,
                formParameters = emptyMap(),
                headers = getHeadersAsMap(headers),
                trace = trace,
                enctype = null
            )
        )
    }

    @JavascriptInterface
    fun recordFetch(url: String, method: String, body: String, headers: String, trace: String) {
        addRecordedRequest(
            RecordedRequest(
                type = WebViewRequestType.FETCH,
                url = url,
                method = method.uppercase(Locale.US),
                body = body,
                formParameters = emptyMap(),
                headers = getHeadersAsMap(headers),
                trace = trace,
                enctype = null
            )
        )
    }

    private fun getHeadersAsMap(headersString: String): Map<String, String> {
        val result = HashMap<String, String>()
        if (headersString.isEmpty()) return result
        try {
            val obj = JSONObject(headersString)
            val keys = obj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                result[key.lowercase(Locale.getDefault())] = obj.getString(key)
            }
        } catch (_: Exception) {
        }
        return result
    }

    private fun getFormParametersAsMap(formArray: JSONArray): Map<String, String> {
        val map = HashMap<String, String>()
        for (i in 0 until formArray.length()) {
            val item = formArray.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isEmpty()) continue
            val value = item.optString("value")
            val checked = item.optBoolean("checked")
            val type = item.optString("type")
            if ((type == "radio" || type == "checkbox") && !checked) continue
            map[name] = value
        }
        return map
    }

    private fun getUrlEncodedFormBody(formArray: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until formArray.length()) {
            val item = formArray.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isEmpty()) continue
            val value = item.optString("value")
            val checked = item.optBoolean("checked")
            val type = item.optString("type")
            if ((type == "radio" || type == "checkbox") && !checked) continue
            val encodedValue = URLEncoder.encode(value, "UTF-8")
            if (sb.isNotEmpty()) sb.append("&")
            sb.append(name).append("=").append(encodedValue)
        }
        return sb.toString()
    }

    private fun getMultiPartFormBody(formArray: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until formArray.length()) {
            val item = formArray.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isEmpty()) continue
            val value = item.optString("value")
            val checked = item.optBoolean("checked")
            val type = item.optString("type")
            if ((type == "radio" || type == "checkbox") && !checked) continue
            sb.append("--").append(MULTIPART_FORM_BOUNDARY).append("\n")
            sb.append("Content-Disposition: form-data; name=\"").append(name).append("\"\n\n")
            sb.append(value).append("\n")
        }
        sb.append("--").append(MULTIPART_FORM_BOUNDARY).append("--")
        return sb.toString()
    }

    private fun getPlainTextFormBody(formArray: JSONArray): String {
        val sb = StringBuilder()
        for (i in 0 until formArray.length()) {
            val item = formArray.optJSONObject(i) ?: continue
            val name = item.optString("name")
            if (name.isEmpty()) continue
            val value = item.optString("value")
            val checked = item.optBoolean("checked")
            val type = item.optString("type")
            if ((type == "radio" || type == "checkbox") && !checked) continue
            if (sb.isNotEmpty()) sb.append("\n")
            sb.append(name).append("=").append(value)
        }
        return sb.toString()
    }

    companion object {
        private const val LOG_TAG = "RequestInspectorJs"
        private const val MULTIPART_FORM_BOUNDARY = "----WebKitFormBoundaryU7CgQs9WnqlZYKs6"
        private const val INTERFACE_NAME = "RequestInspection"

        // Use ES5 var syntax so KitKat (Chromium 30/33) and modern WebViews both execute cleanly
        private const val JAVASCRIPT_INTERCEPTION_CODE = """
(function() {
    if (window.__requestInspectorInstalled) return;
    window.__requestInspectorInstalled = true;
    function getFullUrl(url) {
        if (!url) return location.href;
        if (url.indexOf('/') === 0 && url.indexOf('//') !== 0) {
            return location.protocol + '//' + location.host + url;
        }
        return url;
    }
    function recordFormSubmission(form) {
        var jsonArr = [];
        for (var i = 0; i < form.elements.length; i++) {
            var el = form.elements[i];
            jsonArr.push({
                name: el.name || '',
                value: el.value || '',
                type: el.type || '',
                checked: !!el.checked,
                id: el.id || ''
            });
        }
        var actionAttr = form.attributes['action'];
        var methodAttr = form.attributes['method'];
        var enctypeAttr = form.attributes['enctype'];
        var path = !actionAttr ? location.pathname + location.search : actionAttr.nodeValue;
        var method = !methodAttr ? 'GET' : methodAttr.nodeValue;
        var url = getFullUrl(path);
        var encType = !enctypeAttr ? 'application/x-www-form-urlencoded' : enctypeAttr.nodeValue;
        var err = new Error();
        RequestInspection.recordFormSubmission(url, method, JSON.stringify(jsonArr), '{}', err.stack || '', encType);
    }
    function handleFormSubmission(e) {
        var form = e ? (e.target || this) : this;
        if (form && form.elements) {
            recordFormSubmission(form);
        }
        if (form && form._submit && !e) {
            form._submit();
        }
    }
    if (!HTMLFormElement.prototype._submit) {
        HTMLFormElement.prototype._submit = HTMLFormElement.prototype.submit;
        HTMLFormElement.prototype.submit = function() {
            recordFormSubmission(this);
            this._submit();
        };
    }
    window.addEventListener('submit', function(submitEvent) {
        handleFormSubmission(submitEvent);
    }, true);

    var lastXhrMethod = null;
    var xhrHeaders = {};
    var xhrUrl = null;
    if (!XMLHttpRequest.prototype._open) {
        XMLHttpRequest.prototype._open = XMLHttpRequest.prototype.open;
        XMLHttpRequest.prototype.open = function(method, url, async, user, password) {
            lastXhrMethod = method;
            xhrUrl = url;
            var asyncVal = (async === undefined) ? true : async;
            this._open(method, url, asyncVal, user, password);
        };
        XMLHttpRequest.prototype._setRequestHeader = XMLHttpRequest.prototype.setRequestHeader;
        XMLHttpRequest.prototype.setRequestHeader = function(header, value) {
            xhrHeaders[header] = value;
            this._setRequestHeader(header, value);
        };
        XMLHttpRequest.prototype._send = XMLHttpRequest.prototype.send;
        XMLHttpRequest.prototype.send = function(body) {
            var err = new Error();
            var url = getFullUrl(xhrUrl);
            RequestInspection.recordXhr(url, lastXhrMethod || 'GET', body || '', JSON.stringify(xhrHeaders), err.stack || '');
            lastXhrMethod = null;
            xhrUrl = null;
            xhrHeaders = {};
            this._send(body);
        };
    }
    if (window.fetch && !window._fetch) {
        window._fetch = window.fetch;
        window.fetch = function() {
            var firstArg = arguments[0];
            var url, method, body, headers;
            if (typeof firstArg === 'string') {
                url = firstArg;
                method = (arguments[1] && arguments[1].method) ? arguments[1].method : 'GET';
                body = (arguments[1] && arguments[1].body) ? String(arguments[1].body) : '';
                headers = JSON.stringify((arguments[1] && arguments[1].headers) ? arguments[1].headers : {});
            } else {
                url = firstArg.url;
                method = firstArg.method || 'GET';
                body = '';
                headers = '{}';
            }
            var err = new Error();
            RequestInspection.recordFetch(getFullUrl(url), method, body, headers, err.stack || '');
            return window._fetch.apply(this, arguments);
        };
    }
})();
"""

        fun enabledRequestInspection(webView: WebView, extraJavaScriptToInject: String) {
            val script = "javascript: $JAVASCRIPT_INTERCEPTION_CODE\n$extraJavaScriptToInject"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                webView.evaluateJavascript(JAVASCRIPT_INTERCEPTION_CODE + "\n" + extraJavaScriptToInject, null)
            } else {
                webView.loadUrl(script)
            }
        }
    }
}
