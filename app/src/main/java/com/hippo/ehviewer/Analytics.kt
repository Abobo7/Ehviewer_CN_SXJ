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
package com.hippo.ehviewer

import android.content.Context
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import com.hippo.scene.SceneFragment
import java.util.Locale

/**
 * google监控（反射兼容可选 Firebase，避免无 google-services.json 或低版本系统依赖冲突）
 */
object Analytics {
    private const val LOG_TAG = "Analytics"
    private const val DEVICE_LANGUAGE = "device_language"

    private var analyticsInstance: Any? = null

    @JvmStatic
    fun start(context: Context) {
        try {
            val faClass = Class.forName("com.google.firebase.analytics.FirebaseAnalytics")
            val instance = faClass.getMethod("getInstance", Context::class.java).invoke(null, context)
                ?: return
            analyticsInstance = instance
            faClass.getMethod("setUserId", String::class.java).invoke(instance, Settings.getUserID())

            val locale = Locale.getDefault()
            var language = locale.language
            if (TextUtils.isEmpty(language)) {
                language = "none"
            }
            val country = locale.country
            if (!TextUtils.isEmpty(country)) {
                language = "$language-$country"
            }
            language = language.lowercase(Locale.getDefault())
            faClass.getMethod("setUserProperty", String::class.java, String::class.java)
                .invoke(instance, DEVICE_LANGUAGE, language)
        } catch (t: Throwable) {
            Log.d(LOG_TAG, "FirebaseAnalytics unavailable, skipping init")
        }
    }

    @JvmStatic
    val isEnabled: Boolean
        get() = analyticsInstance != null && Settings.getEnableAnalytics()

    @JvmStatic
    fun onSceneView(scene: SceneFragment) {
        val instance = analyticsInstance ?: return
        if (isEnabled) {
            try {
                val bundle = Bundle()
                bundle.putString("scene_simple_class", scene.javaClass.simpleName)
                bundle.putString("scene_class", scene.javaClass.name)
                instance.javaClass.getMethod("logEvent", String::class.java, Bundle::class.java)
                    .invoke(instance, "scene_view", bundle)
            } catch (_: Throwable) {
            }
        }
    }

    @JvmStatic
    fun recordException(e: Throwable) {
        Log.e(LOG_TAG, "Unexpected error raised", e)

        if (isEnabled) {
            try {
                val fcClass = Class.forName("com.google.firebase.crashlytics.FirebaseCrashlytics")
                val fc = fcClass.getMethod("getInstance").invoke(null)
                fcClass.getMethod("recordException", Throwable::class.java).invoke(fc, e)
            } catch (ex: Throwable) {
                Log.d(LOG_TAG, "FirebaseCrashlytics skipped: $ex")
            }
        }
    }
}
