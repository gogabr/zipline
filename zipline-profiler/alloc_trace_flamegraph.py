#!/usr/bin/env python3
"""Convert a qjs-alloc-trace dump to Chrome Trace Event format flamegraph JSON.

Input is a v2 event stream produced by QuickJs.startAllocTracing(path)
(A/F/R events with native stacks, '+'/'-' JS stack push/pop markers);
v1 aggregate dumps (S lines) are also accepted. The JS stack and the live
heap are reconstructed by replaying the stream, so the live set is simply
the retained metric; `H` lines written by QuickJs.dumpAllocHeap() mark heap
snapshot points (see --heap-at).

The X axis represents memory (bytes), not time: each bucket's weight
(alloc_bytes by default) becomes the width of its frames. Every stack depth
is emitted on its own "thread" (lane), so chrome://tracing or Perfetto renders
it as a flamegraph.

Usage:
  # Pull a stream from the device
  adb exec-out "cat /sdcard/Android/data/<app-id>/files/alloc_trace.txt" > alloc_trace.txt

  # Live heap flamegraph + top allocation sites
  python3 alloc_trace_flamegraph.py alloc_trace.txt --metric retained --top 15 -o flame.json

  # Symbolized native frames (use the unstripped .so matching the device ABI)
  python3 alloc_trace_flamegraph.py alloc_trace.txt --metric retained \
      --native /path/to/libquickjs.so \
      --symbolizer ~/Library/Android/sdk/ndk/*/toolchains/llvm/prebuilt/*/bin/llvm-symbolizer \
      -o flame.json

  # Live heap at the Nth dumpAllocHeap() marker (default: whole stream)
  python3 alloc_trace_flamegraph.py alloc_trace.txt --metric retained --heap-at 1 -o heap.json

  # Growth between two streams
  python3 alloc_trace_flamegraph.py --diff dump1.txt dump2.txt -o diff.json
"""

import argparse
import json
import re
import subprocess
import sys

LINE_RE = re.compile(
    r"^S allocs=(\d+) alloc_bytes=(\d+) frees=(\d+) free_bytes=(\d+) "
    r"reallocs=(\d+) js=(.*?) native=(\S*)\s*$"
)


class Node:
    __slots__ = ("name", "weight", "self_weight", "children", "alloc_w", "free_w")

    def __init__(self, name):
        self.name = name
        self.weight = 0        # total weight of the subtree
        self.self_weight = 0   # weight of buckets ending exactly at this node
        self.children = {}
        self.alloc_w = 0       # allocated bytes attributed to this node (direct replay)
        self.free_w = 0        # freed bytes attributed to this node (direct replay)


V2_HEADER_PREFIX = "# qjs-alloc-trace v2 "
V2_ALLOC_RE = re.compile(r"^A (\S+) (\d+) native=(\S*)$")
V2_FREE_RE = re.compile(r"^F (\S+)$")
V2_REALLOC_RE = re.compile(r"^R (\S+) (\S+) (\d+) native=(\S*)$")
V2_POP_RE = re.compile(r"^- (\d+)$")
V2_FRAME_DEF_RE = re.compile(r"^D (\d+) (.*)$")
V2_PUSH_RE = re.compile(r"^\+ (\d+)$")


def replay_stream(lines, metric, heap_at=0):
    """Replay a v2 event stream into per-stack buckets.

    The JS stack is implicit in the stream: `+ frame` pushes, `- n` pops.
    Allocation events inherit the stack at their position; frees/reallocs
    resolve through the live pointer map. `H` lines are heap-snapshot
    markers: with heap_at=N, only events up to the Nth marker are replayed.
    """
    if heap_at > 0:
        seen = 0
        cut = len(lines)
        for i, line in enumerate(lines):
            if line.rstrip() == "H":
                seen += 1
                if seen == heap_at:
                    cut = i
                    break
        lines = lines[:cut]

    stack = []
    frame_names = {}
    ptr_map = {}
    stats = {}

    def record_alloc(ptr, size, native):
        # The push sequence already yields the stack root-first; bucket frames
        # follow the v1 convention (root-first).
        frames = tuple(stack) if stack else ("<no js stack>",)
        key = (frames, native)
        s = stats.setdefault(key, [0, 0, 0, 0])
        s[0] += 1
        s[1] += size
        ptr_map[ptr] = (key, size)

    def record_free(ptr):
        entry = ptr_map.pop(ptr, None)
        if entry is None:
            return
        key, size = entry
        stats[key][2] += 1
        stats[key][3] += size

    for line in lines:
        c = line[0]
        if c == "D":
            ident, _, name = line[2:].partition(" ")
            frame_names[int(ident)] = name.rstrip("\n")
            continue
        if c == "+":
            stack.append(frame_names.get(int(line[2:-1]), "?"))
            continue
        if c == "-":
            n = int(line[2:-1])
            if n >= len(stack):
                stack.clear()
            else:
                del stack[-n:]
            continue
        if c == "A":
            _a, ptr, rest = line.split(" ", 2)
            size, _, native = rest.rpartition(" native=")
            record_alloc(ptr, int(size), native.rstrip("\n"))
            continue
        if c == "F":
            record_free(line[2:-1])
            continue
        if c == "R":
            _r, new_ptr, rest = line.split(" ", 2)
            old_ptr, rest = rest.split(" ", 1)
            size, _, native = rest.rpartition(" native=")
            record_free(old_ptr)
            record_alloc(new_ptr, int(size), native.rstrip("\n"))

    buckets = []
    for (frames, native), (allocs, alloc_bytes, frees, free_bytes) in stats.items():
        if metric == "alloc":
            weight = alloc_bytes
        elif metric == "free":
            weight = free_bytes
        else:  # retained
            weight = alloc_bytes - free_bytes
        buckets.append((weight, list(frames), [a for a in native.split(",") if a]))
    return buckets


def parse_buckets(path, metric, heap_at=0):
    buckets = []
    header = {}
    with open(path, "r", encoding="utf-8", errors="replace") as f:
        lines = f.readlines()
    if any(line.startswith(V2_HEADER_PREFIX) for line in lines[:5]):
        for line in lines:
            if line.startswith("#"):
                hm = re.match(r"^# (\w+)=(.*)", line)
                if hm:
                    header[hm.group(1)] = hm.group(2)
        return replay_stream(lines, metric, heap_at), header
    for line in lines:
        if line.startswith("#"):
            hm = re.match(r"^# (\w+)=(.*)", line)
            if hm:
                header[hm.group(1)] = hm.group(2)
            continue
        m = LINE_RE.match(line)
        if not m:
            continue
        allocs, alloc_bytes, frees, free_bytes, _reallocs, js, native = m.groups()
        alloc_bytes = int(alloc_bytes)
        free_bytes = int(free_bytes)
        if metric == "alloc":
            weight = alloc_bytes
        elif metric == "free":
            weight = free_bytes
        else:  # retained
            weight = alloc_bytes - free_bytes
        frames = [f for f in js.split(";") if f]
        if not frames:
            frames = ["<no js stack>"]
        # dump order is innermost-first; flamegraph wants root-first
        frames.reverse()
        buckets.append((weight, frames, native.split(",") if native else []))
    return buckets, header


# Allocator frames between the traced call site and the allocation itself;
# skipped when grouping buckets by allocation site for --top.
ALLOC_PREAMBLE = frozenset({
    "qjs_at_record", "qjs_at_trace", "qjs_at_event", "qjs_at_capture_native",
    "__js_malloc", "js_malloc", "js_mallocz",
    "js_malloc_rt", "js_mallocz_rt", "js_realloc_rt", "js_realloc2",
    "js_realloc", "js_realloc2_rt",
})


def print_top_sites(buckets, native_names, limit):
    groups = {}
    for weight, _frames, native in buckets:
        if weight <= 0:
            continue
        if native_names:
            names = [native_names.get(o, "0x" + o) for o in native if o]
        else:
            names = ["0x" + o for o in native if o]
        site = next((n for n in names if n not in ALLOC_PREAMBLE),
                    names[-1] if names else "<no native stack>")
        weight_sum, alloc_count = groups.get(site, (0, 0))
        groups[site] = (weight_sum + weight, alloc_count + 1)
    for site, (weight, _count) in sorted(groups.items(), key=lambda kv: -kv[1][0])[:limit]:
        print("%9.2f MB  %s" % (weight / 1e6, site), file=sys.stderr)


def symbolize(native_offsets, library, symbolizer):
    unique = sorted({o for o in native_offsets if o})
    if not unique:
        return {}
    proc = subprocess.run(
        # GNU style + no inlines: exactly two lines (name, location) per address,
        # which the line indexing below relies on.
        [symbolizer, "--obj=" + library, "--functions=linkage", "--output-style=GNU", "--no-inlines"],
        input="\n".join("0x" + o for o in unique),
        capture_output=True,
        text=True,
    )
    result = {}
    lines = proc.stdout.splitlines()
    for i, off in enumerate(unique):
        name = lines[2 * i] if 2 * i < len(lines) else ""
        result[off] = name if name and name != "??" else "0x" + off
    return result


def build_trie(buckets, min_bytes, native_names):
    """Build a synthetic-rooted trie keyed on the JS frames of each bucket.

    Frames are reversed to root-first; native C frames captured by the
    tracer are deeper than any JS frame in the call stack, so they are
    appended at the deepest positions.
    """
    root = Node("root")
    dropped = 0
    for weight, frames, native in buckets:
        if weight <= 0:
            continue
        if weight < min_bytes:
            dropped += 1
            continue
        if native_names:
            native_frames = ["[native] " + native_names.get(o, "0x" + o) for o in native if o]
        else:
            native_frames = ["[n:%04x]" % (int(o, 16) & 0xffff) for o in native if o]
        full_frames = frames + list(reversed(native_frames))
        node = root
        node.weight += weight
        for frame in full_frames:
            child = node.children.get(frame)
            if child is None:
                child = Node(frame)
                node.children[frame] = child
            child.weight += weight
            node = child
        node.self_weight += weight
    return root, dropped


def emit_events(root):
    """One X event per trie node, all on a single track (tid = 1).

    Parent durations cover their children, so the trace viewer stacks slices
    by start/width itself; sf references the stackFrames map so each slice
    also carries its full call chain (rootmost parent -> ... -> this frame).
    """
    events = []
    stack_frames = {}
    next_id = [0]

    def frame_id(node, parent_id):
        fid = str(next_id[0])
        next_id[0] += 1
        entry = {"name": node.name, "category": "alloc"}
        if parent_id is not None:
            entry["parent"] = parent_id
        stack_frames[fid] = entry
        return fid

    def emit(node, node_id, start):
        events.append({
            "name": node.name,
            "cat": "alloc",
            "ph": "X",
            "ts": start,
            "dur": node.weight,
            "pid": 1,
            "tid": 1,
            "sf": node_id,
        })
        offset = start
        for child in sorted(node.children.values(), key=lambda c: -c.weight):
            cid = frame_id(child, node_id)
            emit(child, cid, offset)
            offset += child.weight

    root_id = frame_id(root, None)
    emit(root, root_id, 0)
    events.append({
        "name": "thread_name",
        "ph": "M",
        "pid": 1,
        "tid": 1,
        "args": {"name": "allocations"},
    })
    events.append({
        "name": "process_name",
        "ph": "M",
        "pid": 1,
        "args": {"name": "QuickJS allocations (X axis = bytes)"},
    })
    return events, stack_frames


def parse_buckets_diff(path1, path2, metric):
    """Per-stack delta of `metric` (positive = growth from dump1 to dump2)."""
    by_key = {}

    def add(path, sign):
        for weight, frames, native in parse_buckets(path, metric)[0]:
            key = (tuple(frames), tuple(native))
            by_key[key] = by_key.get(key, 0) + sign * weight

    add(path1, -1)
    add(path2, +1)
    return [(w, list(k[0]), list(k[1])) for k, w in by_key.items() if w > 0]


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("inputs", nargs="+",
                        help="qjs-alloc-trace dump(s): one or two for independent flamegraphs, "
                             "or two with --diff to compute newly-allocated objects")
    parser.add_argument("-o", "--outputs", nargs="+", default=[],
                        help="output JSON path(s), one per input (default: <input>.flame.json)")
    parser.add_argument("--metric", choices=["alloc", "free", "retained"], default="alloc",
                        help="weight per stack: alloc_bytes (default), free_bytes, or alloc-free")
    parser.add_argument("--min-bytes", type=int, default=0,
                        help="drop buckets with weight below this (default: 0, keep everything)")
    parser.add_argument("--diff", action="store_true",
                        help="treat the two positional inputs as dump1 (earlier) and dump2 "
                             "(later); emit one flamegraph of positive per-stack deltas")
    parser.add_argument("--native", metavar="LIBQUICKJS.SO",
                        help="append symbolized native frames using this unstripped library")
    parser.add_argument("--symbolizer", default="llvm-symbolizer",
                        help="path to llvm-symbolizer (default: from PATH)")
    parser.add_argument("--top", type=int, metavar="N", default=0,
                        help="print the N biggest allocation sites (grouped by first "
                             "non-allocator native frame) to stderr")
    parser.add_argument("--heap-at", type=int, metavar="N", default=0,
                        help="v2 streams only: replay only up to the Nth heap-snapshot "
                             "marker (H line) written by dumpAllocHeap")
    args = parser.parse_args()

    if args.diff:
        if len(args.inputs) != 2:
            parser.error("--diff requires exactly two inputs")
        output = args.outputs[0] if args.outputs else args.inputs[1] + ".diff.flame.json"
        buckets = parse_buckets_diff(args.inputs[0], args.inputs[1], args.metric)
        native_names = None
        if args.native:
            all_offsets = [o for _, _, native in buckets for o in native]
            native_names = symbolize(all_offsets, args.native, args.symbolizer)
        root, dropped = build_trie(buckets, args.min_bytes, native_names)
        events, stack_frames = emit_events(root)
        out = json.dumps({"traceEvents": events, "stackFrames": stack_frames}, indent=1)
        if output == "-":
            print(out)
        else:
            with open(output, "w") as f:
                f.write(out)
        print("diff: buckets=%d positive=%d dropped=%d total_growth_bytes=%d events=%d -> %s" % (
            len(buckets), len(buckets) - dropped, dropped, root.weight, len(events),
            output if output != "-" else "stdout"), file=sys.stderr)
        return

    outputs = list(args.outputs)
    while len(outputs) < len(args.inputs):
        outputs.append(args.inputs[len(outputs)] + ".flame.json")

    for input_path, output_path in zip(args.inputs, outputs):
        with open(input_path, "r", encoding="utf-8", errors="replace") as f:
            lines = f.readlines()
        is_v2 = any(line.startswith(V2_HEADER_PREFIX) for line in lines[:5])

        header = {}
        for line in lines:
            if line.startswith("#"):
                hm = re.match(r"^# (\w+)=(.*)", line)
                if hm:
                    header[hm.group(1)] = hm.group(2)

        native_names = None
        if args.native:
            all_offsets = []
            for line in lines:
                m = re.search(r"native=(\S+)", line)
                if m:
                    all_offsets.extend(o for o in m.group(1).split(",") if o)
            native_names = symbolize(all_offsets, args.native, args.symbolizer)

        buckets, _header = parse_buckets(input_path, args.metric, args.heap_at)
        root, dropped = build_trie(buckets, args.min_bytes, native_names)
        n_buckets = len(buckets)
        events, stack_frames = emit_events(root)

        out = json.dumps({"traceEvents": events, "stackFrames": stack_frames}, indent=1)
        if output_path == "-":
            print(out)
        else:
            with open(output_path, "w") as f:
                f.write(out)
        print("buckets=%d pruned=%d total_%s_bytes=%d events=%d -> %s" % (
            n_buckets, dropped, args.metric, root.weight, len(events),
            output_path if output_path != "-" else "stdout"), file=sys.stderr)
        # Sanity: for retained on a heap-mode dump the bucket total should
        # match the tracer's own live_bytes.
        live_bytes = header.get("live_bytes")
        if live_bytes and args.metric == "retained" and abs(root.weight - int(live_bytes)) > int(live_bytes) // 100:
            print("warning: bucket total %d differs from header live_bytes=%s "
                  "(wrong --metric or truncated dump?)" % (root.weight, live_bytes), file=sys.stderr)
        if args.top:
            print_top_sites(buckets, native_names, args.top)


if __name__ == "__main__":
    main()
