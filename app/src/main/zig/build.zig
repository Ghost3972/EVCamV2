const std = @import("std");

pub fn build(b: *std.Build) void {
    const target = b.standardTargetOptions(.{});
    const optimize = b.standardOptimizeOption(.{});
    const android_sysroot = b.option([]const u8, "android-sysroot", "Android NDK sysroot path") orelse
        @panic("missing -Dandroid-sysroot");
    const android_api = b.option([]const u8, "android-api", "Android API level") orelse "28";
    const libc_file = b.option([]const u8, "libc-file", "Zig libc file for Android target") orelse
        @panic("missing -Dlibc-file");

    const include_dir = b.fmt("{s}/usr/include", .{android_sysroot});
    const arch_include_dir = b.fmt("{s}/usr/include/aarch64-linux-android", .{android_sysroot});
    const lib_dir = b.fmt("{s}/usr/lib/aarch64-linux-android/{s}", .{ android_sysroot, android_api });

    const translate_c = b.addTranslateC(.{
        .root_source_file = b.path("evcam_c.h"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
    });
    translate_c.addSystemIncludePath(.{ .cwd_relative = include_dir });
    translate_c.addSystemIncludePath(.{ .cwd_relative = arch_include_dir });

    const c_module = translate_c.createModule();

    const root_module = b.createModule(.{
        .root_source_file = b.path("evcam_gles_compositor.zig"),
        .target = target,
        .optimize = optimize,
        .link_libc = true,
        .imports = &.{
            .{ .name = "c", .module = c_module },
        },
    });
    root_module.addSystemIncludePath(.{ .cwd_relative = include_dir });
    root_module.addSystemIncludePath(.{ .cwd_relative = arch_include_dir });
    root_module.addLibraryPath(.{ .cwd_relative = lib_dir });
    root_module.linkSystemLibrary("log", .{ .use_pkg_config = .no });
    root_module.linkSystemLibrary("android", .{ .use_pkg_config = .no });
    root_module.linkSystemLibrary("mediandk", .{ .use_pkg_config = .no });
    root_module.linkSystemLibrary("camera2ndk", .{ .use_pkg_config = .no });
    root_module.linkSystemLibrary("jnigraphics", .{ .use_pkg_config = .no });
    root_module.linkSystemLibrary("EGL", .{ .use_pkg_config = .no });
    root_module.linkSystemLibrary("GLESv2", .{ .use_pkg_config = .no });

    const lib = b.addLibrary(.{
        .name = "evcam_gles_compositor",
        .linkage = .dynamic,
        .root_module = root_module,
    });
    lib.setLibCFile(.{ .cwd_relative = libc_file });

    b.installArtifact(lib);
}
