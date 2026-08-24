const std = @import("std");

pub fn build(b: *std.Build) !void {
  try setupTarget(b, .linux, .aarch64, "aarch64");
  try setupTarget(b, .linux, .x86_64, "amd64");
  try setupTarget(b, .macos, .aarch64, "aarch64");
  try setupTarget(b, .macos, .x86_64, "x86_64");
  try setupTarget(b, .windows, .aarch64, "aarch64");
  try setupTarget(b, .windows, .x86_64, "amd64");
}

fn setupTarget(b: *std.Build, tag: std.Target.Os.Tag, arch: std.Target.Cpu.Arch, dir: []const u8) !void {
  const lib = b.addLibrary(.{
    .name = "quickjs",
    .linkage = .dynamic,
    .root_module = b.createModule(.{
      .target = b.resolveTargetQuery(.{
        .cpu_arch = arch,
        .os_tag = tag,
        // We need to explicitly specify gnu for linux, as otherwise it defaults to musl.
        // See https://github.com/ziglang/zig/issues/16624#issuecomment-1801175600.
        .abi = if (tag == .linux) .gnu else null,
      }),
      .optimize = .ReleaseSmall,
    }),
  });

  const version = readVersionFile();
  var quoted_version_buf: [64]u8 = undefined;
  const quoted_version = try std.fmt.bufPrint(&quoted_version_buf, "\"{s}\"", .{ version });
  lib.root_module.addCMacro("CONFIG_VERSION", quoted_version);
  // Uncomment for allocation tracing build
  // lib.root_module.addCMacro("QJS_ALLOC_TRACE", "1");
  // lib.root_module.addCMacro("QJS_AT_FP_WALK", "1");

  lib.root_module.addIncludePath(b.path("native/include/share"));
  lib.root_module.addIncludePath(
    switch (tag) {
      .windows => b.path("native/include/windows"),
      else => b.path("native/include/unix"),
    }
  );

  lib.root_module.link_libc = true;

  lib.root_module.addCSourceFiles(.{
    .files = &.{
      "native/alloc-trace.c",
      "native/common/context-no-eval.c",
      "native/common/finalization-registry.c",
      "native/common/global-gc.c",
      "native/common/JsIntrinsics.c",
      "native/quickjs/cutils.c",
      "native/quickjs/libregexp.c",
      "native/quickjs/libunicode.c",
      "native/quickjs/quickjs.c",
      "native/quickjs/dtoa.c",
    },
    .flags = &.{
      "-std=gnu99",
      // glibc declares dladdr/Dl_info (used by alloc-trace.c) only under _GNU_SOURCE.
      // Uncomment for allocation tracing build
      // "-D_GNU_SOURCE",
    },
  });

  lib.root_module.link_libcpp = true;
  // TODO Tree-walk this dirs for all C++ files.
  lib.root_module.addCSourceFiles(.{
    .files = &.{
      "native/Context.cpp",
      "native/ExceptionThrowers.cpp",
      "native/InboundCallChannel.cpp",
      "native/OutboundCallChannel.cpp",
      "native/quickjs-jni.cpp",
    },
    .flags = &.{
      "-std=c++11",
    },
  });

  const install = b.addInstallArtifact(lib, .{
    .dest_dir = .{
      .override = .{
        .custom = dir,
      },
    },
  });

  b.getInstallStep().dependOn(&install.step);
}

fn readVersionFile() []const u8 {
  const version = @embedFile("native/quickjs/VERSION");
  return std.mem.trim(u8, version, "\r\n");
}
