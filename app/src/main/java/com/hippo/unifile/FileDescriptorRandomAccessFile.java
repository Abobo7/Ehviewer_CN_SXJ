/*
 * Copyright 2016 Hippo Seven
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hippo.unifile;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

class FileDescriptorRandomAccessFile implements UniRandomAccessFile {

    public static final int FLUSH_NONE = 0;
    public static final int FLUSH_FSYNC = 1;
    public static final int FLUSH_FDATASYNC = 2;
    private final ParcelFileDescriptor pfd;
    private final FileDescriptor fd;
    private final int flushAfterWrite;
    private final Object closeLock = new Object();
    private volatile boolean closed = false;

    // Pre-Lollipop (API 19-20) FileChannel fallback when android.system.Os is absent
    private final FileInputStream legacyIn;
    private final FileOutputStream legacyOut;
    private final FileChannel legacyChannel;

    private FileDescriptorRandomAccessFile(ParcelFileDescriptor pfd, FileDescriptor fd, String mode) {
        this.pfd = pfd;
        this.fd = fd;

        switch (mode) {
            case "rws":
                flushAfterWrite = FLUSH_FSYNC;
                break;
            case "rwd":
                flushAfterWrite = FLUSH_FDATASYNC;
                break;
            default:
                flushAfterWrite = FLUSH_NONE;
                break;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            if (mode != null && mode.contains("w")) {
                legacyIn = null;
                legacyOut = new FileOutputStream(fd);
                legacyChannel = legacyOut.getChannel();
            } else {
                legacyOut = null;
                legacyIn = new FileInputStream(fd);
                legacyChannel = legacyIn.getChannel();
            }
        } else {
            legacyIn = null;
            legacyOut = null;
            legacyChannel = null;
        }
    }

    @NonNull
    static FileDescriptorRandomAccessFile create(Context context, Uri uri, String mode) throws IOException {
        ParcelFileDescriptor pfd;
        try {
            pfd = context.getContentResolver().openFileDescriptor(uri, mode);
        } catch (Throwable e) {
            com.hippo.unifile.Utils.throwIfFatal(e);
            throw new IOException("Can't get ParcelFileDescriptor", e);
        }
        if (pfd == null) {
            throw new IOException("Can't get ParcelFileDescriptor");
        }
        FileDescriptor fd;
        try {
            fd = pfd.getFileDescriptor();
        } catch (SecurityException e) {
            pfd.close();
            throw new IOException("Can't get FileDescriptor", e);
        }
        if (fd == null) {
            pfd.close();
            throw new IOException("Can't get FileDescriptor");
        }

        return new FileDescriptorRandomAccessFile(pfd, fd, mode);
    }

    private void maybeSync() {
        if (flushAfterWrite == FLUSH_FSYNC) {
            try {
                fd.sync();
            } catch (IOException ignored) {
            }
        } else if (flushAfterWrite == FLUSH_FDATASYNC) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                Api21Os.fdatasyncQuietly(fd);
            } else {
                try {
                    fd.sync();
                } catch (IOException ignored) {
                }
            }
        }
    }

    @Override
    public void close() throws IOException {
        synchronized (closeLock) {
            if (closed) {
                return;
            }
            closed = true;
        }

        if (legacyChannel != null) {
            try {
                legacyChannel.close();
            } catch (IOException ignored) {
            }
        }
        if (legacyIn != null) {
            try {
                legacyIn.close();
            } catch (IOException ignored) {
            }
        }
        if (legacyOut != null) {
            try {
                legacyOut.close();
            } catch (IOException ignored) {
            }
        }

        pfd.close();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Api21Os.close(fd);
        }
    }

    @Override
    public long getFilePointer() throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return Api21Os.getFilePointer(fd);
        }
        return legacyChannel.position();
    }

    @Override
    public void seek(long pos) throws IOException {
        if (pos < 0) {
            throw new IOException("offset < 0: " + pos);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Api21Os.seek(fd, pos);
        } else {
            legacyChannel.position(pos);
        }
    }

    @Override
    public int skipBytes(int n) throws IOException {
        if (n <= 0) {
            return 0;
        }
        long pos = getFilePointer();
        long len = length();
        long newpos = pos + n;
        if (newpos > len) {
            newpos = len;
        }
        seek(newpos);
        return (int) (newpos - pos);
    }

    @Override
    public long length() throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return Api21Os.length(fd);
        }
        return legacyChannel.size();
    }

    @Override
    public void setLength(long newLength) throws IOException {
        if (newLength < 0) {
            throw new IllegalArgumentException("newLength < 0");
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Api21Os.ftruncate(fd, newLength);
        } else {
            legacyChannel.truncate(newLength);
        }

        long filePointer = getFilePointer();
        if (filePointer > newLength) {
            seek(newLength);
        }
        maybeSync();
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            return Api21Os.read(fd, b, off, len);
        }
        if (len == 0) {
            return 0;
        }
        int n = legacyChannel.read(ByteBuffer.wrap(b, off, len));
        return n < 0 ? 0 : n;
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Api21Os.write(fd, b, off, len);
        } else {
            ByteBuffer buf = ByteBuffer.wrap(b, off, len);
            while (buf.hasRemaining()) {
                legacyChannel.write(buf);
            }
        }
        maybeSync();
    }

    @RequiresApi(Build.VERSION_CODES.LOLLIPOP)
    private static final class Api21Os {
        static void fdatasyncQuietly(FileDescriptor fd) {
            try {
                Os.fdatasync(fd);
            } catch (ErrnoException ignored) {
            }
        }

        static void close(FileDescriptor fd) throws IOException {
            try {
                Os.close(fd);
            } catch (ErrnoException e) {
                throw new IOException("An ErrnoException occurs", e);
            }
        }

        static long getFilePointer(FileDescriptor fd) throws IOException {
            try {
                return Os.lseek(fd, 0L, OsConstants.SEEK_CUR);
            } catch (ErrnoException e) {
                throw new IOException("An ErrnoException occurs", e);
            }
        }

        static void seek(FileDescriptor fd, long pos) throws IOException {
            try {
                Os.lseek(fd, pos, OsConstants.SEEK_SET);
            } catch (ErrnoException e) {
                throw new IOException("An ErrnoException occurs", e);
            }
        }

        static long length(FileDescriptor fd) throws IOException {
            try {
                return Os.fstat(fd).st_size;
            } catch (ErrnoException e) {
                throw new IOException("An ErrnoException occurs", e);
            }
        }

        static void ftruncate(FileDescriptor fd, long newLength) throws IOException {
            try {
                Os.ftruncate(fd, newLength);
            } catch (ErrnoException e) {
                throw new IOException("An ErrnoException occurs", e);
            }
        }

        static int read(FileDescriptor fd, byte[] b, int off, int len) throws IOException {
            try {
                return Os.read(fd, b, off, len);
            } catch (ErrnoException e) {
                throw new IOException("An ErrnoException occurs", e);
            }
        }

        static void write(FileDescriptor fd, byte[] b, int off, int len) throws IOException {
            try {
                Os.write(fd, b, off, len);
            } catch (ErrnoException e) {
                throw new IOException("An ErrnoException occurs", e);
            }
        }
    }

    @IntDef({FLUSH_NONE, FLUSH_FSYNC, FLUSH_FDATASYNC})
    @Retention(RetentionPolicy.SOURCE)
    public @interface FlushMode {
    }
}
