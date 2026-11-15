/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.ehviewer.ui.scene.sign;

import static com.hippo.ehviewer.client.parser.ParserUtils.formatDate;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.google.android.material.textfield.TextInputLayout;
import com.hippo.ehviewer.EhApplication;
import com.hippo.ehviewer.R;
import com.hippo.ehviewer.client.EhCookieStore;
import com.hippo.ehviewer.client.EhUrl;
import com.hippo.ehviewer.client.EhUtils;
import com.hippo.ehviewer.ui.scene.SolidScene;
import com.hippo.lib.yorozuya.AssertUtils;
import com.hippo.lib.yorozuya.ViewUtils;

import java.util.List;

import okhttp3.Cookie;
import okhttp3.HttpUrl;

public class CookieSignInScene extends SolidScene implements EditText.OnEditorActionListener,
        View.OnClickListener {

    private static final String TAG = "CookieSignInScene";
    public static final String KEY_OPEN_EXTERNAL_BROWSER = "open_external_browser";

    /*---------------
     View life cycle
     ---------------*/
    @Nullable
    private TextInputLayout mIpbMemberIdLayout;
    @Nullable
    private TextInputLayout mIpbPassHashLayout;
    @Nullable
    private TextInputLayout mIgneousLayout;
    @Nullable
    private EditText mIpbMemberId;
    @Nullable
    private EditText mIpbPassHash;
    @Nullable
    private EditText mIgneous;
    @Nullable
    private View mOk;
    @Nullable
    private Button importInClipboard;
    @Nullable
    private Button mOpenExternalBrowser;

    private boolean mWaitingExternalBrowser;

    @Override
    public boolean needShowLeftDrawer() {
        return false;
    }

    @Nullable
    @Override
    public View onCreateView2(LayoutInflater inflater,
                              @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.scene_cookie_sign_in, container, false);
        mIpbMemberIdLayout = (TextInputLayout) ViewUtils.$$(view, R.id.ipb_member_id_layout);
        mIpbMemberId = mIpbMemberIdLayout.getEditText();
        AssertUtils.assertNotNull(mIpbMemberId);
        mIpbPassHashLayout = (TextInputLayout) ViewUtils.$$(view, R.id.ipb_pass_hash_layout);
        mIpbPassHash = mIpbPassHashLayout.getEditText();
        AssertUtils.assertNotNull(mIpbPassHash);
        mIgneousLayout = (TextInputLayout) ViewUtils.$$(view, R.id.igneous_layout);
        mIgneous = mIgneousLayout.getEditText();
        AssertUtils.assertNotNull(mIgneous);
        mOk = ViewUtils.$$(view, R.id.ok);
        importInClipboard = (Button) ViewUtils.$$(view, R.id.import_in_clipboard);
        mOpenExternalBrowser = (Button) ViewUtils.$$(view, R.id.open_external_browser);

        mIpbPassHash.setOnEditorActionListener(this);

        mOk.setOnClickListener(this);
        importInClipboard.setOnClickListener(this);
        mOpenExternalBrowser.setOnClickListener(this);

        // Try to get old version cookie info
        Context context = getEHContext();
        AssertUtils.assertNotNull(context);
        SharedPreferences sharedPreferences = context.getSharedPreferences("eh_info", 0);
        String ipbMemberId = sharedPreferences.getString("ipb_member_id", null);
        String ipbPassHash = sharedPreferences.getString("ipb_pass_hash", null);
        String igneous = sharedPreferences.getString("igneous", null);
        boolean getIt = false;
        if (!TextUtils.isEmpty(ipbMemberId)) {
            mIpbMemberId.setText(ipbMemberId);
            getIt = true;
        }
        if (!TextUtils.isEmpty(ipbPassHash)) {
            mIpbPassHash.setText(ipbPassHash);
            getIt = true;
        }
        if (!TextUtils.isEmpty(igneous)) {
            mIgneous.setText(igneous);
            getIt = true;
        }
        if (getIt) {
            showTip(R.string.found_cookies, LENGTH_SHORT);
        }

        return view;
    }


    @Override
    public void onViewCreated(View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        boolean autoOpenExternal = savedInstanceState == null
                && args != null
                && args.getBoolean(KEY_OPEN_EXTERNAL_BROWSER, false);
        if (autoOpenExternal) {
            args.putBoolean(KEY_OPEN_EXTERNAL_BROWSER, false);
            Context context = getEHContext();
            if (context != null) {
                ExternalBrowserLoginHelper.showExternalBrowserGuideDialog(context,
                        () -> mWaitingExternalBrowser = true);
            }
        } else {
            showSoftInput(mIpbMemberId);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mWaitingExternalBrowser || (mIpbMemberId != null && TextUtils.isEmpty(mIpbMemberId.getText()))) {
            Context context = getEHContext();
            if (context != null) {
                ExternalBrowserLoginHelper.ParsedCookies parsed =
                        ExternalBrowserLoginHelper.inspectClipboardCookies(context);
                if (parsed != null && parsed.isValid()) {
                    applyParsedCookies(parsed);
                    if (mWaitingExternalBrowser) {
                        mWaitingExternalBrowser = false;
                        showTip(R.string.external_browser_cookies_autofilled, LENGTH_LONG);
                    }
                }
            }
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();

        mIpbMemberIdLayout = null;
        mIpbPassHashLayout = null;
        mIgneousLayout = null;
        mIpbMemberId = null;
        mIpbPassHash = null;
        mIgneous = null;
        mOk = null;
        importInClipboard = null;
        mOpenExternalBrowser = null;
    }

    @Override
    public void onClick(View v) {
        if (mOk == v) {
            enter();
        } else if (importInClipboard == v) {
            importCookie();
        } else if (mOpenExternalBrowser == v) {
            Context context = getEHContext();
            if (context != null) {
                ExternalBrowserLoginHelper.showExternalBrowserGuideDialog(context,
                        () -> mWaitingExternalBrowser = true);
            }
        }
    }

    @Override
    public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
        if (mIpbPassHash == v) {
            enter();
        }
        return true;
    }

    // false for error
    private static boolean checkIpbMemberId(String id) {
        for (int i = 0, n = id.length(); i < n; i++) {
            char ch = id.charAt(i);
            if (!(ch >= '0' && ch <= '9')) {
                return false;
            }
        }
        return true;
    }

    private static boolean checkIpbPassHash(String hash) {
        if (32 != hash.length()) {
            return false;
        }

        for (int i = 0, n = hash.length(); i < n; i++) {
            char ch = hash.charAt(i);
            if (!(ch >= '0' && ch <= '9') && !(ch >= 'a' && ch <= 'z')) {
                return false;
            }
        }
        return true;
    }

    public void enter() {
        Context context = getEHContext();
        if (null == context || null == mIpbMemberIdLayout || null == mIpbPassHashLayout ||
                null == mIgneousLayout || null == mIpbMemberId || null == mIpbPassHash ||
                null == mIgneous) {
            return;
        }

        final String ipbMemberId = mIpbMemberId.getText().toString().trim();
        final String ipbPassHash = mIpbPassHash.getText().toString().trim();
        final String igneous = mIgneous.getText().toString().trim();

        if (TextUtils.isEmpty(ipbMemberId)) {
            mIpbMemberIdLayout.setError(getString(R.string.text_is_empty));
            return;
        } else {
            mIpbMemberIdLayout.setError(null);
        }
        if (TextUtils.isEmpty(ipbPassHash)) {
            mIpbPassHashLayout.setError(getString(R.string.text_is_empty));
            return;
        } else {
            mIpbPassHashLayout.setError(null);
        }

        hideSoftInput();

        if (!checkIpbMemberId(ipbMemberId) || !(checkIpbPassHash(ipbPassHash))) {
            new AlertDialog.Builder(context).setTitle(R.string.waring)
                    .setMessage(R.string.wrong_cookie_warning)
                    .setNegativeButton(R.string.i_dont_think_so, null)
                    .setPositiveButton(R.string.i_will_check_it, (dialog, which) -> {
                        storeCookie(ipbMemberId, ipbPassHash, igneous);
                        setResult(RESULT_OK, null);
                        finish();
                    }).show();
        } else {
            storeCookie(ipbMemberId, ipbPassHash, igneous);
            setResult(RESULT_OK, null);
            finish();
        }
    }

    private void applyParsedCookies(ExternalBrowserLoginHelper.ParsedCookies parsed) {
        if (mIpbMemberId != null && !TextUtils.isEmpty(parsed.ipbMemberId)) {
            mIpbMemberId.setText(parsed.ipbMemberId);
        }
        if (mIpbPassHash != null && !TextUtils.isEmpty(parsed.ipbPassHash)) {
            mIpbPassHash.setText(parsed.ipbPassHash);
        }
        if (mIgneous != null && !TextUtils.isEmpty(parsed.igneous)) {
            mIgneous.setText(parsed.igneous);
        }
    }

    public void onExternalAuthSuccess(ExternalBrowserLoginHelper.ParsedCookies parsed) {
        mWaitingExternalBrowser = false;
        applyParsedCookies(parsed);
        setResult(RESULT_OK, null);
        finish();
    }

    private void importCookie() {
        try {
            Context context = getEHContext();
            if (context == null) {
                return;
            }
            ClipboardManager clipboardManager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboardManager == null || !clipboardManager.hasPrimaryClip()) {
                Toast.makeText(context, R.string.no_cookie_in_clipboard, Toast.LENGTH_LONG).show();
                return;
            }
            ClipData data = clipboardManager.getPrimaryClip();
            if (data == null || data.getItemCount() == 0) {
                Toast.makeText(context, R.string.no_cookie_in_clipboard, Toast.LENGTH_LONG).show();
                return;
            }
            CharSequence raw = data.getItemAt(0).coerceToText(context);
            ExternalBrowserLoginHelper.ParsedCookies parsed =
                    ExternalBrowserLoginHelper.parseCookies(raw != null ? raw.toString() : null);
            if (!parsed.isValid()) {
                Toast.makeText(context, R.string.no_cookie_in_clipboard, Toast.LENGTH_LONG).show();
                return;
            }
            applyParsedCookies(parsed);
            showTip(R.string.external_browser_cookies_autofilled, LENGTH_SHORT);
        } catch (Throwable e) {
            Context context = getContext();
            if (context != null) {
                Toast.makeText(context, R.string.cookie_in_clipboard_incorrect, Toast.LENGTH_LONG).show();
            }
        }
    }

    private static Cookie newCookie(String name, String value, String domain) {
        return new Cookie.Builder().name(name).value(value)
                .domain(domain).expiresAt(Long.MAX_VALUE).build();
    }

    private void storeCookie(String id, String hash, String igneous) {
        Context context = getEHContext();
        if (null == context) {
            return;
        }
        storeCookiesStatic(context, id, hash, igneous);
    }

    public static void storeCookiesStatic(Context context, String id, String hash, @Nullable String igneous) {
        if (null == context) {
            return;
        }

        EhUtils.signOut(context);

        EhCookieStore store = EhApplication.getEhCookieStore(context);
        store.addCookie(newCookie(EhCookieStore.KEY_IPD_MEMBER_ID, id, EhUrl.DOMAIN_E));
        store.addCookie(newCookie(EhCookieStore.KEY_IPD_MEMBER_ID, id, EhUrl.DOMAIN_EX));
        store.addCookie(newCookie(EhCookieStore.KEY_IPD_MEMBER_ID, id, EhUrl.DOMAIN_FORUMS));
        store.addCookie(newCookie(EhCookieStore.KEY_IPD_PASS_HASH, hash, EhUrl.DOMAIN_E));
        store.addCookie(newCookie(EhCookieStore.KEY_IPD_PASS_HASH, hash, EhUrl.DOMAIN_EX));
        store.addCookie(newCookie(EhCookieStore.KEY_IPD_PASS_HASH, hash, EhUrl.DOMAIN_FORUMS));
        if (igneous != null && !igneous.isEmpty()) {
            store.addCookie(newCookie(EhCookieStore.KEY_IGNEOUS, igneous, EhUrl.DOMAIN_E));
            store.addCookie(newCookie(EhCookieStore.KEY_IGNEOUS, igneous, EhUrl.DOMAIN_EX));
            store.addCookie(newCookie(EhCookieStore.KEY_IGNEOUS, igneous, EhUrl.DOMAIN_FORUMS));
        }
        HttpUrl httpUrl = HttpUrl.parse(EhUrl.URL_FORUMS);
        if (httpUrl == null) {
            return;
        }
        try {
            CookieManager cookieManager = CookieManager.getInstance();
            cookieManager.setAcceptCookie(true);
            List<Cookie> cookies = store.loadForRequest(httpUrl);
            for (Cookie cookie : cookies) {
                String cookieString = formatCookieForWebView(cookie);
                cookieManager.setCookie(EhUrl.DOMAIN_FORUMS, cookieString);
            }
            com.hippo.util.CookieManagerCompat.flush(context, cookieManager);
        } catch (Throwable t) {
            Log.e(TAG, "CookieManager/WebView unavailable, OkHttp cookies still stored", t);
        }
    }

    /**
     * 将 OkHttp Cookie 对象转换为 WebView 可接受的字符串格式
     */
    private static String formatCookieForWebView(Cookie cookie) {
        StringBuilder builder = new StringBuilder();

        // 基本格式：name=value
        builder.append(cookie.name())
                .append("=")
                .append(cookie.value());

        // 添加 Domain（如果需要）
        if (cookie.domain() != null && !cookie.domain().isEmpty()) {
            builder.append("; Domain=").append(cookie.domain());
        }

        // 添加 Path
        if (cookie.path() != null && !cookie.path().isEmpty()) {
            builder.append("; Path=").append(cookie.path());
        } else {
            builder.append("; Path=/");
        }

        // 添加 Expires/Max-Age
        if (cookie.persistent()) {
            if (cookie.expiresAt() > 0) {
                builder.append("; Expires=")
                        .append(formatDate(cookie.expiresAt()));
            }
//            else if (cookie.maxAge() != Cookie.MAX_AGE) {
//                builder.append("; Max-Age=").append(cookie.maxAge());
//            }
        }

        // 添加 Secure 标志
        if (cookie.secure()) {
            builder.append("; Secure");
        }

        // 添加 HttpOnly 标志
        if (cookie.httpOnly()) {
            builder.append("; HttpOnly");
        }

        return builder.toString();
    }
}
