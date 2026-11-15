package com.hippo.lib.image

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.ImageDecoder.ALLOCATOR_DEFAULT
import android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
import android.graphics.ImageDecoder.DecodeException
import android.graphics.ImageDecoder.ImageInfo
import android.graphics.ImageDecoder.Source
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Animatable
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.AnimationDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import androidx.core.graphics.createBitmap
import androidx.core.graphics.drawable.toDrawable
import com.hippo.ehviewer.Analytics
import com.hippo.ehviewer.EhApplication
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.min

class Image private constructor(
    source: InputStream?,
    drawable: Drawable? = null,
    val hardware: Boolean = false,
    val release: () -> Unit? = {},
) {
    private val nativeLock = Any()
    internal var mImage1: Image1? = null
    private var mUseNative = false
    private var mNativeOpaque = false
    private var mObtainedDrawable: Drawable? = null
    private var mBitmap: Bitmap? = null
    private var mReferences = 0
    private var started = false

    val animated: Boolean
    val width: Int
    val height: Int

    init {
        if (source != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && source is FileInputStream) {
                var simpleSize: Int? = null
                val avail = try { source.available() } catch (_: Exception) { 0 }
                if (avail > 10485760) {
                    simpleSize = avail / 10485760 + 1
                }
                mObtainedDrawable = Api28ImageHelper.decode(source, hardware, simpleSize)
            } else {
                val bis = if (source is BufferedInputStream) source else BufferedInputStream(source, 64 * 1024)
                bis.mark(64 * 1024)
                val nativeDecoded = try {
                    Image1.decode(bis, false)?.also { it.complete() }
                } catch (t: Throwable) {
                    Analytics.recordException(t)
                    null
                }
                if (nativeDecoded != null && nativeDecoded.width > 0 && nativeDecoded.height > 0) {
                    mImage1 = nativeDecoded
                    mUseNative = true
                    mNativeOpaque = nativeDecoded.isOpaque
                } else {
                    nativeDecoded?.recycle()
                    try {
                        bis.reset()
                    } catch (_: Exception) {
                    }
                    mObtainedDrawable = decodeStreamWithBitmapFactory(bis)
                }
            }
        }

        if (!mUseNative && mObtainedDrawable == null) {
            mObtainedDrawable = drawable ?: throw IllegalArgumentException("数据解码出错")
        }

        if (mUseNative) {
            val img1 = mImage1!!
            animated = img1.frameCount > 1
            width = img1.width
            height = img1.height
        } else {
            animated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Api28ImageHelper.isAnimated(mObtainedDrawable)
            } else {
                mObtainedDrawable is AnimationDrawable || mObtainedDrawable is Animatable
            }
            width = (mObtainedDrawable as? BitmapDrawable)?.bitmap?.width
                ?: mObtainedDrawable!!.intrinsicWidth
            height = (mObtainedDrawable as? BitmapDrawable)?.bitmap?.height
                ?: mObtainedDrawable!!.intrinsicHeight
        }
    }

    val isRecycled: Boolean
        get() = synchronized(nativeLock) {
            if (mUseNative) {
                mImage1 == null && mObtainedDrawable == null
            } else {
                mObtainedDrawable == null
            }
        }

    @Synchronized
    fun recycle() {
        synchronized(nativeLock) {
            if (mImage1 == null && mObtainedDrawable == null) return
            if (mObtainedDrawable is NativeAnimatedDrawable) {
                (mObtainedDrawable as NativeAnimatedDrawable).dispose()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                Api28ImageHelper.stopIfAnimated(mObtainedDrawable)
            }
            if (mObtainedDrawable is BitmapDrawable) {
                (mObtainedDrawable as BitmapDrawable?)?.bitmap?.recycle()
            }
            mObtainedDrawable?.callback = null
            mObtainedDrawable = null
            mBitmap?.recycle()
            mBitmap = null
            mImage1?.recycle()
            mImage1 = null
        }
        release()
    }

    private fun prepareBitmap() {
        if (mBitmap != null) return
        mBitmap = createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    private fun updateBitmap() {
        prepareBitmap()
        mObtainedDrawable?.let { d ->
            d.setBounds(0, 0, width.coerceAtLeast(1), height.coerceAtLeast(1))
            d.draw(Canvas(mBitmap!!))
        }
    }

    @Synchronized
    fun obtain(): Boolean {
        return if (isRecycled) {
            false
        } else {
            ++mReferences
            true
        }
    }

    @Synchronized
    fun release() {
        --mReferences
        if (mReferences <= 0 && !isRecycled) {
            recycle()
        }
    }

    fun getDrawable(): Drawable {
        check(obtain()) { "Recycled!" }
        synchronized(nativeLock) {
            mObtainedDrawable?.let { return it }
            val img1 = mImage1 ?: throw IllegalStateException("Recycled!")
            if (img1.isRecycled) throw IllegalStateException("Recycled!")
            if (!animated) {
                val w = width.coerceAtLeast(1)
                val h = height.coerceAtLeast(1)
                val bmp = createBitmap(w, h)
                img1.render(0, 0, bmp, 0, 0, w, h, false, 0)
                img1.recycle()
                mImage1 = null
                val res = EhApplication.getInstance()?.resources ?: Resources.getSystem()
                mObtainedDrawable = BitmapDrawable(res, bmp)
            } else {
                mObtainedDrawable = NativeAnimatedDrawable(this, nativeLock)
            }
            return mObtainedDrawable!!
        }
    }

    fun texImage(init: Boolean, offsetX: Int, offsetY: Int, width: Int, height: Int) {
        check(!hardware) { "Hardware buffer cannot be used in glgallery" }
        synchronized(nativeLock) {
            val img1 = mImage1
            if (img1 != null && !img1.isRecycled) {
                img1.texImage(init, offsetX, offsetY, width, height)
                return
            }
            if (mObtainedDrawable == null) {
                return
            }
            try {
                val bitmap: Bitmap = if (animated) {
                    updateBitmap()
                    mBitmap!!
                } else if (mObtainedDrawable is BitmapDrawable) {
                    (mObtainedDrawable as BitmapDrawable).bitmap
                } else {
                    val stickerBitmap = createBitmap(
                        mObtainedDrawable!!.intrinsicWidth.coerceAtLeast(1),
                        mObtainedDrawable!!.intrinsicHeight.coerceAtLeast(1)
                    )
                    val canvas = Canvas(stickerBitmap)
                    mObtainedDrawable!!.setBounds(0, 0, stickerBitmap.width, stickerBitmap.height)
                    mObtainedDrawable!!.draw(canvas)
                    stickerBitmap
                }
                nativeTexImage(
                    bitmap,
                    init,
                    offsetX,
                    offsetY,
                    width,
                    height
                )
            } catch (e: ClassCastException) {
                Analytics.recordException(e)
            }
        }
    }

    fun start() {
        synchronized(nativeLock) {
            if (mUseNative) {
                val img1 = mImage1
                if (animated && img1 != null && !img1.isRecycled) {
                    if (!started) {
                        started = true
                    } else {
                        img1.advance()
                    }
                }
                return
            }
            if (!started) {
                started = true
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    Api28ImageHelper.startIfAnimated(mObtainedDrawable)
                } else if (mObtainedDrawable is Animatable) {
                    (mObtainedDrawable as Animatable).start()
                }
            }
        }
    }

    val delay: Int
        get() = synchronized(nativeLock) {
            if (!animated) return 0
            if (mUseNative) {
                val d = mImage1?.takeIf { !it.isRecycled }?.delay ?: 100
                return d.coerceAtLeast(20)
            }
            return 10
        }

    @get:SuppressWarnings("deprecation")
    val isOpaque: Boolean
        get() = if (mUseNative) {
            mNativeOpaque
        } else {
            mObtainedDrawable?.opacity == PixelFormat.OPAQUE
        }

    private class NativeAnimatedDrawable(
        private val owner: Image,
        private val lock: Any,
    ) : Drawable(), Animatable, Runnable {
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val w = owner.width.coerceAtLeast(1)
        private val h = owner.height.coerceAtLeast(1)
        private var frameBitmap: Bitmap? = createBitmap(w, h)
        private var running = false
        private var disposed = false

        init {
            synchronized(lock) {
                val img1 = owner.mImage1
                val bmp = frameBitmap
                if (img1 != null && !img1.isRecycled && bmp != null && !bmp.isRecycled) {
                    img1.render(0, 0, bmp, 0, 0, w, h, false, 0)
                }
            }
        }

        override fun draw(canvas: Canvas) {
            val bmp = synchronized(lock) { frameBitmap }
            if (bmp != null && !bmp.isRecycled) {
                canvas.drawBitmap(bmp, null, bounds, paint)
            }
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
        }

        @Deprecated("Deprecated in Java")
        override fun getOpacity(): Int =
            if (owner.isOpaque) PixelFormat.OPAQUE else PixelFormat.TRANSLUCENT

        override fun getIntrinsicWidth(): Int = w

        override fun getIntrinsicHeight(): Int = h

        override fun setVisible(visible: Boolean, restart: Boolean): Boolean {
            val changed = super.setVisible(visible, restart)
            if (!visible) {
                MAIN_HANDLER.removeCallbacks(this)
            } else if (running) {
                MAIN_HANDLER.removeCallbacks(this)
                MAIN_HANDLER.postDelayed(this, currentDelayMs())
            }
            return changed
        }

        private fun currentDelayMs(): Long = synchronized(lock) {
            val img1 = owner.mImage1
            if (img1 != null && !img1.isRecycled) {
                img1.delay.coerceAtLeast(20).toLong()
            } else {
                100L
            }
        }

        override fun start() {
            if (disposed || running) return
            running = true
            MAIN_HANDLER.removeCallbacks(this)
            MAIN_HANDLER.postDelayed(this, currentDelayMs())
        }

        override fun stop() {
            running = false
            MAIN_HANDLER.removeCallbacks(this)
        }

        override fun isRunning(): Boolean = running

        override fun run() {
            var nextDelay = 100L
            var valid = false
            synchronized(lock) {
                val img1 = owner.mImage1
                val bmp = frameBitmap
                if (!disposed && running && img1 != null && !img1.isRecycled && bmp != null && !bmp.isRecycled) {
                    img1.advance()
                    img1.render(0, 0, bmp, 0, 0, w, h, false, 0)
                    nextDelay = img1.delay.coerceAtLeast(20).toLong()
                    valid = true
                } else {
                    running = false
                }
            }
            if (valid) {
                invalidateSelf()
                if (running && isVisible && callback != null) {
                    MAIN_HANDLER.postDelayed(this, nextDelay)
                }
            }
        }

        fun dispose() {
            disposed = true
            running = false
            MAIN_HANDLER.removeCallbacks(this)
            synchronized(lock) {
                frameBitmap?.recycle()
                frameBitmap = null
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.P)
    private object Api28ImageHelper {
        fun decode(source: FileInputStream, hardware: Boolean, simpleSize: Int?): Drawable {
            val src = ImageDecoder.createSource(
                source.channel.map(
                    FileChannel.MapMode.READ_ONLY, 0,
                    source.available().toLong()
                )
            )
            return try {
                ImageDecoder.decodeDrawable(src) { decoder: ImageDecoder, info: ImageInfo, _: Source ->
                    decoder.allocator = if (hardware) ALLOCATOR_DEFAULT else ALLOCATOR_SOFTWARE
                    val screenSize = min(
                        info.size.width / screenWidth.coerceAtLeast(1),
                        info.size.height / screenHeight.coerceAtLeast(1)
                    ).coerceAtLeast(1)
                    decoder.setTargetSampleSize(max(screenSize, simpleSize ?: 1))
                }
            } catch (e: DecodeException) {
                try {
                    source.channel.position(0)
                    decodeWithBitmapFactory(source, simpleSize)
                } catch (fallbackException: Exception) {
                    Analytics.recordException(fallbackException)
                    throw Exception("Android 9 解码失败", e)
                }
            }
        }

        fun isAnimated(drawable: Drawable?): Boolean = drawable is AnimatedImageDrawable

        fun stopIfAnimated(drawable: Drawable?) {
            if (drawable is AnimatedImageDrawable) {
                drawable.stop()
            }
        }

        fun startIfAnimated(drawable: Drawable?) {
            if (drawable is AnimatedImageDrawable) {
                drawable.start()
            }
        }
    }

    companion object {
        private val MAIN_HANDLER = Handler(Looper.getMainLooper())
        var screenWidth: Int = 0
        var screenHeight: Int = 0

        internal fun decodeStreamWithBitmapFactory(source: InputStream): Drawable {
            val res = EhApplication.getInstance()?.resources ?: Resources.getSystem()
            val bitmap = BitmapFactory.decodeStream(source)
                ?: throw IllegalArgumentException("BitmapFactory.decodeStream 返回空")
            return BitmapDrawable(res, bitmap)
        }

        internal fun decodeWithBitmapFactory(source: FileInputStream, simpleSize: Int?): Drawable {
            val res = EhApplication.getInstance()?.resources ?: Resources.getSystem()
            return if (simpleSize != null) {
                val option = BitmapFactory.Options().apply {
                    inSampleSize = simpleSize
                }
                val bitmap = BitmapFactory.decodeStream(source, null, option)
                    ?: throw IllegalArgumentException("BitmapFactory.decodeStream 返回空")
                BitmapDrawable(res, bitmap)
            } else {
                BitmapDrawable.createFromStream(source, null)
                    ?: throw IllegalArgumentException("BitmapDrawable.createFromStream 返回空")
            }
        }

        @JvmStatic
        fun initialize(ehApplication: EhApplication) {
            screenWidth = ehApplication.resources.displayMetrics.widthPixels
            screenHeight = ehApplication.resources.displayMetrics.heightPixels
            Image1.initialize(ehApplication)
        }

        @JvmStatic
        @JvmOverloads
        fun decode(stream: FileInputStream, hardware: Boolean = true): Image? {
            return decode(stream as InputStream, hardware)
        }

        @JvmStatic
        @JvmOverloads
        fun decode(stream: InputStream, hardware: Boolean = true): Image? {
            try {
                return Image(stream, hardware = hardware)
            } catch (e: Throwable) {
                e.printStackTrace()
                Analytics.recordException(e)
                return null
            }
        }

        @JvmStatic
        @JvmOverloads
        fun decode(drawable: Drawable?, hardware: Boolean = true): Image? {
            try {
                return Image(null, drawable, hardware = hardware)
            } catch (e: Throwable) {
                e.printStackTrace()
                Analytics.recordException(e)
                return null
            }
        }

        @JvmStatic
        fun create(bitmap: Bitmap): Image? {
            try {
                return Image(null, bitmap.toDrawable(Resources.getSystem()), false)
            } catch (e: Throwable) {
                e.printStackTrace()
                return null
            }
        }

        @JvmStatic
        private external fun nativeRender(
            bitmap: Bitmap,
            srcX: Int, srcY: Int, dst: Bitmap, dstX: Int, dstY: Int,
            width: Int, height: Int,
        )

        @JvmStatic
        private external fun nativeTexImage(
            bitmap: Bitmap,
            init: Boolean,
            offsetX: Int,
            offsetY: Int,
            width: Int,
            height: Int,
        )
    }
}
