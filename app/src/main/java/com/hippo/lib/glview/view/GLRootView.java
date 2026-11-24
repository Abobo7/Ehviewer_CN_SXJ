/*
 * Copyright (C) 2010 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.lib.glview.view;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Parcelable;
import android.os.Process;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.Display;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.SurfaceHolder;

import androidx.annotation.NonNull;

import com.hippo.lib.glview.anim.CanvasAnimation;
import com.hippo.lib.glview.glrenderer.BasicTexture;
import com.hippo.lib.glview.glrenderer.GLCanvas;
import com.hippo.lib.glview.glrenderer.GLES11Canvas;
import com.hippo.lib.glview.glrenderer.GLES20Canvas;
import com.hippo.lib.glview.glrenderer.UploadedTexture;
import com.hippo.ehviewer.Settings;
import com.hippo.ehviewer.util.ReadingRefreshRate;
import com.hippo.lib.glview.util.ApiHelper;
import com.hippo.lib.glview.util.GalleryUtils;
import com.hippo.lib.glview.util.MotionEventHelper;
import com.hippo.lib.yorozuya.AssertUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLContext;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.egl.EGLSurface;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.opengles.GL11;

// TODO Call attachToRoot and detachFromRoot in render thread
// The root component of all <code>GLView</code>s. The rendering is done in GL
// thread while the event handling is done in the main thread.  To synchronize
// the two threads, the entry points of this package need to synchronize on the
// <code>GLRootView</code> instance unless it can be proved that the rendering
// thread won't access the same thing as the method. The entry points include:
// (1) The public methods of HeadUpDisplay
// (2) The public methods of CameraHeadUpDisplay
// (3) The overridden methods in GLRootView.
public class GLRootView extends GLSurfaceView
        implements GLRoot {
    private static final String TAG = "GLRootView";

    private static final boolean DEBUG_FPS = false;
    private int mFrameCount = 0;
    private long mFrameCountingStart = 0;

    private static final boolean DEBUG_INVALIDATE = false;
    private int mInvalidateColor = 0;

    private static final boolean DEBUG_DRAWING_STAT = false;

    private static final int FLAG_INITIALIZED = 1;
    private static final int FLAG_NEED_LAYOUT = 2;

    private GL11 mGL;
    private GLCanvas mCanvas;
    private GLView mContentView;

    private OrientationSource mOrientationSource;
    // mCompensation is the difference between the UI orientation on GLCanvas
    // and the framework orientation. See OrientationManager for details.
    private int mCompensation;
    // mCompensationMatrix maps the coordinates of touch events. It is kept sync
    // with mCompensation.
    private final Matrix mCompensationMatrix = new Matrix();
    private int mDisplayRotation;

    private int mFlags = FLAG_NEED_LAYOUT;
    private volatile boolean mRenderRequested = false;

    private final ArrayList<CanvasAnimation> mAnimations =
            new ArrayList<>();

    private final ArrayDeque<OnGLIdleListener> mIdleListeners =
            new ArrayDeque<>();

    private final IdleRunner mIdleRunner = new IdleRunner();

    private final ReentrantLock mRenderLock = new ReentrantLock();
    private final Condition mFreezeCondition =
            mRenderLock.newCondition();
    private boolean mFreeze;

    private boolean mInDownState = false;
    private int mEGLContextClientVersion;

    public GLRootView(Context context) {
        this(context, null);
    }

    @SuppressWarnings("deprecation")
    public GLRootView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFlags |= FLAG_INITIALIZED;
        setBackgroundDrawable(null);

        mEGLContextClientVersion = 2;
        setEGLContextClientVersion(mEGLContextClientVersion);
        ConfigChooser configChooser = new ConfigChooser();
        setEGLConfigChooser(configChooser);
        setEGLContextFactory(configChooser);
        setEGLWindowSurfaceFactory(configChooser);
        setRenderer(new GLRootRenderer());
        getHolder().setFormat(PixelFormat.RGBA_8888);

        // Uncomment this to enable gl error check.
        // setDebugFlags(DEBUG_CHECK_GL_ERROR);
    }

    @Override
    public void registerLaunchedAnimation(CanvasAnimation animation) {
        // Register the newly launched animation so that we can set the start
        // time more precisely. (Usually, it takes much longer for first
        // rendering, so we set the animation start time as the time we
        // complete rendering)
        mAnimations.add(animation);
    }

    @Override
    public void addOnGLIdleListener(OnGLIdleListener listener) {
        synchronized (mIdleListeners) {
            mIdleListeners.addLast(listener);
            if (mCanvas != null) {
                // Wait for onSurfaceCreated
                mIdleRunner.enable();
            }
        }
    }

    @Override
    public void setContentPane(GLView content) {
        if (mContentView == content) return;
        if (mContentView != null) {
            if (mInDownState) {
                long now = SystemClock.uptimeMillis();
                MotionEvent cancelEvent = MotionEvent.obtain(
                        now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
                mContentView.dispatchTouchEvent(cancelEvent);
                cancelEvent.recycle();
                mInDownState = false;
            }
            mContentView.detachFromRoot();
            BasicTexture.yieldAllTextures();
        }
        mContentView = content;
        if (content != null) {
            content.attachToRoot(this);
            requestLayoutContentPane();
        }
    }

    @Override
    public void requestRenderForced() {
        superRequestRender();
    }

    @Override
    public void requestRender() {
        if (DEBUG_INVALIDATE) {
            StackTraceElement e = Thread.currentThread().getStackTrace()[4];
            String caller = e.getFileName() + ":" + e.getLineNumber() + " ";
            Log.d(TAG, "invalidate: " + caller);
        }
        if (mRenderRequested) return;
        mRenderRequested = true;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
            postOnAnimation(mRequestRenderOnAnimationFrame);
        } else {
            super.requestRender();
        }
    }

    private final Runnable mRequestRenderOnAnimationFrame = new Runnable() {
        @Override
        public void run() {
            superRequestRender();
        }
    };

    private void superRequestRender() {
        super.requestRender();
    }

    @Override
    public void requestLayoutContentPane() {
        mRenderLock.lock();
        try {
            if (mContentView == null || (mFlags & FLAG_NEED_LAYOUT) != 0) return;

            // "View" system will invoke onLayout() for initialization(bug ?), we
            // have to ignore it since the GLThread is not ready yet.
            if ((mFlags & FLAG_INITIALIZED) == 0) return;

            mFlags |= FLAG_NEED_LAYOUT;
            requestRender();
        } finally {
            mRenderLock.unlock();
        }
    }

    private void layoutContentPane() {
        mFlags &= ~FLAG_NEED_LAYOUT;

        int w = getWidth();
        int h = getHeight();
        int displayRotation;
        int compensation;

        // Get the new orientation values
        if (mOrientationSource != null) {
            displayRotation = mOrientationSource.getDisplayRotation();
            compensation = mOrientationSource.getCompensation();
        } else {
            displayRotation = 0;
            compensation = 0;
        }

        if (mCompensation != compensation) {
            mCompensation = compensation;
            if (mCompensation % 180 != 0) {
                mCompensationMatrix.setRotate(mCompensation);
                // move center to origin before rotation
                mCompensationMatrix.preTranslate(-w / 2, -h / 2);
                // align with the new origin after rotation
                mCompensationMatrix.postTranslate(h / 2, w / 2);
            } else {
                mCompensationMatrix.setRotate(mCompensation, w / 2, h / 2);
            }
        }
        mDisplayRotation = displayRotation;

        // Do the actual layout.
        if (mCompensation % 180 != 0) {
            int tmp = w;
            w = h;
            h = tmp;
        }
        Log.i(TAG, "layout content pane " + w + "x" + h + " (compensation " + mCompensation + ")");
        if (mContentView != null && w != 0 && h != 0) {
            mContentView.measure(GLView.MeasureSpec.makeMeasureSpec(w, GLView.MeasureSpec.EXACTLY),
                    GLView.MeasureSpec.makeMeasureSpec(h, GLView.MeasureSpec.EXACTLY));
            mContentView.layout(0, 0, w, h);
        }
        // Uncomment this to dump the view hierarchy.
        //mContentView.dumpTree("");
    }

    @Override
    protected void onLayout(
            boolean changed, int left, int top, int right, int bottom) {
        if (changed) requestLayoutContentPane();
    }

    private void outputFps() {
        long now = System.nanoTime();
        if (mFrameCountingStart == 0) {
            mFrameCountingStart = now;
        } else if ((now - mFrameCountingStart) > 1000000000) {
            Log.d(TAG, "fps: " + (double) mFrameCount
                    * 1000000000 / (now - mFrameCountingStart));
            mFrameCountingStart = now;
            mFrameCount = 0;
        }
        ++mFrameCount;
    }

    private void onDrawFrameLocked(GL10 gl) {
        if (DEBUG_FPS) outputFps();
        // release the unbound textures and deleted buffers.
        mCanvas.deleteRecycledResources();

        // reset texture upload limit
        UploadedTexture.resetUploadLimit();

        mRenderRequested = false;

        if ((mOrientationSource != null
                && mDisplayRotation != mOrientationSource.getDisplayRotation())
                || (mFlags & FLAG_NEED_LAYOUT) != 0) {
            layoutContentPane();
        }

        mCanvas.save(GLCanvas.SAVE_FLAG_ALL);
        rotateCanvas(-mCompensation);
        if (mContentView != null) {
           mContentView.render(mCanvas);
        } else {
            // Make sure we always draw something to prevent displaying garbage
            mCanvas.clearBuffer();
        }
        mCanvas.restore();

        if (!mAnimations.isEmpty()) {
            long now = AnimationTime.get();
            for (int i = 0, n = mAnimations.size(); i < n; i++) {
                mAnimations.get(i).setStartTime(now);
            }
            mAnimations.clear();
        }

        if (UploadedTexture.uploadLimitReached()) {
            requestRender();
        }

        synchronized (mIdleListeners) {
            if (!mIdleListeners.isEmpty()) mIdleRunner.enable();
        }

        if (DEBUG_INVALIDATE) {
            mCanvas.fillRect(10, 10, 5, 5, mInvalidateColor);
            mInvalidateColor = ~mInvalidateColor;
        }

        if (DEBUG_DRAWING_STAT) {
            mCanvas.dumpStatisticsAndClear();
        }
    }

    private void rotateCanvas(int degrees) {
        if (degrees == 0) return;
        int w = getWidth();
        int h = getHeight();
        int cx = w / 2;
        int cy = h / 2;
        mCanvas.translate(cx, cy);
        mCanvas.rotate(degrees, 0, 0, 1);
        if (degrees % 180 != 0) {
            mCanvas.translate(-cy, -cx);
        } else {
            mCanvas.translate(-cx, -cy);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;

        int action = event.getAction();
        if (action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_UP) {
            mInDownState = false;
        } else if (!mInDownState && action != MotionEvent.ACTION_DOWN) {
            return false;
        }

        if (mCompensation != 0) {
            event = MotionEventHelper.transformEvent(event, mCompensationMatrix);
        }

        mRenderLock.lock();
        try {
            // If this has been detached from root, we don't need to handle event
            boolean handled = mContentView != null
                    && mContentView.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_DOWN && handled) {
                mInDownState = true;
            }
            return handled;
        } finally {
            mRenderLock.unlock();
        }
    }

    @Override
    public void lockRenderThread() {
        mRenderLock.lock();
    }

    @Override
    public void unlockRenderThread() {
        mRenderLock.unlock();
    }

    @Override
    public void onPause() {
        unfreeze();
        super.onPause();
    }

    @Override
    public void setOrientationSource(OrientationSource source) {
        mOrientationSource = source;
    }

    @Override
    public int getDisplayRotation() {
        return mDisplayRotation;
    }

    @Override
    public int getCompensation() {
        return mCompensation;
    }

    @Override
    public Matrix getCompensationMatrix() {
        return mCompensationMatrix;
    }

    @Override
    public void freeze() {
        mRenderLock.lock();
        mFreeze = true;
        mRenderLock.unlock();
    }

    @Override
    public void unfreeze() {
        mRenderLock.lock();
        mFreeze = false;
        mFreezeCondition.signalAll();
        mRenderLock.unlock();
    }

    @Override
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    public void setLightsOutMode(boolean enabled) {
        if (!ApiHelper.HAS_SET_SYSTEM_UI_VISIBILITY) return;

        int flags = 0;
        if (enabled) {
            flags = STATUS_BAR_HIDDEN;
            if (ApiHelper.HAS_VIEW_SYSTEM_UI_FLAG_LAYOUT_STABLE) {
                flags |= (SYSTEM_UI_FLAG_FULLSCREEN | SYSTEM_UI_FLAG_LAYOUT_STABLE);
            }
        }
        setSystemUiVisibility(flags);
    }

    // We need to unfreeze in the following methods and in onPause().
    // These methods will wait on GLThread. If we have freezed the GLRootView,
    // the GLThread will wait on main thread to call unfreeze and cause dead
    // lock.
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        unfreeze();
        super.surfaceChanged(holder, format, w, h);
        applySurfaceFrameRate(holder);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        unfreeze();
        super.surfaceCreated(holder);
        applySurfaceFrameRate(holder);
    }

    private void applySurfaceFrameRate(SurfaceHolder holder) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        Surface surface = holder.getSurface();
        if (surface == null || !surface.isValid()) {
            return;
        }
        Display display = getDisplay();
        float hz = ReadingRefreshRate.resolveHz(display, Settings.getReadingRefreshRateHz());
        surface.setFrameRate(hz, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        unfreeze();
        super.surfaceDestroyed(holder);
    }

    @Override
    protected void onDetachedFromWindow() {
        unfreeze();
        super.onDetachedFromWindow();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void dispatchSaveInstanceState(@NonNull SparseArray<Parcelable> container) {
        super.dispatchSaveInstanceState(container);
        if (mContentView != null) {
            mContentView.saveHierarchyState(container);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void dispatchRestoreInstanceState(@NonNull SparseArray<Parcelable> container) {
        super.dispatchRestoreInstanceState(container);
        if (mContentView != null) {
            mContentView.restoreHierarchyState(container);
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            unfreeze();
        } finally {
            super.finalize();
        }
    }

    private class GLRootRenderer implements GLSurfaceView.Renderer {

        /**
         * Called when the context is created, possibly after automatic destruction.
         */
        @Override
        public void onSurfaceCreated(GL10 gl1, EGLConfig config) {
            GL11 gl = (GL11) gl1;
            if (mGL != null) {
                // The GL Object has changed
                Log.i(TAG, "GLObject has changed from " + mGL + " to " + gl);
            }
            mRenderLock.lock();
            try {
                mGL = gl;
                mCanvas = mEGLContextClientVersion == 2 ? new GLES20Canvas() : new GLES11Canvas(gl);
                BasicTexture.invalidateAllTextures();
            } finally {
                mRenderLock.unlock();
            }

            if (DEBUG_FPS) {
                setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
            } else {
                setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
            }
        }

        /**
         * Called when the OpenGL surface is recreated without destroying the
         * context.
         */
        @Override
        public void onSurfaceChanged(GL10 gl1, int width, int height) {
            Log.i(TAG, "onSurfaceChanged: " + width + "x" + height + ", gl10: " + gl1.toString());
            Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);
            GalleryUtils.setRenderThread();
            GL11 gl = (GL11) gl1;
            AssertUtils.assertTrue(mGL == gl);

            mCanvas.setSize(width, height);
        }

        @Override
        public void onDrawFrame(GL10 gl) {
            AnimationTime.update();

            long t0 = System.nanoTime();

            mRenderLock.lock();

            while (mFreeze) {
                mFreezeCondition.awaitUninterruptibly();
            }

            try {
                onDrawFrameLocked(gl);
            } finally {
                mRenderLock.unlock();
            }

            long t = System.nanoTime();
            long duration = (t - t0) / 1000000;

            if (duration > 50) {
                Log.v(TAG, "--- " + duration + " ---");
            }
        }
    }

    private class IdleRunner implements Runnable {
        // true if the idle runner is in the queue
        private boolean mActive = false;

        @Override
        public void run() {
            OnGLIdleListener listener;
            synchronized (mIdleListeners) {
                mActive = false;
                if (mIdleListeners.isEmpty()) return;
                listener = mIdleListeners.removeFirst();
            }
            mRenderLock.lock();
            boolean keepInQueue;
            try {
                keepInQueue = listener.onGLIdle(mCanvas, mRenderRequested);
            } finally {
                mRenderLock.unlock();
            }
            synchronized (mIdleListeners) {
                if (keepInQueue) mIdleListeners.addLast(listener);
                if (!mRenderRequested && !mIdleListeners.isEmpty()) enable();
            }
        }

        public void enable() {
            // Who gets the flag can add it to the queue
            if (mActive) return;
            mActive = true;
            queueEvent(this);
        }
    }

    // Select a verified window EGLConfig and honor the resolved GLES client version in createContext
    private class ConfigChooser implements EGLConfigChooser, EGLContextFactory, EGLWindowSurfaceFactory {

        private final int[] mValue = new int[1];
        private static final int EGL_CONTEXT_CLIENT_VERSION = 0x3098;
        private static final int EGL_OPENGL_ES2_BIT = 0x0004;
        private static final int EGL_WINDOW_BIT = 0x0004;
        private static final int EGL_NOT_INITIALIZED = 0x3001;

        @Override
        public EGLConfig chooseConfig(EGL10 egl, EGLDisplay display) {
            if (display == null || display == EGL10.EGL_NO_DISPLAY) {
                int error = egl.eglGetError();
                throw new IllegalArgumentException("Invalid EGL display, error: 0x" +
                        Integer.toHexString(error));
            }

            for (int attempt = 0; attempt < 2; attempt++) {
                if (!ensureDisplayInitialized(egl, display)) {
                    int error = egl.eglGetError();
                    throw new IllegalArgumentException("EGL display not initialized, error: 0x" +
                            Integer.toHexString(error));
                }

                try {
                    return chooseVerifiedConfig(egl, display);
                } catch (IllegalArgumentException e) {
                    int error = egl.eglGetError();
                    if (attempt == 0 && error == EGL_NOT_INITIALIZED) {
                        Log.w(TAG, "eglGetConfigs hit EGL_NOT_INITIALIZED, retrying eglInitialize once");
                        if (tryInitializeDisplay(egl, display)) {
                            continue;
                        }
                    }
                    throw e;
                }
            }

            throw new IllegalArgumentException("chooseConfig failed after reinitialize retry");
        }

        private boolean ensureDisplayInitialized(EGL10 egl, EGLDisplay display) {
            int[] numConfig = new int[1];
            if (egl.eglGetConfigs(display, null, 0, numConfig)) {
                return true;
            }
            int error = egl.eglGetError();
            if (error != EGL_NOT_INITIALIZED) {
                return false;
            }
            return tryInitializeDisplay(egl, display) &&
                    egl.eglGetConfigs(display, null, 0, numConfig);
        }

        private boolean tryInitializeDisplay(EGL10 egl, EGLDisplay display) {
            int[] version = new int[2];
            boolean initialized = egl.eglInitialize(display, version);
            if (!initialized) {
                int error = egl.eglGetError();
                Log.w(TAG, "eglInitialize retry failed, error: 0x" + Integer.toHexString(error));
            }
            return initialized;
        }

        private EGLConfig chooseVerifiedConfig(EGL10 egl, EGLDisplay display) {
            int[] num = new int[1];
            if (!egl.eglGetConfigs(display, null, 0, num) || num[0] <= 0) {
                throw new IllegalArgumentException("No EGL configs available");
            }
            int total = num[0];
            EGLConfig[] all = new EGLConfig[total];
            if (!egl.eglGetConfigs(display, all, total, num) || num[0] <= 0) {
                throw new IllegalArgumentException("eglGetConfigs returned empty");
            }
            int count = num[0];

            // Pass 1: OpenGL ES 2.0 window configs verified by live eglCreateContext probe.
            // Avoids EGL_BAD_CONFIG on GPUs (such as PowerVR GX6250) where omitting
            // EGL_RENDERABLE_TYPE defaults to EGL_OPENGL_ES_BIT (ES 1.1-only configs).
            EGLConfig bestEs2 = pickVerifiedConfig(egl, display, all, count, 2);
            if (bestEs2 != null) {
                mEGLContextClientVersion = 2;
                return bestEs2;
            }

            // Pass 2: OpenGL ES 1.1 fallback verified by live eglCreateContext probe.
            EGLConfig bestEs1 = pickVerifiedConfig(egl, display, all, count, 1);
            if (bestEs1 != null) {
                mEGLContextClientVersion = 1;
                Log.w(TAG, "Fell back to verified OpenGL ES 1.1 config");
                return bestEs1;
            }

            throw new IllegalArgumentException("No working EGLConfig found among " + count + " configs");
        }

        private EGLConfig pickVerifiedConfig(EGL10 egl, EGLDisplay display,
                                             EGLConfig[] all, int count, int version) {
            int[] idx = new int[count];
            int[] scores = new int[count];
            int n = 0;

            for (int i = 0; i < count; i++) {
                final EGLConfig c = all[i];
                if (c == null) continue;

                final int surf = findConfigAttrib(egl, display, c, EGL10.EGL_SURFACE_TYPE);
                if ((surf & EGL_WINDOW_BIT) == 0) continue;

                final int rt = findConfigAttrib(egl, display, c, EGL10.EGL_RENDERABLE_TYPE);
                if (version == 2 && (rt & EGL_OPENGL_ES2_BIT) == 0) continue;

                final int r = findConfigAttrib(egl, display, c, EGL10.EGL_RED_SIZE);
                final int g = findConfigAttrib(egl, display, c, EGL10.EGL_GREEN_SIZE);
                final int b = findConfigAttrib(egl, display, c, EGL10.EGL_BLUE_SIZE);
                final int a = findConfigAttrib(egl, display, c, EGL10.EGL_ALPHA_SIZE);
                if (r > 8 || g > 8 || b > 8 || a > 8) continue;

                final int cav = findConfigAttrib(egl, display, c, EGL10.EGL_CONFIG_CAVEAT);
                final int sb = findConfigAttrib(egl, display, c, EGL10.EGL_SAMPLE_BUFFERS);
                final int sp = findConfigAttrib(egl, display, c, EGL10.EGL_SAMPLES);
                final int d = findConfigAttrib(egl, display, c, EGL10.EGL_DEPTH_SIZE);
                final int st = findConfigAttrib(egl, display, c, EGL10.EGL_STENCIL_SIZE);
                final int vid = findConfigAttrib(egl, display, c, EGL10.EGL_NATIVE_VISUAL_ID);

                int score = 0;
                if (cav == EGL10.EGL_NONE) score += 20000;
                if (r == 8 && g == 8 && b == 8 && a == 8) score += 8000;
                else if (r == 8 && g == 8 && b == 8 && a == 0) score += 7000;
                else if (r == 5 && g == 6 && b == 5) score += 3000;
                else score += (r + g + b + a) * 50;

                if (vid == PixelFormat.RGBA_8888) score += 2000;
                else if (vid == PixelFormat.RGBX_8888) score += 1500;
                else if (vid == PixelFormat.RGB_565) score += 500;

                if (sb == 0 && sp == 0) score += 500;
                else score -= sp * 30;

                score -= (d + st);

                idx[n] = i;
                scores[n] = score;
                n++;
            }

            for (int i = 1; i < n; i++) {
                int ki = idx[i], ks = scores[i];
                int j = i - 1;
                while (j >= 0 && scores[j] < ks) {
                    idx[j + 1] = idx[j];
                    scores[j + 1] = scores[j];
                    j--;
                }
                idx[j + 1] = ki;
                scores[j + 1] = ks;
            }

            int[] attribs = (version == 2)
                    ? new int[]{EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE}
                    : null;
            for (int k = 0; k < n; k++) {
                EGLConfig cand = all[idx[k]];
                EGLContext ctx = egl.eglCreateContext(display, cand, EGL10.EGL_NO_CONTEXT, attribs);
                if (ctx != null && ctx != EGL10.EGL_NO_CONTEXT) {
                    egl.eglDestroyContext(display, ctx);
                    return cand;
                } else {
                    egl.eglGetError();
                }
            }
            return null;
        }

        @Override
        public EGLContext createContext(EGL10 egl, EGLDisplay display, EGLConfig config) {
            int ver = mEGLContextClientVersion;
            int[] attribs = (ver == 2)
                    ? new int[]{EGL_CONTEXT_CLIENT_VERSION, 2, EGL10.EGL_NONE}
                    : null;
            EGLContext ctx = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, attribs);
            if (ctx != null && ctx != EGL10.EGL_NO_CONTEXT) {
                return ctx;
            }
            if (ver == 2) {
                EGLContext ctx1 = egl.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, null);
                if (ctx1 != null && ctx1 != EGL10.EGL_NO_CONTEXT) {
                    mEGLContextClientVersion = 1;
                    return ctx1;
                }
            }
            return EGL10.EGL_NO_CONTEXT;
        }

        @Override
        public void destroyContext(EGL10 egl, EGLDisplay display, EGLContext context) {
            if (!egl.eglDestroyContext(display, context)) {
                Log.e(TAG, "eglDestroyContext failed: 0x" + Integer.toHexString(egl.eglGetError()));
            }
        }

        @Override
        public EGLSurface createWindowSurface(EGL10 egl, EGLDisplay display,
                                              EGLConfig config, Object nativeWindow) {
            try {
                return egl.eglCreateWindowSurface(display, config, nativeWindow, null);
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "eglCreateWindowSurface failed", e);
                return null;
            }
        }

        @Override
        public void destroySurface(EGL10 egl, EGLDisplay display, EGLSurface surface) {
            egl.eglDestroySurface(display, surface);
        }

        private int findConfigAttrib(EGL10 egl, EGLDisplay display,
                                     EGLConfig config, int attribute) {
            if (egl.eglGetConfigAttrib(display, config, attribute, mValue)) {
                return mValue[0];
            }
            return 0;
        }
    }
}
