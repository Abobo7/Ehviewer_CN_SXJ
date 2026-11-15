package com.hippo.ehviewer.ui.scene.sign;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.provider.Browser;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;

import com.hippo.ehviewer.R;
import com.hippo.ehviewer.UrlOpener;
import com.hippo.ehviewer.client.EhCookieStore;
import com.hippo.ehviewer.client.EhUrl;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Helper for signing in via an external modern browser (e.g. Kiwi / Chrome / Firefox / UC)
 * on devices with outdated system WebView (such as Android 4.4 KitKat).
 */
public final class ExternalBrowserLoginHelper {

    public static final String AUTH_SCHEME = "ehviewer";
    public static final String AUTH_HOST = "auth";

    public static final String BOOKMARKLET_SNIPPET =
            "javascript:location.href='ehviewer://auth?cookie='+encodeURIComponent(document.cookie)";

    public static final String CONSOLE_SNIPPET =
            "location.href='ehviewer://auth?cookie='+encodeURIComponent(document.cookie)";

    private static final Pattern COOKIE_PAIR_PATTERN = Pattern.compile(
            "(?i)\\b(ipb_member_id|ipb_pass_hash|igneous)\\s*[=:\\t]\\s*([^;\\r\\n\\t\"'\\s,]+)");

    private static final String[] PREFERRED_BROWSERS = new String[]{
            "com.kiwibrowser.browser.classic",
            "com.kiwibrowser.browser",
            "com.android.chrome",
            "com.chrome.beta",
            "com.chrome.dev",
            "org.mozilla.firefox",
            "org.mozilla.fenix",
            "com.microsoft.emmx",
            "com.brave.browser",
            "com.vivaldi.browser",
            "com.opera.browser",
            "com.opera.mini.native",
            "mark.via",
            "mark.via.gp",
            "com.UCMobile",
            "com.UCMobile.intl",
            "com.quark.browser"
    };

    private ExternalBrowserLoginHelper() {
    }

    public static final class ParsedCookies {
        @Nullable
        public String ipbMemberId;
        @Nullable
        public String ipbPassHash;
        @Nullable
        public String igneous;

        public boolean isValid() {
            return !TextUtils.isEmpty(ipbMemberId) && !TextUtils.isEmpty(ipbPassHash);
        }
    }

    private static final class BrowserCandidate {
        final String packageName;
        final String activityName;
        final String label;
        final int rank;

        BrowserCandidate(String packageName, String activityName, String label, int rank) {
            this.packageName = packageName;
            this.activityName = activityName;
            this.label = label;
            this.rank = rank;
        }
    }

    @NonNull
    public static ParsedCookies parseCookiesFromUri(@Nullable Uri uri) {
        ParsedCookies out = new ParsedCookies();
        if (uri == null) {
            return out;
        }
        String cookieParam = uri.getQueryParameter("cookie");
        if (!TextUtils.isEmpty(cookieParam)) {
            out = parseCookies(cookieParam);
        }
        String directId = sanitizeValue(EhCookieStore.KEY_IPD_MEMBER_ID,
                uri.getQueryParameter(EhCookieStore.KEY_IPD_MEMBER_ID));
        String directHash = sanitizeValue(EhCookieStore.KEY_IPD_PASS_HASH,
                uri.getQueryParameter(EhCookieStore.KEY_IPD_PASS_HASH));
        String directIgneous = sanitizeValue(EhCookieStore.KEY_IGNEOUS,
                uri.getQueryParameter(EhCookieStore.KEY_IGNEOUS));
        if (!TextUtils.isEmpty(directId)) {
            out.ipbMemberId = directId;
        }
        if (!TextUtils.isEmpty(directHash)) {
            out.ipbPassHash = directHash;
        }
        if (!TextUtils.isEmpty(directIgneous)) {
            out.igneous = directIgneous;
        }
        return out;
    }

    @NonNull
    public static ParsedCookies parseCookies(@Nullable String rawText) {
        ParsedCookies out = new ParsedCookies();
        if (TextUtils.isEmpty(rawText)) {
            return out;
        }
        String text = rawText.trim();
        if (text.contains("encodeURIComponent(document.cookie)")) {
            return out;
        }

        // 1. Check if text itself is an ehviewer://auth callback URL
        int schemeIdx = text.indexOf("ehviewer://auth");
        if (schemeIdx >= 0) {
            try {
                Uri uri = Uri.parse(text.substring(schemeIdx).split("\\s+")[0]);
                ParsedCookies fromUri = parseCookiesFromUri(uri);
                if (fromUri.isValid()) {
                    return fromUri;
                }
            } catch (Throwable ignored) {
            }
        }

        // 2. Check JSON array/object (e.g. Cookie-Editor / EditThisCookie export)
        if (text.startsWith("[") || text.startsWith("{")) {
            try {
                if (text.startsWith("[")) {
                    JSONArray arr = new JSONArray(text);
                    for (int i = 0; i < arr.length(); i++) {
                        JSONObject obj = arr.optJSONObject(i);
                        if (obj != null) {
                            String name = obj.optString("name", obj.optString("key", ""));
                            String val = obj.optString("value", "");
                            assignCookieField(out, name, val);
                        }
                    }
                } else {
                    JSONObject obj = new JSONObject(text);
                    assignCookieField(out, EhCookieStore.KEY_IPD_MEMBER_ID,
                            obj.optString(EhCookieStore.KEY_IPD_MEMBER_ID, ""));
                    assignCookieField(out, EhCookieStore.KEY_IPD_PASS_HASH,
                            obj.optString(EhCookieStore.KEY_IPD_PASS_HASH, ""));
                    assignCookieField(out, EhCookieStore.KEY_IGNEOUS,
                            obj.optString(EhCookieStore.KEY_IGNEOUS, ""));
                }
                if (out.isValid()) {
                    return out;
                }
            } catch (Throwable ignored) {
            }
        }

        // 3. URL-decode if needed (e.g. copied encoded query string)
        String candidateText = text;
        if (text.contains("%3D") || text.contains("%3B") || text.contains("%3d") || text.contains("%3b")) {
            try {
                candidateText = URLDecoder.decode(text, "UTF-8");
            } catch (Throwable ignored) {
            }
        }

        // 4. Match key=value, key: value, or DevTools tab-separated rows
        Matcher matcher = COOKIE_PAIR_PATTERN.matcher(candidateText);
        while (matcher.find()) {
            String key = matcher.group(1);
            String val = matcher.group(2);
            assignCookieField(out, key, val);
        }

        return out;
    }

    private static void assignCookieField(@NonNull ParsedCookies out, @Nullable String rawKey, @Nullable String rawVal) {
        if (TextUtils.isEmpty(rawKey) || TextUtils.isEmpty(rawVal)) {
            return;
        }
        String key = rawKey.trim().toLowerCase();
        String val = sanitizeValue(key, rawVal);
        if (TextUtils.isEmpty(val)) {
            return;
        }
        if (EhCookieStore.KEY_IPD_MEMBER_ID.equals(key)) {
            out.ipbMemberId = val;
        } else if (EhCookieStore.KEY_IPD_PASS_HASH.equals(key)) {
            out.ipbPassHash = val;
        } else if (EhCookieStore.KEY_IGNEOUS.equals(key)) {
            out.igneous = val;
        }
    }

    @Nullable
    private static String sanitizeValue(@NonNull String key, @Nullable String rawVal) {
        if (rawVal == null) {
            return null;
        }
        String val = rawVal.trim();
        if (val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2) {
            val = val.substring(1, val.length() - 1).trim();
        }
        if (val.isEmpty() || "null".equalsIgnoreCase(val) || "deleted".equalsIgnoreCase(val)) {
            return null;
        }
        if (EhCookieStore.KEY_IGNEOUS.equals(key) && "mystery".equalsIgnoreCase(val)) {
            return null;
        }
        return val;
    }

    public static void copyCallbackScript(@NonNull Context context, boolean asBookmarklet, boolean showToast) {
        ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm != null) {
            String snippet = asBookmarklet ? BOOKMARKLET_SNIPPET : CONSOLE_SNIPPET;
            cm.setPrimaryClip(ClipData.newPlainText("EhViewer Auth Callback", snippet));
            if (showToast) {
                Toast.makeText(context, R.string.external_browser_script_copied, Toast.LENGTH_LONG).show();
            }
        }
    }

    @Nullable
    public static ParsedCookies inspectClipboardCookies(@NonNull Context context) {
        try {
            ClipboardManager cm = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) {
                return null;
            }
            ClipData clip = cm.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) {
                return null;
            }
            CharSequence text = clip.getItemAt(0).coerceToText(context);
            if (text == null) {
                return null;
            }
            ParsedCookies parsed = parseCookies(text.toString());
            return parsed.isValid() ? parsed : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static void showExternalBrowserGuideDialog(@NonNull Context context,
                                                      @Nullable Runnable onBrowserLaunched) {
        new AlertDialog.Builder(context)
                .setTitle(R.string.sign_in_via_external_browser)
                .setMessage(R.string.external_browser_login_guide)
                .setPositiveButton(R.string.external_browser_open_forums, (d, which) -> {
                    if (inspectClipboardCookies(context) == null) {
                        copyCallbackScript(context, true, false);
                    }
                    openUrlInExternalBrowser(context, EhUrl.URL_SIGN_IN, onBrowserLaunched);
                })
                .setNeutralButton(R.string.external_browser_open_ex, (d, which) -> {
                    if (inspectClipboardCookies(context) == null) {
                        copyCallbackScript(context, true, false);
                    }
                    openUrlInExternalBrowser(context, EhUrl.HOST_EX, onBrowserLaunched);
                })
                .setNegativeButton(R.string.external_browser_copy_script, (d, which) ->
                        copyCallbackScript(context, true, true))
                .show();
    }

    public static void openUrlInExternalBrowser(@NonNull Context context,
                                                @NonNull String url,
                                                @Nullable Runnable onLaunched) {
        List<BrowserCandidate> browsers = findExternalBrowsers(context);
        if (browsers.isEmpty()) {
            if (onLaunched != null) {
                onLaunched.run();
            }
            UrlOpener.openUrl(context, url, false);
            return;
        }

        if (browsers.size() == 1) {
            launchBrowserCandidate(context, browsers.get(0), url, onLaunched);
            return;
        }

        CharSequence[] items = new CharSequence[browsers.size()];
        for (int i = 0; i < browsers.size(); i++) {
            BrowserCandidate b = browsers.get(i);
            items[i] = b.label + " (" + b.packageName + ")";
        }

        new AlertDialog.Builder(context)
                .setTitle(R.string.external_browser_choose_title)
                .setItems(items, (dialog, which) -> {
                    if (which >= 0 && which < browsers.size()) {
                        launchBrowserCandidate(context, browsers.get(which), url, onLaunched);
                    }
                })
                .show();
    }

    private static void launchBrowserCandidate(@NonNull Context context,
                                               @NonNull BrowserCandidate candidate,
                                               @NonNull String url,
                                               @Nullable Runnable onLaunched) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addCategory(Intent.CATEGORY_BROWSABLE);
            intent.setClassName(candidate.packageName, candidate.activityName);
            intent.putExtra(Browser.EXTRA_APPLICATION_ID, context.getPackageName());
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (onLaunched != null) {
                onLaunched.run();
            }
            context.startActivity(intent);
            Toast.makeText(context, R.string.external_browser_launched_tip, Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            UrlOpener.openUrl(context, url, false);
        }
    }

    @NonNull
    private static List<BrowserCandidate> findExternalBrowsers(@NonNull Context context) {
        PackageManager pm = context.getPackageManager();
        Intent probe = new Intent(Intent.ACTION_VIEW, Uri.parse("https://forums.e-hentai.org/"));
        probe.addCategory(Intent.CATEGORY_BROWSABLE);
        List<ResolveInfo> infos = pm.queryIntentActivities(probe, 0);
        if (infos == null || infos.isEmpty()) {
            return Collections.emptyList();
        }

        String selfPkg = context.getPackageName();
        List<BrowserCandidate> result = new ArrayList<>();
        for (ResolveInfo info : infos) {
            ActivityInfo ai = info.activityInfo;
            if (ai == null || TextUtils.isEmpty(ai.packageName) || TextUtils.isEmpty(ai.name)) {
                continue;
            }
            if (selfPkg.equals(ai.packageName) || ai.packageName.contains("ehviewer")) {
                continue;
            }
            CharSequence rawLabel = info.loadLabel(pm);
            String label = rawLabel != null ? rawLabel.toString() : ai.packageName;
            int rank = computeBrowserRank(ai.packageName);
            result.add(new BrowserCandidate(ai.packageName, ai.name, label, rank));
        }

        Collections.sort(result, new Comparator<BrowserCandidate>() {
            @Override
            public int compare(BrowserCandidate a, BrowserCandidate b) {
                if (a.rank != b.rank) {
                    return a.rank < b.rank ? -1 : 1;
                }
                return a.label.compareToIgnoreCase(b.label);
            }
        });
        return result;
    }

    private static int computeBrowserRank(@NonNull String pkg) {
        for (int i = 0; i < PREFERRED_BROWSERS.length; i++) {
            if (PREFERRED_BROWSERS[i].equalsIgnoreCase(pkg)) {
                return i;
            }
        }
        if ("com.android.browser".equalsIgnoreCase(pkg) || pkg.contains("webview")) {
            return 1000;
        }
        return 500;
    }
}
