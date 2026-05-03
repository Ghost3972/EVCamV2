const std = @import("std");
const c = @import("c");
const types = @import("evcam_types.zig");

const MAX_CLEANUP_FILES = types.MAX_CLEANUP_FILES;
const MAX_EMERGENCY_SOURCES = types.MAX_EMERGENCY_SOURCES;
const MAX_EMERGENCY_REQUESTS = types.MAX_EMERGENCY_REQUESTS;
const EmergencySourceSegment = types.EmergencySourceSegment;
const EmergencyClipRequest = types.EmergencyClipRequest;

const O_RDONLY_ANDROID = types.O_RDONLY_ANDROID;
const SEEK_END_ANDROID = types.SEEK_END_ANDROID;

extern fn open(path: [*c]const u8, flags: c_int, mode: c_int) c_int;
extern fn close(fd: c_int) c_int;
extern fn lseek64(fd: c_int, offset: i64, whence: c_int) i64;
extern fn rename(oldpath: [*c]const u8, newpath: [*c]const u8) c_int;
extern fn unlink(path: [*c]const u8) c_int;
extern fn malloc(size: usize) ?*anyopaque;
extern fn free(ptr: ?*anyopaque) void;

pub const CleanupCallbacks = struct {
    cleanupLimitReached: *const fn (usize) void,
    skippedPendingEmergency: *const fn ([:0]const u8) void,
    skippedNewPendingEmergency: *const fn ([:0]const u8) void,
    deletedOldSegment: *const fn ([:0]const u8, i64, i64, i64) void,
    isPendingEmergencyPath: *const fn ([*c]const u8) bool,
};

const CleanupCandidate = struct {
    path: [1024:0]u8 = [_:0]u8{0} ** 1024,
    name: [256:0]u8 = [_:0]u8{0} ** 256,
    size: i64 = 0,
    is_event: bool = false,
    protected_by_emergency: bool = false,
};

pub const EmergencyProtectionSnapshot = struct {
    sources: [MAX_EMERGENCY_SOURCES]EmergencySourceSegment = [_]EmergencySourceSegment{EmergencySourceSegment{}} ** MAX_EMERGENCY_SOURCES,
    source_count: usize = 0,
    requests: [MAX_EMERGENCY_REQUESTS]EmergencyClipRequest = [_]EmergencyClipRequest{EmergencyClipRequest{}} ** MAX_EMERGENCY_REQUESTS,
    request_count: usize = 0,
};

pub const PlaybackScanResult = struct {
    paths: [MAX_CLEANUP_FILES][1024:0]u8 = undefined,
    count: usize = 0,
};

pub const PlaybackCacheEntry = struct {
    path: [1024:0]u8 = [_:0]u8{0} ** 1024,
    name: [256:0]u8 = [_:0]u8{0} ** 256,
    key_len: usize = 0,
    size: i64 = 0,
    thumbnail_path: [1024:0]u8 = [_:0]u8{0} ** 1024,
    thumbnail_size: i64 = 0,
};

pub const PlaybackCacheBuildResult = struct {
    entries: [MAX_CLEANUP_FILES]PlaybackCacheEntry = undefined,
    count: usize = 0,
};

fn copyCStringToBuffer(dst: []u8, src: [*c]const u8) bool {
    const value = std.mem.span(src);
    if (value.len >= dst.len) return false;
    @memset(dst, 0);
    @memcpy(dst[0..value.len], value);
    return true;
}

fn pathJoin(dst: *[1024:0]u8, dir_path: [*c]const u8, name: []const u8) bool {
    const dir = std.mem.span(dir_path);
    const slash_len: usize = if (dir.len > 0 and dir[dir.len - 1] == '/') 0 else 1;
    if (dir.len + slash_len + name.len >= dst.len) return false;
    @memset(dst, 0);
    @memcpy(dst[0..dir.len], dir);
    var offset = dir.len;
    if (slash_len == 1) {
        dst[offset] = '/';
        offset += 1;
    }
    @memcpy(dst[offset..][0..name.len], name);
    return true;
}

fn fileSizeNative(path: [*c]const u8) i64 {
    const fd = open(path, O_RDONLY_ANDROID, 0);
    if (fd < 0) return 0;
    defer _ = close(fd);
    const size = lseek64(fd, 0, SEEK_END_ANDROID);
    return @max(size, 0);
}

fn fileExistsNative(path: [*c]const u8) bool {
    const fd = open(path, O_RDONLY_ANDROID, 0);
    if (fd < 0) return false;
    _ = close(fd);
    return true;
}

fn availableBytesNative(path: [*c]const u8) i64 {
    var stats: c.struct_statvfs = undefined;
    if (c.statvfs(path, &stats) != 0) return -1;
    const block_size: u128 = if (stats.f_frsize > 0) @intCast(stats.f_frsize) else @intCast(stats.f_bsize);
    const available_blocks: u128 = @intCast(stats.f_bavail);
    const bytes = block_size * available_blocks;
    return if (bytes > @as(u128, @intCast(std.math.maxInt(i64)))) std.math.maxInt(i64) else @intCast(bytes);
}

fn deleteNativeFile(path: [*c]const u8) i64 {
    const size = fileSizeNative(path);
    if (unlink(path) != 0) return 0;
    return size;
}

fn deleteThumbnailSidecarsNative(video_path: [*c]const u8) i64 {
    const video = std.mem.span(video_path);
    const base_len = if (std.mem.endsWith(u8, video, ".mp4")) video.len - 4 else video.len;
    var deleted: i64 = 0;
    const exts = [_][]const u8{ ".bmp", ".jpg", ".jpeg" };
    for (exts) |ext| {
        var sidecar: [1024:0]u8 = [_:0]u8{0} ** 1024;
        if (base_len + ext.len >= sidecar.len) continue;
        @memcpy(sidecar[0..base_len], video[0..base_len]);
        @memcpy(sidecar[base_len..][0..ext.len], ext);
        deleted += deleteNativeFile(&sidecar);
    }
    return deleted;
}

fn sidecarPathForVideo(dst: *[1024:0]u8, video_path: [*c]const u8, ext: []const u8) bool {
    const path = std.mem.span(video_path);
    var base_len = path.len;
    if (std.mem.endsWith(u8, path, ".mp4")) base_len = path.len - 4;
    if (base_len + ext.len >= dst.len) return false;
    @memset(dst, 0);
    @memcpy(dst[0..base_len], path[0..base_len]);
    @memcpy(dst[base_len..][0..ext.len], ext);
    return true;
}

fn findThumbnailSidecarForVideo(dst: *[1024:0]u8, video_path: [*c]const u8) i64 {
    const exts = [_][]const u8{ ".jpg", ".jpeg", ".bmp" };
    for (exts) |ext| {
        if (!sidecarPathForVideo(dst, video_path, ext)) continue;
        const size = fileSizeNative(dst);
        if (size > 0) return size;
    }
    @memset(dst, 0);
    return 0;
}

fn isDigitByte(ch: u8) bool {
    return ch >= '0' and ch <= '9';
}

fn looksLikePlaybackVideoName(name: []const u8) bool {
    if (!std.ascii.endsWithIgnoreCase(name, ".mp4") or name.len < 17) return false;
    for (name[0..8]) |ch| if (!isDigitByte(ch)) return false;
    if (name[8] != '_') return false;
    for (name[9..13]) |ch| if (!isDigitByte(ch)) return false;
    if (name.len >= 19 and isDigitByte(name[13]) and isDigitByte(name[14])) return true;
    return name[13] == '_' or name[13] == '.';
}

fn looksLikeLegacyBarePlaybackVideoName(name: []const u8) bool {
    if (name.len != 13) return false;
    for (name[0..8]) |ch| if (!isDigitByte(ch)) return false;
    if (name[8] != '_') return false;
    for (name[9..13]) |ch| if (!isDigitByte(ch)) return false;
    return true;
}

fn promoteLegacyBarePlaybackVideo(out_path: *[1024:0]u8, dir_path: [*c]const u8, name: []const u8) bool {
    if (!looksLikeLegacyBarePlaybackVideoName(name)) return false;
    var old_path: [1024:0]u8 = [_:0]u8{0} ** 1024;
    if (!pathJoin(&old_path, dir_path, name)) return false;
    if (fileSizeNative(&old_path) <= 0) return false;

    var promoted_name_buf: [260:0]u8 = [_:0]u8{0} ** 260;
    const promoted_name = std.fmt.bufPrintZ(&promoted_name_buf, "{s}.mp4", .{name}) catch return false;
    var promoted_path: [1024:0]u8 = [_:0]u8{0} ** 1024;
    if (!pathJoin(&promoted_path, dir_path, promoted_name)) return false;
    if (fileExistsNative(&promoted_path)) return false;
    if (rename(&old_path, &promoted_path) != 0) return false;
    out_path.* = promoted_path;
    return true;
}

fn appendJsonEscaped(buffer: []u8, offset: *usize, value: []const u8) bool {
    for (value) |ch| {
        const needed: usize = if (ch == '"' or ch == '\\') 2 else 1;
        if (offset.* + needed >= buffer.len) return false;
        if (needed == 2) {
            buffer[offset.*] = '\\';
            offset.* += 1;
        }
        buffer[offset.*] = ch;
        offset.* += 1;
    }
    return true;
}

pub fn appendJsonLiteral(buffer: []u8, offset: *usize, value: []const u8) bool {
    if (offset.* + value.len >= buffer.len) return false;
    @memcpy(buffer[offset.*..][0..value.len], value);
    offset.* += value.len;
    return true;
}

fn appendJsonInt(buffer: []u8, offset: *usize, value: i64) bool {
    var tmp: [32]u8 = undefined;
    const text = std.fmt.bufPrint(&tmp, "{d}", .{value}) catch return false;
    return appendJsonLiteral(buffer, offset, text);
}

pub fn appendPlaybackCacheEntryJson(buffer: []u8, offset: *usize, entry: *const PlaybackCacheEntry) bool {
    const path = std.mem.sliceTo(&entry.path, 0);
    const name = std.mem.sliceTo(&entry.name, 0);
    const key = name[0..@min(entry.key_len, name.len)];
    if (!appendJsonLiteral(buffer, offset, "{\"key\":\"")) return false;
    if (!appendJsonEscaped(buffer, offset, key)) return false;
    if (!appendJsonLiteral(buffer, offset, "\",\"path\":\"")) return false;
    if (!appendJsonEscaped(buffer, offset, path)) return false;
    if (!appendJsonLiteral(buffer, offset, "\",\"length\":")) return false;
    if (!appendJsonInt(buffer, offset, entry.size)) return false;
    if (!appendJsonLiteral(buffer, offset, ",\"modified\":0")) return false;
    if (entry.thumbnail_size > 0) {
        const thumb = std.mem.sliceTo(&entry.thumbnail_path, 0);
        if (!appendJsonLiteral(buffer, offset, ",\"thumbnailPath\":\"")) return false;
        if (!appendJsonEscaped(buffer, offset, thumb)) return false;
        if (!appendJsonLiteral(buffer, offset, "\",\"thumbnailModified\":0")) return false;
    }
    return appendJsonLiteral(buffer, offset, "}");
}

pub fn playbackEntryFromVideoPath(entry: *PlaybackCacheEntry, video_path: [*c]const u8) bool {
    const path = std.mem.span(video_path);
    if (!looksLikePlaybackVideoName(std.fs.path.basename(path))) return false;
    const size = fileSizeNative(video_path);
    if (size <= 0) return false;
    entry.* = PlaybackCacheEntry{};
    entry.size = size;
    if (!copyCStringToBuffer(entry.path[0..], video_path)) return false;
    const name = std.fs.path.basename(path);
    entry.key_len = if (std.ascii.endsWithIgnoreCase(name, ".mp4")) name.len - 4 else name.len;
    if (name.len < entry.name.len) @memcpy(entry.name[0..name.len], name);
    entry.thumbnail_size = findThumbnailSidecarForVideo(&entry.thumbnail_path, video_path);
    return true;
}

pub fn scanPlaybackVideosNative(result: *PlaybackScanResult, dir_path: [*c]const u8) void {
    const dir = c.opendir(dir_path) orelse return;
    defer _ = c.closedir(dir);
    while (result.count < result.paths.len) {
        const entry = c.readdir(dir) orelse break;
        const d_name = entry.*.d_name;
        var name_len: usize = 0;
        while (name_len < d_name.len and d_name[name_len] != 0) : (name_len += 1) {}
        const name = d_name[0..name_len];
        var path: [1024:0]u8 = [_:0]u8{0} ** 1024;
        if (std.ascii.endsWithIgnoreCase(name, ".mp4")) {
            if (!pathJoin(&path, dir_path, name)) continue;
        } else if (!promoteLegacyBarePlaybackVideo(&path, dir_path, name)) {
            continue;
        }
        if (fileSizeNative(&path) <= 0) continue;
        result.paths[result.count] = path;
        result.count += 1;
    }
}

pub fn scanPlaybackImagesNative(result: *PlaybackScanResult, dir_path: [*c]const u8) void {
    const dir = c.opendir(dir_path) orelse return;
    defer _ = c.closedir(dir);
    while (result.count < result.paths.len) {
        const entry = c.readdir(dir) orelse break;
        const d_name = entry.*.d_name;
        var name_len: usize = 0;
        while (name_len < d_name.len and d_name[name_len] != 0) : (name_len += 1) {}
        const name = d_name[0..name_len];
        if (!std.ascii.endsWithIgnoreCase(name, ".jpg") and
            !std.ascii.endsWithIgnoreCase(name, ".jpeg") and
            !std.ascii.endsWithIgnoreCase(name, ".png")) continue;
        var path: [1024:0]u8 = [_:0]u8{0} ** 1024;
        if (!pathJoin(&path, dir_path, name)) continue;
        if (fileSizeNative(&path) <= 0) continue;
        result.paths[result.count] = path;
        result.count += 1;
    }
}

pub fn buildPlaybackCacheNative(result: *PlaybackCacheBuildResult, dir_path: [*c]const u8) void {
    const dir = c.opendir(dir_path) orelse return;
    defer _ = c.closedir(dir);
    while (result.count < result.entries.len) {
        const entry = c.readdir(dir) orelse break;
        const d_name = entry.*.d_name;
        var name_len: usize = 0;
        while (name_len < d_name.len and d_name[name_len] != 0) : (name_len += 1) {}
        const name = d_name[0..name_len];
        var path: [1024:0]u8 = [_:0]u8{0} ** 1024;
        if (looksLikePlaybackVideoName(name)) {
            if (!pathJoin(&path, dir_path, name)) continue;
        } else if (!promoteLegacyBarePlaybackVideo(&path, dir_path, name)) {
            continue;
        }
        const size = fileSizeNative(&path);
        if (size <= 0) continue;
        var cache_entry = PlaybackCacheEntry{};
        if (!playbackEntryFromVideoPath(&cache_entry, &path)) continue;
        result.entries[result.count] = cache_entry;
        result.count += 1;
    }
}

fn pathNeededByEmergencySnapshot(snapshot: *const EmergencyProtectionSnapshot, path: [*c]const u8) bool {
    const candidate = std.mem.span(path);
    if (candidate.len == 0 or snapshot.source_count == 0 or snapshot.request_count == 0) return false;
    for (snapshot.sources[0..snapshot.source_count]) |source| {
        if (!std.mem.eql(u8, std.mem.sliceTo(&source.path, 0), candidate)) continue;
        for (snapshot.requests[0..snapshot.request_count]) |request| {
            if (source.end_ms > request.start_ms and source.start_ms < request.end_ms) return true;
        }
    }
    return false;
}

pub fn cleanupStorageNative(
    dir_path: [*c]const u8,
    reserved_bytes: i64,
    available_bytes: i64,
    protected_path: ?[*c]const u8,
    emergency_snapshot: *const EmergencyProtectionSnapshot,
    callbacks: CleanupCallbacks,
    out_deleted_count: *i64,
    out_deleted_bytes: *i64,
) i64 {
    const fresh_available = availableBytesNative(dir_path);
    var available = if (fresh_available >= 0) fresh_available else available_bytes;
    const protected_slice = if (protected_path) |p| std.mem.span(p) else "";
    const dir = c.opendir(dir_path) orelse return available;
    defer _ = c.closedir(dir);

    const candidates_raw = malloc(@sizeOf([MAX_CLEANUP_FILES]CleanupCandidate)) orelse return available;
    defer free(candidates_raw);
    const candidates: *[MAX_CLEANUP_FILES]CleanupCandidate = @ptrCast(@alignCast(candidates_raw));
    var candidate_count: usize = 0;

    while (c.readdir(dir)) |entry| {
        const d_name = entry.*.d_name;
        var name_len: usize = 0;
        while (name_len < d_name.len and d_name[name_len] != 0) : (name_len += 1) {}
        const name = d_name[0..name_len];
        if (name.len == 0 or std.mem.eql(u8, name, ".") or std.mem.eql(u8, name, "..")) continue;
        var path: [1024:0]u8 = [_:0]u8{0} ** 1024;
        if (!pathJoin(&path, dir_path, name)) continue;
        if (protected_slice.len > 0 and std.mem.eql(u8, std.mem.sliceTo(&path, 0), protected_slice)) continue;
        if (std.ascii.endsWithIgnoreCase(name, ".mp4") and candidate_count < candidates.len) {
            candidates[candidate_count] = CleanupCandidate{};
            candidates[candidate_count].size = fileSizeNative(&path);
            candidates[candidate_count].is_event = std.mem.indexOf(u8, name, "_event") != null;
            candidates[candidate_count].protected_by_emergency = pathNeededByEmergencySnapshot(emergency_snapshot, &path);
            _ = copyCStringToBuffer(&candidates[candidate_count].path, &path);
            if (name.len < candidates[candidate_count].name.len) {
                @memcpy(candidates[candidate_count].name[0..name.len], name);
            }
            candidate_count += 1;
        } else if (std.ascii.endsWithIgnoreCase(name, ".mp4") and candidate_count >= candidates.len) {
            callbacks.cleanupLimitReached(candidates.len);
        }
    }

    std.mem.sort(CleanupCandidate, candidates[0..candidate_count], {}, struct {
        fn lessThan(_: void, a: CleanupCandidate, b: CleanupCandidate) bool {
            if (a.is_event != b.is_event) return !a.is_event;
            return std.mem.order(u8, std.mem.sliceTo(&a.name, 0), std.mem.sliceTo(&b.name, 0)) == .lt;
        }
    }.lessThan);

    if (reserved_bytes <= 0 or available >= reserved_bytes) return available;
    for (candidates[0..candidate_count]) |candidate| {
        if (available >= reserved_bytes) break;
        if (candidate.protected_by_emergency) {
            callbacks.skippedPendingEmergency(std.mem.sliceTo(&candidate.name, 0));
            continue;
        }
        if (callbacks.isPendingEmergencyPath(&candidate.path)) {
            callbacks.skippedNewPendingEmergency(std.mem.sliceTo(&candidate.name, 0));
            continue;
        }
        const deleted = deleteNativeFile(&candidate.path);
        if (deleted > 0) {
            out_deleted_count.* += 1;
            out_deleted_bytes.* += deleted;
            const sidecars_deleted = deleteThumbnailSidecarsNative(&candidate.path);
            out_deleted_bytes.* += sidecars_deleted;
            available += deleted + sidecars_deleted;
            callbacks.deletedOldSegment(std.mem.sliceTo(&candidate.name, 0), deleted + sidecars_deleted, available, reserved_bytes);
        }
    }
    return available;
}
