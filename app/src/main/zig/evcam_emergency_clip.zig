const std = @import("std");
const c = @import("c");
const types = @import("evcam_types.zig");

const TAG = types.TAG;
const O_RDONLY_ANDROID = types.O_RDONLY_ANDROID;
const O_RDWR_ANDROID = types.O_RDWR_ANDROID;
const O_CREAT_ANDROID = types.O_CREAT_ANDROID;
const O_TRUNC_ANDROID = types.O_TRUNC_ANDROID;
const SEEK_SET_ANDROID = types.SEEK_SET_ANDROID;
const SEEK_END_ANDROID = types.SEEK_END_ANDROID;
const SAMPLE_BUFFER_BYTES = types.SAMPLE_BUFFER_BYTES;

extern fn open(path: [*c]const u8, flags: c_int, mode: c_int) c_int;
extern fn close(fd: c_int) c_int;
extern fn lseek64(fd: c_int, offset: i64, whence: c_int) i64;
extern fn malloc(size: usize) ?*anyopaque;
extern fn free(ptr: ?*anyopaque) void;

pub const Callbacks = struct {
    setErrorText: *const fn ([:0]const u8) void,
    logInfoText: *const fn ([:0]const u8) void,
};

var g_callbacks: ?Callbacks = null;

pub fn configure(callbacks: Callbacks) void {
    g_callbacks = callbacks;
}

fn logFallback(comptime priority: c_int, comptime fmt: []const u8, args: anytype) void {
    var buf: [512:0]u8 = [_:0]u8{0} ** 512;
    const msg = std.fmt.bufPrintSentinel(&buf, fmt, args, 0) catch "log format error";
    _ = c.__android_log_print(priority, TAG, "%s", msg.ptr);
}

fn logInfo(comptime fmt: []const u8, args: anytype) void {
    var buf: [512:0]u8 = [_:0]u8{0} ** 512;
    const msg = std.fmt.bufPrintSentinel(&buf, fmt, args, 0) catch "log format error";
    if (g_callbacks) |callbacks| {
        callbacks.logInfoText(msg);
    } else {
        _ = c.__android_log_print(c.ANDROID_LOG_INFO, TAG, "%s", msg.ptr);
    }
}

fn setError(comptime fmt: []const u8, args: anytype) void {
    var buf: [256:0]u8 = [_:0]u8{0} ** 256;
    const msg = std.fmt.bufPrintSentinel(&buf, fmt, args, 0) catch "error format failed";
    if (g_callbacks) |callbacks| {
        callbacks.setErrorText(msg);
    } else {
        logFallback(c.ANDROID_LOG_ERROR, "{s}", .{msg});
    }
}

fn setErrorSlice(msg: [:0]const u8) void {
    if (g_callbacks) |callbacks| {
        callbacks.setErrorText(msg);
    } else {
        logFallback(c.ANDROID_LOG_ERROR, "{s}", .{msg});
    }
}

fn selectVideoTrack(extractor: *c.AMediaExtractor) ?usize {
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

fn frameDurationUs(format: *c.AMediaFormat) i64 {
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

pub fn fileExists(path: [*:0]const u8) bool {
    const fd = open(path, O_RDONLY_ANDROID, 0);
    if (fd < 0) return false;
    _ = close(fd);
    return true;
}

pub fn extract(
    output_path: [*c]const u8,
    clip_start_ms: i64,
    clip_end_ms: i64,
    source_paths: [*]const [*c]const u8,
    source_start_ms: [*]const c.jlong,
    source_end_ms: [*]const c.jlong,
    count: usize,
) c.jlong {
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
        const track = selectVideoTrack(extractor) orelse continue;
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
        const frame_duration_us = frameDurationUs(format);
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
    logInfo("native emergency clip extracted samples={d}", .{written_samples});
    return written_samples;
}
