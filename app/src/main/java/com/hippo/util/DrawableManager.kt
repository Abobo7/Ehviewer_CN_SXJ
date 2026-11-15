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
package com.hippo.util

import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Build
import androidx.annotation.DrawableRes
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.res.ResourcesCompat
import androidx.vectordrawable.graphics.drawable.VectorDrawableCompat

object DrawableManager {
    @JvmStatic
    fun getVectorDrawable(context: Context, @DrawableRes resId: Int): Drawable? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                ResourcesCompat.getDrawable(context.resources, resId, context.theme)
            } else {
                VectorDrawableCompat.create(context.resources, resId, context.theme)
                    ?: AppCompatResources.getDrawable(context, resId)
            }
        } catch (_: Throwable) {
            try {
                AppCompatResources.getDrawable(context, resId)
            } catch (_: Throwable) {
                null
            }
        }
    }
}
