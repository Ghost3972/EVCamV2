const std = @import("std");
const types = @import("evcam_types.zig");

const FinalizeQueue = types.FinalizeQueue;
const ManagedSegmentFinalize = types.ManagedSegmentFinalize;

pub const Callbacks = struct {
    nativeIo: *const fn () std.Io,
    finalizeSegment: *const fn (ManagedSegmentFinalize) i64,
    logSpawnFailed: *const fn ([]const u8) void,
};

pub const Metrics = struct {
    depth: usize = 0,
    active: usize = 0,
    running: bool = false,
    last_available_bytes: i64 = 0,
    submitted_count: i64 = 0,
    completed_count: i64 = 0,
    fallback_count: i64 = 0,
    accepting: bool = false,
};

var g_queue: FinalizeQueue = .{};
var g_callbacks: ?Callbacks = null;

pub fn configure(cb: Callbacks) void {
    g_callbacks = cb;
    if (!g_queue.group_pending and !g_queue.draining) {
        g_queue.accepting = true;
    }
}

fn currentCallbacks() ?Callbacks {
    return g_callbacks;
}

fn nativeIo() std.Io {
    return currentCallbacks().?.nativeIo();
}

fn lockQueue() void {
    g_queue.lock.lockUncancelable(nativeIo());
}

fn unlockQueue() void {
    g_queue.lock.unlock(nativeIo());
}

fn finalizeTask(finalize: ManagedSegmentFinalize) std.Io.Cancelable!void {
    const available = currentCallbacks().?.finalizeSegment(finalize);
    lockQueue();
    g_queue.last_available_bytes = available;
    g_queue.completed_count += 1;
    if (g_queue.active_count > 0) g_queue.active_count -= 1;
    unlockQueue();
}

pub fn submit(finalize: ManagedSegmentFinalize) bool {
    if (finalize.writer_handle == 0) return true;
    if (currentCallbacks() == null) return false;
    lockQueue();
    defer unlockQueue();
    if (!g_queue.accepting or g_queue.draining) return false;
    g_queue.group.concurrent(nativeIo(), finalizeTask, .{finalize}) catch |err| {
        currentCallbacks().?.logSpawnFailed(@errorName(err));
        return false;
    };
    g_queue.active_count += 1;
    g_queue.group_pending = true;
    g_queue.submitted_count += 1;
    return true;
}

pub fn recordFallback() void {
    if (currentCallbacks() == null) return;
    lockQueue();
    g_queue.fallback_count += 1;
    unlockQueue();
}

pub fn drain() i64 {
    if (currentCallbacks() == null) return 0;
    lockQueue();
    const should_await = g_queue.group_pending;
    if (should_await) g_queue.draining = true;
    unlockQueue();

    if (should_await) {
        g_queue.group.await(nativeIo()) catch |err| {
            currentCallbacks().?.logSpawnFailed(@errorName(err));
        };

        lockQueue();
        g_queue.active_count = 0;
        g_queue.group_pending = false;
        g_queue.draining = false;
        unlockQueue();
    }

    lockQueue();
    defer unlockQueue();
    return g_queue.last_available_bytes;
}

pub fn stopWorker() void {
    if (currentCallbacks() == null) return;
    lockQueue();
    g_queue.accepting = false;
    const should_await = g_queue.group_pending;
    if (should_await) g_queue.draining = true;
    unlockQueue();

    if (should_await) {
        g_queue.group.await(nativeIo()) catch |err| {
            currentCallbacks().?.logSpawnFailed(@errorName(err));
        };
    }

    lockQueue();
    g_queue.active_count = 0;
    g_queue.group_pending = false;
    g_queue.draining = false;
    unlockQueue();
}

pub fn snapshotMetrics() Metrics {
    if (currentCallbacks() == null) return .{};
    lockQueue();
    defer unlockQueue();
    return .{
        .depth = g_queue.active_count,
        .active = g_queue.active_count,
        .running = g_queue.active_count > 0,
        .last_available_bytes = g_queue.last_available_bytes,
        .submitted_count = g_queue.submitted_count,
        .completed_count = g_queue.completed_count,
        .fallback_count = g_queue.fallback_count,
        .accepting = g_queue.accepting,
    };
}
