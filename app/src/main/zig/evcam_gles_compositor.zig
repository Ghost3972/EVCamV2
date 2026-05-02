
const std = @import("std");

const c = @cImport({
    @cInclude("jni.h");
    @cInclude("android/bitmap.h");
    @cInclude("android/native_window_jni.h");
    @cInclude("android/log.h");
    @cInclude("media/NdkMediaCodec.h");
    @cInclude("media/NdkMediaFormat.h");
    @cInclude("media/NdkMediaMuxer.h");
    @cInclude("EGL/egl.h");
    @cInclude("EGL/eglext.h");
    @cInclude("GLES2/gl2.h");
    @cInclude("GLES2/gl2ext.h");
});

// This compositor has no stream I/O path. For future native I/O code, use Zig
// 0.16's std.Io namespace; std.io was removed.
comptime { _ = std.Io; }

const TAG = "EVCamGLES";
const MAX_PIPES = 8;
const EGL_RECORDABLE_ANDROID: c.EGLint = 0x3142;
const GL_TEXTURE_EXTERNAL_OES: c.GLenum = 0x8D65;
const JNI_TRUE: c.jboolean = 1;
const JNI_FALSE: c.jboolean = 0;
const CHECK_RENDER_GL_ERROR = false;
const PREVIEW_REQUEST_LOCK_BUSY: c.jlong = -2;
const MAX_NATIVE_WRITERS = 4;
const COLOR_FORMAT_SURFACE: i32 = 0x7F000789;
const O_RDWR_ANDROID: c_int = 2;
const O_CREAT_ANDROID: c_int = 64;
const O_TRUNC_ANDROID: c_int = 512;

extern fn open(path: [*c]const u8, flags: c_int, mode: c_int) c_int;
extern fn close(fd: c_int) c_int;

const EglPresentationTimeAndroidFn = *const fn (c.EGLDisplay, c.EGLSurface, c.EGLnsecsANDROID) callconv(.c) c.EGLBoolean;

const NativeSegmentWriter = struct {
    handle: c.jlong = 0,
    codec: ?*c.AMediaCodec = null,
    muxer: ?*c.AMediaMuxer = null,
    input_window: ?*c.ANativeWindow = null,
    fd: c_int = -1,
    track_index: c_int = -1,
    width: i32 = 0,
    height: i32 = 0,
    fps: i32 = 0,
    bitrate: i32 = 0,
    started: bool = false,
    muxer_started: bool = false,
};

const Input = struct {
    surface_texture: c.jobject = null,
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
    overlay_bitmap_texture: c.GLuint = 0,
    overlay_bitmap_width: i32 = 0,
    overlay_bitmap_height: i32 = 0,
    overlay_bitmap_x: f32 = 0,
    overlay_bitmap_y: f32 = 0,
    overlay_bitmap_batch: TexturedOverlayBatch = TexturedOverlayBatch{},
};

const Pipe = struct {
    lock_flag: u32 = 0,
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

var g_lock_flag: u32 = 0;
var g_pipes: [MAX_PIPES]Pipe = [_]Pipe{Pipe{}} ** MAX_PIPES;
var g_used: [MAX_PIPES]bool = [_]bool{false} ** MAX_PIPES;
var g_next_handle: c.jlong = 1;
var g_last_error: [256:0]u8 = initError("OK");
var g_error_scratch: [128:0]u8 = [_:0]u8{0} ** 128;
var g_update_tex_image_method: c.jmethodID = null;
var g_presentation_time_android: ?EglPresentationTimeAndroidFn = null;
var g_native_writers: [MAX_NATIVE_WRITERS]NativeSegmentWriter = [_]NativeSegmentWriter{NativeSegmentWriter{}} ** MAX_NATIVE_WRITERS;
var g_native_writer_used: [MAX_NATIVE_WRITERS]bool = [_]bool{false} ** MAX_NATIVE_WRITERS;
var g_next_native_writer_handle: c.jlong = 1;

fn initError(comptime s: []const u8) [256:0]u8 {
    var out: [256:0]u8 = [_:0]u8{0} ** 256;
    @memcpy(out[0..s.len], s);
    return out;
}


fn lockGlobal() void {
    while (@cmpxchgStrong(u32, &g_lock_flag, 0, 1, .acquire, .monotonic) != null) {
        // busy wait; native calls are short and serialized like the previous C++ mutex
    }
}

fn tryLockGlobal() bool {
    return @cmpxchgStrong(u32, &g_lock_flag, 0, 1, .acquire, .monotonic) == null;
}

fn tryLockGlobalBounded(iterations: usize) bool {
    for (0..iterations) |_| {
        if (tryLockGlobal()) return true;
    }
    return false;
}

fn unlockGlobal() void {
    @atomicStore(u32, &g_lock_flag, 0, .release);
}

fn lockPipe(p: *Pipe) void {
    while (@cmpxchgStrong(u32, &p.lock_flag, 0, 1, .acquire, .monotonic) != null) {}
}

fn tryLockPipe(p: *Pipe) bool {
    return @cmpxchgStrong(u32, &p.lock_flag, 0, 1, .acquire, .monotonic) == null;
}

fn tryLockPipeBounded(p: *Pipe, iterations: usize) bool {
    for (0..iterations) |_| {
        if (tryLockPipe(p)) return true;
    }
    return false;
}

fn unlockPipe(p: *Pipe) void {
    @atomicStore(u32, &p.lock_flag, 0, .release);
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

fn loge(comptime fmt: []const u8, args: anytype) void {
    var buf: [512:0]u8 = [_:0]u8{0} ** 512;
    const msg = std.fmt.bufPrintZ(&buf, fmt, args) catch "log format error";
    _ = c.__android_log_print(c.ANDROID_LOG_ERROR, TAG, "%s", msg.ptr);
}

fn logd(comptime fmt: []const u8, args: anytype) void {
    var buf: [512:0]u8 = [_:0]u8{0} ** 512;
    const msg = std.fmt.bufPrintZ(&buf, fmt, args) catch "log format error";
    _ = c.__android_log_print(c.ANDROID_LOG_DEBUG, TAG, "%s", msg.ptr);
}

fn logi(comptime fmt: []const u8, args: anytype) void {
    var buf: [512:0]u8 = [_:0]u8{0} ** 512;
    const msg = std.fmt.bufPrintZ(&buf, fmt, args) catch "log format error";
    _ = c.__android_log_print(c.ANDROID_LOG_INFO, TAG, "%s", msg.ptr);
}

fn setError(comptime fmt: []const u8, args: anytype) void {
    @memset(g_last_error[0..], 0);
    const msg = std.fmt.bufPrintZ(&g_last_error, fmt, args) catch "error format failed";
    loge("{s}", .{msg});
}

fn setErrorSlice(msg: [:0]const u8) void {
    @memset(g_last_error[0..], 0);
    const n = @min(msg.len, g_last_error.len - 1);
    @memcpy(g_last_error[0..n], msg[0..n]);
    loge("{s}", .{msg});
}

fn eglError(what: []const u8) [:0]const u8 {
    @memset(g_error_scratch[0..], 0);
    return std.fmt.bufPrintZ(&g_error_scratch, "{s} egl=0x{x:0>4}", .{ what, c.eglGetError() }) catch "egl error format failed";
}

fn glError(what: []const u8) ?[:0]const u8 {
    const err = c.glGetError();
    if (err == c.GL_NO_ERROR) return null;
    @memset(g_error_scratch[0..], 0);
    return std.fmt.bufPrintZ(&g_error_scratch, "{s} gl=0x{x:0>4}", .{ what, err }) catch "gl error format failed";
}

const Timespec = extern struct { tv_sec: i64, tv_nsec: i64 };
extern fn clock_gettime(clock_id: c_int, ts: *Timespec) c_int;
const CLOCK_MONOTONIC: c_int = 1;

fn nowMs() i64 {
    var ts: Timespec = undefined;
    if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
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

fn updateSurfaceTexture(env: [*c]c.JNIEnv, st: c.jobject) bool {
    if (g_update_tex_image_method == null) {
        const cls = env.*[0].GetObjectClass.?(env, st);
        g_update_tex_image_method = env.*[0].GetMethodID.?(env, cls, "updateTexImage", "()V");
        env.*[0].DeleteLocalRef.?(env, cls);
    }
    env.*[0].CallVoidMethod.?(env, st, g_update_tex_image_method);
    if (env.*[0].ExceptionCheck.?(env) == JNI_TRUE) {
        env.*[0].ExceptionClear.?(env);
        setError("SurfaceTexture.updateTexImage threw", .{});
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
    r.overlay_text_batch.len = 0;
    r.overlay_bitmap_width = 0;
    r.overlay_bitmap_height = 0;
    r.overlay_bitmap_batch.len = 0;
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

fn rebuildOverlayBitmapGeometry(p: *Pipe) void {
    const r = &p.recording;
    if (r.overlay_bitmap_width <= 0 or r.overlay_bitmap_height <= 0) return;
    if (r.overlay_geometry_width == p.width and r.overlay_geometry_height == p.height and r.overlay_bitmap_batch.len > 0) return;
    r.overlay_bitmap_batch.len = 0;
    appendOverlayTexturedRect(
        &r.overlay_bitmap_batch,
        p,
        r.overlay_bitmap_x,
        r.overlay_bitmap_y,
        @floatFromInt(r.overlay_bitmap_width),
        @floatFromInt(r.overlay_bitmap_height),
        0.0,
        0.0,
        1.0,
        1.0,
    );
    r.overlay_geometry_width = p.width;
    r.overlay_geometry_height = p.height;
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
    r.overlay_text_batch.len = 0;

    var cursor = x;
    for (text) |ch| {
        appendOverlayChar(&r.overlay_text_batch, p, ch, cursor, y, scale);
        cursor += overlayCharAdvance(ch, scale);
    }

    r.overlay_geometry_second = second;
    r.overlay_geometry_width = p.width;
    r.overlay_geometry_height = p.height;
}

fn drawOverlay(p: *Pipe) void {
    if (!p.recording.recording or p.recording.overlay_bitmap_texture == 0) return;
    rebuildOverlayBitmapGeometry(p);
    if (p.recording.overlay_bitmap_batch.len == 0) return;

    c.glEnable(c.GL_BLEND);
    c.glBlendFunc(c.GL_SRC_ALPHA, c.GL_ONE_MINUS_SRC_ALPHA);

    c.glUseProgram(p.overlay_text_program);
    c.glEnableVertexAttribArray(@intCast(p.overlay_text_pos_loc));
    c.glEnableVertexAttribArray(@intCast(p.overlay_text_tex_loc));
    flushOverlayTextureBatch(p, &p.recording.overlay_bitmap_batch, p.recording.overlay_bitmap_texture, .{ 1.0, 1.0, 1.0, 1.0 });

    c.glDisable(c.GL_BLEND);
    c.glDisableVertexAttribArray(@intCast(p.overlay_text_tex_loc));
    c.glDisableVertexAttribArray(@intCast(p.overlay_text_pos_loc));
    c.glUseProgram(p.program);
}

fn renderPreviewLocked(env: [*c]c.JNIEnv, p: *Pipe, index: i32) bool {
    if (index < 0 or index >= 4) return false;
    const i: usize = @intCast(index);
    if (p.input[i].surface_texture == null) return false;
    if (p.preview_surface[i] == c.EGL_NO_SURFACE) { p.input[i].preview_drop_count += 1; return true; }
    const start = nowMs();
    if (!makeCurrent(p, p.preview_surface[i])) return false;
    if (p.input[i].preview_generation != p.input[i].frame_generation) {
        if (p.input[i].latched_generation != p.input[i].frame_generation) {
            p.input[i].dirty_count += 1;
            if (!updateSurfaceTexture(env, p.input[i].surface_texture)) { clearCurrent(p); return false; }
            p.input[i].latched_generation = p.input[i].frame_generation;
            p.input[i].dirty = false;
            p.input[i].has_latched_frame = true;
            p.input[i].update_count += 1;
        }
        p.input[i].preview_generation = p.input[i].frame_generation;
    }
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
fn hasDirtyInput(p: *const Pipe) bool { for (p.input) |inp| if (inp.surface_texture != null and inp.encoder_generation != inp.frame_generation) return true; return false; }
fn updateDirtyInputsLocked(env: [*c]c.JNIEnv, p: *Pipe) bool {
    for (&p.input) |*inp| {
        if (inp.surface_texture != null and inp.encoder_generation != inp.frame_generation) {
            if (inp.latched_generation != inp.frame_generation) {
                inp.dirty_count += 1;
                if (!updateSurfaceTexture(env, inp.surface_texture)) return false;
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
        _ = std.fmt.bufPrintZ(&p.last_render_error, "OK", .{}) catch {};
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

fn drainNativeWriterLocked(w: *NativeSegmentWriter, timeout_us: c.jlong) c.jlong {
    const codec = w.codec orelse return -1;
    var info: c.AMediaCodecBufferInfo = undefined;
    var drained: c.jlong = 0;
    while (true) {
        const index = c.AMediaCodec_dequeueOutputBuffer(codec, &info, timeout_us);
        if (index >= 0) {
            defer _ = c.AMediaCodec_releaseOutputBuffer(codec, @intCast(index), false);
            const buffer = c.AMediaCodec_getOutputBuffer(codec, @intCast(index), null);
            if (buffer != null and w.muxer != null and w.muxer_started and w.track_index >= 0 and info.size > 0 and (info.flags & c.AMEDIACODEC_BUFFER_FLAG_CODEC_CONFIG) == 0) {
                const status = c.AMediaMuxer_writeSampleData(w.muxer.?, @intCast(w.track_index), buffer, &info);
                if (status != c.AMEDIA_OK) {
                    setError("AMediaMuxer_writeSampleData failed status={d}", .{status});
                    return -1;
                }
                drained += 1;
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
    inp.surface_texture = null;
    inp.dirty = false;
    inp.has_latched_frame = false;
    inp.preview_pending = false;
    inp.frame_generation = 0;
    inp.latched_generation = 0;
    inp.preview_generation = 0;
    inp.encoder_generation = 0;
}

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_getNativeVersion(env: [*c]c.JNIEnv, _: c.jobject) callconv(.c) c.jstring { return newString(env, "gles-oes-zig-1"); }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_isVulkanAvailable(_: [*c]c.JNIEnv, _: c.jobject) callconv(.c) c.jboolean { return JNI_FALSE; }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_getVulkanSummary(env: [*c]c.JNIEnv, _: c.jobject) callconv(.c) c.jstring { return newString(env, "GLES/OES Zig native compositor"); }

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_setCompositorRuntimeConfig(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, width: c.jint, height: c.jint, preview_fps: c.jint, encoder_fps: c.jint, side_left_rotation: c.jint, side_right_rotation: c.jint, layout_mode: c.jint, fisheye_enabled: c.jbooleanArray, k1: c.jfloatArray, k2: c.jfloatArray, zoom: c.jfloatArray, center_x: c.jfloatArray, center_y: c.jfloatArray) callconv(.c) c.jboolean {
    lockGlobal(); defer unlockGlobal();
    const p = getPipe(handle) orelse return JNI_FALSE;
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

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_setPreviewMaxFps(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, fps: c.jint) callconv(.c) c.jboolean { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return JNI_FALSE; if (fps <= 0) { p.preview_max_fps = 0; p.preview_min_interval_ms = 0; } else { p.preview_max_fps = @min(@max(fps, 1), 120); p.preview_min_interval_ms = @divTrunc(1000, p.preview_max_fps); } logd("preview max fps={d} minIntervalMs={d}", .{ p.preview_max_fps, p.preview_min_interval_ms }); return JNI_TRUE; }

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_startRecordingSession(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, fps: c.jint, segment_duration_ms: c.jlong, wall_clock_ms: c.jlong) callconv(.c) c.jlong { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return 0; p.recording.recording = true; p.recording.generation += 1; p.recording.fps = @min(@max(fps, 1), 120); p.encoder_fps = p.recording.fps; p.recording.segment_duration_ms = if (segment_duration_ms <= 0) 60000 else segment_duration_ms; p.recording.segment_index = 0; p.recording.pending_segment_index = 0; p.recording.segment_switch_pending = false; p.recording.pending_segment_wall_clock_ms = 0; p.recording.requested_frames = 0; p.recording.rendered_frames = 0; p.recording.dropped_frames = 0; p.recording.last_tick_steady_ms = 0; p.recording.encoder_segment_start_steady_ms = nowMs(); p.recording.last_presentation_time_ns = -1; resetOverlayCache(&p.recording, wall_clock_ms); p.encoder_signal_count = 0; p.encoder_scheduled_count = 0; p.encoder_coalesced_count = 0; const first = floorToSegment(wall_clock_ms, p.recording.segment_duration_ms); p.recording.next_segment_wall_clock_ms = first + p.recording.segment_duration_ms; p.encoder_pending = false; return first; }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_stopRecordingSession(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) c.jboolean { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return JNI_FALSE; p.recording.recording = false; p.recording.segment_switch_pending = false; resetOverlayCache(&p.recording, 0); p.recording.generation += 1; p.encoder_pending = false; return JNI_TRUE; }

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_recordingTickAndRender(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, wall_clock_ms: c.jlong) callconv(.c) c.jlong { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return 0; if (!p.recording.recording) return 0; p.recording.overlay_wall_clock_ms = wall_clock_ms; p.recording.last_tick_steady_ms = nowMs(); p.recording.requested_frames += 1; var flags: c.jlong = 0; if (requestEncoderRenderLocked(p)) { var rendered = false; const ok = renderEncoderLocked(env, p, true, &rendered); p.encoder_pending = false; if (!ok) return -1; if (rendered) { p.recording.rendered_frames += 1; flags |= 1; } else { p.recording.dropped_frames += 1; flags |= 2; } } else { p.recording.dropped_frames += 1; flags |= 2; } if (wall_clock_ms >= p.recording.next_segment_wall_clock_ms and !p.recording.segment_switch_pending) { p.recording.segment_switch_pending = true; p.recording.pending_segment_index = p.recording.segment_index + 1; p.recording.pending_segment_wall_clock_ms = p.recording.next_segment_wall_clock_ms; flags |= 4; flags |= (@as(c.jlong, p.recording.pending_segment_index) << 32); } return flags; }

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_updateRecordingOverlayBitmap(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, bitmap: c.jobject, x: c.jfloat, y: c.jfloat) callconv(.c) c.jboolean {
    lockGlobal();
    defer unlockGlobal();
    const p = getPipe(handle) orelse return JNI_FALSE;
    if (bitmap == null) return JNI_FALSE;
    if (!makePbufferCurrent(p)) return JNI_FALSE;

    var info: c.AndroidBitmapInfo = undefined;
    if (c.AndroidBitmap_getInfo(env, bitmap, &info) != 0) { setError("AndroidBitmap_getInfo failed", .{}); return JNI_FALSE; }
    if (info.width == 0 or info.height == 0 or info.format != c.ANDROID_BITMAP_FORMAT_RGBA_8888) { setError("overlay bitmap must be RGBA_8888", .{}); return JNI_FALSE; }

    var pixels: ?*anyopaque = null;
    if (c.AndroidBitmap_lockPixels(env, bitmap, &pixels) != 0 or pixels == null) { setError("AndroidBitmap_lockPixels failed", .{}); return JNI_FALSE; }
    defer _ = c.AndroidBitmap_unlockPixels(env, bitmap);

    if (p.recording.overlay_bitmap_texture == 0) {
        c.glGenTextures(1, &p.recording.overlay_bitmap_texture);
        if (p.recording.overlay_bitmap_texture == 0) { setError("overlay bitmap texture allocation failed", .{}); return JNI_FALSE; }
        c.glBindTexture(c.GL_TEXTURE_2D, p.recording.overlay_bitmap_texture);
        c.glTexParameteri(c.GL_TEXTURE_2D, c.GL_TEXTURE_MIN_FILTER, c.GL_LINEAR);
        c.glTexParameteri(c.GL_TEXTURE_2D, c.GL_TEXTURE_MAG_FILTER, c.GL_LINEAR);
        c.glTexParameteri(c.GL_TEXTURE_2D, c.GL_TEXTURE_WRAP_S, c.GL_CLAMP_TO_EDGE);
        c.glTexParameteri(c.GL_TEXTURE_2D, c.GL_TEXTURE_WRAP_T, c.GL_CLAMP_TO_EDGE);
    } else {
        c.glBindTexture(c.GL_TEXTURE_2D, p.recording.overlay_bitmap_texture);
    }

    c.glPixelStorei(c.GL_UNPACK_ALIGNMENT, 1);
    c.glTexImage2D(c.GL_TEXTURE_2D, 0, c.GL_RGBA, @intCast(info.width), @intCast(info.height), 0, c.GL_RGBA, c.GL_UNSIGNED_BYTE, pixels);
    c.glPixelStorei(c.GL_UNPACK_ALIGNMENT, 4);
    if (glError("updateRecordingOverlayBitmap")) |e| { setErrorSlice(e); return JNI_FALSE; }

    p.recording.overlay_bitmap_width = @intCast(info.width);
    p.recording.overlay_bitmap_height = @intCast(info.height);
    p.recording.overlay_bitmap_x = x;
    p.recording.overlay_bitmap_y = y;
    p.recording.overlay_bitmap_batch.len = 0;
    p.recording.overlay_geometry_width = 0;
    p.recording.overlay_geometry_height = 0;
    return JNI_TRUE;
}
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_getRecordingNextTickDelayMs(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) c.jlong { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return -1; if (!p.recording.recording) return -1; const interval = recordingTickIntervalMs(&p.recording); if (p.recording.last_tick_steady_ms <= 0) return 0; const remaining = interval - (nowMs() - p.recording.last_tick_steady_ms); return if (remaining > 0) remaining else 0; }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_beginNextRecordingSegment(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) c.jlong { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return 0; return if (p.recording.segment_switch_pending) p.recording.pending_segment_wall_clock_ms else p.recording.next_segment_wall_clock_ms; }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_completeRecordingSegmentSwitch(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, success: c.jboolean) callconv(.c) c.jboolean { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return JNI_FALSE; if (!p.recording.segment_switch_pending) return JNI_TRUE; if (success == JNI_TRUE) { p.recording.segment_index = p.recording.pending_segment_index; p.recording.next_segment_wall_clock_ms = p.recording.pending_segment_wall_clock_ms + p.recording.segment_duration_ms; } p.recording.segment_switch_pending = false; p.recording.pending_segment_index = 0; p.recording.pending_segment_wall_clock_ms = 0; return JNI_TRUE; }

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_createNativeSegmentWriter(env: [*c]c.JNIEnv, _: c.jobject, width: c.jint, height: c.jint, fps: c.jint, bitrate: c.jint, mime_type: c.jstring) callconv(.c) c.jlong {
    if (mime_type == null or width <= 0 or height <= 0 or fps <= 0 or bitrate <= 0) {
        setError("invalid native segment writer config", .{});
        return 0;
    }
    const mime_chars = env.*[0].GetStringUTFChars.?(env, mime_type, null) orelse {
        setError("native writer mime unavailable", .{});
        return 0;
    };
    defer env.*[0].ReleaseStringUTFChars.?(env, mime_type, mime_chars);

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
        return 0;
    };

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

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_nativeSegmentWriterInputSurface(env: [*c]c.JNIEnv, _: c.jobject, writer_handle: c.jlong) callconv(.c) c.jobject {
    lockGlobal();
    defer unlockGlobal();
    const w = getNativeWriter(writer_handle) orelse return null;
    const window = w.input_window orelse {
        setError("native writer input window unavailable", .{});
        return null;
    };
    return c.ANativeWindow_toSurface(env, window);
}

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_nativeSegmentWriterStartSegment(env: [*c]c.JNIEnv, _: c.jobject, writer_handle: c.jlong, path: c.jstring, segment_index: c.jint, wall_clock_ms: c.jlong) callconv(.c) c.jboolean {
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

    lockGlobal();
    defer unlockGlobal();
    const w = getNativeWriter(writer_handle) orelse return JNI_FALSE;
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

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_nativeSegmentWriterDrain(_: [*c]c.JNIEnv, _: c.jobject, writer_handle: c.jlong, timeout_us: c.jlong) callconv(.c) c.jlong {
    lockGlobal();
    defer unlockGlobal();
    const w = getNativeWriter(writer_handle) orelse return -1;
    return drainNativeWriterLocked(w, timeout_us);
}

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_nativeSegmentWriterStop(_: [*c]c.JNIEnv, _: c.jobject, writer_handle: c.jlong) callconv(.c) c.jboolean {
    lockGlobal();
    defer unlockGlobal();
    const w = getNativeWriter(writer_handle) orelse return JNI_FALSE;
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
    return JNI_TRUE;
}

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_nativeSegmentWriterRelease(_: [*c]c.JNIEnv, _: c.jobject, writer_handle: c.jlong) callconv(.c) c.jboolean {
    lockGlobal();
    defer unlockGlobal();
    for (0..MAX_NATIVE_WRITERS) |i| {
        if (!g_native_writer_used[i] or g_native_writers[i].handle != writer_handle) continue;
        releaseNativeWriterResources(&g_native_writers[i]);
        g_native_writer_used[i] = false;
        return JNI_TRUE;
    }
    setError("native writer release missing handle", .{});
    return JNI_FALSE;
}

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_getMetricsSnapshot(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) c.jlongArray { lockGlobal(); defer unlockGlobal(); var values = [_]c.jlong{0} ** 48; if (getPipe(handle)) |p| { values[0]=p.preview_render_count; values[1]=p.encoder_render_count; values[2]=p.encoder_drop_count; values[3]=p.no_surface_count; values[4]=p.last_render_ms; values[5]=p.recording.requested_frames; values[6]=p.recording.rendered_frames; values[7]=p.recording.dropped_frames; values[8]=p.recording.segment_index; values[9]=if(p.recording.segment_switch_pending)1 else 0; values[10]=p.recording.pending_segment_index; values[11]=p.recording.next_segment_wall_clock_ms; values[12]=p.encoder_signal_count; values[13]=p.encoder_scheduled_count; values[14]=p.encoder_coalesced_count; values[15]=p.preview_max_fps; values[16]=p.preview_min_interval_ms; values[17]=p.config_version; values[18]=if(p.encoder_pending)1 else 0; values[19]=p.recording.generation; for (0..4) |i| { const base = 20 + i*7; values[base]=p.input[i].frame_signal_count; values[base+1]=p.input[i].preview_scheduled_count; values[base+2]=p.input[i].preview_delayed_count; values[base+3]=p.input[i].preview_coalesced_count; values[base+4]=p.input[i].update_count; values[base+5]=p.input[i].preview_render_count; values[base+6]=p.input[i].preview_drop_count; } } const result = env.*[0].NewLongArray.?(env, 48); if (result != null) env.*[0].SetLongArrayRegion.?(env, result, 0, 48, &values); return result; }

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_createCompositor(_: [*c]c.JNIEnv, _: c.jobject, width: c.jint, height: c.jint) callconv(.c) c.jlong { lockGlobal(); defer unlockGlobal(); for (0..MAX_PIPES) |i| if (!g_used[i]) { const handle = g_next_handle; g_next_handle += 1; g_pipes[i] = Pipe{}; g_used[i] = true; g_pipes[i].handle = handle; g_pipes[i].width = width; g_pipes[i].height = height; if (!initEgl(&g_pipes[i])) { g_used[i] = false; return 0; } updateEncoderLayout(&g_pipes[i]); return handle; }; setError("no free native pipe slots", .{}); return 0; }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_createOesTexture(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint) callconv(.c) c.jint { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return 0; if (index < 0 or index >= 4 or !initEgl(p) or !makePbufferCurrent(p)) return 0; const i: usize = @intCast(index); if (p.input[i].texture != 0) { c.glDeleteTextures(1, &p.input[i].texture); p.input[i].texture = 0; } var tex: c.GLuint = 0; c.glGenTextures(1, &tex); if (tex == 0) { setError("glGenTextures returned 0", .{}); clearCurrent(p); return 0; } c.glBindTexture(GL_TEXTURE_EXTERNAL_OES, tex); c.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, c.GL_TEXTURE_MIN_FILTER, c.GL_LINEAR); c.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, c.GL_TEXTURE_MAG_FILTER, c.GL_LINEAR); c.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, c.GL_TEXTURE_WRAP_S, c.GL_CLAMP_TO_EDGE); c.glTexParameteri(GL_TEXTURE_EXTERNAL_OES, c.GL_TEXTURE_WRAP_T, c.GL_CLAMP_TO_EDGE); if (glError("createOesTexture")) |e| { setErrorSlice(e); c.glDeleteTextures(1, &tex); clearCurrent(p); return 0; } p.input[i].texture = tex; clearCurrent(p); logd("created OES texture index={d} tex={d}", .{index, tex}); return @intCast(tex); }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_destroyOesInput(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint) callconv(.c) c.jboolean { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return JNI_FALSE; if (index < 0 or index >= 4) return JNI_FALSE; resetInput(env, p, @intCast(index), true); logd("destroyed OES input index={d}", .{index}); return JNI_TRUE; }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_createOesInput(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint, surface_texture: c.jobject) callconv(.c) c.jboolean { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return JNI_FALSE; if (index < 0 or index >= 4) return JNI_FALSE; const i: usize = @intCast(index); if (p.input[i].surface_texture != null) env.*[0].DeleteGlobalRef.?(env, p.input[i].surface_texture); p.input[i].surface_texture = env.*[0].NewGlobalRef.?(env, surface_texture); p.input[i].dirty = false; p.input[i].has_latched_frame = false; p.input[i].preview_pending = false; p.input[i].frame_generation = 0; p.input[i].latched_generation = 0; p.input[i].preview_generation = 0; p.input[i].encoder_generation = 0; logd("created OES input index={d}", .{index}); return JNI_TRUE; }
fn attachPreviewSurfaceLocked(env: [*c]c.JNIEnv, p: *Pipe, index: c.jint, surface: c.jobject, apply_fisheye: bool, apply_native_transform: bool) c.jboolean { if (index < 0 or index >= 4 or !initEgl(p)) return JNI_FALSE; const i: usize = @intCast(index); if (p.preview_surface[i] != c.EGL_NO_SURFACE) { if (p.current_surface == p.preview_surface[i]) clearCurrent(p); _ = c.eglDestroySurface(p.display, p.preview_surface[i]); p.preview_surface[i] = c.EGL_NO_SURFACE; } if (p.preview_window[i]) |w| c.ANativeWindow_release(w); p.preview_window[i] = c.ANativeWindow_fromSurface(env, surface); if (p.preview_window[i] == null) { setError("preview window unavailable", .{}); return JNI_FALSE; } p.preview_surface[i] = c.eglCreateWindowSurface(p.display, p.config, p.preview_window[i], null); if (p.preview_surface[i] == c.EGL_NO_SURFACE) { setErrorSlice(eglError("eglCreateWindowSurface preview failed")); c.ANativeWindow_release(p.preview_window[i].?); p.preview_window[i] = null; return JNI_FALSE; } p.preview_apply_fisheye[i] = apply_fisheye; p.preview_apply_native_transform[i] = apply_native_transform; updatePreviewLayout(p, index, c.ANativeWindow_getWidth(p.preview_window[i].?), c.ANativeWindow_getHeight(p.preview_window[i].?)); logd("attached preview surface index={d} size={d}x{d} fisheye={d} nativeTransform={d}", .{index, c.ANativeWindow_getWidth(p.preview_window[i].?), c.ANativeWindow_getHeight(p.preview_window[i].?), if(apply_fisheye)@as(i32,1) else @as(i32,0), if(apply_native_transform)@as(i32,1) else @as(i32,0)}); return JNI_TRUE; }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_attachPreviewSurface(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint, surface: c.jobject) callconv(.c) c.jboolean { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return JNI_FALSE; return attachPreviewSurfaceLocked(env, p, index, surface, true, true); }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_attachPreviewSurfaceWithMode(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint, surface: c.jobject, apply_fisheye: c.jboolean, apply_native_transform: c.jboolean) callconv(.c) c.jboolean { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return JNI_FALSE; return attachPreviewSurfaceLocked(env, p, index, surface, apply_fisheye == JNI_TRUE, apply_native_transform == JNI_TRUE); }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_detachPreviewSurface(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint) callconv(.c) c.jboolean { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return JNI_FALSE; if (index < 0 or index >= 4) return JNI_FALSE; const i: usize = @intCast(index); if (p.preview_surface[i] != c.EGL_NO_SURFACE) { if (p.current_surface == p.preview_surface[i]) clearCurrent(p); _ = c.eglDestroySurface(p.display, p.preview_surface[i]); p.preview_surface[i] = c.EGL_NO_SURFACE; } if (p.preview_window[i]) |w| { c.ANativeWindow_release(w); p.preview_window[i] = null; } p.input[i].preview_pending = false; return JNI_TRUE; }

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_setFisheyeCorrection(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, enabled: c.jboolean, k1: c.jfloat, k2: c.jfloat, zoom: c.jfloat, center_x: c.jfloat, center_y: c.jfloat) callconv(.c) c.jboolean { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return JNI_FALSE; for (0..4) |i| { p.fisheye_enabled[i] = enabled == JNI_TRUE; p.fisheye_k1[i] = k1; p.fisheye_k2[i] = k2; p.fisheye_zoom[i] = if (zoom <= 0.01) 1.0 else zoom; p.fisheye_center_x[i] = center_x; p.fisheye_center_y[i] = center_y; } logd("fisheye all enabled={d} k1={d} k2={d} zoom={d} center={d},{d}", .{ if(enabled==JNI_TRUE)@as(i32,1) else @as(i32,0), k1, k2, if(zoom<=0.01)@as(f32,1.0) else zoom, center_x, center_y }); return JNI_TRUE; }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_setFisheyeCorrectionForCamera(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint, enabled: c.jboolean, k1: c.jfloat, k2: c.jfloat, zoom: c.jfloat, center_x: c.jfloat, center_y: c.jfloat) callconv(.c) c.jboolean { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return JNI_FALSE; if (index < 0 or index >= 4) return JNI_FALSE; const i: usize = @intCast(index); p.fisheye_enabled[i] = enabled == JNI_TRUE; p.fisheye_k1[i] = k1; p.fisheye_k2[i] = k2; p.fisheye_zoom[i] = if (zoom <= 0.01) 1.0 else zoom; p.fisheye_center_x[i] = center_x; p.fisheye_center_y[i] = center_y; logd("fisheye index={d} enabled={d} k1={d} k2={d} zoom={d} center={d},{d}", .{ index, if(p.fisheye_enabled[i])@as(i32,1) else @as(i32,0), p.fisheye_k1[i], p.fisheye_k2[i], p.fisheye_zoom[i], p.fisheye_center_x[i], p.fisheye_center_y[i] }); return JNI_TRUE; }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_attachEncoderSurface(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, surface: c.jobject) callconv(.c) c.jboolean { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return JNI_FALSE; if (!initEgl(p)) return JNI_FALSE; const new_window = c.ANativeWindow_fromSurface(env, surface); if (new_window == null) { setError("encoder window unavailable", .{}); return JNI_FALSE; } const new_surface = c.eglCreateWindowSurface(p.display, p.config, new_window, null); if (new_surface == c.EGL_NO_SURFACE) { setErrorSlice(eglError("eglCreateWindowSurface encoder failed")); c.ANativeWindow_release(new_window); return JNI_FALSE; } const old_surface = p.encoder_surface; const old_window = p.encoder_window; p.encoder_surface = new_surface; p.encoder_window = new_window; if (old_surface != c.EGL_NO_SURFACE) { if (p.current_surface == old_surface) clearCurrent(p); _ = c.eglDestroySurface(p.display, old_surface); } if (old_window) |w| c.ANativeWindow_release(w); p.encoder_frame_index = 0; if (p.recording.recording) { p.recording.encoder_segment_start_steady_ms = nowMs(); p.recording.last_presentation_time_ns = -1; } p.encoder_pending = false; p.encoder_generation += 1; return JNI_TRUE; }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_detachEncoderSurface(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) c.jboolean { lockGlobal(); defer unlockGlobal(); const p = getPipe(handle) orelse return JNI_FALSE; if (p.encoder_surface != c.EGL_NO_SURFACE) { if (p.current_surface == p.encoder_surface) clearCurrent(p); _ = c.eglDestroySurface(p.display, p.encoder_surface); p.encoder_surface = c.EGL_NO_SURFACE; } if (p.encoder_window) |w| { c.ANativeWindow_release(w); p.encoder_window = null; } p.encoder_generation = 0; p.encoder_frame_index = 0; p.encoder_pending = false; return JNI_TRUE; }

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_requestPreviewRender(_: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint) callconv(.c) c.jlong {
    const p = tryLockPipeForHandleBounded(handle, 64) orelse return PREVIEW_REQUEST_LOCK_BUSY;
    defer unlockPipe(p);
    if (index < 0 or index >= 4) return -1;
    const i: usize = @intCast(index);
    const inp = &p.input[i];
    inp.frame_signal_count += 1;
    inp.frame_generation += 1;
    inp.dirty = true;
    if (inp.surface_texture == null or p.preview_surface[i] == c.EGL_NO_SURFACE) {
        inp.preview_drop_count += 1;
        return -1;
    }
    if (inp.preview_pending) {
        inp.preview_coalesced_count += 1;
        return -1;
    }
    inp.preview_pending = true;
    inp.preview_scheduled_count += 1;
    const delay = previewDelayMs(p, inp);
    if (delay > 0) inp.preview_delayed_count += 1;
    return delay;
}
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_signalPreviewFrame(env: [*c]c.JNIEnv, obj: c.jobject, handle: c.jlong, index: c.jint) callconv(.c) c.jlong { return Java_com_kooo_evcam_v2_nativebridge_VulkanNative_requestPreviewRender(env, obj, handle, index); }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_renderScheduledPreview(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong, index: c.jint) callconv(.c) c.jboolean { const p = lockPipeForHandle(handle) orelse return JNI_FALSE; defer unlockPipe(p); if (index < 0 or index >= 4) return JNI_FALSE; const i: usize = @intCast(index); if (!p.input[i].preview_pending) return JNI_TRUE; const ok = renderPreviewLocked(env, p, index); p.input[i].preview_pending = false; return if(ok)JNI_TRUE else JNI_FALSE; }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_renderCompositor(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) c.jboolean { const p = lockPipeForHandle(handle) orelse return JNI_FALSE; defer unlockPipe(p); return if(renderEncoderLocked(env, p, false, null))JNI_TRUE else JNI_FALSE; }

export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_releaseCompositor(env: [*c]c.JNIEnv, _: c.jobject, handle: c.jlong) callconv(.c) void { lockGlobal(); defer unlockGlobal(); for (0..MAX_PIPES) |idx| { if (!g_used[idx] or g_pipes[idx].handle != handle) continue; const p = &g_pipes[idx]; if (p.display != c.EGL_NO_DISPLAY) { _ = makePbufferCurrent(p); for (0..4) |i| { if (p.preview_surface[i] != c.EGL_NO_SURFACE) _ = c.eglDestroySurface(p.display, p.preview_surface[i]); if (p.input[i].texture != 0) c.glDeleteTextures(1, &p.input[i].texture); } if (p.encoder_surface != c.EGL_NO_SURFACE) _ = c.eglDestroySurface(p.display, p.encoder_surface); if (p.overlay_font_texture != 0) c.glDeleteTextures(1, &p.overlay_font_texture); if (p.recording.overlay_bitmap_texture != 0) c.glDeleteTextures(1, &p.recording.overlay_bitmap_texture); if (p.program != 0) c.glDeleteProgram(p.program); if (p.overlay_program != 0) c.glDeleteProgram(p.overlay_program); if (p.overlay_text_program != 0) c.glDeleteProgram(p.overlay_text_program); clearCurrent(p); if (p.pbuffer != c.EGL_NO_SURFACE) _ = c.eglDestroySurface(p.display, p.pbuffer); if (p.context != c.EGL_NO_CONTEXT) _ = c.eglDestroyContext(p.display, p.context); _ = c.eglTerminate(p.display); } for (0..4) |i| { if (p.input[i].surface_texture != null) env.*[0].DeleteGlobalRef.?(env, p.input[i].surface_texture); if (p.preview_window[i]) |w| c.ANativeWindow_release(w); } if (p.encoder_window) |w| c.ANativeWindow_release(w); g_used[idx] = false; g_pipes[idx] = Pipe{}; return; } }
export fn Java_com_kooo_evcam_v2_nativebridge_VulkanNative_getLastError(env: [*c]c.JNIEnv, _: c.jobject) callconv(.c) c.jstring { return newString(env, &g_last_error); }
