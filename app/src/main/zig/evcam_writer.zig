const std = @import("std");
const c = @import("c");
const types = @import("evcam_types.zig");

const MAX_NATIVE_WRITERS = types.MAX_NATIVE_WRITERS;
const COLOR_FORMAT_SURFACE = types.COLOR_FORMAT_SURFACE;
const O_RDWR_ANDROID = types.O_RDWR_ANDROID;
const O_RDONLY_ANDROID = types.O_RDONLY_ANDROID;
const O_CREAT_ANDROID = types.O_CREAT_ANDROID;
const O_TRUNC_ANDROID = types.O_TRUNC_ANDROID;
const NativeTm = types.NativeTm;
const NativeSegmentWriter = types.NativeSegmentWriter;
const WRITER_HANDLE_SLOT_BASE: c.jlong = 1000;

pub const SegmentFinishResult = enum(c_int) {
    failed = -1,
    skipped_empty = 0,
    finalized = 1,
};

extern fn open(path: [*c]const u8, flags: c_int, mode: c_int) c_int;
extern fn close(fd: c_int) c_int;
extern fn rename(oldpath: [*c]const u8, newpath: [*c]const u8) c_int;
extern fn unlink(path: [*c]const u8) c_int;
extern fn localtime_r(timep: *const c_long, result: *NativeTm) ?*NativeTm;

pub const Callbacks = struct {
    nativeIo: *const fn () std.Io,
    nowMs: *const fn () i64,
    setErrorText: *const fn ([:0]const u8) void,
    logInfoText: *const fn ([:0]const u8) void,
};

var g_writers: [MAX_NATIVE_WRITERS]NativeSegmentWriter = [_]NativeSegmentWriter{NativeSegmentWriter{}} ** MAX_NATIVE_WRITERS;
var g_used: [MAX_NATIVE_WRITERS]bool = [_]bool{false} ** MAX_NATIVE_WRITERS;
var g_next_handle: c.jlong = 1;
var g_lock: std.Io.Mutex = .init;
var g_callbacks: ?Callbacks = null;

pub fn configure(cb: Callbacks) void {
    g_callbacks = cb;
}

fn callbacks() ?Callbacks {
    return g_callbacks;
}

fn nativeIo() std.Io {
    return callbacks().?.nativeIo();
}

fn nowMs() i64 {
    return callbacks().?.nowMs();
}

fn setErrorText(msg: [:0]const u8) void {
    if (callbacks()) |cb| cb.setErrorText(msg);
}

fn logInfoText(msg: [:0]const u8) void {
    if (callbacks()) |cb| cb.logInfoText(msg);
}

fn setError(comptime fmt: []const u8, args: anytype) void {
    var buf: [256:0]u8 = [_:0]u8{0} ** 256;
    const text = std.fmt.bufPrintZ(&buf, fmt, args) catch std.fmt.bufPrintZ(&buf, "writer error", .{}) catch return;
    setErrorText(text);
}

fn logInfo(comptime fmt: []const u8, args: anytype) void {
    var buf: [256:0]u8 = [_:0]u8{0} ** 256;
    const text = std.fmt.bufPrintZ(&buf, fmt, args) catch return;
    logInfoText(text);
}

fn lockGlobal() void {
    g_lock.lockUncancelable(nativeIo());
}

fn unlockGlobal() void {
    g_lock.unlock(nativeIo());
}

fn lockWriter(w: *NativeSegmentWriter) void {
    const start = nowMs();
    w.lock.lockUncancelable(nativeIo());
    const waited = nowMs() - start;
    w.writer_lock_wait_total_ms += waited;
    if (waited > w.writer_lock_wait_max_ms) w.writer_lock_wait_max_ms = waited;
}

fn unlockWriter(w: *NativeSegmentWriter) void {
    w.lock.unlock(nativeIo());
}

fn lockForHandle(handle: c.jlong) ?*NativeSegmentWriter {
    lockGlobal();
    const w = lockForHandleNoGlobal(handle);
    unlockGlobal();
    return w;
}

fn writerIndexFromHandle(handle: c.jlong) ?usize {
    if (handle <= 0) return null;
    const slot = @mod(handle, WRITER_HANDLE_SLOT_BASE);
    if (slot <= 0 or slot > MAX_NATIVE_WRITERS) return null;
    return @intCast(slot - 1);
}

fn lockForEncodedHandle(handle: c.jlong) ?*NativeSegmentWriter {
    const index = writerIndexFromHandle(handle) orelse return null;
    const w = &g_writers[index];
    lockWriter(w);
    if (w.handle == handle) return w;
    unlockWriter(w);
    setError("invalid native writer handle", .{});
    return null;
}

fn lockForHandleNoGlobal(handle: c.jlong) ?*NativeSegmentWriter {
    for (0..MAX_NATIVE_WRITERS) |i| {
        if (!g_used[i] or g_writers[i].handle != handle) continue;
        const w = &g_writers[i];
        lockWriter(w);
        return w;
    }
    setError("invalid native writer handle", .{});
    return null;
}

fn copyCStringToBuffer(dst: []u8, src: [*c]const u8) bool {
    if (src == null) return false;
    const len = std.mem.len(src);
    if (len >= dst.len) return false;
    @memset(dst, 0);
    @memcpy(dst[0..len], src[0..len]);
    return true;
}

fn appendFixedDecimal(dst: []u8, offset: *usize, value: u64, width: usize) bool {
    if (offset.* + width >= dst.len) return false;
    var remaining = value;
    var i = width;
    while (i > 0) {
        i -= 1;
        dst[offset.* + i] = '0' + @as(u8, @intCast(remaining % 10));
        remaining /= 10;
    }
    if (remaining != 0) return false;
    offset.* += width;
    return true;
}

fn formatSegmentTimestamp(dst: *[32:0]u8, wall_clock_ms: c.jlong) bool {
    const seconds: c_long = @intCast(@divTrunc(wall_clock_ms, 1000));
    var tm: NativeTm = undefined;
    if (localtime_r(&seconds, &tm) == null) return false;
    @memset(dst, 0);
    var offset: usize = 0;
    if (!appendFixedDecimal(dst[0..], &offset, @intCast(tm.tm_year + 1900), 4)) return false;
    if (!appendFixedDecimal(dst[0..], &offset, @intCast(tm.tm_mon + 1), 2)) return false;
    if (!appendFixedDecimal(dst[0..], &offset, @intCast(tm.tm_mday), 2)) return false;
    if (offset >= dst.len - 1) return false;
    dst[offset] = '_';
    offset += 1;
    if (!appendFixedDecimal(dst[0..], &offset, @intCast(tm.tm_hour), 2)) return false;
    if (!appendFixedDecimal(dst[0..], &offset, @intCast(tm.tm_min), 2)) return false;
    return offset > 0;
}

fn formatIndexSuffix(dst: *[8:0]u8, index: usize) bool {
    @memset(dst, 0);
    var offset: usize = 0;
    if (offset >= dst.len - 1) return false;
    dst[offset] = '_';
    offset += 1;
    return appendFixedDecimal(dst[0..], &offset, @intCast(index), 3);
}

fn fileExists(path: [*:0]const u8) bool {
    const fd = open(path, O_RDONLY_ANDROID, 0);
    if (fd < 0) return false;
    _ = close(fd);
    return true;
}

fn releaseResources(w: *NativeSegmentWriter) void {
    if (w.muxer) |muxer| {
        if (w.muxer_started and w.segment_samples > 0) _ = c.AMediaMuxer_stop(muxer);
        _ = c.AMediaMuxer_delete(muxer);
    }
    if (w.codec) |codec| {
        if (w.started) _ = c.AMediaCodec_stop(codec);
        _ = c.AMediaCodec_delete(codec);
    }
    if (w.input_window) |window| c.ANativeWindow_release(window);
    if (w.fd >= 0) _ = close(w.fd);
    w.handle = 0;
    w.codec = null;
    w.muxer = null;
    w.input_window = null;
    w.fd = -1;
    w.output_path = [_:0]u8{0} ** 1024;
    w.output_path_set = false;
    w.track_index = -1;
    w.width = 0;
    w.height = 0;
    w.fps = 0;
    w.bitrate = 0;
    w.started = false;
    w.muxer_started = false;
    w.segment_first_pts_us = -1;
    w.segment_last_pts_us = -1;
    w.segment_samples = 0;
    w.writer_lock_wait_total_ms = 0;
    w.writer_lock_wait_max_ms = 0;
    w.drain_calls = 0;
    w.drain_samples = 0;
    w.drain_total_ms = 0;
    w.drain_max_ms = 0;
    w.muxer_write_total_ms = 0;
    w.muxer_write_max_ms = 0;
}

fn drainLocked(w: *NativeSegmentWriter, timeout_us: c.jlong) c.jlong {
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
                var write_info = info;
                if (w.segment_first_pts_us < 0) w.segment_first_pts_us = info.presentationTimeUs;
                var segment_pts = info.presentationTimeUs - w.segment_first_pts_us;
                if (segment_pts < 0) segment_pts = 0;
                if (w.segment_last_pts_us >= 0 and segment_pts <= w.segment_last_pts_us) {
                    segment_pts = w.segment_last_pts_us + 1;
                }
                write_info.presentationTimeUs = segment_pts;
                const write_start_ms = nowMs();
                const status = c.AMediaMuxer_writeSampleData(w.muxer.?, @intCast(w.track_index), buffer, &write_info);
                const write_ms = nowMs() - write_start_ms;
                w.muxer_write_total_ms += write_ms;
                if (write_ms > w.muxer_write_max_ms) w.muxer_write_max_ms = write_ms;
                if (status != c.AMEDIA_OK) {
                    setError("AMediaMuxer_writeSampleData failed status={d}", .{status});
                    return -1;
                }
                w.segment_last_pts_us = segment_pts;
                w.segment_samples += 1;
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

fn stopLocked(w: *NativeSegmentWriter, writer_handle: c.jlong) bool {
    const lock_wait_total = w.writer_lock_wait_total_ms;
    const lock_wait_max = w.writer_lock_wait_max_ms;
    const drain_calls = w.drain_calls;
    const drain_samples = w.drain_samples;
    const drain_total = w.drain_total_ms;
    const drain_max = w.drain_max_ms;
    const muxer_write_total = w.muxer_write_total_ms;
    const muxer_write_max = w.muxer_write_max_ms;
    var has_finalizable_segment = w.output_path_set and w.segment_samples > 0;
    if (w.codec) |codec| {
        if (w.started) {
            _ = c.AMediaCodec_signalEndOfInputStream(codec);
            _ = drainLocked(w, 10_000);
            has_finalizable_segment = w.output_path_set and w.segment_samples > 0;
            _ = c.AMediaCodec_stop(codec);
            w.started = false;
        }
    }
    if (w.muxer) |muxer| {
        if (w.muxer_started and w.segment_samples > 0) {
            const stop_status = c.AMediaMuxer_stop(muxer);
            if (stop_status != c.AMEDIA_OK) has_finalizable_segment = false;
        } else {
            has_finalizable_segment = false;
        }
        _ = c.AMediaMuxer_delete(muxer);
        w.muxer = null;
        w.muxer_started = false;
        w.track_index = -1;
    }
    if (w.fd >= 0) {
        _ = close(w.fd);
        w.fd = -1;
    }
    logInfo("native writer perf handle={d} lockWaitTotalMs={d} lockWaitMaxMs={d} drainCalls={d} samples={d} drainTotalMs={d} drainMaxMs={d} muxWriteTotalMs={d} muxWriteMaxMs={d}", .{ writer_handle, lock_wait_total, lock_wait_max, drain_calls, drain_samples, drain_total, drain_max, muxer_write_total, muxer_write_max });
    return has_finalizable_segment;
}

fn startMuxerFromCurrentFormatLocked(w: *NativeSegmentWriter, codec: *c.AMediaCodec) bool {
    if (w.muxer == null or w.muxer_started) return true;
    const out_format = c.AMediaCodec_getOutputFormat(codec) orelse {
        setError("AMediaCodec_getOutputFormat failed for continuing segment", .{});
        return false;
    };
    defer _ = c.AMediaFormat_delete(out_format);
    const track = c.AMediaMuxer_addTrack(w.muxer.?, out_format);
    if (track < 0) {
        setError("AMediaMuxer_addTrack continuing segment failed track={d}", .{track});
        return false;
    }
    const start_status = c.AMediaMuxer_start(w.muxer.?);
    if (start_status != c.AMEDIA_OK) {
        setError("AMediaMuxer_start continuing segment failed status={d}", .{start_status});
        return false;
    }
    w.track_index = @intCast(track);
    w.muxer_started = true;
    return true;
}

fn closeSegmentLocked(w: *NativeSegmentWriter, final_chars: [*c]const u8) SegmentFinishResult {
    const has_output = w.output_path_set;
    var temp_path: [1024:0]u8 = [_:0]u8{0} ** 1024;
    if (has_output) @memcpy(temp_path[0..], w.output_path[0..]);
    var result: SegmentFinishResult = if (has_output and final_chars != null) .finalized else .failed;
    const has_samples = w.segment_samples > 0;
    if (w.muxer) |muxer| {
        if (w.muxer_started and has_samples) {
            // On this head unit AMediaMuxer_delete finalizes the active MPEG4Writer.
            // Calling AMediaMuxer_stop first finalizes correctly, but delete then
            // logs a second Stop() error for the already-stopped track.
        } else {
            result = if (has_output) .skipped_empty else .failed;
        }
        _ = c.AMediaMuxer_delete(muxer);
        w.muxer = null;
    } else {
        result = .failed;
    }
    if (w.fd >= 0) {
        _ = close(w.fd);
        w.fd = -1;
    }
    w.output_path_set = false;
    @memset(w.output_path[0..], 0);
    w.track_index = -1;
    w.muxer_started = false;
    w.segment_first_pts_us = -1;
    w.segment_last_pts_us = -1;
    w.segment_samples = 0;
    if (result != .finalized) {
        if (has_output) _ = unlink(&temp_path);
        return result;
    }
    if (rename(&temp_path, final_chars) != 0) {
        setError("rename segment failed", .{});
        _ = unlink(&temp_path);
        return .failed;
    }
    return .finalized;
}

pub fn createForMime(width: c.jint, height: c.jint, fps: c.jint, bitrate: c.jint, mime_chars: [*c]const u8) c.jlong {
    if (callbacks() == null) return 0;
    if (mime_chars == null or width <= 0 or height <= 0 or fps <= 0 or bitrate <= 0) {
        setError("invalid native segment writer config", .{});
        return 0;
    }
    const codec = c.AMediaCodec_createEncoderByType(mime_chars) orelse {
        setError("AMediaCodec_createEncoderByType failed", .{});
        return 0;
    };
    const format = c.AMediaFormat_new() orelse {
        _ = c.AMediaCodec_delete(codec);
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
        _ = c.AMediaCodec_delete(codec);
        setError("AMediaCodec_configure failed status={d}", .{configure_status});
        return 0;
    }
    var input_window: ?*c.ANativeWindow = null;
    const surface_status = c.AMediaCodec_createInputSurface(codec, &input_window);
    if (surface_status != c.AMEDIA_OK or input_window == null) {
        if (input_window) |w| c.ANativeWindow_release(w);
        _ = c.AMediaCodec_delete(codec);
        setError("AMediaCodec_createInputSurface failed status={d}", .{surface_status});
        return 0;
    }

    lockGlobal();
    defer unlockGlobal();
    var slot_index: ?usize = null;
    for (0..MAX_NATIVE_WRITERS) |i| {
        if (!g_used[i]) {
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
    const generation = g_next_handle;
    g_next_handle += 1;
    const handle = generation * WRITER_HANDLE_SLOT_BASE + @as(c.jlong, @intCast(index + 1));
    g_used[index] = true;
    g_writers[index] = NativeSegmentWriter{
        .handle = handle,
        .codec = codec,
        .input_window = input_window,
        .width = width,
        .height = height,
        .fps = fps,
        .bitrate = bitrate,
        .started = false,
    };
    logInfo("native writer created handle={d} size={d}x{d} fps={d} bitrate={d}", .{ handle, width, height, fps, bitrate });
    return handle;
}

pub fn startSegment(writer_handle: c.jlong, dir_chars: [*c]const u8, suffix_chars: [*c]const u8, wall_clock_ms: c.jlong, out_final_path: *[1024:0]u8) bool {
    var timestamp: [32:0]u8 = [_:0]u8{0} ** 32;
    if (!formatSegmentTimestamp(&timestamp, wall_clock_ms)) return false;
    const dir = std.mem.span(dir_chars);
    const suffix = std.mem.span(suffix_chars);
    const timestamp_text = std.mem.sliceTo(&timestamp, 0);
    const w = lockForHandle(writer_handle) orelse return false;
    defer unlockWriter(w);
    const codec = w.codec orelse return false;
    if (w.muxer != null or w.fd >= 0) {
        setError("native segment writer already has active segment", .{});
        return false;
    }
    var temp_path: [1024:0]u8 = [_:0]u8{0} ** 1024;
    var index: usize = 0;
    while (index < 999) : (index += 1) {
        var index_suffix: [8:0]u8 = [_:0]u8{0} ** 8;
        const final_name = if (index == 0) blk: {
            break :blk std.fmt.bufPrintZ(out_final_path, "{s}/{s}{s}.mp4", .{ dir, timestamp_text, suffix }) catch continue;
        } else blk: {
            if (!formatIndexSuffix(&index_suffix, index)) continue;
            const index_text = std.mem.sliceTo(&index_suffix, 0);
            break :blk std.fmt.bufPrintZ(out_final_path, "{s}/{s}{s}{s}.mp4", .{ dir, timestamp_text, suffix, index_text }) catch continue;
        };
        const temp_name = std.fmt.bufPrintZ(&temp_path, "{s}.recording", .{final_name}) catch continue;
        if (fileExists(final_name)) continue;
        if (fileExists(temp_name)) continue;
        const fd = open(temp_name, O_CREAT_ANDROID | O_TRUNC_ANDROID | O_RDWR_ANDROID, 0o644);
        if (fd < 0) continue;
        const muxer = c.AMediaMuxer_new(fd, c.AMEDIAMUXER_OUTPUT_FORMAT_MPEG_4) orelse {
            _ = close(fd);
            _ = unlink(temp_name);
            continue;
        };
        w.fd = fd;
        if (!copyCStringToBuffer(w.output_path[0..], temp_name)) {
            _ = c.AMediaMuxer_delete(muxer);
            _ = close(fd);
            _ = unlink(temp_name);
            w.fd = -1;
            w.output_path_set = false;
            @memset(w.output_path[0..], 0);
            setError("native segment writer path too long", .{});
            return false;
        }
        w.output_path_set = true;
        w.muxer = muxer;
        w.track_index = -1;
        w.muxer_started = false;
        w.segment_first_pts_us = -1;
        w.segment_last_pts_us = -1;
        w.segment_samples = 0;
        if (w.started) {
            if (!startMuxerFromCurrentFormatLocked(w, codec)) {
                _ = c.AMediaMuxer_delete(muxer);
                _ = close(fd);
                _ = unlink(temp_name);
                w.muxer = null;
                w.fd = -1;
                w.output_path_set = false;
                @memset(w.output_path[0..], 0);
                return false;
            }
        } else {
            const status = c.AMediaCodec_start(codec);
            if (status != c.AMEDIA_OK) {
                _ = c.AMediaMuxer_delete(muxer);
                _ = close(fd);
                _ = unlink(temp_name);
                w.muxer = null;
                w.fd = -1;
                w.output_path_set = false;
                @memset(w.output_path[0..], 0);
                setError("AMediaCodec_start failed status={d}", .{status});
                return false;
            }
            w.started = true;
        }
        return true;
    }
    setError("native segment writer could not allocate unique path", .{});
    return false;
}

pub fn finishSegment(writer_handle: c.jlong, final_chars: [*c]const u8) SegmentFinishResult {
    const w = lockForEncodedHandle(writer_handle) orelse return .failed;
    defer unlockWriter(w);
    return closeSegmentLocked(w, final_chars);
}

pub fn acquireInputWindowNoGlobal(writer_handle: c.jlong) ?*c.ANativeWindow {
    const w = lockForHandleNoGlobal(writer_handle) orelse return null;
    defer unlockWriter(w);
    const window = w.input_window orelse {
        setError("native writer input window unavailable", .{});
        return null;
    };
    c.ANativeWindow_acquire(window);
    return window;
}

pub fn acquireInputWindow(writer_handle: c.jlong) ?*c.ANativeWindow {
    lockGlobal();
    defer unlockGlobal();
    return acquireInputWindowNoGlobal(writer_handle);
}

pub fn drain(writer_handle: c.jlong, timeout_us: c.jlong) c.jlong {
    const w = lockForHandle(writer_handle) orelse return -1;
    defer unlockWriter(w);
    return drainLocked(w, timeout_us);
}

pub fn drainFast(writer_handle: c.jlong, timeout_us: c.jlong) c.jlong {
    const w = lockForEncodedHandle(writer_handle) orelse return drain(writer_handle, timeout_us);
    defer unlockWriter(w);
    return drainLocked(w, timeout_us);
}

pub fn finishToPath(writer_handle: c.jlong, final_chars: [*c]const u8) bool {
    lockGlobal();
    var writer: ?*NativeSegmentWriter = null;
    var writer_index: ?usize = null;
    for (0..MAX_NATIVE_WRITERS) |i| {
        if (!g_used[i] or g_writers[i].handle != writer_handle) continue;
        writer = &g_writers[i];
        writer_index = i;
        break;
    }
    if (writer) |w| lockWriter(w);
    unlockGlobal();
    const w = writer orelse {
        setError("native writer finish missing handle", .{});
        return false;
    };
    defer {
        unlockWriter(w);
        if (writer_index) |idx| {
            lockGlobal();
            if (g_used[idx] and &g_writers[idx] == w and g_writers[idx].handle == 0) g_used[idx] = false;
            unlockGlobal();
        }
    }
    const has_output = w.output_path_set;
    var temp_path: [1024:0]u8 = [_:0]u8{0} ** 1024;
    if (has_output) @memcpy(temp_path[0..], w.output_path[0..]);
    const stopped = stopLocked(w, writer_handle);
    if (!stopped or !has_output) {
        if (has_output) _ = unlink(&temp_path);
        releaseResources(w);
        setError("native writer finish missing output samples", .{});
        return false;
    }
    const renamed = rename(&temp_path, final_chars) == 0;
    if (!renamed) {
        _ = unlink(&temp_path);
        releaseResources(w);
        setError("native writer rename failed", .{});
        return false;
    }
    releaseResources(w);
    return true;
}

pub fn releaseHandle(writer_handle: c.jlong) bool {
    lockGlobal();
    var writer: ?*NativeSegmentWriter = null;
    var writer_index: ?usize = null;
    for (0..MAX_NATIVE_WRITERS) |i| {
        if (!g_used[i] or g_writers[i].handle != writer_handle) continue;
        writer = &g_writers[i];
        writer_index = i;
        break;
    }
    if (writer) |w| lockWriter(w);
    unlockGlobal();
    const w = writer orelse {
        setError("native writer release missing handle", .{});
        return false;
    };
    releaseResources(w);
    unlockWriter(w);
    if (writer_index) |idx| {
        lockGlobal();
        if (g_used[idx] and &g_writers[idx] == w and g_writers[idx].handle == 0) g_used[idx] = false;
        unlockGlobal();
    }
    return true;
}
