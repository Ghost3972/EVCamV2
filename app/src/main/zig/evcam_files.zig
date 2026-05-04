const std = @import("std");
const c = @import("c");
const types = @import("evcam_types.zig");

const O_RDONLY_ANDROID = types.O_RDONLY_ANDROID;
const O_RDWR_ANDROID = types.O_RDWR_ANDROID;
const O_CREAT_ANDROID = types.O_CREAT_ANDROID;
const O_TRUNC_ANDROID = types.O_TRUNC_ANDROID;
const SEEK_END_ANDROID = types.SEEK_END_ANDROID;

extern fn open(path: [*c]const u8, flags: c_int, mode: c_int) c_int;
extern fn close(fd: c_int) c_int;
extern fn read(fd: c_int, buf: ?*anyopaque, count: usize) isize;
extern fn write(fd: c_int, buf: ?*const anyopaque, count: usize) isize;
extern fn lseek64(fd: c_int, offset: i64, whence: c_int) i64;
extern fn rename(oldpath: [*c]const u8, newpath: [*c]const u8) c_int;
extern fn unlink(path: [*c]const u8) c_int;

pub fn copyCStringToBuffer(dst: []u8, src: [*c]const u8) bool {
    const value = std.mem.span(src);
    if (value.len >= dst.len) return false;
    @memset(dst, 0);
    @memcpy(dst[0..value.len], value);
    return true;
}

pub fn writeAllFd(fd: c_int, data: []const u8) bool {
    var written: usize = 0;
    while (written < data.len) {
        const rc = write(fd, data.ptr + written, data.len - written);
        if (rc <= 0) return false;
        written += @intCast(rc);
    }
    return true;
}

pub fn thumbnailPathForVideo(dst: *[1024:0]u8, video_path: [*c]const u8) bool {
    const path = std.mem.span(video_path);
    var base_len = path.len;
    if (std.mem.endsWith(u8, path, ".mp4")) base_len = path.len - 4;
    if (base_len + 4 >= dst.len) return false;
    @memset(dst, 0);
    @memcpy(dst[0..base_len], path[0..base_len]);
    @memcpy(dst[base_len..][0..4], ".jpg");
    return true;
}

pub fn appendPathSuffix(dst: *[1024:0]u8, path_z: [*c]const u8, suffix: []const u8) bool {
    const path = std.mem.span(path_z);
    if (path.len + suffix.len >= dst.len) return false;
    @memset(dst, 0);
    @memcpy(dst[0..path.len], path);
    @memcpy(dst[path.len..][0..suffix.len], suffix);
    return true;
}

pub fn copyFile(src_path: [*c]const u8, dst_path: [*c]const u8) bool {
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

pub fn pathJoin(dst: *[1024:0]u8, dir_path: [*c]const u8, name: []const u8) bool {
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

pub fn fileSize(path: [*c]const u8) i64 {
    const fd = open(path, O_RDONLY_ANDROID, 0);
    if (fd < 0) return 0;
    defer _ = close(fd);
    const size = lseek64(fd, 0, SEEK_END_ANDROID);
    return @max(size, 0);
}

pub fn availableBytes(path: [*c]const u8) i64 {
    var stats: c.struct_statvfs = undefined;
    if (c.statvfs(path, &stats) != 0) return -1;
    const block_size: u128 = if (stats.f_frsize > 0) @intCast(stats.f_frsize) else @intCast(stats.f_bsize);
    const available_blocks: u128 = @intCast(stats.f_bavail);
    const bytes = block_size * available_blocks;
    return if (bytes > @as(u128, @intCast(std.math.maxInt(i64)))) std.math.maxInt(i64) else @intCast(bytes);
}

pub fn deleteFile(path: [*c]const u8) i64 {
    const size = fileSize(path);
    if (unlink(path) != 0) return 0;
    return size;
}

pub fn deleteThumbnailSidecars(video_path: [*c]const u8) i64 {
    const video = std.mem.span(video_path);
    const base_len = if (std.mem.endsWith(u8, video, ".mp4")) video.len - 4 else video.len;
    var deleted: i64 = 0;
    const exts = [_][]const u8{ ".bmp", ".jpg", ".jpeg" };
    for (exts) |ext| {
        var sidecar: [1024:0]u8 = [_:0]u8{0} ** 1024;
        if (base_len + ext.len >= sidecar.len) continue;
        @memcpy(sidecar[0..base_len], video[0..base_len]);
        @memcpy(sidecar[base_len..][0..ext.len], ext);
        deleted += deleteFile(&sidecar);
    }
    return deleted;
}

pub fn unlinkPath(path: [*c]const u8) c_int {
    return unlink(path);
}

pub fn renamePath(oldpath: [*c]const u8, newpath: [*c]const u8) c_int {
    return rename(oldpath, newpath);
}
