const std = @import("std");
const c = @import("c");
const types = @import("evcam_types.zig");
const files = @import("evcam_files.zig");

const TAG = types.TAG;
const JNI_TRUE = types.JNI_TRUE;

pub const PlaybackThumbnailJni = struct {
    retriever_class: c.jclass,
    bitmap_class: c.jclass,
    compress_format_class: c.jclass,
    output_stream_class: c.jclass,
    retriever_ctor: c.jmethodID,
    retriever_set_data_source: c.jmethodID,
    retriever_get_scaled_frame: c.jmethodID,
    retriever_get_frame: c.jmethodID,
    retriever_release: c.jmethodID,
    bitmap_get_width: c.jmethodID,
    bitmap_get_height: c.jmethodID,
    bitmap_compress: c.jmethodID,
    bitmap_recycle: c.jmethodID,
    bitmap_create_scaled: c.jmethodID,
    output_stream_ctor: c.jmethodID,
    output_stream_close: c.jmethodID,
    jpeg_format: c.jobject,
};

fn loge(comptime fmt: []const u8, args: anytype) void {
    var buf: [512:0]u8 = [_:0]u8{0} ** 512;
    const msg = std.fmt.bufPrintSentinel(&buf, fmt, args, 0) catch "log format error";
    _ = c.__android_log_print(c.ANDROID_LOG_ERROR, TAG, "%s", msg.ptr);
}

fn clearJniException(env: [*c]c.JNIEnv, comptime context: []const u8) bool {
    if (env.*[0].ExceptionCheck.?(env) != JNI_TRUE) return false;
    env.*[0].ExceptionClear.?(env);
    loge("{s} threw Java exception", .{context});
    return true;
}

pub fn loadJni(env: [*c]c.JNIEnv) ?PlaybackThumbnailJni {
    const retriever_class = env.*[0].FindClass.?(env, "android/media/MediaMetadataRetriever") orelse {
        _ = clearJniException(env, "FindClass MediaMetadataRetriever");
        return null;
    };
    const bitmap_class = env.*[0].FindClass.?(env, "android/graphics/Bitmap") orelse {
        _ = clearJniException(env, "FindClass Bitmap");
        env.*[0].DeleteLocalRef.?(env, retriever_class);
        return null;
    };
    const compress_format_class = env.*[0].FindClass.?(env, "android/graphics/Bitmap$CompressFormat") orelse {
        _ = clearJniException(env, "FindClass Bitmap CompressFormat");
        env.*[0].DeleteLocalRef.?(env, retriever_class);
        env.*[0].DeleteLocalRef.?(env, bitmap_class);
        return null;
    };
    const output_stream_class = env.*[0].FindClass.?(env, "java/io/FileOutputStream") orelse {
        _ = clearJniException(env, "FindClass FileOutputStream");
        env.*[0].DeleteLocalRef.?(env, retriever_class);
        env.*[0].DeleteLocalRef.?(env, bitmap_class);
        env.*[0].DeleteLocalRef.?(env, compress_format_class);
        return null;
    };

    const retriever_ctor = env.*[0].GetMethodID.?(env, retriever_class, "<init>", "()V") orelse return null;
    const retriever_set_data_source = env.*[0].GetMethodID.?(env, retriever_class, "setDataSource", "(Ljava/lang/String;)V") orelse return null;
    const retriever_get_scaled_frame = env.*[0].GetMethodID.?(env, retriever_class, "getScaledFrameAtTime", "(JIII)Landroid/graphics/Bitmap;") orelse return null;
    const retriever_get_frame = env.*[0].GetMethodID.?(env, retriever_class, "getFrameAtTime", "(JI)Landroid/graphics/Bitmap;") orelse return null;
    const retriever_release = env.*[0].GetMethodID.?(env, retriever_class, "release", "()V") orelse return null;
    const bitmap_get_width = env.*[0].GetMethodID.?(env, bitmap_class, "getWidth", "()I") orelse return null;
    const bitmap_get_height = env.*[0].GetMethodID.?(env, bitmap_class, "getHeight", "()I") orelse return null;
    const bitmap_compress = env.*[0].GetMethodID.?(env, bitmap_class, "compress", "(Landroid/graphics/Bitmap$CompressFormat;ILjava/io/OutputStream;)Z") orelse return null;
    const bitmap_recycle = env.*[0].GetMethodID.?(env, bitmap_class, "recycle", "()V") orelse return null;
    const bitmap_create_scaled = env.*[0].GetStaticMethodID.?(env, bitmap_class, "createScaledBitmap", "(Landroid/graphics/Bitmap;IIZ)Landroid/graphics/Bitmap;") orelse return null;
    const output_stream_ctor = env.*[0].GetMethodID.?(env, output_stream_class, "<init>", "(Ljava/lang/String;)V") orelse return null;
    const output_stream_close = env.*[0].GetMethodID.?(env, output_stream_class, "close", "()V") orelse return null;
    const jpeg_field = env.*[0].GetStaticFieldID.?(env, compress_format_class, "JPEG", "Landroid/graphics/Bitmap$CompressFormat;") orelse return null;
    const jpeg_format = env.*[0].GetStaticObjectField.?(env, compress_format_class, jpeg_field) orelse return null;

    const retriever_global = env.*[0].NewGlobalRef.?(env, retriever_class) orelse {
        _ = clearJniException(env, "NewGlobalRef MediaMetadataRetriever");
        return null;
    };
    const bitmap_global = env.*[0].NewGlobalRef.?(env, bitmap_class) orelse {
        _ = clearJniException(env, "NewGlobalRef Bitmap");
        env.*[0].DeleteGlobalRef.?(env, retriever_global);
        return null;
    };
    const compress_format_global = env.*[0].NewGlobalRef.?(env, compress_format_class) orelse {
        _ = clearJniException(env, "NewGlobalRef Bitmap CompressFormat");
        env.*[0].DeleteGlobalRef.?(env, retriever_global);
        env.*[0].DeleteGlobalRef.?(env, bitmap_global);
        return null;
    };
    const output_stream_global = env.*[0].NewGlobalRef.?(env, output_stream_class) orelse {
        _ = clearJniException(env, "NewGlobalRef FileOutputStream");
        env.*[0].DeleteGlobalRef.?(env, retriever_global);
        env.*[0].DeleteGlobalRef.?(env, bitmap_global);
        env.*[0].DeleteGlobalRef.?(env, compress_format_global);
        return null;
    };
    const jpeg_global = env.*[0].NewGlobalRef.?(env, jpeg_format) orelse {
        _ = clearJniException(env, "NewGlobalRef JPEG CompressFormat");
        env.*[0].DeleteGlobalRef.?(env, retriever_global);
        env.*[0].DeleteGlobalRef.?(env, bitmap_global);
        env.*[0].DeleteGlobalRef.?(env, compress_format_global);
        env.*[0].DeleteGlobalRef.?(env, output_stream_global);
        return null;
    };
    env.*[0].DeleteLocalRef.?(env, jpeg_format);
    env.*[0].DeleteLocalRef.?(env, output_stream_class);
    env.*[0].DeleteLocalRef.?(env, compress_format_class);
    env.*[0].DeleteLocalRef.?(env, bitmap_class);
    env.*[0].DeleteLocalRef.?(env, retriever_class);

    return .{
        .retriever_class = @ptrCast(retriever_global),
        .bitmap_class = @ptrCast(bitmap_global),
        .compress_format_class = @ptrCast(compress_format_global),
        .output_stream_class = @ptrCast(output_stream_global),
        .retriever_ctor = retriever_ctor,
        .retriever_set_data_source = retriever_set_data_source,
        .retriever_get_scaled_frame = retriever_get_scaled_frame,
        .retriever_get_frame = retriever_get_frame,
        .retriever_release = retriever_release,
        .bitmap_get_width = bitmap_get_width,
        .bitmap_get_height = bitmap_get_height,
        .bitmap_compress = bitmap_compress,
        .bitmap_recycle = bitmap_recycle,
        .bitmap_create_scaled = bitmap_create_scaled,
        .output_stream_ctor = output_stream_ctor,
        .output_stream_close = output_stream_close,
        .jpeg_format = jpeg_global,
    };
}

pub fn releaseJni(env: [*c]c.JNIEnv, jni: *const PlaybackThumbnailJni) void {
    env.*[0].DeleteGlobalRef.?(env, jni.jpeg_format);
    env.*[0].DeleteGlobalRef.?(env, jni.output_stream_class);
    env.*[0].DeleteGlobalRef.?(env, jni.compress_format_class);
    env.*[0].DeleteGlobalRef.?(env, jni.bitmap_class);
    env.*[0].DeleteGlobalRef.?(env, jni.retriever_class);
}

fn recycleBitmap(env: [*c]c.JNIEnv, jni: *const PlaybackThumbnailJni, bitmap: c.jobject) void {
    if (bitmap == null) return;
    env.*[0].CallVoidMethodA.?(env, bitmap, jni.bitmap_recycle, null);
    _ = clearJniException(env, "Bitmap.recycle");
}

fn scaleBitmap(env: [*c]c.JNIEnv, jni: *const PlaybackThumbnailJni, bitmap: c.jobject) c.jobject {
    const width = env.*[0].CallIntMethodA.?(env, bitmap, jni.bitmap_get_width, null);
    if (clearJniException(env, "Bitmap.getWidth") or width <= 0) return bitmap;
    const height = env.*[0].CallIntMethodA.?(env, bitmap, jni.bitmap_get_height, null);
    if (clearJniException(env, "Bitmap.getHeight") or height <= 0) return bitmap;
    var target_width = width;
    var target_height = height;
    if (width > 320 or height > 180) {
        if (@as(i64, width) * 180 >= @as(i64, height) * 320) {
            target_width = 320;
            target_height = @max(1, @divTrunc(height * 320, width));
        } else {
            target_height = 180;
            target_width = @max(1, @divTrunc(width * 180, height));
        }
    }
    if (target_width == width and target_height == height) return bitmap;
    var args = [_]c.jvalue{
        .{ .l = bitmap },
        .{ .i = target_width },
        .{ .i = target_height },
        .{ .z = JNI_TRUE },
    };
    const scaled = env.*[0].CallStaticObjectMethodA.?(env, jni.bitmap_class, jni.bitmap_create_scaled, &args);
    if (clearJniException(env, "Bitmap.createScaledBitmap") or scaled == null) return bitmap;
    return scaled;
}

fn extractFrame(env: [*c]c.JNIEnv, jni: *const PlaybackThumbnailJni, retriever: c.jobject) c.jobject {
    const OPTION_CLOSEST: c.jint = 3;
    const times = [_]c.jlong{ 2_000_000, 4_000_000, 1_000_000, 0 };
    for (times) |time_us| {
        var scaled_args = [_]c.jvalue{
            .{ .j = time_us },
            .{ .i = OPTION_CLOSEST },
            .{ .i = 320 },
            .{ .i = 180 },
        };
        const scaled = env.*[0].CallObjectMethodA.?(env, retriever, jni.retriever_get_scaled_frame, &scaled_args);
        if (!clearJniException(env, "MediaMetadataRetriever.getScaledFrameAtTime") and scaled != null) return scaled;
        var args = [_]c.jvalue{
            .{ .j = time_us },
            .{ .i = OPTION_CLOSEST },
        };
        const frame = env.*[0].CallObjectMethodA.?(env, retriever, jni.retriever_get_frame, &args);
        if (clearJniException(env, "MediaMetadataRetriever.getFrameAtTime")) continue;
        if (frame != null) return frame;
    }
    return null;
}

pub fn generateWithJni(env: [*c]c.JNIEnv, jni: *const PlaybackThumbnailJni, video_path: [*c]const u8) bool {
    var target_path: [1024:0]u8 = [_:0]u8{0} ** 1024;
    if (!files.thumbnailPathForVideo(&target_path, video_path)) return false;
    if (files.fileSize(&target_path) > 0) return true;
    var temp_path: [1024:0]u8 = [_:0]u8{0} ** 1024;
    if (!files.appendPathSuffix(&temp_path, &target_path, ".tmp")) return false;
    _ = files.unlinkPath(&temp_path);

    const retriever = env.*[0].NewObjectA.?(env, jni.retriever_class, jni.retriever_ctor, null) orelse {
        _ = clearJniException(env, "MediaMetadataRetriever.new");
        return false;
    };
    defer env.*[0].DeleteLocalRef.?(env, retriever);
    defer {
        env.*[0].CallVoidMethodA.?(env, retriever, jni.retriever_release, null);
        _ = clearJniException(env, "MediaMetadataRetriever.release");
    }

    const video_string = env.*[0].NewStringUTF.?(env, video_path) orelse {
        _ = clearJniException(env, "NewStringUTF video path");
        return false;
    };
    defer env.*[0].DeleteLocalRef.?(env, video_string);
    var source_args = [_]c.jvalue{.{ .l = video_string }};
    env.*[0].CallVoidMethodA.?(env, retriever, jni.retriever_set_data_source, &source_args);
    if (clearJniException(env, "MediaMetadataRetriever.setDataSource")) return false;

    const frame = extractFrame(env, jni, retriever);
    if (frame == null) return false;
    defer env.*[0].DeleteLocalRef.?(env, frame);
    defer recycleBitmap(env, jni, frame);

    const bitmap = scaleBitmap(env, jni, frame);
    const bitmap_scaled = bitmap != frame;
    if (bitmap_scaled) {
        defer env.*[0].DeleteLocalRef.?(env, bitmap);
        defer recycleBitmap(env, jni, bitmap);
    }

    const temp_string = env.*[0].NewStringUTF.?(env, &temp_path) orelse {
        _ = clearJniException(env, "NewStringUTF thumbnail path");
        return false;
    };
    defer env.*[0].DeleteLocalRef.?(env, temp_string);
    var stream_args = [_]c.jvalue{.{ .l = temp_string }};
    const stream = env.*[0].NewObjectA.?(env, jni.output_stream_class, jni.output_stream_ctor, &stream_args) orelse {
        _ = clearJniException(env, "FileOutputStream.new");
        _ = files.unlinkPath(&temp_path);
        return false;
    };
    defer env.*[0].DeleteLocalRef.?(env, stream);
    var stream_closed = false;
    defer {
        if (!stream_closed) {
            env.*[0].CallVoidMethodA.?(env, stream, jni.output_stream_close, null);
            _ = clearJniException(env, "FileOutputStream.close");
        }
    }

    var compress_args = [_]c.jvalue{
        .{ .l = jni.jpeg_format },
        .{ .i = 82 },
        .{ .l = stream },
    };
    const compressed = env.*[0].CallBooleanMethodA.?(env, bitmap, jni.bitmap_compress, &compress_args);
    if (clearJniException(env, "Bitmap.compress") or compressed != JNI_TRUE) {
        _ = files.unlinkPath(&temp_path);
        return false;
    }
    env.*[0].CallVoidMethodA.?(env, stream, jni.output_stream_close, null);
    stream_closed = true;
    if (clearJniException(env, "FileOutputStream.close")) {
        _ = files.unlinkPath(&temp_path);
        return false;
    }
    if (files.fileSize(&temp_path) <= 0) {
        _ = files.unlinkPath(&temp_path);
        return false;
    }
    _ = files.unlinkPath(&target_path);
    if (files.renamePath(&temp_path, &target_path) != 0) {
        _ = files.unlinkPath(&temp_path);
        return false;
    }
    return files.fileSize(&target_path) > 0;
}
