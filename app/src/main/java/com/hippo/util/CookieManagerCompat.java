package com.hippo.util;

import android.content.Context;
import android.os.Build;
import android.webkit.CookieManager;
import android.webkit.CookieSyncManager;
import android.webkit.WebResourceResponse;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import java.io.InputStream;
import java.util.Map;

/**
 * Compatibility wrapper for CookieManager methods introduced in API 21 (Lollipop).
 */
public final class CookieManagerCompat {

    private CookieManagerCompat() {
    }

    @SuppressWarnings("deprecation")
    public static void flush(Context context, CookieManager cookieManager) {
        if (cookieManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Api21Impl.flush(cookieManager);
        } else if (context != null) {
            CookieSyncManager.createInstance(context.getApplicationContext());
            CookieSyncManager.getInstance().sync();
        }
    }

    @SuppressWarnings("deprecation")
    public static void clearAllCookies(Context context, CookieManager cookieManager) {
        if (cookieManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Api21Impl.clearAll(cookieManager);
        } else {
            if (context != null) {
                CookieSyncManager.createInstance(context.getApplicationContext());
            }
            cookieManager.removeAllCookie();
            cookieManager.removeSessionCookie();
            if (context != null) {
                CookieSyncManager.getInstance().sync();
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private static final class Api21Impl {
        static void flush(CookieManager cookieManager) {
            cookieManager.flush();
        }

        static void clearAll(CookieManager cookieManager) {
            cookieManager.flush();
            cookieManager.removeAllCookies(null);
            cookieManager.removeSessionCookies(null);
        }

        static WebResourceResponse createResponse(
                String mimeType,
                String encoding,
                int statusCode,
                String reasonPhrase,
                Map<String, String> responseHeaders,
                @Nullable InputStream data
        ) {
            return new WebResourceResponse(mimeType, encoding, statusCode, reasonPhrase, responseHeaders, data);
        }
    }

    public static WebResourceResponse createWebResourceResponse(
            String mimeType,
            String encoding,
            int statusCode,
            String reasonPhrase,
            Map<String, String> responseHeaders,
            @Nullable InputStream data
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return Api21Impl.createResponse(mimeType, encoding, statusCode, reasonPhrase, responseHeaders, data);
        }
        return new WebResourceResponse(mimeType, encoding, data);
    }
}
