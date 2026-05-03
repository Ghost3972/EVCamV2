
const std = @import("std");
const c = @import("c");

// Use Zig 0.16's std.Io namespace for future file/stream I/O task coordination;
// std.io was removed. NDK camera callbacks, EGL/GLES render ownership, and
// AMediaCodec surface rendering still use native workers plus explicit locks.
comptime { _ = std.Io; }

const TAG = "EVCamGLES";
const MAX_PIPES = 8;
const EGL_RECORDABLE_ANDROID: c.EGLint = 0x3142;
const GL_TEXTURE_EXTERNAL_OES: c.GLenum = 0x8D65;
const JNI_TRUE: c.jboolean = 1;
const JNI_FALSE: c.jboolean = 0;
const JNI_ABORT: c.jint = 2;
const CHECK_RENDER_GL_ERROR = false;
const TICK_SHOULD_RENDER: c.jlong = 1;
const TICK_DROPPED: c.jlong = 2;
const TICK_SEGMENT_DUE: c.jlong = 4;
const TICK_DRAINED_SHIFT = 8;
const TICK_DRAINED_MASK: c.jlong = 0x00FF_FFFF;
const TICK_NEXT_INDEX_SHIFT = 32;
const WORKER_ERROR_THREAD_ATTACH: c.jlong = -10;
const WORKER_ERROR_TICK_RENDER_DRAIN: c.jlong = -11;
const MAX_NATIVE_WRITERS = 4;
const MAX_NATIVE_CAMERAS = 4;
const COLOR_FORMAT_SURFACE: i32 = 0x7F000789;
const O_RDWR_ANDROID: c_int = 2;
const O_RDONLY_ANDROID: c_int = 0;
const O_CREAT_ANDROID: c_int = 64;
const O_TRUNC_ANDROID: c_int = 512;
const SAMPLE_BUFFER_BYTES: usize = 8 * 1024 * 1024;
const THUMBNAIL_WIDTH: usize = 240;
const THUMBNAIL_HEIGHT: usize = 135;
const SEEK_SET_ANDROID: c_int = 0;
const SEEK_END_ANDROID: c_int = 2;

extern fn open(path: [*c]const u8, flags: c_int, mode: c_int) c_int;
extern fn close(fd: c_int) c_int;
extern fn read(fd: c_int, buf: ?*anyopaque, count: usize) isize;
extern fn write(fd: c_int, buf: ?*const anyopaque, count: usize) isize;
extern fn lseek64(fd: c_int, offset: i64, whence: c_int) i64;
extern fn rename(oldpath: [*c]const u8, newpath: [*c]const u8) c_int;
extern fn unlink(path: [*c]const u8) c_int;
extern fn usleep(usec: c_uint) c_int;
extern fn malloc(size: usize) ?*anyopaque;
extern fn free(ptr: ?*anyopaque) void;

const EglPresentationTimeAndroidFn = *const fn (c.EGLDisplay, c.EGLSurface, c.EGLnsecsANDROID) callconv(.c) c.EGLBoolean;

const NativeSegmentWriter = struct {
    lock: std.Io.Mutex = .init,
    handle: c.jlong = 0,
    codec: ?*c.AMediaCodec = null,
    muxer: ?*c.AMediaMuxer = null,
    input_window: ?*c.ANativeWindow = null,
    fd: c_int = -1,
    output_path: [1024:0]u8 = [_:0]u8{0} ** 1024,
    output_path_set: bool = false,
    track_index: c_int = -1,
    width: i32 = 0,
    height: i32 = 0,
    fps: i32 = 0,
    bitrate: i32 = 0,
    started: bool = false,
    muxer_started: bool = false,
    writer_lock_wait_total_ms: i64 = 0,
    writer_lock_wait_max_ms: i64 = 0,
    drain_calls: i64 = 0,
    drain_samples: i64 = 0,
    drain_total_ms: i64 = 0,
    drain_max_ms: i64 = 0,
    muxer_write_total_ms: i64 = 0,
    muxer_write_max_ms: i64 = 0,
};

const NativeCameraPreview = struct {
    handle: c.jlong = 0,
    manager: ?*c.ACameraManager = null,
    device: ?*c.ACameraDevice = null,
    session: ?*c.ACameraCaptureSession = null,
    window: ?*c.ANativeWindow = null,
    outputs: ?*c.ACaptureSessionOutputContainer = null,
    output: ?*c.ACaptureSessionOutput = null,
    target: ?*c.ACameraOutputTarget = null,
    request: ?*c.ACaptureRequest = null,
    sequence_id: c_int = -1,
};

const Input = struct {
    surface_texture: c.jobject = null,
    surface_texture_native: ?*c.ASurfaceTexture = null,
    texture: c.GLuint = 0,
    dirty: bool = false,
    has_latched_frame: bool = false,
    preview_pending: bool = false,
    frame_generation: i64 = 0,
    latched_generation: i64 = 0,
    preview_generation: i64 = 0,
    encoder_generation: i64 = 0,
    dirty_count: i64 = 0,
    update_count: i64 = 0,
    frame_signal_count: i64 = 0,
    preview_scheduled_count: i64 = 0,
    preview_coalesced_count: i64 = 0,
    preview_delayed_count: i64 = 0,
    preview_render_count: i64 = 0,
    preview_drop_count: i64 = 0,
    preview_swap_ms: i64 = 0,
    last_preview_render_ms: i64 = 0,
    last_preview_error: i64 = 0,
};

const Quad = struct {
    verts: [8]c.GLfloat = [_]c.GLfloat{0} ** 8,
    tex: [8]c.GLfloat = [_]c.GLfloat{ 0, 0, 1, 0, 0, 1, 1, 1 },
};

const OverlayBatch = struct {
    verts: [2048]c.GLfloat = [_]c.GLfloat{0} ** 2048,
    len: usize = 0,
};

const TexturedOverlayBatch = struct {
    verts: [4096]c.GLfloat = [_]c.GLfloat{0} ** 4096,
    tex: [4096]c.GLfloat = [_]c.GLfloat{0} ** 4096,
    len: usize = 0,
};

const RecordingState = struct {
    recording: bool = false,
    segment_switch_pending: bool = false,
    generation: c.jlong = 0,
    fps: i32 = 15,
    segment_index: i32 = 0,
    pending_segment_index: i32 = 0,
    segment_duration_ms: i64 = 60000,
    next_segment_wall_clock_ms: i64 = 0,
    pending_segment_wall_clock_ms: i64 = 0,
    requested_frames: i64 = 0,
    rendered_frames: i64 = 0,
    dropped_frames: i64 = 0,
    last_tick_steady_ms: i64 = 0,
    encoder_segment_start_steady_ms: i64 = 0,
    last_presentation_time_ns: i64 = -1,
    overlay_wall_clock_ms: i64 = 0,
    overlay_cached_second: i64 = -1,
    overlay_text: [24]u8 = [_]u8{0} ** 24,
    overlay_text_len: usize = 0,
    overlay_geometry_second: i64 = -1,
    overlay_geometry_width: i32 = 0,
    overlay_geometry_height: i32 = 0,
    overlay_bg_batch: OverlayBatch = OverlayBatch{},
    overlay_shadow_text_batch: TexturedOverlayBatch = TexturedOverlayBatch{},
    overlay_text_batch: TexturedOverlayBatch = TexturedOverlayBatch{},
    thumbnail_path: [1024:0]u8 = [_:0]u8{0} ** 1024,
    thumbnail_path_set: bool = false,
    thumbnail_written: bool = false,
};

const Pipe = struct {
    lock: std.Io.Mutex = .init,
    handle: c.jlong = 0,
    display: c.EGLDisplay = c.EGL_NO_DISPLAY,
    context: c.EGLContext = c.EGL_NO_CONTEXT,
    config: c.EGLConfig = null,
    pbuffer: c.EGLSurface = c.EGL_NO_SURFACE,
    current_surface: c.EGLSurface = c.EGL_NO_SURFACE,
    preview_surface: [4]c.EGLSurface = [_]c.EGLSurface{ c.EGL_NO_SURFACE, c.EGL_NO_SURFACE, c.EGL_NO_SURFACE, c.EGL_NO_SURFACE },
    preview_apply_fisheye: [4]bool = [_]bool{true} ** 4,
    preview_apply_native_transform: [4]bool = [_]bool{true} ** 4,
    encoder_surface: c.EGLSurface = c.EGL_NO_SURFACE,
    preview_window: [4]?*c.ANativeWindow = [_]?*c.ANativeWindow{ null, null, null, null },
    encoder_window: ?*c.ANativeWindow = null,
    encoder_generation: c.jlong = 0,
    input: [4]Input = [_]Input{ Input{}, Input{}, Input{}, Input{} },
    encoder_quad: [4]Quad = [_]Quad{ Quad{}, Quad{}, Quad{}, Quad{} },
    preview_quad: [4]Quad = [_]Quad{ Quad{}, Quad{}, Quad{}, Quad{} },
    preview_quad_width: [4]i32 = [_]i32{0} ** 4,
    preview_quad_height: [4]i32 = [_]i32{0} ** 4,
    config_version: i64 = 0,
    program: c.GLuint = 0,
    overlay_program: c.GLuint = 0,
    overlay_text_program: c.GLuint = 0,
    overlay_font_texture: c.GLuint = 0,
    pos_loc: c.GLint = -1,
    tex_loc: c.GLint = -1,
    sampler_loc: c.GLint = -1,
    overlay_pos_loc: c.GLint = -1,
    overlay_color_loc: c.GLint = -1,
    overlay_text_pos_loc: c.GLint = -1,
    overlay_text_tex_loc: c.GLint = -1,
    overlay_text_sampler_loc: c.GLint = -1,
    overlay_text_color_loc: c.GLint = -1,
    fisheye_enabled_loc: c.GLint = -1,
    k1_loc: c.GLint = -1,
    k2_loc: c.GLint = -1,
    zoom_loc: c.GLint = -1,
    center_loc: c.GLint = -1,
    width: i32 = 1280,
    height: i32 = 720,
    layout_mode: i32 = 0,
    side_left_rotation: i32 = 270,
    side_right_rotation: i32 = 90,
    overlay_enabled: bool = true,
    encoder_fps: i32 = 15,
    encoder_pending: bool = false,
    recording_worker_running: bool = false,
    recording_worker_stop: bool = false,
    recording_worker_paused_for_segment: bool = false,
    recording_worker_writer_handle: c.jlong = 0,
    recording_worker_last_event: c.jlong = 0,
    recording_worker_generation: c.jlong = 0,
    recording_worker_thread: ?std.Thread = null,
    recording_worker_condition: std.Io.Condition = .init,
    preview_worker_running: bool = false,
    preview_worker_stop: bool = false,
    preview_worker_generation: c.jlong = 0,
    preview_worker_thread: ?std.Thread = null,
    recording: RecordingState = RecordingState{},
    encoder_frame_index: i64 = 0,
    encoder_signal_count: i64 = 0,
    encoder_scheduled_count: i64 = 0,
    encoder_coalesced_count: i64 = 0,
    render_count: i64 = 0,
    preview_render_count: i64 = 0,
    encoder_render_count: i64 = 0,
    encoder_drop_count: i64 = 0,
    dropped_count: i64 = 0,
    no_surface_count: i64 = 0,
    last_render_ms: i64 = 0,
    preview_max_fps: i32 = 0,
    preview_min_interval_ms: i64 = 0,
    last_render_error: [128:0]u8 = initZ("OK"),
    fisheye_enabled: [4]bool = [_]bool{false} ** 4,
    fisheye_k1: [4]f32 = [_]f32{0.35} ** 4,
    fisheye_k2: [4]f32 = [_]f32{0.10} ** 4,
    fisheye_zoom: [4]f32 = [_]f32{1.15} ** 4,
    fisheye_center_x: [4]f32 = [_]f32{0.5} ** 4,
    fisheye_center_y: [4]f32 = [_]f32{0.5} ** 4,
};

fn initZ(comptime s: []const u8) [128:0]u8 {
    var out: [128:0]u8 = [_:0]u8{0} ** 128;
    @memcpy(out[0..s.len], s);
    return out;
}

var g_io_instance: std.Io.Threaded = .init_single_threaded;
var g_io_threaded_ready: bool = false;
var g_lock: std.Io.Mutex = .init;
var g_pipes: [MAX_PIPES]Pipe = [_]Pipe{Pipe{}} ** MAX_PIPES;
var g_used: [MAX_PIPES]bool = [_]bool{false} ** MAX_PIPES;
var g_next_handle: c.jlong = 1;
var g_last_error: [256:0]u8 = initError("OK");
var g_error_scratch: [128:0]u8 = [_:0]u8{0} ** 128;
var g_presentation_time_android: ?EglPresentationTimeAndroidFn = null;
var g_native_writers: [MAX_NATIVE_WRITERS]NativeSegmentWriter = [_]NativeSegmentWriter{NativeSegmentWriter{}} ** MAX_NATIVE_WRITERS;
var g_native_writer_used: [MAX_NATIVE_WRITERS]bool = [_]bool{false} ** MAX_NATIVE_WRITERS;
var g_next_native_writer_handle: c.jlong = 1;
var g_native_cameras: [MAX_NATIVE_CAMERAS]NativeCameraPreview = [_]NativeCameraPreview{NativeCameraPreview{}} ** MAX_NATIVE_CAMERAS;
var g_native_camera_used: [MAX_NATIVE_CAMERAS]bool = [_]bool{false} ** MAX_NATIVE_CAMERAS;
var g_next_native_camera_handle: c.jlong = 1;
var g_java_vm: [*c]c.JavaVM = null;

export fn JNI_OnLoad(vm: [*c]c.JavaVM, _: ?*anyopaque) callconv(.c) c.jint {
    g_java_vm = vm;
    if (!g_io_threaded_ready) {
        g_io_instance = std.Io.Threaded.init(std.heap.c_allocator, .{
            .async_limit = .limited(2),
            .concurrent_limit = .limited(2),
        });
        g_io_threaded_ready = true;
    }
    return 0x00010006;
}

export fn JNI_OnUnload(_: [*c]c.JavaVM, _: ?*anyopaque) callconv(.c) void {
    if (g_io_threaded_ready) {
        g_io_instance.deinit();
        g_io_instance = .init_single_threaded;
        g_io_threaded_ready = false;
    }
    g_java_vm = null;
}

fn initError(comptime s: []const u8) [256:0]u8 {
    var out: [256:0]u8 = [_:0]u8{0} ** 256;
    @memcpy(out[0..s.len], s);
    return out;
}


fn nativeIo() std.Io {
    return g_io_instance.io();
}

fn lockGlobal() void {
    g_lock.lockUncancelable(nativeIo());
}

fn tryLockGlobal() bool {
    return g_lock.tryLock();
}

fn tryLockGlobalBounded(iterations: usize) bool {
    for (0..iterations) |_| {
        if (tryLockGlobal()) return true;
    }
    return false;
}

fn unlockGlobal() void {
    g_lock.unlock(nativeIo());
}

fn lockPipe(p: *Pipe) void {
    p.lock.lockUncancelable(nativeIo());
}

fn tryLockPipe(p: *Pipe) bool {
    return p.lock.tryLock();
}

fn tryLockPipeBounded(p: *Pipe, iterations: usize) bool {
    for (0..iterations) |_| {
        if (tryLockPipe(p)) return true;
    }
    return false;
}

fn unlockPipe(p: *Pipe) void {
    p.lock.unlock(nativeIo());
}

fn lockPipeForHandle(handle: c.jlong) ?*Pipe {
    lockGlobal();
    const p = getPipe(handle) orelse {
        unlockGlobal();
        return null;
    };
    lockPipe(p);
    unlockGlobal();
    return p;
}

fn tryLockPipeForHandleBounded(handle: c.jlong, iterations: usize) ?*Pipe {
    if (!tryLockGlobalBounded(iterations)) return null;
    const p = getPipe(handle) orelse {
        unlockGlobal();
        return null;
    };
    if (!tryLockPipeBounded(p, iterations)) {
        unlockGlobal();
        return null;
    }
    unlockGlobal();
    return p;
}

fn lockWriter(w: *NativeSegmentWriter) void {
    w.lock.lockUncancelable(nativeIo());
}

fn unlockWriter(w: *NativeSegmentWriter) void {
    w.lock.unlock(nativeIo());
}

fn lockWriterForHandle(handle: c.jlong) ?*NativeSegmentWriter {
    const wait_start_ms = nowMs();
    lockGlobal();
    const w = getNativeWriter(handle) orelse {
        unlockGlobal();
        return null;
    };
    lockWriter(w);
    const wait_ms = nowMs() - wait_start_ms;
    if (wait_ms > 0) {
        w.writer_lock_wait_total_ms += wait_ms;
        if (wait_ms > w.writer_lock_wait_max_ms) w.writer_lock_wait_max_ms = wait_ms;
    }
    unlockGlobal();
    return w;
}

fn attachWorkerEnv() ?[*c]c.JNIEnv {
    if (g_java_vm == null) return null;
    var env: [*c]c.JNIEnv = null;
    const rc = g_java_vm.*[0].AttachCurrentThread.?(g_java_vm, &env, null);
    if (rc != 0 or env == null) return null;
    return env;
}

fn detachWorkerEnv() void {
    if (g_java_vm != null) _ = g_java_vm.*[0].DetachCurrentThread.?(g_java_vm);
}

fn sleepMs(ms: u64) void {
    const capped_ms = @min(ms, 60_000);
    const duration: std.Io.Clock.Duration = .{
        .raw = .fromMilliseconds(@intCast(capped_ms)),
        .clock = .awake,
    };
    duration.sleep(nativeIo()) catch {
        _ = usleep(@intCast(capped_ms * 1000));
    };
}

fn loge(comptime fmt: []const u8, args: anytype) void {
    var buf: [512:0]u8 = [_:0]u8{0} ** 512;
    const msg = std.fmt.bufPrintSentinel(&buf, fmt, args, 0) catch "log format error";
    _ = c.__android_log_print(c.ANDROID_LOG_ERROR, TAG, "%s", msg.ptr);
}

fn logd(comptime fmt: []const u8, args: anytype) void {
    var buf: [512:0]u8 = [_:0]u8{0} ** 512;
    const msg = std.fmt.bufPrintSentinel(&buf, fmt, args, 0) catch "log format error";
    _ = c.__android_log_print(c.ANDROID_LOG_DEBUG, TAG, "%s", msg.ptr);
}

fn logi(comptime fmt: []const u8, args: anytype) void {
    var buf: [512:0]u8 = [_:0]u8{0} ** 512;
    const msg = std.fmt.bufPrintSentinel(&buf, fmt, args, 0) catch "log format error";
    _ = c.__android_log_print(c.ANDROID_LOG_INFO, TAG, "%s", msg.ptr);
}

fn setError(comptime fmt: []const u8, args: anytype) void {
    @memset(g_last_error[0..], 0);
    const msg = std.fmt.bufPrintSentinel(&g_last_error, fmt, args, 0) catch "error format failed";
    loge("{s}", .{msg});
}

fn setErrorSlice(msg: [:0]const u8) void {
    @memset(g_last_error[0..], 0);
    const n = @min(msg.len, g_last_error.len - 1);
    @memcpy(g_last_error[0..n], msg[0..n]);
    loge("{s}", .{msg});
}

fn copyCStringToBuffer(dst: []u8, src: [*c]const u8) bool {
    const value = std.mem.span(src);
    if (value.len >= dst.len) return false;
    @memset(dst, 0);
    @memcpy(dst[0..value.len], value);
    return true;
}

fn writeAllFd(fd: c_int, data: []const u8) bool {
    var written: usize = 0;
    while (written < data.len) {
        const rc = write(fd, data.ptr + written, data.len - written);
        if (rc <= 0) return false;
        written += @intCast(rc);
    }
    return true;
}

fn bmpPathForVideo(dst: *[1024:0]u8, video_path: [*c]const u8) bool {
    const path = std.mem.span(video_path);
    var base_len = path.len;
    if (std.mem.endsWith(u8, path, ".mp4")) base_len = path.len - 4;
    if (base_len + 4 >= dst.len) return false;
    @memset(dst, 0);
    @memcpy(dst[0..base_len], path[0..base_len]);
    @memcpy(dst[base_len..][0..4], ".bmp");
    return true;
}

fn copyFileNative(src_path: [*c]const u8, dst_path: [*c]const u8) bool {
    const src_fd = open(src_path, O_RDONLY_ANDROID, 0);
    if (src_fd < 0) return false;
    defer _ = close(src_fd);

    const dst_fd = open(dst_path, O_CREAT_ANDROID | O_TRUNC_ANDROID | O_RDWR_ANDROID, 0o644);
    if (dst_fd < 0) return false;
    defer _ = close(dst_fd);

    var buffer: [16 * 1024]u8 = undefined;
    while (true) {
        const n = read(src_fd, &buffer, buffer.len);
        if (n < 0) return false;
        if (n == 0) break;
        if (!writeAllFd(dst_fd, buffer[0..@intCast(n)])) return false;
    }
    return true;
}

fn putLe16(buf: []u8, offset: usize, value: u16) void {
    buf[offset] = @intCast(value & 0xff);
    buf[offset + 1] = @intCast((value >> 8) & 0xff);
}

fn putLe32(buf: []u8, offset: usize, value: u32) void {
    buf[offset] = @intCast(value & 0xff);
    buf[offset + 1] = @intCast((value >> 8) & 0xff);
    buf[offset + 2] = @intCast((value >> 16) & 0xff);
    buf[offset + 3] = @intCast((value >> 24) & 0xff);
}

fn writeFirstFrameThumbnailBmpLocked(p: *Pipe) void {
    if (!p.recording.thumbnail_path_set or p.recording.thumbnail_written) return;
    if (p.width <= 0 or p.height <= 0) return;

    const src_w: usize = @intCast(p.width);
    const src_h: usize = @intCast(p.height);
    const src_size = src_w * src_h * 4;
    const raw = malloc(src_size) orelse {
        loge("thumbnail malloc failed bytes={d}", .{src_size});
        return;
    };
    defer free(raw);
    const rgba: [*]u8 = @ptrCast(raw);

    c.glPixelStorei(c.GL_PACK_ALIGNMENT, 1);
    c.glReadPixels(0, 0, p.width, p.height, c.GL_RGBA, c.GL_UNSIGNED_BYTE, raw);
    if (glError("thumbnail glReadPixels")) |e| {
        loge("thumbnail read failed {s}", .{e});
        return;
    }

    const thumb_w = @min(THUMBNAIL_WIDTH, src_w);
    const thumb_h = @min(THUMBNAIL_HEIGHT, src_h);
    const row_stride = ((thumb_w * 3 + 3) / 4) * 4;
    const image_size = row_stride * thumb_h;
    const file_size = 54 + image_size;

    var header: [54]u8 = [_]u8{0} ** 54;
    header[0] = 'B';
    header[1] = 'M';
    putLe32(header[0..], 2, @intCast(file_size));
    putLe32(header[0..], 10, 54);
    putLe32(header[0..], 14, 40);
    putLe32(header[0..], 18, @intCast(thumb_w));
    putLe32(header[0..], 22, @intCast(thumb_h));
    putLe16(header[0..], 26, 1);
    putLe16(header[0..], 28, 24);
    putLe32(header[0..], 34, @intCast(image_size));

    const fd = open(&p.recording.thumbnail_path, O_CREAT_ANDROID | O_TRUNC_ANDROID | O_RDWR_ANDROID, 0o644);
    if (fd < 0) {
        loge("thumbnail open failed path={s}", .{std.mem.sliceTo(&p.recording.thumbnail_path, 0)});
        return;
    }
    defer _ = close(fd);
    if (!writeAllFd(fd, header[0..])) return;

    const row_raw = malloc(row_stride) orelse return;
    defer free(row_raw);
    const row: [*]u8 = @ptrCast(row_raw);

    var y: usize = 0;
    while (y < thumb_h) : (y += 1) {
        @memset(row[0..row_stride], 0);
        const src_y = (y * src_h) / thumb_h;
        var x: usize = 0;
        while (x < thumb_w) : (x += 1) {
            const src_x = (x * src_w) / thumb_w;
            const src = (src_y * src_w + src_x) * 4;
            const dst = x * 3;
            row[dst] = rgba[src + 2];
            row[dst + 1] = rgba[src + 1];
            row[dst + 2] = rgba[src];
        }
        if (!writeAllFd(fd, row[0..row_stride])) return;
    }
    p.recording.thumbnail_written = true;
    logi("thumbnail generated path={s} size={d}x{d}", .{ std.mem.sliceTo(&p.recording.thumbnail_path, 0), thumb_w, thumb_h });
}

fn eglError(what: []const u8) [:0]const u8 {
    @memset(g_error_scratch[0..], 0);
    return std.fmt.bufPrintSentinel(&g_error_scratch, "{s} egl=0x{x:0>4}", .{ what, c.eglGetError() }, 0) catch "egl error format failed";
}

fn glError(what: []const u8) ?[:0]const u8 {
    const err = c.glGetError();
    if (err == c.GL_NO_ERROR) return null;
    @memset(g_error_scratch[0..], 0);
    return std.fmt.bufPrintSentinel(&g_error_scratch, "{s} gl=0x{x:0>4}", .{ what, err }, 0) catch "gl error format failed";
}

const Timespec = extern struct { tv_sec: i64, tv_nsec: i64 };
extern fn clock_gettime(clock_id: c_int, ts: *Timespec) c_int;
const CLOCK_REALTIME: c_int = 0;
const CLOCK_MONOTONIC: c_int = 1;

fn nowMs() i64 {
    var ts: Timespec = undefined;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
    return ts.tv_sec * 1000 + @divTrunc(ts.tv_nsec, 1_000_000);
}

fn wallClockMs() i64 {
    var ts: Timespec = undefined;
    if (clock_gettime(CLOCK_REALTIME, &ts) != 0) return nowMs();
    return ts.tv_sec * 1000 + @divTrunc(ts.tv_nsec, 1_000_000);
}

fn newString(env: [*c]c.JNIEnv, s: [*:0]const u8) c.jstring {
    return env.*[0].NewStringUTF.?(env, s);
}

fn getArrayLen(env: [*c]c.JNIEnv, arr: anytype) c.jsize {
    return env.*[0].GetArrayLength.?(env, arr);
}

fn clearCurrent(p: *Pipe) void {
    if (p.display != c.EGL_NO_DISPLAY) _ = c.eglMakeCurrent(p.display, c.EGL_NO_SURFACE, c.EGL_NO_SURFACE, c.EGL_NO_CONTEXT);
    p.current_surface = c.EGL_NO_SURFACE;
}

const VERT = "attribute vec4 aPosition;attribute vec2 aTexCoord;varying vec2 vTexCoord;void main(){gl_Position=aPosition;vTexCoord=aTexCoord;}";
const FRAG = "#extension GL_OES_EGL_image_external : require\n" ++
    "precision mediump float;varying vec2 vTexCoord;uniform samplerExternalOES uTexture;uniform int uFisheyeEnabled;uniform float uK1;uniform float uK2;uniform float uZoom;uniform vec2 uCenter;" ++
    "void main(){ if(uFisheyeEnabled==0){gl_FragColor=texture2D(uTexture,vTexCoord);return;} vec2 coord=(vTexCoord-uCenter)/uZoom; float r2=dot(coord,coord); float r4=r2*r2; float distortion=1.0+uK1*r2+uK2*r4; vec2 corrected=coord*distortion+uCenter; if(corrected.x<0.0||corrected.x>1.0||corrected.y<0.0||corrected.y>1.0){ gl_FragColor=vec4(0.0,0.0,0.0,1.0); }else{ gl_FragColor=texture2D(uTexture,corrected); }}";
const OVERLAY_VERT = "attribute vec2 aPosition;void main(){gl_Position=vec4(aPosition,0.0,1.0);}";
const OVERLAY_FRAG = "precision mediump float;uniform vec4 uColor;void main(){gl_FragColor=uColor;}";
const OVERLAY_TEXT_VERT = "attribute vec2 aPosition;attribute vec2 aTexCoord;varying vec2 vTexCoord;void main(){gl_Position=vec4(aPosition,0.0,1.0);vTexCoord=aTexCoord;}";
const OVERLAY_TEXT_FRAG = "precision mediump float;varying vec2 vTexCoord;uniform sampler2D uTexture;uniform vec4 uColor;void main(){float a=texture2D(uTexture,vTexCoord).a;gl_FragColor=vec4(uColor.rgb,uColor.a*a);}";

fn compileShader(kind: c.GLenum, source: [*c]const u8) c.GLuint {
    const shader = c.glCreateShader(kind);
    var src = source;
    c.glShaderSource(shader, 1, &src, null);
    c.glCompileShader(shader);
    var ok: c.GLint = 0;
    c.glGetShaderiv(shader, c.GL_COMPILE_STATUS, &ok);
    if (ok == 0) {
        var log: [512]u8 = [_]u8{0} ** 512;
        c.glGetShaderInfoLog(shader, log.len, null, &log);
        setError("shader compile failed: {s}", .{std.mem.sliceTo(&log, 0)});
    }
    return shader;
}

fn createProgram() c.GLuint {
    const vs = compileShader(c.GL_VERTEX_SHADER, VERT);
    const fs = compileShader(c.GL_FRAGMENT_SHADER, FRAG);
    const program = c.glCreateProgram();
    c.glAttachShader(program, vs);
    c.glAttachShader(program, fs);
    c.glLinkProgram(program);
    var ok: c.GLint = 0;
    c.glGetProgramiv(program, c.GL_LINK_STATUS, &ok);
    if (ok == 0) {
        var log: [512]u8 = [_]u8{0} ** 512;
        c.glGetProgramInfoLog(program, log.len, null, &log);
        setError("program link failed: {s}", .{std.mem.sliceTo(&log, 0)});
    }
    c.glDeleteShader(vs);
    c.glDeleteShader(fs);
    return program;
}

fn createOverlayProgram() c.GLuint {
    const vs = compileShader(c.GL_VERTEX_SHADER, OVERLAY_VERT);
    const fs = compileShader(c.GL_FRAGMENT_SHADER, OVERLAY_FRAG);
    const program = c.glCreateProgram();
    c.glAttachShader(program, vs);
    c.glAttachShader(program, fs);
    c.glLinkProgram(program);
    var ok: c.GLint = 0;
    c.glGetProgramiv(program, c.GL_LINK_STATUS, &ok);
    if (ok == 0) {
        var log: [512]u8 = [_]u8{0} ** 512;
        c.glGetProgramInfoLog(program, log.len, null, &log);
        setError("overlay program link failed: {s}", .{std.mem.sliceTo(&log, 0)});
    }
    c.glDeleteShader(vs);
    c.glDeleteShader(fs);
    return program;
}

fn createOverlayTextProgram() c.GLuint {
    const vs = compileShader(c.GL_VERTEX_SHADER, OVERLAY_TEXT_VERT);
    const fs = compileShader(c.GL_FRAGMENT_SHADER, OVERLAY_TEXT_FRAG);
    const program = c.glCreateProgram();
    c.glAttachShader(program, vs);
    c.glAttachShader(program, fs);
    c.glLinkProgram(program);
    var ok: c.GLint = 0;
    c.glGetProgramiv(program, c.GL_LINK_STATUS, &ok);
    if (ok == 0) {
        var log: [512]u8 = [_]u8{0} ** 512;
        c.glGetProgramInfoLog(program, log.len, null, &log);
        setError("overlay text program link failed: {s}", .{std.mem.sliceTo(&log, 0)});
    }
    c.glDeleteShader(vs);
    c.glDeleteShader(fs);
    return program;
}

const FONT_CELL_W = 8;
const FONT_CELL_H = 9;
const FONT_GLYPHS = "0123456789-: ";
const FONT_PATTERNS = [_][7]u8{
    .{ 0b01110, 0b10001, 0b10011, 0b10101, 0b11001, 0b10001, 0b01110 },
    .{ 0b00100, 0b01100, 0b00100, 0b00100, 0b00100, 0b00100, 0b01110 },
    .{ 0b01110, 0b10001, 0b00001, 0b00010, 0b00100, 0b01000, 0b11111 },
    .{ 0b11110, 0b00001, 0b00001, 0b01110, 0b00001, 0b00001, 0b11110 },
    .{ 0b00010, 0b00110, 0b01010, 0b10010, 0b11111, 0b00010, 0b00010 },
    .{ 0b11111, 0b10000, 0b10000, 0b11110, 0b00001, 0b00001, 0b11110 },
    .{ 0b01110, 0b10000, 0b10000, 0b11110, 0b10001, 0b10001, 0b01110 },
    .{ 0b11111, 0b00001, 0b00010, 0b00100, 0b01000, 0b01000, 0b01000 },
    .{ 0b01110, 0b10001, 0b10001, 0b01110, 0b10001, 0b10001, 0b01110 },
    .{ 0b01110, 0b10001, 0b10001, 0b01111, 0b00001, 0b00001, 0b01110 },
    .{ 0b00000, 0b00000, 0b00000, 0b11110, 0b00000, 0b00000, 0b00000 },
    .{ 0b00000, 0b00100, 0b00100, 0b00000, 0b00100, 0b00100, 0b00000 },
    .{ 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000, 0b00000 },
};
comptime { std.debug.assert(FONT_PATTERNS.len == FONT_GLYPHS.len); }
const FONT_COLS = FONT_GLYPHS.len;
const FONT_ATLAS_W = FONT_COLS * FONT_CELL_W;
const FONT_ATLAS_H = FONT_CELL_H;

fn glyphIndex(ch: u8) usize {
    for (FONT_GLYPHS, 0..) |glyph, index| if (glyph == ch) return index;
    return FONT_GLYPHS.len - 1;
}

fn initOverlayFontTexture(p: *Pipe) bool {
    if (p.overlay_font_texture != 0) return true;
    var pixels: [FONT_ATLAS_W * FONT_ATLAS_H]u8 = [_]u8{0} ** (FONT_ATLAS_W * FONT_ATLAS_H);
    for (FONT_PATTERNS, 0..) |pattern, glyph| {
        const base_x = glyph * FONT_CELL_W;
        for (pattern, 0..) |row_bits, row| {
            for (0..5) |col| {
                if ((row_bits & (@as(u8, 1) << @intCast(4 - col))) == 0) continue;
                const x = base_x + 1 + col;
                const y = 1 + row;
                pixels[y * FONT_ATLAS_W + x] = 255;
                if (x + 1 < base_x + FONT_CELL_W - 1) pixels[y * FONT_ATLAS_W + x + 1] = 180;
                if (y + 1 < FONT_CELL_H - 1) pixels[(y + 1) * FONT_ATLAS_W + x] = 180;
            }
        }
    }
    var texture: c.GLuint = 0;
    c.glGenTextures(1, &texture);
    if (texture == 0) { setError("overlay font texture allocation failed", .{}); return false; }
    c.glBindTexture(c.GL_TEXTURE_2D, texture);
    c.glTexParameteri(c.GL_TEXTURE_2D, c.GL_TEXTURE_MIN_FILTER, c.GL_LINEAR);
    c.glTexParameteri(c.GL_TEXTURE_2D, c.GL_TEXTURE_MAG_FILTER, c.GL_LINEAR);
    c.glTexParameteri(c.GL_TEXTURE_2D, c.GL_TEXTURE_WRAP_S, c.GL_CLAMP_TO_EDGE);
    c.glTexParameteri(c.GL_TEXTURE_2D, c.GL_TEXTURE_WRAP_T, c.GL_CLAMP_TO_EDGE);
    c.glPixelStorei(c.GL_UNPACK_ALIGNMENT, 1);
    c.glTexImage2D(c.GL_TEXTURE_2D, 0, c.GL_ALPHA, FONT_ATLAS_W, FONT_ATLAS_H, 0, c.GL_ALPHA, c.GL_UNSIGNED_BYTE, &pixels);
    c.glPixelStorei(c.GL_UNPACK_ALIGNMENT, 4);
    if (glError("initOverlayFontTexture")) |e| { setErrorSlice(e); c.glDeleteTextures(1, &texture); return false; }
    p.overlay_font_texture = texture;
    return true;
}

fn initEgl(p: *Pipe) bool {
    if (p.display != c.EGL_NO_DISPLAY) return true;
    p.display = c.eglGetDisplay(c.EGL_DEFAULT_DISPLAY);
    if (p.display == c.EGL_NO_DISPLAY) { setError("eglGetDisplay failed", .{}); return false; }
    if (c.eglInitialize(p.display, null, null) == c.EGL_FALSE) { setErrorSlice(eglError("eglInitialize failed")); return false; }
    if (g_presentation_time_android == null) {
        const proc = c.eglGetProcAddress("eglPresentationTimeANDROID");
        if (proc != null) g_presentation_time_android = @ptrCast(proc);
    }
    const attrs = [_]c.EGLint{ c.EGL_RENDERABLE_TYPE, c.EGL_OPENGL_ES2_BIT, c.EGL_SURFACE_TYPE, c.EGL_WINDOW_BIT | c.EGL_PBUFFER_BIT, c.EGL_RED_SIZE, 8, c.EGL_GREEN_SIZE, 8, c.EGL_BLUE_SIZE, 8, c.EGL_ALPHA_SIZE, 8, EGL_RECORDABLE_ANDROID, 1, c.EGL_NONE };
    var count: c.EGLint = 0;
    if (c.eglChooseConfig(p.display, &attrs, &p.config, 1, &count) == c.EGL_FALSE or count <= 0) { setErrorSlice(eglError("eglChooseConfig failed")); return false; }
    const ctx_attrs = [_]c.EGLint{ c.EGL_CONTEXT_CLIENT_VERSION, 2, c.EGL_NONE };
    p.context = c.eglCreateContext(p.display, p.config, c.EGL_NO_CONTEXT, &ctx_attrs);
    if (p.context == c.EGL_NO_CONTEXT) { setErrorSlice(eglError("eglCreateContext failed")); return false; }
    const pb_attrs = [_]c.EGLint{ c.EGL_WIDTH, 1, c.EGL_HEIGHT, 1, c.EGL_NONE };
    p.pbuffer = c.eglCreatePbufferSurface(p.display, p.config, &pb_attrs);
    if (p.pbuffer == c.EGL_NO_SURFACE) { setErrorSlice(eglError("eglCreatePbufferSurface failed")); return false; }
    if (c.eglMakeCurrent(p.display, p.pbuffer, p.pbuffer, p.context) == c.EGL_FALSE) { setErrorSlice(eglError("eglMakeCurrent pbuffer failed")); return false; }
    p.program = createProgram();
    p.overlay_program = createOverlayProgram();
    p.overlay_text_program = createOverlayTextProgram();
    p.pos_loc = c.glGetAttribLocation(p.program, "aPosition");
    p.tex_loc = c.glGetAttribLocation(p.program, "aTexCoord");
    p.sampler_loc = c.glGetUniformLocation(p.program, "uTexture");
    p.overlay_pos_loc = c.glGetAttribLocation(p.overlay_program, "aPosition");
    p.overlay_color_loc = c.glGetUniformLocation(p.overlay_program, "uColor");
    p.overlay_text_pos_loc = c.glGetAttribLocation(p.overlay_text_program, "aPosition");
    p.overlay_text_tex_loc = c.glGetAttribLocation(p.overlay_text_program, "aTexCoord");
    p.overlay_text_sampler_loc = c.glGetUniformLocation(p.overlay_text_program, "uTexture");
    p.overlay_text_color_loc = c.glGetUniformLocation(p.overlay_text_program, "uColor");
    p.fisheye_enabled_loc = c.glGetUniformLocation(p.program, "uFisheyeEnabled");
    p.k1_loc = c.glGetUniformLocation(p.program, "uK1");
    p.k2_loc = c.glGetUniformLocation(p.program, "uK2");
    p.zoom_loc = c.glGetUniformLocation(p.program, "uZoom");
    p.center_loc = c.glGetUniformLocation(p.program, "uCenter");
    if (p.program == 0 or p.pos_loc < 0 or p.tex_loc < 0 or p.sampler_loc < 0 or p.overlay_program == 0 or p.overlay_pos_loc < 0 or p.overlay_color_loc < 0 or p.overlay_text_program == 0 or p.overlay_text_pos_loc < 0 or p.overlay_text_tex_loc < 0 or p.overlay_text_sampler_loc < 0 or p.overlay_text_color_loc < 0) { setError("GLES program locations unavailable", .{}); return false; }
    if (!initOverlayFontTexture(p)) return false;
    clearCurrent(p);
    logd("EGL initialized", .{});
    return true;
}

fn makePbufferCurrent(p: *Pipe) bool {
    if (!initEgl(p)) return false;
    if (p.pbuffer == c.EGL_NO_SURFACE) { setError("missing pbuffer surface", .{}); return false; }
    if (p.current_surface == p.pbuffer) return true;
    if (c.eglMakeCurrent(p.display, p.pbuffer, p.pbuffer, p.context) == c.EGL_FALSE) { setErrorSlice(eglError("eglMakeCurrent pbuffer failed")); return false; }
    p.current_surface = p.pbuffer;
    return true;
}

fn makeCurrent(p: *Pipe, surface: c.EGLSurface) bool {
    if (!initEgl(p)) return false;
    if (surface == c.EGL_NO_SURFACE) { setError("missing EGL surface", .{}); return false; }
    if (p.current_surface == surface) return true;
    if (c.eglMakeCurrent(p.display, surface, surface, p.context) == c.EGL_FALSE) { setErrorSlice(eglError("eglMakeCurrent failed")); return false; }
    p.current_surface = surface;
    return true;
}

fn updateSurfaceTexture(st: ?*c.ASurfaceTexture) bool {
    const native_st = st orelse {
        setErrorSlice("missing native ASurfaceTexture");
        return false;
    };
    if (c.ASurfaceTexture_updateTexImage(native_st) != 0) {
        setErrorSlice("ASurfaceTexture_updateTexImage failed");
        return false;
    }
    return true;
}

fn fillTexCoords(q: *Quad, rotation: f32) void {
    const r0 = [_]c.GLfloat{0,0, 1,0, 0,1, 1,1};
    const r90 = [_]c.GLfloat{0,1, 0,0, 1,1, 1,0};
    const r270 = [_]c.GLfloat{1,0, 1,1, 0,0, 0,1};
    const src = if (rotation == 90.0) &r90 else if (rotation == 270.0) &r270 else &r0;
    q.tex = src.*;
}

fn buildQuadForCanvas(q: *Quad, x: f32, y: f32, w: f32, h: f32, rotation: f32, cw0: f32, ch0: f32) void {
    const cw = if (cw0 <= 0) 1.0 else cw0;
    const ch = if (ch0 <= 0) 1.0 else ch0;
    const x0 = x / cw * 2.0 - 1.0;
    const x1 = (x + w) / cw * 2.0 - 1.0;
    const y0 = 1.0 - y / ch * 2.0;
    const y1 = 1.0 - (y + h) / ch * 2.0;
    q.verts = [_]c.GLfloat{ x0, y0, x1, y0, x0, y1, x1, y1 };
    fillTexCoords(q, rotation);
}

fn updateEncoderLayout(p: *Pipe) void {
    const half_w = @as(f32, @floatFromInt(p.width)) * 0.5;
    const half_h = @as(f32, @floatFromInt(p.height)) * 0.5;
    buildQuadForCanvas(&p.encoder_quad[0], 0, 0, half_w, half_h, 0, @floatFromInt(p.width), @floatFromInt(p.height));
    buildQuadForCanvas(&p.encoder_quad[1], half_w, 0, half_w, half_h, 0, @floatFromInt(p.width), @floatFromInt(p.height));
    buildQuadForCanvas(&p.encoder_quad[2], 0, half_h, half_w, half_h, 0, @floatFromInt(p.width), @floatFromInt(p.height));
    buildQuadForCanvas(&p.encoder_quad[3], half_w, half_h, half_w, half_h, 0, @floatFromInt(p.width), @floatFromInt(p.height));
    p.config_version += 1;
}

fn updatePreviewLayout(p: *Pipe, index: i32, width: i32, height: i32) void {
    if (index < 0 or index >= 4) return;
    const i: usize = @intCast(index);
    var rotation: f32 = 0;
    if (p.preview_apply_native_transform[i]) {
        if (index == 2) rotation = @floatFromInt(p.side_left_rotation);
        if (index == 3) rotation = @floatFromInt(p.side_right_rotation);
    }
    buildQuadForCanvas(&p.preview_quad[i], 0, 0, @floatFromInt(width), @floatFromInt(height), rotation, @floatFromInt(width), @floatFromInt(height));
    p.preview_quad_width[i] = width;
    p.preview_quad_height[i] = height;
}

fn beginDrawPass(p: *Pipe) void {
    c.glUseProgram(p.program);
    c.glEnableVertexAttribArray(@intCast(p.pos_loc));
    c.glEnableVertexAttribArray(@intCast(p.tex_loc));
    c.glActiveTexture(c.GL_TEXTURE0);
    c.glUniform1i(p.sampler_loc, 0);
}

fn drawQuadWithFisheye(p: *Pipe, index: usize, q: *const Quad, apply_fisheye: bool) void {
    if (index >= 4) return;
    const input = &p.input[index];
    if (input.texture == 0) return;
    c.glVertexAttribPointer(@intCast(p.pos_loc), 2, c.GL_FLOAT, c.GL_FALSE, 0, &q.verts);
    c.glVertexAttribPointer(@intCast(p.tex_loc), 2, c.GL_FLOAT, c.GL_FALSE, 0, &q.tex);
    c.glBindTexture(GL_TEXTURE_EXTERNAL_OES, input.texture);
    const fisheye_enabled = apply_fisheye and p.fisheye_enabled[index];
    if (p.fisheye_enabled_loc >= 0) c.glUniform1i(p.fisheye_enabled_loc, if (fisheye_enabled) 1 else 0);
    if (fisheye_enabled) {
        if (p.k1_loc >= 0) c.glUniform1f(p.k1_loc, p.fisheye_k1[index]);
        if (p.k2_loc >= 0) c.glUniform1f(p.k2_loc, p.fisheye_k2[index]);
        if (p.zoom_loc >= 0) c.glUniform1f(p.zoom_loc, p.fisheye_zoom[index]);
        if (p.center_loc >= 0) c.glUniform2f(p.center_loc, p.fisheye_center_x[index], p.fisheye_center_y[index]);
    }
    c.glDrawArrays(c.GL_TRIANGLE_STRIP, 0, 4);
}

fn drawQuad(p: *Pipe, index: usize, q: *const Quad) void {
    drawQuadWithFisheye(p, index, q, true);
}

fn civilFromDays(days_since_epoch: i64) struct { year: i64, month: i64, day: i64 } {
    const z = days_since_epoch + 719468;
    const era = @divFloor(if (z >= 0) z else z - 146096, 146097);
    const doe = z - era * 146097;
    const yoe = @divTrunc(doe - @divTrunc(doe, 1460) + @divTrunc(doe, 36524) - @divTrunc(doe, 146096), 365);
    var year = yoe + era * 400;
    const doy = doe - (365 * yoe + @divTrunc(yoe, 4) - @divTrunc(yoe, 100));
    const mp = @divTrunc(5 * doy + 2, 153);
    const day = doy - @divTrunc(153 * mp + 2, 5) + 1;
    const month = mp + if (mp < 10) @as(i64, 3) else @as(i64, -9);
    if (month <= 2) year += 1;
    return .{ .year = year, .month = month, .day = day };
}

fn formatOverlayTime(buf: *[24]u8, wall_ms: i64) []const u8 {
    if (wall_ms <= 0) return "";
    const china_offset_seconds: i64 = 8 * 60 * 60;
    const local_seconds = @divTrunc(wall_ms, 1000) + china_offset_seconds;
    const day_seconds: i64 = 24 * 60 * 60;
    const days = @divTrunc(local_seconds, day_seconds);
    const seconds_of_day = @mod(local_seconds, day_seconds);
    const date = civilFromDays(days);
    return std.fmt.bufPrint(buf, "{d:0>4}-{d:0>2}-{d:0>2} {d:0>2}:{d:0>2}:{d:0>2}", .{
        date.year,
        date.month,
        date.day,
        @divTrunc(seconds_of_day, 60 * 60),
        @mod(@divTrunc(seconds_of_day, 60), 60),
        @mod(seconds_of_day, 60),
    }) catch "";
}

fn cachedOverlayTime(r: *RecordingState) []const u8 {
    if (r.overlay_wall_clock_ms <= 0) return "";
    const second = @divTrunc(r.overlay_wall_clock_ms, 1000);
    if (r.overlay_cached_second != second) {
        var buf: [24]u8 = undefined;
        const text = formatOverlayTime(&buf, r.overlay_wall_clock_ms);
        const len = @min(text.len, r.overlay_text.len);
        @memset(r.overlay_text[0..], 0);
        if (len > 0) @memcpy(r.overlay_text[0..len], text[0..len]);
        r.overlay_text_len = len;
        r.overlay_cached_second = second;
    }
    return r.overlay_text[0..r.overlay_text_len];
}

fn resetOverlayCache(r: *RecordingState, wall_clock_ms: i64) void {
    r.overlay_wall_clock_ms = wall_clock_ms;
    r.overlay_cached_second = -1;
    r.overlay_text_len = 0;
    r.overlay_geometry_second = -1;
    r.overlay_geometry_width = 0;
    r.overlay_geometry_height = 0;
    r.overlay_bg_batch.len = 0;
    r.overlay_shadow_text_batch.len = 0;
    r.overlay_text_batch.len = 0;
}

fn overlayCharAdvance(ch: u8, scale: f32) f32 {
    if (ch >= '0' and ch <= '9') return 20.0 * scale;
    if (ch == '-') return 14.0 * scale;
    if (ch == ':') return 10.0 * scale;
    if (ch == ' ') return 12.0 * scale;
    return 14.0 * scale;
}

fn overlayTextWidth(text: []const u8, scale: f32) f32 {
    var width: f32 = 0;
    for (text) |ch| width += overlayCharAdvance(ch, scale);
    return width;
}

fn appendOverlayRect(batch: *OverlayBatch, p: *Pipe, x: f32, y: f32, w: f32, h: f32) void {
    if (w <= 0 or h <= 0) return;
    if (batch.len + 12 > batch.verts.len) return;
    const cw = if (p.width <= 0) 1.0 else @as(f32, @floatFromInt(p.width));
    const ch = if (p.height <= 0) 1.0 else @as(f32, @floatFromInt(p.height));
    const x0 = x / cw * 2.0 - 1.0;
    const x1 = (x + w) / cw * 2.0 - 1.0;
    const y0 = 1.0 - y / ch * 2.0;
    const y1 = 1.0 - (y + h) / ch * 2.0;
    const quad = [_]c.GLfloat{ x0, y0, x1, y0, x0, y1, x1, y0, x1, y1, x0, y1 };
    @memcpy(batch.verts[batch.len..][0..quad.len], quad[0..]);
    batch.len += quad.len;
}

fn flushOverlayBatch(p: *Pipe, batch: *OverlayBatch, color: [4]f32) void {
    if (batch.len == 0) return;
    c.glVertexAttribPointer(@intCast(p.overlay_pos_loc), 2, c.GL_FLOAT, c.GL_FALSE, 0, &batch.verts);
    c.glUniform4f(p.overlay_color_loc, color[0], color[1], color[2], color[3]);
    c.glDrawArrays(c.GL_TRIANGLES, 0, @intCast(batch.len / 2));
}

fn appendOverlayTexturedRect(batch: *TexturedOverlayBatch, p: *Pipe, x: f32, y: f32, w: f32, h: f32, tex_u0: f32, tex_v0: f32, tex_u1: f32, tex_v1: f32) void {
    if (w <= 0 or h <= 0) return;
    if (batch.len + 12 > batch.verts.len or batch.len + 12 > batch.tex.len) return;
    const cw = if (p.width <= 0) 1.0 else @as(f32, @floatFromInt(p.width));
    const ch = if (p.height <= 0) 1.0 else @as(f32, @floatFromInt(p.height));
    const x0 = x / cw * 2.0 - 1.0;
    const x1 = (x + w) / cw * 2.0 - 1.0;
    const y0 = 1.0 - y / ch * 2.0;
    const y1 = 1.0 - (y + h) / ch * 2.0;
    const verts = [_]c.GLfloat{ x0, y0, x1, y0, x0, y1, x1, y0, x1, y1, x0, y1 };
    const tex = [_]c.GLfloat{ tex_u0, tex_v0, tex_u1, tex_v0, tex_u0, tex_v1, tex_u1, tex_v0, tex_u1, tex_v1, tex_u0, tex_v1 };
    @memcpy(batch.verts[batch.len..][0..verts.len], verts[0..]);
    @memcpy(batch.tex[batch.len..][0..tex.len], tex[0..]);
    batch.len += verts.len;
}

fn flushOverlayTextBatch(p: *Pipe, batch: *TexturedOverlayBatch, color: [4]f32) void {
    if (batch.len == 0 or p.overlay_font_texture == 0) return;
    flushOverlayTextureBatch(p, batch, p.overlay_font_texture, color);
}

fn flushOverlayTextureBatch(p: *Pipe, batch: *TexturedOverlayBatch, texture: c.GLuint, color: [4]f32) void {
    if (batch.len == 0 or texture == 0) return;
    c.glVertexAttribPointer(@intCast(p.overlay_text_pos_loc), 2, c.GL_FLOAT, c.GL_FALSE, 0, &batch.verts);
    c.glVertexAttribPointer(@intCast(p.overlay_text_tex_loc), 2, c.GL_FLOAT, c.GL_FALSE, 0, &batch.tex);
    c.glActiveTexture(c.GL_TEXTURE0);
    c.glBindTexture(c.GL_TEXTURE_2D, texture);
    c.glUniform1i(p.overlay_text_sampler_loc, 0);
    c.glUniform4f(p.overlay_text_color_loc, color[0], color[1], color[2], color[3]);
    c.glDrawArrays(c.GL_TRIANGLES, 0, @intCast(batch.len / 2));
}

fn appendOverlayChar(batch: *TexturedOverlayBatch, p: *Pipe, ch: u8, x: f32, y: f32, scale: f32) void {
    if (ch == ' ') return;
    const glyph = glyphIndex(ch);
    const tex_u0 = (@as(f32, @floatFromInt(glyph * FONT_CELL_W)) + 0.5) / @as(f32, @floatFromInt(FONT_ATLAS_W));
    const tex_u1 = (@as(f32, @floatFromInt((glyph + 1) * FONT_CELL_W)) - 0.5) / @as(f32, @floatFromInt(FONT_ATLAS_W));
    const tex_v0 = 0.5 / @as(f32, @floatFromInt(FONT_ATLAS_H));
    const tex_v1 = (@as(f32, @floatFromInt(FONT_ATLAS_H)) - 0.5) / @as(f32, @floatFromInt(FONT_ATLAS_H));
    const h = 38.0 * scale;
    const w = overlayCharAdvance(ch, scale);
    appendOverlayTexturedRect(batch, p, x, y, w, h, tex_u0, tex_v0, tex_u1, tex_v1);
}

fn rebuildOverlayGeometry(p: *Pipe, text: []const u8, second: i64, scale: f32, x: f32, y: f32) void {
    const r = &p.recording;
    if (r.overlay_geometry_second == second and r.overlay_geometry_width == p.width and r.overlay_geometry_height == p.height) return;
    r.overlay_bg_batch.len = 0;
    r.overlay_shadow_text_batch.len = 0;
    r.overlay_text_batch.len = 0;

    var cursor = x;
    for (text) |ch| {
        appendOverlayChar(&r.overlay_shadow_text_batch, p, ch, cursor + 2.0 * scale, y + 2.0 * scale, scale);
        appendOverlayChar(&r.overlay_text_batch, p, ch, cursor, y, scale);
        cursor += overlayCharAdvance(ch, scale);
    }

    r.overlay_geometry_second = second;
    r.overlay_geometry_width = p.width;
    r.overlay_geometry_height = p.height;
}

fn drawOverlay(p: *Pipe) void {
    if (!p.recording.recording) return;
    const text = cachedOverlayTime(&p.recording);
    const has_native_text = text.len > 0 and p.overlay_font_texture != 0;
    if (has_native_text) {
        const width_scale = @as(f32, @floatFromInt(p.width)) / 1280.0;
        const height_scale = @as(f32, @floatFromInt(p.height)) / 800.0;
        const scale = @max(@min(width_scale, height_scale), 0.25);
        const x = 50.0 * width_scale;
        const y = 40.0 * height_scale;
        const second = @divTrunc(p.recording.overlay_wall_clock_ms, 1000);
        rebuildOverlayGeometry(p, text, second, scale, x, y);
    }
    if (!has_native_text) return;

    c.glEnable(c.GL_BLEND);
    c.glBlendFunc(c.GL_SRC_ALPHA, c.GL_ONE_MINUS_SRC_ALPHA);

    c.glUseProgram(p.overlay_text_program);
    c.glEnableVertexAttribArray(@intCast(p.overlay_text_pos_loc));
    c.glEnableVertexAttribArray(@intCast(p.overlay_text_tex_loc));
    flushOverlayTextBatch(p, &p.recording.overlay_shadow_text_batch, .{ 0.0, 0.0, 0.0, 0.65 });
    flushOverlayTextBatch(p, &p.recording.overlay_text_batch, .{ 1.0, 1.0, 1.0, 1.0 });

    c.glDisable(c.GL_BLEND);
    c.glDisableVertexAttribArray(@intCast(p.overlay_text_tex_loc));
    c.glDisableVertexAttribArray(@intCast(p.overlay_text_pos_loc));
    c.glUseProgram(p.program);
}

fn renderPreviewLocked(env: [*c]c.JNIEnv, p: *Pipe, index: i32) bool {
    if (index < 0 or index >= 4) return false;
    const i: usize = @intCast(index);
    if (p.input[i].surface_texture_native == null) return false;
    if (p.preview_surface[i] == c.EGL_NO_SURFACE) { p.input[i].preview_drop_count += 1; return true; }
    const start = nowMs();
    if (!makeCurrent(p, p.preview_surface[i])) return false;
    _ = env;
    if (!updateSurfaceTexture(p.input[i].surface_texture_native)) { clearCurrent(p); return false; }
    p.input[i].dirty_count += 1;
    p.input[i].frame_generation += 1;
    p.input[i].latched_generation = p.input[i].frame_generation;
    p.input[i].preview_generation = p.input[i].frame_generation;
    p.input[i].dirty = false;
    p.input[i].has_latched_frame = true;
    p.input[i].update_count += 1;
    if (!p.input[i].has_latched_frame) return true;
    var vw: i32 = if (p.preview_window[i]) |w| c.ANativeWindow_getWidth(w) else p.width;
    var vh: i32 = if (p.preview_window[i]) |w| c.ANativeWindow_getHeight(w) else p.height;
    if (vw <= 0) vw = p.width;
    if (vh <= 0) vh = p.height;
    if (p.preview_quad_width[i] != vw or p.preview_quad_height[i] != vh) updatePreviewLayout(p, index, vw, vh);
    c.glViewport(0, 0, vw, vh);
    c.glClearColor(0, 0, 0, 1);
    c.glClear(c.GL_COLOR_BUFFER_BIT);
    beginDrawPass(p);
    drawQuadWithFisheye(p, i, &p.preview_quad[i], p.preview_apply_fisheye[i]);
    if (CHECK_RENDER_GL_ERROR) if (glError("renderPreview")) |e| { setErrorSlice(e); clearCurrent(p); return false; };
    if (c.eglSwapBuffers(p.display, p.preview_surface[i]) == c.EGL_FALSE) {
        setErrorSlice(eglError("eglSwapBuffers preview failed"));
        clearCurrent(p);
        p.input[i].preview_drop_count += 1;
        return false;
    }
    const elapsed = nowMs() - start;
    clearCurrent(p);
    p.preview_render_count += 1;
    p.input[i].preview_render_count += 1;
    p.input[i].preview_swap_ms = elapsed;
    p.input[i].last_preview_render_ms = nowMs();
    if (elapsed >= 20 or @mod(p.input[i].preview_render_count, 120) == 0) logi("preview perf index={d} totalMs={d} renders={d} updates={d} drops={d}", .{ index, elapsed, p.input[i].preview_render_count, p.input[i].update_count, p.input[i].preview_drop_count });
    return true;
}

fn previewDelayMs(p: *const Pipe, input: *const Input) i64 {
    if (p.preview_min_interval_ms <= 0 or input.last_preview_render_ms <= 0) return 0;
    const remaining = p.preview_min_interval_ms - (nowMs() - input.last_preview_render_ms);
    return if (remaining > 0) remaining else 0;
}
fn floorToSegment(wall: i64, duration0: i64) i64 { const d = if (duration0 <= 0) 60000 else duration0; return wall - @mod(wall, d); }
fn recordingTickIntervalMs(r: *const RecordingState) i64 { const fps = if (r.fps <= 0) 15 else r.fps; return @divTrunc(1000, fps); }

fn requestEncoderRenderLocked(p: *Pipe) bool {
    p.encoder_signal_count += 1;
    if (p.encoder_surface == c.EGL_NO_SURFACE or p.encoder_window == null or p.encoder_generation == 0) { p.encoder_drop_count += 1; p.no_surface_count += 1; return false; }
    if (p.encoder_pending) { p.encoder_coalesced_count += 1; return false; }
    p.encoder_pending = true;
    p.encoder_scheduled_count += 1;
    return true;
}
fn hasDirtyInput(p: *const Pipe) bool { for (p.input) |inp| if (inp.surface_texture_native != null and inp.encoder_generation != inp.frame_generation) return true; return false; }
fn updateDirtyInputsLocked(env: [*c]c.JNIEnv, p: *Pipe) bool {
    for (&p.input) |*inp| {
        const force_recording_latch = p.recording.recording;
        if (inp.surface_texture_native != null and (force_recording_latch or inp.encoder_generation != inp.frame_generation)) {
            if (force_recording_latch or inp.latched_generation != inp.frame_generation) {
                inp.dirty_count += 1;
                _ = env;
                if (!updateSurfaceTexture(inp.surface_texture_native)) return false;
                inp.latched_generation = inp.frame_generation;
                inp.dirty = false;
                inp.has_latched_frame = true;
                inp.update_count += 1;
            }
            inp.encoder_generation = inp.frame_generation;
        }
    }
    return true;
}

fn renderEncoderLocked(env: [*c]c.JNIEnv, p: *Pipe, require_dirty: bool, rendered: ?*bool) bool {
    if (rendered) |r| r.* = false;
    if (p.encoder_surface == c.EGL_NO_SURFACE or p.encoder_window == null or p.encoder_generation == 0) { p.encoder_drop_count += 1; p.no_surface_count += 1; return true; }
    if (require_dirty and !hasDirtyInput(p)) { p.encoder_drop_count += 1; return true; }
    const start = nowMs();
    if (!makeCurrent(p, p.encoder_surface)) return false;
    const update_start = nowMs();
    if (!updateDirtyInputsLocked(env, p)) { clearCurrent(p); return false; }
    const update_ms = nowMs() - update_start;
    p.render_count += 1;
    c.glViewport(0, 0, p.width, p.height);
    c.glClearColor(0, 0, 0, 1);
    c.glClear(c.GL_COLOR_BUFFER_BIT);
    beginDrawPass(p);
    drawQuad(p, 0, &p.encoder_quad[0]); drawQuad(p, 1, &p.encoder_quad[1]); drawQuad(p, 2, &p.encoder_quad[2]); drawQuad(p, 3, &p.encoder_quad[3]);
    drawOverlay(p);
    writeFirstFrameThumbnailBmpLocked(p);
    if (CHECK_RENDER_GL_ERROR) if (glError("renderEncoder")) |e| { setErrorSlice(e); clearCurrent(p); return false; };
    if (g_presentation_time_android) |fnptr| {
        var pts: i64 = undefined;
        if (p.recording.recording) {
            const start_ms = if (p.recording.encoder_segment_start_steady_ms > 0) p.recording.encoder_segment_start_steady_ms else nowMs();
            const elapsed = nowMs() - start_ms;
            pts = if (elapsed > 0) elapsed * 1_000_000 else 0;
            if (pts <= p.recording.last_presentation_time_ns) pts = p.recording.last_presentation_time_ns + 1000;
            p.recording.last_presentation_time_ns = pts;
            p.encoder_frame_index += 1;
        } else {
            const fps = if (p.encoder_fps <= 0) 15 else p.encoder_fps;
            pts = @divTrunc(p.encoder_frame_index * 1_000_000_000, fps);
            p.encoder_frame_index += 1;
        }
        _ = fnptr(p.display, p.encoder_surface, pts);
    }
    const swap_start = nowMs();
    const ok = c.eglSwapBuffers(p.display, p.encoder_surface) != c.EGL_FALSE;
    const swap_ms = nowMs() - swap_start;
    clearCurrent(p);
    p.last_render_ms = nowMs() - start;
    if (ok) {
        p.encoder_render_count += 1;
        if (p.last_render_ms >= 24 or @mod(p.encoder_render_count, 120) == 0) logi("encoder perf totalMs={d} updateMs={d} swapMs={d} renders={d} drops={d}", .{ p.last_render_ms, update_ms, swap_ms, p.encoder_render_count, p.encoder_drop_count });
        _ = std.fmt.bufPrintSentinel(&p.last_render_error, "OK", .{}, 0) catch {};
        if (rendered) |r| r.* = true;
    } else {
        p.encoder_drop_count += 1;
        const e = eglError("eglSwapBuffers encoder failed");
        @memset(p.last_render_error[0..], 0);
        @memcpy(p.last_render_error[0..@min(e.len, p.last_render_error.len - 1)], e[0..@min(e.len, p.last_render_error.len - 1)]);
        setErrorSlice(e);
    }
    return ok;
}

fn getPipe(handle: c.jlong) ?*Pipe {
    for (0..MAX_PIPES) |i| if (g_used[i] and g_pipes[i].handle == handle) return &g_pipes[i];
    setError("invalid native handle", .{});
    return null;
}

fn getNativeWriter(handle: c.jlong) ?*NativeSegmentWriter {
    for (0..MAX_NATIVE_WRITERS) |i| if (g_native_writer_used[i] and g_native_writers[i].handle == handle) return &g_native_writers[i];
    setError("invalid native writer handle", .{});
    return null;
}

fn getNativeCamera(handle: c.jlong) ?*NativeCameraPreview {
    for (0..MAX_NATIVE_CAMERAS) |i| if (g_native_camera_used[i] and g_native_cameras[i].handle == handle) return &g_native_cameras[i];
    setError("invalid native camera handle", .{});
    return null;
}

fn releaseNativeCameraResources(cam: *NativeCameraPreview) void {
    if (cam.session) |session| {
        _ = c.ACameraCaptureSession_stopRepeating(session);
        c.ACameraCaptureSession_close(session);
    }
    cam.session = null;
    if (cam.request) |request| c.ACaptureRequest_free(request);
    cam.request = null;
    if (cam.target) |target| c.ACameraOutputTarget_free(target);
    cam.target = null;
    if (cam.output) |output| c.ACaptureSessionOutput_free(output);
    cam.output = null;
    if (cam.outputs) |outputs| c.ACaptureSessionOutputContainer_free(outputs);
    cam.outputs = null;
    if (cam.device) |device| _ = c.ACameraDevice_close(device);
    cam.device = null;
    if (cam.window) |window| c.ANativeWindow_release(window);
    cam.window = null;
    if (cam.manager) |manager| c.ACameraManager_delete(manager);
    cam.manager = null;
    cam.sequence_id = -1;
}

fn onNativeCameraDisconnected(_: ?*anyopaque, _: ?*c.ACameraDevice) callconv(.c) void {
    logi("NDK camera disconnected", .{});
}

fn onNativeCameraError(_: ?*anyopaque, _: ?*c.ACameraDevice, err_code: c_int) callconv(.c) void {
    setError("NDK camera error={d}", .{err_code});
}

fn onNativeSessionClosed(_: ?*anyopaque, _: ?*c.ACameraCaptureSession) callconv(.c) void { logd("NDK camera session closed", .{}); }
fn onNativeSessionReady(_: ?*anyopaque, _: ?*c.ACameraCaptureSession) callconv(.c) void { logd("NDK camera session ready", .{}); }
fn onNativeSessionActive(_: ?*anyopaque, _: ?*c.ACameraCaptureSession) callconv(.c) void { logd("NDK camera session active", .{}); }

fn releaseNativeWriterResources(w: *NativeSegmentWriter) void {
    if (w.muxer) |muxer| {
        if (w.muxer_started) _ = c.AMediaMuxer_stop(muxer);
        _ = c.AMediaMuxer_delete(muxer);
    }
    if (w.codec) |codec| {
        if (w.started) _ = c.AMediaCodec_stop(codec);
        _ = c.AMediaCodec_delete(codec);
    }
    if (w.input_window) |window| c.ANativeWindow_release(window);
    if (w.fd >= 0) _ = close(w.fd);
    w.* = NativeSegmentWriter{};
}

fn stopNativeWriterLocked(w: *NativeSegmentWriter, writer_handle: c.jlong) bool {
    const lock_wait_total = w.writer_lock_wait_total_ms;
    const lock_wait_max = w.writer_lock_wait_max_ms;
    const drain_calls = w.drain_calls;
    const drain_samples = w.drain_samples;
    const drain_total = w.drain_total_ms;
    const drain_max = w.drain_max_ms;
    const muxer_write_total = w.muxer_write_total_ms;
    const muxer_write_max = w.muxer_write_max_ms;
    if (w.codec) |codec| {
        if (w.started) {
            _ = c.AMediaCodec_signalEndOfInputStream(codec);
            _ = drainNativeWriterLocked(w, 10_000);
            _ = c.AMediaCodec_stop(codec);
            w.started = false;
        }
    }
    if (w.muxer) |muxer| {
        if (w.muxer_started) _ = c.AMediaMuxer_stop(muxer);
        _ = c.AMediaMuxer_delete(muxer);
        w.muxer = null;
        w.muxer_started = false;
        w.track_index = -1;
    }
    if (w.fd >= 0) {
        _ = close(w.fd);
        w.fd = -1;
    }
    logi("native writer perf handle={d} lockWaitTotalMs={d} lockWaitMaxMs={d} drainCalls={d} samples={d} drainTotalMs={d} drainMaxMs={d} muxWriteTotalMs={d} muxWriteMaxMs={d}", .{ writer_handle, lock_wait_total, lock_wait_max, drain_calls, drain_samples, drain_total, drain_max, muxer_write_total, muxer_write_max });
    return true;
}

fn drainNativeWriterLocked(w: *NativeSegmentWriter, timeout_us: c.jlong) c.jlong {
    const codec = w.codec orelse return -1;
    const drain_start_ms = nowMs();
    w.drain_calls += 1;
    defer {
        const drain_ms = nowMs() - drain_start_ms;
        w.drain_total_ms += drain_ms;
        if (drain_ms > w.drain_max_ms) w.drain_max_ms = drain_ms;
    }
    var info: c.AMediaCodecBufferInfo = undefined;
    var drained: c.jlong = 0;
    while (true) {
        const index = c.AMediaCodec_dequeueOutputBuffer(codec, &info, timeout_us);
        if (index >= 0) {
            defer _ = c.AMediaCodec_releaseOutputBuffer(codec, @intCast(index), false);
            const buffer = c.AMediaCodec_getOutputBuffer(codec, @intCast(index), null);
            if (buffer != null and w.muxer != null and w.muxer_started and w.track_index >= 0 and info.size > 0 and (info.flags & c.AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) == 0) {
                const write_start_ms = nowMs();
                const status = c.AMediaMuxer_writeSampleData(w.muxer.?, @intCast(w.track_index), buffer, &info);
                const write_ms = nowMs() - write_start_ms;
                w.muxer_write_total_ms += write_ms;
                if (write_ms > w.muxer_write_max_ms) w.muxer_write_max_ms = write_ms;
                if (status != c.AMEDIA_OK) {
                    setError("AMediaMuxer_writeSampleData failed status={d}", .{status});
                    return -1;
                }
                drained += 1;
                w.drain_samples += 1;
            }
            if ((info.flags & c.AMEDIACODEC_BUFFER_FLAG_END_OF_STREAM) != 0) return drained;
            continue;
        }
        if (index == c.AMEDIACODEC_INFO_OUTPUT_FORMAT_CHANGED) {
            if (w.muxer == null) {
                setError("output format changed before muxer", .{});
                return -1;
            }
            if (!w.muxer_started) {
                const out_format = c.AMediaCodec_getOutputFormat(codec) orelse {
                    setError("AMediaCodec_getOutputFormat failed", .{});
                    return -1;
                };
                defer _ = c.AMediaFormat_delete(out_format);
                const track = c.AMediaMuxer_addTrack(w.muxer.?, out_format);
                if (track < 0) {
                    setError("AMediaMuxer_addTrack failed track={d}", .{track});
                    return -1;
                }
                const start_status = c.AMediaMuxer_start(w.muxer.?);
                if (start_status != c.AMEDIA_OK) {
                    setError("AMediaMuxer_start failed status={d}", .{start_status});
                    return -1;
                }
                w.track_index = @intCast(track);
                w.muxer_started = true;
            }
            continue;
        }
        if (index == c.AMEDIA_ERROR_UNKNOWN) return -1;
        return drained;
    }
}

fn selectVideoTrackNative(extractor: *c.AMediaExtractor) ?usize {
    const count = c.AMediaExtractor_getTrackCount(extractor);
    var i: usize = 0;
    while (i < count) : (i += 1) {
        const format = c.AMediaExtractor_getTrackFormat(extractor, i) orelse continue;
        defer _ = c.AMediaFormat_delete(format);
        var mime_ptr: [*c]const u8 = null;
        if (c.AMediaFormat_getString(format, c.AMEDIAFORMAT_KEY_MIME, &mime_ptr) and mime_ptr != null) {
            const mime = std.mem.span(mime_ptr);
            if (std.mem.startsWith(u8, mime, "video/")) return i;
        }
    }
    return null;
}

fn frameDurationUsNative(format: *c.AMediaFormat) i64 {
    var fps: i32 = 24;
    if (!c.AMediaFormat_getInt32(format, c.AMEDIAFORMAT_KEY_FRAME_RATE, &fps) or fps <= 0) fps = 24;
    return @divTrunc(1_000_000, @as(i64, fps));
}

fn closeMuxer(muxer: ?*c.AMediaMuxer, started: bool) bool {
    if (muxer) |m| {
        var ok = true;
        if (started) {
            const status = c.AMediaMuxer_stop(m);
            if (status != c.AMEDIA_OK) {
                setError("AMediaMuxer_stop failed status={d}", .{status});
                ok = false;
            }
        }
        _ = c.AMediaMuxer_delete(m);
        return ok;
    }
    return false;
}

fn extractEmergencyClipNative(output_path: [*c]const u8, clip_start_ms: i64, clip_end_ms: i64, source_paths: [*]const [*c]const u8, source_start_ms: [*]const c.jlong, source_end_ms: [*]const c.jlong, count: usize) c.jlong {
    if (clip_end_ms <= clip_start_ms or count == 0) {
        setErrorSlice("invalid emergency clip window");
        return -1;
    }

    const fd = open(output_path, O_CREAT_ANDROID | O_TRUNC_ANDROID | O_RDWR_ANDROID, 0o644);
    if (fd < 0) {
        setErrorSlice("open emergency output failed");
        return -1;
    }
    defer _ = close(fd);

    var muxer: ?*c.AMediaMuxer = c.AMediaMuxer_new(fd, c.AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4);
    if (muxer == null) {
        setErrorSlice("AMediaMuxer_new emergency failed");
        return -1;
    }

    const raw_buffer = malloc(SAMPLE_BUFFER_BYTES) orelse {
        _ = closeMuxer(muxer, false);
        setErrorSlice("malloc emergency sample buffer failed");
        return -1;
    };
    defer free(raw_buffer);
    const buffer: [*]u8 = @ptrCast(raw_buffer);

    var muxer_started = false;
    var muxer_track: isize = -1;
    var next_presentation_us: i64 = 0;
    var written_samples: c.jlong = 0;

    var source_index: usize = 0;
    while (source_index < count) : (source_index += 1) {
        const source_start = source_start_ms[source_index];
        const source_end = source_end_ms[source_index];
        if (source_end <= clip_start_ms or source_start >= clip_end_ms) continue;

        const extractor = c.AMediaExtractor_new() orelse continue;
        defer _ = c.AMediaExtractor_delete(extractor);
        const source_fd = open(source_paths[source_index], O_RDONLY_ANDROID, 0);
        if (source_fd < 0) {
            setError("open emergency source failed index={d}", .{source_index});
            continue;
        }
        defer _ = close(source_fd);
        const source_len = lseek64(source_fd, 0, SEEK_END_ANDROID);
        _ = lseek64(source_fd, 0, SEEK_SET_ANDROID);
        if (source_len <= 0) {
            setError("emergency source invalid length={d} index={d}", .{ source_len, source_index });
            continue;
        }
        const ds_status = c.AMediaExtractor_setDataSourceFd(extractor, source_fd, 0, source_len);
        if (ds_status != c.AMEDIA_OK) {
            setError("AMediaExtractor_setDataSourceFd failed status={d}", .{ds_status});
            continue;
        }
        const track = selectVideoTrackNative(extractor) orelse continue;
        const format = c.AMediaExtractor_getTrackFormat(extractor, track) orelse continue;
        defer _ = c.AMediaFormat_delete(format);

        if (!muxer_started) {
            muxer_track = c.AMediaMuxer_addTrack(muxer.?, format);
            if (muxer_track < 0) {
                _ = closeMuxer(muxer, false);
                setError("AMediaMuxer_addTrack emergency failed track={d}", .{muxer_track});
                return -1;
            }
            const start_status = c.AMediaMuxer_start(muxer.?);
            if (start_status != c.AMEDIA_OK) {
                _ = closeMuxer(muxer, false);
                setError("AMediaMuxer_start emergency failed status={d}", .{start_status});
                return -1;
            }
            muxer_started = true;
        }

        const select_status = c.AMediaExtractor_selectTrack(extractor, track);
        if (select_status != c.AMEDIA_OK) continue;
        const source_clip_start_us = @max(clip_start_ms - source_start, 0) * 1000;
        const source_clip_end_us = @min(clip_end_ms - source_start, source_end - source_start) * 1000;
        if (source_clip_end_us <= 0 or source_clip_start_us >= source_clip_end_us) continue;
        _ = c.AMediaExtractor_seekTo(extractor, source_clip_start_us, c.AMEDIAEXTRACTOR_SEEK_PREVIOUS_SYNC);

        var base_sample_time_us: i64 = std.math.minInt(i64);
        var last_written_relative_us: i64 = std.math.minInt(i64);
        const frame_duration_us = frameDurationUsNative(format);
        while (true) {
            const sample_track = c.AMediaExtractor_getSampleTrackIndex(extractor);
            if (sample_track < 0) break;
            if (@as(usize, @intCast(sample_track)) != track) {
                if (!c.AMediaExtractor_advance(extractor)) break;
                continue;
            }
            const sample_time_us = c.AMediaExtractor_getSampleTime(extractor);
            if (sample_time_us < 0 or sample_time_us >= source_clip_end_us) break;
            if (base_sample_time_us == std.math.minInt(i64)) base_sample_time_us = sample_time_us;
            const relative_us = @max(sample_time_us - base_sample_time_us, 0);
            const sample_size = c.AMediaExtractor_readSampleData(extractor, buffer, SAMPLE_BUFFER_BYTES);
            if (sample_size > 0) {
                var info = c.AMediaCodecBufferInfo{
                    .offset = 0,
                    .size = @intCast(sample_size),
                    .presentationTimeUs = next_presentation_us + relative_us,
                    .flags = @intCast(c.AMediaExtractor_getSampleFlags(extractor)),
                };
                const status = c.AMediaMuxer_writeSampleData(muxer.?, @intCast(muxer_track), buffer, &info);
                if (status != c.AMEDIA_OK) {
                    _ = closeMuxer(muxer, muxer_started);
                    setError("AMediaMuxer_writeSampleData emergency failed status={d}", .{status});
                    return -1;
                }
                written_samples += 1;
                last_written_relative_us = relative_us;
            }
            if (!c.AMediaExtractor_advance(extractor)) break;
        }
        if (last_written_relative_us != std.math.minInt(i64)) next_presentation_us += last_written_relative_us + frame_duration_us;
    }

    const close_ok = closeMuxer(muxer, muxer_started);
    muxer = null;
    if (!close_ok or written_samples <= 0) return -1;
    logi("native emergency clip extracted samples={d}", .{written_samples});
    return written_samples;
}

fn resetInput(env: ?[*c]c.JNIEnv, p: *Pipe, index: usize, delete_texture: bool) void {
    if (index >= 4) return;
    const inp = &p.input[index];
    if (delete_texture and inp.texture != 0 and p.display != c.EGL_NO_DISPLAY) {
        if (makePbufferCurrent(p)) {
            c.glDeleteTextures(1, &inp.texture);
            clearCurrent(p);
        }
        inp.texture = 0;
    }
    if (env) |e| {
        if (inp.surface_texture != null) e.*[0].DeleteGlobalRef.?(e, inp.surface_texture);
    }
    if (inp.surface_texture_native) |st| c.ASurfaceTexture_release(st);
    inp.surface_texture = null;
    inp.surface_texture_native = null;
    inp.dirty = false;
    inp.has_latched_frame = false;
    inp.preview_pending = false;
    inp.frame_generation = 0;
    inp.latched_generation = 0;
    inp.preview_generation = 0;
    inp.encoder_generation = 0;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_getGlesSummary(env: [*c]c.JNIEnv, _: c.jobject) callconv(.c) c.jstring { return newString(env, "GLES/OES Zig native compositor"); }

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_setCompositorRuntimeConfig(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, width: c.jint, height: c.jint, preview_fps: c.jint, encoder_fps: c.jint, side_left_rotation: c.jint, side_right_rotation: c.jint, layout_mode: c.jint, fisheye_enabled: c.jbooleanArray, k1: c.jfloatArray, k2: c.jfloatArray, zoom: c.jfloatArray, center_x: c.jfloatArray, center_y: c.jfloatArray) callconv(.c) c.jboolean {
    const p = lockPipeForHandle(handle) orelse return JNI_FALSE;
    defer unlockPipe(p);
    p.width = width; p.height = height; p.side_left_rotation = side_left_rotation; p.side_right_rotation = side_right_rotation; p.layout_mode = layout_mode;
    if (preview_fps <= 0) { p.preview_max_fps = 0; p.preview_min_interval_ms = 0; } else { p.preview_max_fps = @min(@max(preview_fps, 1), 120); p.preview_min_interval_ms = @divTrunc(1000, p.preview_max_fps); }
    p.encoder_fps = @min(@max(encoder_fps, 1), 120);
    if (fisheye_enabled != null and k1 != null and k2 != null and zoom != null and center_x != null and center_y != null and getArrayLen(env, fisheye_enabled) >= 4 and getArrayLen(env, k1) >= 4 and getArrayLen(env, k2) >= 4 and getArrayLen(env, zoom) >= 4 and getArrayLen(env, center_x) >= 4 and getArrayLen(env, center_y) >= 4) {
        const enabled = env.*[0].GetBooleanArrayElements.?(env, fisheye_enabled, null);
        const k1v = env.*[0].GetFloatArrayElements.?(env, k1, null);
        const k2v = env.*[0].GetFloatArrayElements.?(env, k2, null);
        const zoomv = env.*[0].GetFloatArrayElements.?(env, zoom, null);
        const cx = env.*[0].GetFloatArrayElements.?(env, center_x, null);
        const cy = env.*[0].GetFloatArrayElements.?(env, center_y, null);
        if (enabled != null and k1v != null and k2v != null and zoomv != null and cx != null and cy != null) {
            for (0..4) |i| { p.fisheye_enabled[i] = enabled[i] == JNI_TRUE; p.fisheye_k1[i] = k1v[i]; p.fisheye_k2[i] = k2v[i]; p.fisheye_zoom[i] = if (zoomv[i] <= 0.01) 1.0 else zoomv[i]; p.fisheye_center_x[i] = cx[i]; p.fisheye_center_y[i] = cy[i]; }
        }
        if (enabled != null) env.*[0].ReleaseBooleanArrayElements.?(env, fisheye_enabled, enabled, c.JNI_ABORT);
        if (k1v != null) env.*[0].ReleaseFloatArrayElements.?(env, k1, k1v, c.JNI_ABORT);
        if (k2v != null) env.*[0].ReleaseFloatArrayElements.?(env, k2, k2v, c.JNI_ABORT);
        if (zoomv != null) env.*[0].ReleaseFloatArrayElements.?(env, zoom, zoomv, c.JNI_ABORT);
        if (cx != null) env.*[0].ReleaseFloatArrayElements.?(env, center_x, cx, c.JNI_ABORT);
        if (cy != null) env.*[0].ReleaseFloatArrayElements.?(env, center_y, cy, c.JNI_ABORT);
    }
    updateEncoderLayout(p);
    for (0..4) |i| if (p.preview_quad_width[i] > 0 and p.preview_quad_height[i] > 0) updatePreviewLayout(p, @intCast(i), p.preview_quad_width[i], p.preview_quad_height[i]);
    logd("runtime config size={d}x{d} previewFps={d} encoderFps={d} config={d}", .{ p.width, p.height, p.preview_max_fps, p.encoder_fps, p.config_version });
    return JNI_TRUE;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_setPreviewMaxFps(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, fps: c.jint) callconv(.c) c.jboolean { const p = lockPipeForHandle(handle) orelse return JNI_FALSE; defer unlockPipe(p); if (fps <= 0) { p.preview_max_fps = 0; p.preview_min_interval_ms = 0; } else { p.preview_max_fps = @min(@max(fps, 1), 120); p.preview_min_interval_ms = @divTrunc(1000, p.preview_max_fps); } logd("preview max fps={d} minIntervalMs={d}", .{ p.preview_max_fps, p.preview_min_interval_ms }); return JNI_TRUE; }

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_startRecordingSession(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, fps: c.jint, segment_duration_ms: c.jlong, wall_clock_ms: c.jlong) callconv(.c) c.jlong { const p = lockPipeForHandle(handle) orelse return 0; defer unlockPipe(p); p.recording.recording = true; p.recording.generation += 1; p.recording.fps = @min(@max(fps, 1), 120); p.encoder_fps = p.recording.fps; p.recording.segment_duration_ms = if (segment_duration_ms <= 0) 60000 else segment_duration_ms; p.recording.segment_index = 0; p.recording.pending_segment_index = 0; p.recording.segment_switch_pending = false; p.recording.pending_segment_wall_clock_ms = 0; p.recording.requested_frames = 0; p.recording.rendered_frames = 0; p.recording.dropped_frames = 0; p.recording.last_tick_steady_ms = 0; p.recording.encoder_segment_start_steady_ms = nowMs(); p.recording.last_presentation_time_ns = -1; resetOverlayCache(&p.recording, wall_clock_ms); p.encoder_signal_count = 0; p.encoder_scheduled_count = 0; p.encoder_coalesced_count = 0; const first = floorToSegment(wall_clock_ms, p.recording.segment_duration_ms); p.recording.next_segment_wall_clock_ms = first + p.recording.segment_duration_ms; p.encoder_pending = false; return first; }
export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_stopRecordingSession(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) c.jboolean { const p = lockPipeForHandle(handle) orelse return JNI_FALSE; defer unlockPipe(p); p.recording.recording = false; p.recording.segment_switch_pending = false; resetOverlayCache(&p.recording, 0); p.recording.generation += 1; p.encoder_pending = false; return JNI_TRUE; }

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_setRecordingThumbnailPath(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, path: c.jstring) callconv(.c) c.jboolean {
    if (path == null) return JNI_FALSE;
    const chars = env.*[0].GetStringUTFChars.?(env, path, null) orelse return JNI_FALSE;
    defer env.*[0].ReleaseStringUTFChars.?(env, path, chars);
    const p = lockPipeForHandle(handle) orelse return JNI_FALSE;
    defer unlockPipe(p);
    if (!copyCStringToBuffer(&p.recording.thumbnail_path, chars)) {
        setError("thumbnail path too long", .{});
        return JNI_FALSE;
    }
    p.recording.thumbnail_path_set = true;
    p.recording.thumbnail_written = false;
    return JNI_TRUE;
}

fn recordingTickRenderAndDrainInternal(env: [*c]c.JNIEnv, handle: c.jlong, writer_handle: c.jlong, wall_clock_ms: c.jlong, timeout_us: c.jlong) c.jlong {
    var result: c.jlong = 0;
    var rendered = false;
    {
        const p = lockPipeForHandle(handle) orelse return -1;
        defer unlockPipe(p);
        if (!p.recording.recording) return 0;

        p.recording.requested_frames += 1;
        p.recording.overlay_wall_clock_ms = wall_clock_ms;
        if (!renderEncoderLocked(env, p, false, &rendered)) return -1;
        if (rendered) {
            p.recording.rendered_frames += 1;
            result |= 1;
        } else {
            p.recording.dropped_frames += 1;
            result |= 2;
        }
        if (!p.recording.segment_switch_pending and p.recording.next_segment_wall_clock_ms > 0 and wall_clock_ms >= p.recording.next_segment_wall_clock_ms) {
            const next_index = p.recording.segment_index + 1;
            result |= 4;
            result |= (@as(c.jlong, next_index) << 32);
        }
        p.recording.last_tick_steady_ms = nowMs();
    }

    if (rendered and writer_handle != 0) {
        const w = lockWriterForHandle(writer_handle) orelse return -1;
        defer unlockWriter(w);
        const drained = drainNativeWriterLocked(w, timeout_us);
        if (drained < 0) return -1;
        const drained_capped: c.jlong = @min(drained, 0x00FF_FFFF);
        result |= (drained_capped << 8);
    }
    return result;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_finalRenderAndDrain(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, writer_handle: c.jlong, timeout_us: c.jlong) callconv(.c) c.jlong {
    var result: c.jlong = 0;
    var rendered = false;
    {
        const p = lockPipeForHandle(handle) orelse return -1;
        defer unlockPipe(p);
        if (!renderEncoderLocked(env, p, false, &rendered)) return -1;
        if (rendered) result |= TICK_SHOULD_RENDER;
    }
    if (writer_handle != 0) {
        const w = lockWriterForHandle(writer_handle) orelse return -1;
        defer unlockWriter(w);
        const drained = drainNativeWriterLocked(w, timeout_us);
        if (drained < 0) return -1;
        const drained_capped: c.jlong = @min(drained, TICK_DRAINED_MASK);
        result |= (drained_capped << TICK_DRAINED_SHIFT);
    }
    return result;
}

fn combineWorkerEvent(existing: c.jlong, event: c.jlong) c.jlong {
    if (existing < 0) return existing;
    if (event < 0) return event;
    const flags = (existing | event) & 0xFF;
    const existing_drained = (existing >> TICK_DRAINED_SHIFT) & TICK_DRAINED_MASK;
    const event_drained = (event >> TICK_DRAINED_SHIFT) & TICK_DRAINED_MASK;
    const drained = @min(existing_drained + event_drained, TICK_DRAINED_MASK);
    const existing_next = existing >> TICK_NEXT_INDEX_SHIFT;
    const event_next = event >> TICK_NEXT_INDEX_SHIFT;
    const next_index = @max(existing_next, event_next);
    var combined = flags | (drained << TICK_DRAINED_SHIFT);
    if (next_index > 0) combined |= (next_index << TICK_NEXT_INDEX_SHIFT);
    return combined;
}

fn recordingWorkerLoop(handle: c.jlong, generation: c.jlong) void {
    const env = attachWorkerEnv() orelse {
        setErrorSlice("recording worker failed to attach JNI env");
        if (lockPipeForHandle(handle)) |p| {
            defer unlockPipe(p);
            if (p.recording_worker_generation == generation and p.recording_worker_running) {
                p.recording_worker_last_event = WORKER_ERROR_THREAD_ATTACH;
                p.recording_worker_stop = true;
            }
        }
        return;
    };
    defer detachWorkerEnv();

    while (true) {
        var writer_handle: c.jlong = 0;
        var fps: i32 = 15;
        const p_state = lockPipeForHandle(handle) orelse break;
        if (!p_state.recording_worker_running or p_state.recording_worker_generation != generation or p_state.recording_worker_stop or !p_state.recording.recording) {
            unlockPipe(p_state);
            break;
        }
        writer_handle = p_state.recording_worker_writer_handle;
        fps = p_state.recording.fps;
        if (p_state.recording_worker_paused_for_segment or writer_handle == 0) {
            p_state.recording_worker_condition.waitUncancelable(nativeIo(), &p_state.lock);
            unlockPipe(p_state);
            continue;
        }
        unlockPipe(p_state);

        const event = recordingTickRenderAndDrainInternal(env, handle, writer_handle, wallClockMs(), 0);
        {
            const p = lockPipeForHandle(handle) orelse break;
            defer unlockPipe(p);
            if (p.recording_worker_generation == generation and p.recording_worker_running) {
                if (event < 0) {
                    p.recording_worker_last_event = WORKER_ERROR_TICK_RENDER_DRAIN;
                    p.recording_worker_stop = true;
                    p.recording_worker_condition.broadcast(nativeIo());
                } else if (event != 0) {
                    p.recording_worker_last_event = combineWorkerEvent(p.recording_worker_last_event, event);
                    if ((event & TICK_SEGMENT_DUE) != 0) p.recording_worker_paused_for_segment = true;
                }
            }
        }

        const interval_ms: u64 = @intCast(@max(@divTrunc(1000, @max(fps, 1)), 1));
        sleepMs(interval_ms);
    }

    if (lockPipeForHandle(handle)) |p| {
        defer unlockPipe(p);
        if (p.recording_worker_generation == generation) {
            p.recording_worker_running = false;
            p.recording_worker_stop = true;
            p.recording_worker_writer_handle = 0;
            p.recording_worker_thread = null;
            p.recording_worker_condition.broadcast(nativeIo());
        }
    }
}

fn previewWorkerLoop(handle: c.jlong, generation: c.jlong) void {
    const env = attachWorkerEnv() orelse {
        setErrorSlice("preview worker failed to attach JNI env");
        if (lockPipeForHandle(handle)) |p| {
            defer unlockPipe(p);
            if (p.preview_worker_generation == generation and p.preview_worker_running) {
                p.preview_worker_stop = true;
            }
        }
        return;
    };
    defer detachWorkerEnv();

    while (true) {
        var interval_ms: u64 = 33;
        var rendered_any = false;
        const p = lockPipeForHandle(handle) orelse break;
        if (!p.preview_worker_running or p.preview_worker_generation != generation or p.preview_worker_stop) {
            unlockPipe(p);
            break;
        }
        interval_ms = @intCast(@max(p.preview_min_interval_ms, 1));
        for (0..4) |idx| {
            if (p.preview_surface[idx] == c.EGL_NO_SURFACE or p.input[idx].surface_texture_native == null) continue;
            if (renderPreviewLocked(env, p, @intCast(idx))) rendered_any = true;
            p.input[idx].preview_pending = false;
        }
        unlockPipe(p);
        sleepMs(if (rendered_any) interval_ms else @min(interval_ms, 10));
    }

    if (lockPipeForHandle(handle)) |p| {
        defer unlockPipe(p);
        if (p.preview_worker_generation == generation) {
            p.preview_worker_running = false;
            p.preview_worker_stop = true;
            p.preview_worker_thread = null;
        }
    }
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_startPreviewWorker(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, fps: c.jint) callconv(.c) c.jboolean {
    var generation: c.jlong = 0;
    {
        const p = lockPipeForHandle(handle) orelse return JNI_FALSE;
        defer unlockPipe(p);
        if (p.preview_worker_running) return JNI_TRUE;
        p.preview_worker_running = true;
        p.preview_worker_stop = false;
        p.preview_worker_generation += 1;
        if (fps > 0) {
            p.preview_max_fps = @min(@max(fps, 1), 120);
            p.preview_min_interval_ms = @divTrunc(1000, p.preview_max_fps);
        }
        generation = p.preview_worker_generation;
    }
    const thread = std.Thread.spawn(.{}, previewWorkerLoop, .{ handle, generation }) catch |err| {
        if (lockPipeForHandle(handle)) |p| {
            defer unlockPipe(p);
            if (p.preview_worker_generation == generation) {
                p.preview_worker_running = false;
                p.preview_worker_stop = true;
            }
        }
        setError("preview worker spawn failed: {}", .{err});
        return JNI_FALSE;
    };
    if (lockPipeForHandle(handle)) |p| {
        defer unlockPipe(p);
        if (p.preview_worker_generation == generation) p.preview_worker_thread = thread;
    }
    logd("preview worker started fps={d} generation={d}", .{ fps, generation });
    return JNI_TRUE;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_stopPreviewWorker(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, timeout_ms: c.jlong) callconv(.c) c.jboolean {
    var thread: ?std.Thread = null;
    var generation: c.jlong = 0;
    {
        const p = lockPipeForHandle(handle) orelse return JNI_FALSE;
        defer unlockPipe(p);
        generation = p.preview_worker_generation;
        p.preview_worker_running = false;
        p.preview_worker_stop = true;
        thread = p.preview_worker_thread;
        p.preview_worker_thread = null;
    }
    if (thread) |t| {
        const join_start_ms = nowMs();
        t.join();
        const join_ms = nowMs() - join_start_ms;
        if (timeout_ms > 0 and join_ms > timeout_ms) loge("preview worker stop exceeded timeout generation={d} joinMs={d} timeoutMs={d}", .{ generation, join_ms, timeout_ms })
        else logd("preview worker stopped generation={d} joinMs={d}", .{ generation, join_ms });
    }
    return JNI_TRUE;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_startRecordingWorker(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, writer_handle: c.jlong, fps: c.jint) callconv(.c) c.jboolean {
    var generation: c.jlong = 0;
    {
        const p = lockPipeForHandle(handle) orelse return JNI_FALSE;
        defer unlockPipe(p);
        if (!p.recording.recording or writer_handle == 0 or p.recording_worker_running) {
            setErrorSlice("recording worker start requires active recording, writer, and idle worker");
            return JNI_FALSE;
        }
        p.recording_worker_running = true;
        p.recording_worker_stop = false;
        p.recording_worker_paused_for_segment = false;
        p.recording_worker_writer_handle = writer_handle;
        p.recording_worker_last_event = 0;
        p.recording_worker_generation += 1;
        p.recording.fps = @min(@max(fps, 1), 120);
        generation = p.recording_worker_generation;
        p.recording_worker_condition.broadcast(nativeIo());
    }

    const thread = std.Thread.spawn(.{}, recordingWorkerLoop, .{ handle, generation }) catch |err| {
        if (lockPipeForHandle(handle)) |p| {
            defer unlockPipe(p);
            if (p.recording_worker_generation == generation) {
                p.recording_worker_running = false;
                p.recording_worker_stop = true;
                p.recording_worker_writer_handle = 0;
            }
        }
        setError("recording worker spawn failed: {}", .{err});
        return JNI_FALSE;
    };
    if (lockPipeForHandle(handle)) |p| {
        defer unlockPipe(p);
        if (p.recording_worker_generation == generation) p.recording_worker_thread = thread;
    }
    logd("recording worker started writer={d} fps={d} generation={d}", .{ writer_handle, fps, generation });
    return JNI_TRUE;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_pollRecordingWorker(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) c.jlong {
    const p = lockPipeForHandle(handle) orelse return -1;
    defer unlockPipe(p);
    const event = p.recording_worker_last_event;
    p.recording_worker_last_event = 0;
    return event;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_resumeRecordingWorker(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, writer_handle: c.jlong) callconv(.c) c.jboolean {
    const p = lockPipeForHandle(handle) orelse return JNI_FALSE;
    defer unlockPipe(p);
    if (!p.recording_worker_running or writer_handle == 0) return JNI_FALSE;
    p.recording_worker_writer_handle = writer_handle;
    p.recording_worker_paused_for_segment = false;
    p.recording_worker_last_event = 0;
    p.recording_worker_condition.broadcast(nativeIo());
    return JNI_TRUE;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_stopRecordingWorker(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, timeout_ms: c.jlong) callconv(.c) c.jlong {
    var thread: ?std.Thread = null;
    var event: c.jlong = 0;
    var generation: c.jlong = 0;
    {
        const p = lockPipeForHandle(handle) orelse return -1;
        defer unlockPipe(p);
        event = p.recording_worker_last_event;
        generation = p.recording_worker_generation;
        p.recording_worker_running = false;
        p.recording_worker_stop = true;
        p.recording_worker_paused_for_segment = false;
        p.recording_worker_writer_handle = 0;
        p.recording_worker_last_event = 0;
        thread = p.recording_worker_thread;
        p.recording_worker_thread = null;
        p.recording_worker_condition.broadcast(nativeIo());
    }
    if (thread) |t| {
        const join_start_ms = nowMs();
        t.join();
        const join_ms = nowMs() - join_start_ms;
        if (timeout_ms > 0 and join_ms > timeout_ms) {
            loge("recording worker stop exceeded timeout generation={d} joinMs={d} timeoutMs={d}", .{ generation, join_ms, timeout_ms });
        } else {
            logd("recording worker stopped generation={d} joinMs={d} event={d}", .{ generation, join_ms, event });
        }
    }
    return event;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_snapshotRecordingWorker(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) c.jlongArray {
    var values: [8]c.jlong = [_]c.jlong{0} ** 8;
    if (lockPipeForHandle(handle)) |p| {
        defer unlockPipe(p);
        values[0] = if (p.recording_worker_running) 1 else 0;
        values[1] = if (p.recording_worker_stop) 1 else 0;
        values[2] = if (p.recording_worker_paused_for_segment) 1 else 0;
        values[3] = p.recording_worker_writer_handle;
        values[4] = p.recording_worker_last_event;
        values[5] = p.recording_worker_generation;
        values[6] = p.recording.requested_frames;
        values[7] = p.recording.rendered_frames;
    }
    const arr = env.*[0].NewLongArray.?(env, values.len) orelse return null;
    env.*[0].SetLongArrayRegion.?(env, arr, 0, values.len, &values);
    return arr;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_beginNextRecordingSegment(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) c.jlong {
    const p = lockPipeForHandle(handle) orelse return 0;
    defer unlockPipe(p);
    if (!p.recording.recording) return 0;
    p.recording.segment_switch_pending = true;
    p.recording.pending_segment_index = p.recording.segment_index + 1;
    p.recording.pending_segment_wall_clock_ms = p.recording.next_segment_wall_clock_ms;
    return p.recording.pending_segment_wall_clock_ms;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_completeRecordingSegmentSwitch(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, success: c.jboolean) callconv(.c) c.jboolean {
    const p = lockPipeForHandle(handle) orelse return JNI_FALSE;
    defer unlockPipe(p);
    if (success == JNI_TRUE) {
        p.recording.segment_index = p.recording.pending_segment_index;
        p.recording.next_segment_wall_clock_ms = p.recording.pending_segment_wall_clock_ms + p.recording.segment_duration_ms;
        p.recording.encoder_segment_start_steady_ms = nowMs();
        p.recording.last_presentation_time_ns = -1;
    }
    p.recording.segment_switch_pending = false;
    p.recording.pending_segment_index = 0;
    p.recording.pending_segment_wall_clock_ms = 0;
    return JNI_TRUE;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_nativeExtractEmergencyClip(env: [*c]c.JNIEnv, _: c.jobject, output_path: c.jstring, final_output_path: c.jstring, clip_start_ms: c.jlong, clip_end_ms: c.jlong, source_paths_array: c.jobjectArray, source_start_array: c.jlongArray, source_end_array: c.jlongArray) callconv(.c) c.jlong {
    if (output_path == null or final_output_path == null or source_paths_array == null or source_start_array == null or source_end_array == null) {
        setErrorSlice("nativeExtractEmergencyClip null args");
        return -1;
    }
    const count_jsize = getArrayLen(env, source_paths_array);
    if (count_jsize <= 0 or getArrayLen(env, source_start_array) < count_jsize or getArrayLen(env, source_end_array) < count_jsize) {
        setErrorSlice("nativeExtractEmergencyClip invalid arrays");
        return -1;
    }
    const count: usize = @intCast(count_jsize);
    if (count > 32) {
        setErrorSlice("nativeExtractEmergencyClip too many sources");
        return -1;
    }

    const output_chars = env.*[0].GetStringUTFChars.?(env, output_path, null) orelse {
        setErrorSlice("nativeExtractEmergencyClip output path chars failed");
        return -1;
    };
    defer env.*[0].ReleaseStringUTFChars.?(env, output_path, output_chars);
    const final_chars = env.*[0].GetStringUTFChars.?(env, final_output_path, null) orelse {
        setErrorSlice("nativeExtractEmergencyClip final path chars failed");
        return -1;
    };
    defer env.*[0].ReleaseStringUTFChars.?(env, final_output_path, final_chars);

    const start_values = env.*[0].GetLongArrayElements.?(env, source_start_array, null) orelse {
        setErrorSlice("nativeExtractEmergencyClip start array failed");
        return -1;
    };
    defer env.*[0].ReleaseLongArrayElements.?(env, source_start_array, start_values, JNI_ABORT);
    const end_values = env.*[0].GetLongArrayElements.?(env, source_end_array, null) orelse {
        setErrorSlice("nativeExtractEmergencyClip end array failed");
        return -1;
    };
    defer env.*[0].ReleaseLongArrayElements.?(env, source_end_array, end_values, JNI_ABORT);

    var path_objects: [32]c.jstring = [_]c.jstring{null} ** 32;
    var path_chars: [32][*c]const u8 = [_][*c]const u8{null} ** 32;
    var loaded: usize = 0;
    while (loaded < count) : (loaded += 1) {
        const obj = env.*[0].GetObjectArrayElement.?(env, source_paths_array, @intCast(loaded));
        if (obj == null) {
            setError("nativeExtractEmergencyClip source path null index={d}", .{loaded});
            break;
        }
        path_objects[loaded] = obj;
        path_chars[loaded] = env.*[0].GetStringUTFChars.?(env, obj, null) orelse {
            setError("nativeExtractEmergencyClip source chars failed index={d}", .{loaded});
            break;
        };
    }
    defer {
        var i: usize = 0;
        while (i < loaded) : (i += 1) {
            if (path_chars[i] != null) env.*[0].ReleaseStringUTFChars.?(env, path_objects[i], path_chars[i]);
            if (path_objects[i] != null) env.*[0].DeleteLocalRef.?(env, path_objects[i]);
        }
    }
    if (loaded != count) return -1;

    const result = extractEmergencyClipNative(output_chars, clip_start_ms, clip_end_ms, &path_chars, start_values, end_values, count);
    if (result > 0) {
        _ = unlink(final_chars);
        if (rename(output_chars, final_chars) != 0) {
            _ = unlink(output_chars);
            setErrorSlice("native emergency rename failed");
            return -1;
        }
        var event_thumb: [1024:0]u8 = [_:0]u8{0} ** 1024;
        if (bmpPathForVideo(&event_thumb, final_chars)) {
            var copied_thumb = false;
            var i: usize = 0;
            while (i < loaded and !copied_thumb) : (i += 1) {
                var source_thumb: [1024:0]u8 = [_:0]u8{0} ** 1024;
                if (!bmpPathForVideo(&source_thumb, path_chars[i])) continue;
                _ = unlink(&event_thumb);
                copied_thumb = copyFileNative(&source_thumb, &event_thumb);
                if (copied_thumb) {
                    logi("native emergency thumbnail copied src={s} dst={s}", .{ std.mem.sliceTo(&source_thumb, 0), std.mem.sliceTo(&event_thumb, 0) });
                }
            }
            if (!copied_thumb) logd("native emergency thumbnail source missing dst={s}", .{std.mem.sliceTo(&event_thumb, 0)});
        }
    } else {
        _ = unlink(output_chars);
    }
    return result;
}
export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_createNativeSegmentWriter(env: [*c]c.JNIEnv, _: c.jobject, width: c.jint, height: c.jint, fps: c.jint, bitrate: c.jint, mime_type: c.jstring) callconv(.c) c.jlong {
    if (mime_type == null or width <= 0 or height <= 0 or fps <= 0 or bitrate <= 0) {
        setError("invalid native segment writer config", .{});
        return 0;
    }
    const mime_chars = env.*[0].GetStringUTFChars.?(env, mime_type, null) orelse {
        setError("native writer mime unavailable", .{});
        return 0;
    };
    defer env.*[0].ReleaseStringUTFChars.?(env, mime_type, mime_chars);

    const codec = c.AMediaCodec_createEncoderByType(mime_chars) orelse {
        setError("AMediaCodec_createEncoderByType failed", .{});
        return 0;
    };
    errdefer _ = c.AMediaCodec_delete(codec);

    const format = c.AMediaFormat_new() orelse {
        setError("AMediaFormat_new failed", .{});
        return 0;
    };
    defer _ = c.AMediaFormat_delete(format);
    c.AMediaFormat_setString(format, c.AMEDIAFORMAT_KEY_MIME, mime_chars);
    c.AMediaFormat_setInt32(format, c.AMEDIAFORMAT_KEY_WIDTH, width);
    c.AMediaFormat_setInt32(format, c.AMEDIAFORMAT_KEY_HEIGHT, height);
    c.AMediaFormat_setInt32(format, c.AMEDIAFORMAT_KEY_FRAME_RATE, fps);
    c.AMediaFormat_setInt32(format, c.AMEDIAFORMAT_KEY_BIT_RATE, bitrate);
    c.AMediaFormat_setInt32(format, c.AMEDIAFORMAT_KEY_COLOR_FORMAT, COLOR_FORMAT_SURFACE);
    c.AMediaFormat_setInt32(format, c.AMEDIAFORMAT_KEY_I_FRAME_INTERVAL, 1);

    const configure_status = c.AMediaCodec_configure(codec, format, null, null, c.AMEDIACODEC_CONFIGURE_FLAG_ENCODE);
    if (configure_status != c.AMEDIA_OK) {
        setError("AMediaCodec_configure failed status={d}", .{configure_status});
        return 0;
    }

    var input_window: ?*c.ANativeWindow = null;
    const surface_status = c.AMediaCodec_createInputSurface(codec, &input_window);
    if (surface_status != c.AMEDIA_OK or input_window == null) {
        setError("AMediaCodec_createInputSurface failed status={d}", .{surface_status});
        return 0;
    }

    lockGlobal();
    defer unlockGlobal();
    var slot_index: ?usize = null;
    for (0..MAX_NATIVE_WRITERS) |i| {
        if (!g_native_writer_used[i]) {
            slot_index = i;
            break;
        }
    }
    const index = slot_index orelse {
        setError("no free native writer slots", .{});
        c.ANativeWindow_release(input_window.?);
        _ = c.AMediaCodec_delete(codec);
        return 0;
    };

    const handle = g_next_native_writer_handle;
    g_next_native_writer_handle += 1;
    g_native_writer_used[index] = true;
    g_native_writers[index] = NativeSegmentWriter{
        .handle = handle,
        .codec = codec,
        .input_window = input_window,
        .width = width,
        .height = height,
        .fps = fps,
        .bitrate = bitrate,
        .started = false,
    };
    logi("native writer created handle={d} size={d}x{d} fps={d} bitrate={d}", .{ handle, width, height, fps, bitrate });
    return handle;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_nativeSegmentWriterInputSurface(env: [*c]c.JNIEnv, _: c.jobject, writer_handle: c.jlong) callconv(.c) c.jobject {
    const w = lockWriterForHandle(writer_handle) orelse return null;
    defer unlockWriter(w);
    const window = w.input_window orelse {
        setError("native writer input window unavailable", .{});
        return null;
    };
    return c.ANativeWindow_toSurface(env, window);
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_nativeSegmentWriterStartSegment(env: [*c]c.JNIEnv, _: c.jobject, writer_handle: c.jlong, path: c.jstring, segment_index: c.jint, wall_clock_ms: c.jlong) callconv(.c) c.jboolean {
    _ = segment_index;
    _ = wall_clock_ms;
    if (path == null) {
        setError("native segment writer path unavailable", .{});
        return JNI_FALSE;
    }
    const path_chars = env.*[0].GetStringUTFChars.?(env, path, null) orelse {
        setError("native segment writer path chars unavailable", .{});
        return JNI_FALSE;
    };
    defer env.*[0].ReleaseStringUTFChars.?(env, path, path_chars);

    const w = lockWriterForHandle(writer_handle) orelse return JNI_FALSE;
    defer unlockWriter(w);
    const codec = w.codec orelse return JNI_FALSE;
    if (w.muxer != null or w.fd >= 0) {
        setError("native segment writer already has active segment", .{});
        return JNI_FALSE;
    }
    const fd = open(path_chars, O_CREAT_ANDROID | O_TRUNC_ANDROID | O_RDWR_ANDROID, 0o644);
    if (fd < 0) {
        setError("open native segment file failed", .{});
        return JNI_FALSE;
    }
    const muxer = c.AMediaMuxer_new(fd, c.AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4) orelse {
        _ = close(fd);
        setError("AMediaMuxer_new failed", .{});
        return JNI_FALSE;
    };
    w.fd = fd;
    if (!copyCStringToBuffer(w.output_path[0..], path_chars)) {
        _ = c.AMediaMuxer_delete(muxer);
        _ = close(fd);
        w.muxer = null;
        w.fd = -1;
        setError("native segment writer path too long", .{});
        return JNI_FALSE;
    }
    w.output_path_set = true;
    w.muxer = muxer;
    w.track_index = -1;
    w.muxer_started = false;
    if (!w.started) {
        const status = c.AMediaCodec_start(codec);
        if (status != c.AMEDIA_OK) {
            _ = c.AMediaMuxer_delete(muxer);
            _ = close(fd);
            w.muxer = null;
            w.fd = -1;
            setError("AMediaCodec_start failed status={d}", .{status});
            return JNI_FALSE;
        }
        w.started = true;
    }
    return JNI_TRUE;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_nativeSegmentWriterFinish(env: [*c]c.JNIEnv, _: c.jobject, writer_handle: c.jlong, final_path: c.jstring) callconv(.c) c.jboolean {
    if (final_path == null) {
        setError("native segment finish final path unavailable", .{});
        return JNI_FALSE;
    }
    const final_chars = env.*[0].GetStringUTFChars.?(env, final_path, null) orelse {
        setError("native segment finish final path chars unavailable", .{});
        return JNI_FALSE;
    };
    defer env.*[0].ReleaseStringUTFChars.?(env, final_path, final_chars);

    lockGlobal();
    var writer: ?*NativeSegmentWriter = null;
    for (0..MAX_NATIVE_WRITERS) |i| {
        if (!g_native_writer_used[i] or g_native_writers[i].handle != writer_handle) continue;
        g_native_writer_used[i] = false;
        writer = &g_native_writers[i];
        break;
    }
    if (writer) |w| lockWriter(w);
    unlockGlobal();
    const w = writer orelse {
        setError("native writer finish missing handle", .{});
        return JNI_FALSE;
    };
    defer unlockWriter(w);

    const has_output = w.output_path_set;
    var temp_path: [1024:0]u8 = [_:0]u8{0} ** 1024;
    if (has_output) @memcpy(temp_path[0..], w.output_path[0..]);
    const stopped = stopNativeWriterLocked(w, writer_handle);
    if (!stopped or !has_output) {
        releaseNativeWriterResources(w);
        setError("native writer finish missing output path", .{});
        return JNI_FALSE;
    }
    const renamed = rename(&temp_path, final_chars) == 0;
    if (!renamed) {
        _ = unlink(&temp_path);
        releaseNativeWriterResources(w);
        setError("native writer rename failed", .{});
        return JNI_FALSE;
    }
    releaseNativeWriterResources(w);
    return JNI_TRUE;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_nativeSegmentWriterRelease(_: [*c]c.JNIEnv, _: c.jobject, writer_handle: c.jlong) callconv(.c) c.jboolean {
    lockGlobal();
    var writer: ?*NativeSegmentWriter = null;
    for (0..MAX_NATIVE_WRITERS) |i| {
        if (!g_native_writer_used[i] or g_native_writers[i].handle != writer_handle) continue;
        g_native_writer_used[i] = false;
        writer = &g_native_writers[i];
        break;
    }
    if (writer) |w| lockWriter(w);
    unlockGlobal();
    const w = writer orelse {
        setError("native writer release missing handle", .{});
        return JNI_FALSE;
    };
    releaseNativeWriterResources(w);
    unlockWriter(w);
    return JNI_TRUE;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_createNativeCameraPreview(env: [*c]c.JNIEnv, _: c.jobject, camera_id: c.jstring, surface: c.jobject) callconv(.c) c.jlong {
    if (camera_id == null or surface == null) {
        setError("invalid native camera args", .{});
        return 0;
    }
    const camera_id_chars = env.*[0].GetStringUTFChars.?(env, camera_id, null) orelse {
        setError("native camera id unavailable", .{});
        return 0;
    };
    defer env.*[0].ReleaseStringUTFChars.?(env, camera_id, camera_id_chars);

    var cam = NativeCameraPreview{};
    cam.manager = c.ACameraManager_create() orelse {
        setError("ACameraManager_create failed", .{});
        return 0;
    };
    cam.window = c.ANativeWindow_fromSurface(env, surface) orelse {
        releaseNativeCameraResources(&cam);
        setError("native camera window unavailable", .{});
        return 0;
    };
    var device_callbacks = c.ACameraDevice_StateCallbacks{
        .context = null,
        .onDisconnected = onNativeCameraDisconnected,
        .onError = onNativeCameraError,
    };
    var device: ?*c.ACameraDevice = null;
    var status = c.ACameraManager_openCamera(cam.manager.?, camera_id_chars, &device_callbacks, &device);
    if (status != c.ACAMERA_OK or device == null) {
        releaseNativeCameraResources(&cam);
        setError("ACameraManager_openCamera failed status={d}", .{status});
        return 0;
    }
    cam.device = device;

    status = c.ACaptureSessionOutputContainer_create(&cam.outputs);
    if (status != c.ACAMERA_OK or cam.outputs == null) {
        releaseNativeCameraResources(&cam);
        setError("ACaptureSessionOutputContainer_create failed status={d}", .{status});
        return 0;
    }
    status = c.ACaptureSessionOutput_create(cam.window.?, &cam.output);
    if (status != c.ACAMERA_OK or cam.output == null) {
        releaseNativeCameraResources(&cam);
        setError("ACaptureSessionOutput_create failed status={d}", .{status});
        return 0;
    }
    status = c.ACaptureSessionOutputContainer_add(cam.outputs.?, cam.output.?);
    if (status != c.ACAMERA_OK) {
        releaseNativeCameraResources(&cam);
        setError("ACaptureSessionOutputContainer_add failed status={d}", .{status});
        return 0;
    }
    status = c.ACameraDevice_createCaptureRequest(cam.device.?, c.TEMPLATE_PREVIEW, &cam.request);
    if (status != c.ACAMERA_OK or cam.request == null) {
        releaseNativeCameraResources(&cam);
        setError("ACameraDevice_createCaptureRequest failed status={d}", .{status});
        return 0;
    }
    status = c.ACameraOutputTarget_create(cam.window.?, &cam.target);
    if (status != c.ACAMERA_OK or cam.target == null) {
        releaseNativeCameraResources(&cam);
        setError("ACameraOutputTarget_create failed status={d}", .{status});
        return 0;
    }
    status = c.ACaptureRequest_addTarget(cam.request.?, cam.target.?);
    if (status != c.ACAMERA_OK) {
        releaseNativeCameraResources(&cam);
        setError("ACaptureRequest_addTarget failed status={d}", .{status});
        return 0;
    }
    var session_callbacks = c.ACameraCaptureSession_stateCallbacks{
        .context = null,
        .onClosed = onNativeSessionClosed,
        .onReady = onNativeSessionReady,
        .onActive = onNativeSessionActive,
    };
    status = c.ACameraDevice_createCaptureSession(cam.device.?, cam.outputs.?, &session_callbacks, &cam.session);
    if (status != c.ACAMERA_OK or cam.session == null) {
        releaseNativeCameraResources(&cam);
        setError("ACameraDevice_createCaptureSession failed status={d}", .{status});
        return 0;
    }
    var request_array = [_]?*c.ACaptureRequest{cam.request.?};
    status = c.ACameraCaptureSession_setRepeatingRequest(cam.session.?, null, 1, @ptrCast(&request_array), &cam.sequence_id);
    if (status != c.ACAMERA_OK) {
        releaseNativeCameraResources(&cam);
        setError("ACameraCaptureSession_setRepeatingRequest failed status={d}", .{status});
        return 0;
    }

    lockGlobal();
    defer unlockGlobal();
    var slot_index: ?usize = null;
    for (0..MAX_NATIVE_CAMERAS) |i| {
        if (!g_native_camera_used[i]) {
            slot_index = i;
            break;
        }
    }
    const index = slot_index orelse {
        releaseNativeCameraResources(&cam);
        setError("no free native camera slots", .{});
        return 0;
    };
    const handle = g_next_native_camera_handle;
    g_next_native_camera_handle += 1;
    cam.handle = handle;
    g_native_camera_used[index] = true;
    g_native_cameras[index] = cam;
    logi("NDK camera preview started handle={d}", .{handle});
    return handle;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_releaseNativeCameraPreview(_: [*c]c.JNIEnv, _: c.jobject, camera_handle: c.jlong) callconv(.c) c.jboolean {
    lockGlobal();
    var camera: ?*NativeCameraPreview = null;
    for (0..MAX_NATIVE_CAMERAS) |i| {
        if (!g_native_camera_used[i] or g_native_cameras[i].handle != camera_handle) continue;
        g_native_camera_used[i] = false;
        camera = &g_native_cameras[i];
        break;
    }
    unlockGlobal();
    const cam = camera orelse {
        setError("native camera release missing handle", .{});
        return JNI_FALSE;
    };
    releaseNativeCameraResources(cam);
    return JNI_TRUE;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_getMetricsSnapshot(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) c.jlongArray {
    var values = [_]c.jlong{0} ** 48;
    if (lockPipeForHandle(handle)) |p| {
        values[0]=p.preview_render_count; values[1]=p.encoder_render_count; values[2]=p.encoder_drop_count; values[3]=p.no_surface_count; values[4]=p.last_render_ms; values[5]=p.recording.requested_frames; values[6]=p.recording.rendered_frames; values[7]=p.recording.dropped_frames; values[8]=p.recording.segment_index; values[9]=if(p.recording.segment_switch_pending)1 else 0; values[10]=p.recording.pending_segment_index; values[11]=p.recording.next_segment_wall_clock_ms; values[12]=p.encoder_signal_count; values[13]=p.encoder_scheduled_count; values[14]=p.encoder_coalesced_count; values[15]=p.preview_max_fps; values[16]=p.preview_min_interval_ms; values[17]=p.config_version; values[18]=if(p.encoder_pending)1 else 0; values[19]=p.recording.generation; for (0..4) |i| { const base = 20 + i*7; values[base]=@max(p.input[i].frame_signal_count, p.input[i].update_count); values[base+1]=p.input[i].preview_scheduled_count; values[base+2]=p.input[i].preview_delayed_count; values[base+3]=p.input[i].preview_coalesced_count; values[base+4]=p.input[i].update_count; values[base+5]=p.input[i].preview_render_count; values[base+6]=p.input[i].preview_drop_count; }
        unlockPipe(p);
    }
    const result = env.*[0].NewLongArray.?(env, 48);
    if (result != null) env.*[0].SetLongArrayRegion.?(env, result, 0, 48, &values);
    return result;
}

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_createCompositor(_: [*c]c.JNIEnv, _: c.jobject, width: c.jint, height: c.jint) callconv(.c) c.jlong {
    lockGlobal();
    var slot: ?usize = null;
    for (0..MAX_PIPES) |i| {
        if (!g_used[i]) {
            g_pipes[i] = Pipe{};
            g_pipes[i].width = width;
            g_pipes[i].height = height;
            g_used[i] = true;
            slot = i;
            break;
        }
    }
    unlockGlobal();

    const i = slot orelse {
        setError("no free native pipe slots", .{});
        return 0;
    };
    const p = &g_pipes[i];
    lockPipe(p);
    const ok = initEgl(p);
    if (ok) updateEncoderLayout(p);
    unlockPipe(p);
    if (!ok) {
        lockGlobal();
        g_used[i] = false;
        g_pipes[i] = Pipe{};
        unlockGlobal();
        return 0;
    }

    lockGlobal();
    const handle = g_next_handle;
    g_next_handle += 1;
    p.handle = handle;
    unlockGlobal();
    return handle;
}
export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_createOesTexture(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint) callconv(.c) c.jint { const p = lockPipeForHandle(handle) orelse return 0; defer unlockPipe(p); if (index < 0 or index >= 4 or !initEgl(p) or !makePbufferCurrent(p)) return 0; const i: usize = @intCast(index); if (p.input[i].texture != 0) { c.glDeleteTextures(1, &p.input[i].texture); p.input[i].texture = 0; } var tex: c.GLuint = 0; c.glGenTextures(1, &tex); if (tex == 0) { setError("glGenTextures returned 0", .{}); clearCurrent(p); return 0; } c.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex); c.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, c.GL_TEXTURE_MIN_FILTER, c.GL_LINEAR); c.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, c.GL_TEXTURE_MAG_FILTER, c.GL_LINEAR); c.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, c.GL_TEXTURE_WRAP_S, c.GL_CLAMP_TO_EDGE); c.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, c.GL_TEXTURE_WRAP_T, c.GL_CLAMP_TO_EDGE); if (glError("createOesTexture")) |e| { setErrorSlice(e); c.glDeleteTextures(1, &tex); clearCurrent(p); return 0; } p.input[i].texture = tex; clearCurrent(p); logd("created OES texture index={d} tex={d}", .{index, tex}); return @intCast(tex); }
export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_destroyOesInput(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint) callconv(.c) c.jboolean { const p = lockPipeForHandle(handle) orelse return JNI_FALSE; defer unlockPipe(p); if (index < 0 or index >= 4) return JNI_FALSE; resetInput(env, p, @intCast(index), true); logd("destroyed OES input index={d}", .{index}); return JNI_TRUE; }
export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_createOesInput(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint, surface_texture: c.jobject) callconv(.c) c.jboolean {
    const p = lockPipeForHandle(handle) orelse return JNI_FALSE;
    defer unlockPipe(p);
    if (index < 0 or index >= 4 or surface_texture == null) return JNI_FALSE;
    const i: usize = @intCast(index);
    resetInput(env, p, i, false);
    const native_st = c.ASurfaceTexture_fromSurfaceTexture(env, surface_texture) orelse {
        setErrorSlice("ASurfaceTexture_fromSurfaceTexture failed");
        return JNI_FALSE;
    };
    p.input[i].surface_texture = env.*[0].NewGlobalRef.?(env, surface_texture);
    p.input[i].surface_texture_native = native_st;
    p.input[i].dirty = false;
    p.input[i].has_latched_frame = false;
    p.input[i].preview_pending = false;
    p.input[i].frame_generation = 0;
    p.input[i].latched_generation = 0;
    p.input[i].preview_generation = 0;
    p.input[i].encoder_generation = 0;
    logd("created OES input index={d}", .{index});
    return JNI_TRUE;
}
fn attachPreviewSurfaceLocked(env: [*c]c.JNIEnv, p: *Pipe, index: c.jint, surface: c.jobject, apply_fisheye: bool, apply_native_transform: bool) c.jboolean { if (index < 0 or index >= 4 or !initEgl(p)) return JNI_FALSE; const i: usize = @intCast(index); if (p.preview_surface[i] != c.EGL_NO_SURFACE) { if (p.current_surface == p.preview_surface[i]) clearCurrent(p); _ = c.eglDestroySurface(p.display, p.preview_surface[i]); p.preview_surface[i] = c.EGL_NO_SURFACE; } if (p.preview_window[i]) |w| c.ANativeWindow_release(w); p.preview_window[i] = c.ANativeWindow_fromSurface(env, surface); if (p.preview_window[i] == null) { setError("preview window unavailable", .{}); return JNI_FALSE; } p.preview_surface[i] = c.eglCreateWindowSurface(p.display, p.config, p.preview_window[i], null); if (p.preview_surface[i] == c.EGL_NO_SURFACE) { setErrorSlice(eglError("eglCreateWindowSurface preview failed")); c.ANativeWindow_release(p.preview_window[i].?); p.preview_window[i] = null; return JNI_FALSE; } p.preview_apply_fisheye[i] = apply_fisheye; p.preview_apply_native_transform[i] = apply_native_transform; updatePreviewLayout(p, index, c.ANativeWindow_getWidth(p.preview_window[i].?), c.ANativeWindow_getHeight(p.preview_window[i].?)); logd("attached preview surface index={d} size={d}x{d} fisheye={d} nativeTransform={d}", .{index, c.ANativeWindow_getWidth(p.preview_window[i].?), c.ANativeWindow_getHeight(p.preview_window[i].?), if(apply_fisheye)@as(i32,1) else @as(i32,0), if(apply_native_transform)@as(i32,1) else @as(i32,0)}); return JNI_TRUE; }
export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_attachPreviewSurfaceWithMode(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint, surface: c.jobject, apply_fisheye: c.jboolean, apply_native_transform: c.jboolean) callconv(.c) c.jboolean { const p = lockPipeForHandle(handle) orelse return JNI_FALSE; defer unlockPipe(p); return attachPreviewSurfaceLocked(env, p, index, surface, apply_fisheye == JNI_TRUE, apply_native_transform == JNI_TRUE); }
export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_detachPreviewSurface(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint) callconv(.c) c.jboolean { const p = lockPipeForHandle(handle) orelse return JNI_FALSE; defer unlockPipe(p); if (index < 0 or index >= 4) return JNI_FALSE; const i: usize = @intCast(index); if (p.preview_surface[i] != c.EGL_NO_SURFACE) { if (p.current_surface == p.preview_surface[i]) clearCurrent(p); _ = c.eglDestroySurface(p.display, p.preview_surface[i]); p.preview_surface[i] = c.EGL_NO_SURFACE; } if (p.preview_window[i]) |w| { c.ANativeWindow_release(w); p.preview_window[i] = null; } p.input[i].preview_pending = false; return JNI_TRUE; }

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_attachEncoderSurface(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, surface: c.jobject) callconv(.c) c.jboolean { const p = lockPipeForHandle(handle) orelse return JNI_FALSE; defer unlockPipe(p); if (!initEgl(p)) return JNI_FALSE; const new_window = c.ANativeWindow_fromSurface(env, surface); if (new_window == null) { setError("encoder window unavailable", .{}); return JNI_FALSE; } const new_surface = c.eglCreateWindowSurface(p.display, p.config, new_window, null); if (new_surface == c.EGL_NO_SURFACE) { setErrorSlice(eglError("eglCreateWindowSurface encoder failed")); c.ANativeWindow_release(new_window); return JNI_FALSE; } const old_surface = p.encoder_surface; const old_window = p.encoder_window; p.encoder_surface = new_surface; p.encoder_window = new_window; if (old_surface != c.EGL_NO_SURFACE) { if (p.current_surface == old_surface) clearCurrent(p); _ = c.eglDestroySurface(p.display, old_surface); } if (old_window) |w| c.ANativeWindow_release(w); p.encoder_frame_index = 0; if (p.recording.recording) { p.recording.encoder_segment_start_steady_ms = nowMs(); p.recording.last_presentation_time_ns = -1; } p.encoder_pending = false; p.encoder_generation += 1; return JNI_TRUE; }
export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_detachEncoderSurface(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) c.jboolean { const p = lockPipeForHandle(handle) orelse return JNI_FALSE; defer unlockPipe(p); if (p.encoder_surface != c.EGL_NO_SURFACE) { if (p.current_surface == p.encoder_surface) clearCurrent(p); _ = c.eglDestroySurface(p.display, p.encoder_surface); p.encoder_surface = c.EGL_NO_SURFACE; } if (p.encoder_window) |w| { c.ANativeWindow_release(w); p.encoder_window = null; } p.encoder_generation = 0; p.encoder_frame_index = 0; p.encoder_pending = false; return JNI_TRUE; }

export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_releaseCompositor(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) void {
    lockGlobal();
    var pipe: ?*Pipe = null;
    var pipe_index: usize = 0;
    for (0..MAX_PIPES) |idx| {
        if (!g_used[idx] or g_pipes[idx].handle != handle) continue;
        pipe = &g_pipes[idx];
        pipe_index = idx;
        break;
    }
    if (pipe) |p| lockPipe(p);
    if (pipe != null and g_used[pipe_index] and g_pipes[pipe_index].handle == handle) g_pipes[pipe_index].handle = 0;
    unlockGlobal();

    const p = pipe orelse return;
    if (p.display != c.EGL_NO_DISPLAY) {
        _ = makePbufferCurrent(p);
        for (0..4) |i| {
            if (p.preview_surface[i] != c.EGL_NO_SURFACE) _ = c.eglDestroySurface(p.display, p.preview_surface[i]);
            if (p.input[i].texture != 0) c.glDeleteTextures(1, &p.input[i].texture);
        }
        if (p.encoder_surface != c.EGL_NO_SURFACE) _ = c.eglDestroySurface(p.display, p.encoder_surface);
        if (p.overlay_font_texture != 0) c.glDeleteTextures(1, &p.overlay_font_texture);
        if (p.program != 0) c.glDeleteProgram(p.program);
        if (p.overlay_program != 0) c.glDeleteProgram(p.overlay_program);
        if (p.overlay_text_program != 0) c.glDeleteProgram(p.overlay_text_program);
        clearCurrent(p);
        if (p.pbuffer != c.EGL_NO_SURFACE) _ = c.eglDestroySurface(p.display, p.pbuffer);
        if (p.context != c.EGL_NO_CONTEXT) _ = c.eglDestroyContext(p.display, p.context);
        _ = c.eglTerminate(p.display);
    }
    for (0..4) |i| {
        if (p.input[i].surface_texture_native) |st| c.ASurfaceTexture_release(st);
        if (p.input[i].surface_texture != null) env.*[0].DeleteGlobalRef.?(env, p.input[i].surface_texture);
        if (p.preview_window[i]) |w| c.ANativeWindow_release(w);
    }
    if (p.encoder_window) |w| c.ANativeWindow_release(w);

    lockGlobal();
    if (g_used[pipe_index] and &g_pipes[pipe_index] == p and p.handle == 0) g_used[pipe_index] = false;
    unlockGlobal();
    p.* = Pipe{};
    unlockPipe(p);
}
export fn Java_com_kooo_evcam_v2_nativebridge_GlesNative_getLastError(env: [*c]c.JNIEnv, _: c.jobject) callconv(.c) c.jstring { return newString(env, &g_last_error); }
