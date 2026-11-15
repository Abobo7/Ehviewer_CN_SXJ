package com.hippo

import android.content.Context
import com.getkeepsafe.relinker.ReLinker
import java.io.FileDescriptor

object Native {
    @JvmStatic
    fun initialize() {
        System.loadLibrary("ehviewer")
    }

    @JvmStatic
    fun initialize(context: Context) {
        try {
            ReLinker.loadLibrary(context, "ehviewer")
        } catch (_: Throwable) {
            System.loadLibrary("ehviewer")
        }
    }

    @JvmStatic
    external fun getFd(fd: FileDescriptor?): Int
}
