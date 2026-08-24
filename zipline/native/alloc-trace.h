/*
 * Copyright (C) 2019 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
#ifndef QJS_ALLOC_TRACE_H
#define QJS_ALLOC_TRACE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define QJS_AT_MAX_JS_FRAMES 128
#define QJS_AT_MAX_NATIVE_FRAMES 16
#define QJS_AT_JS_NAME_LEN 64

enum {
  QJS_AT_ALLOC = 1,
  QJS_AT_FREE = 2,
  QJS_AT_REALLOC = 3
};

typedef struct {
  char func_name[QJS_AT_JS_NAME_LEN];
  char filename[QJS_AT_JS_NAME_LEN];
  uint32_t line_num; /* 0 = unknown */
  uint8_t is_native;
} QjsAtJsFrame;

extern volatile int qjs_at_enabled;

/* Bumped by qjs_at_start; per-thread stack caches resync on change. */
extern volatile unsigned int qjs_at_generation;

/* Record 1 of every N events; 1 = record everything. Checked before stack capture. */
extern volatile unsigned int qjs_at_sample_rate;

void qjs_at_set_sample_rate(unsigned int rate);

static inline int qjs_at_sample(void) {
  static unsigned int counter = 0;
  unsigned int rate = qjs_at_sample_rate;
  if (rate <= 1) {
    return 1;
  }
  return (++counter % rate) == 0;
}

/*
 * Starts streaming allocation events to the file at [path], one per line:
 *
 *   + func@file.kt:42   a JS frame was pushed onto the current stack
 *   - 2                 N JS frames were popped
 *   A 0x7f.. 128 native=52f70,82508   allocation of <size> at <ptr>
 *   F 0x7f..                          free of <ptr>
 *   R 0x7f.. 0x7e.. 256 native=..     realloc: new ptr, old ptr, new size
 *
 * The JS stack is implicit: replay pushes/pops to reconstruct it at any
 * event. Aggregation (per-stack totals, live set) is left to the offline
 * analyzer (alloc_trace_flamegraph.py).
 *
 * Limitation: the stream assumes a single JS thread; events from multiple
 * runtimes interleave and the reconstructed stack is meaningless.
 *
 * Returns 0 on success, -1 when the file could not be opened.
 */
int qjs_at_start(const char *path);

/* Writes the totals trailer, fsyncs and closes the stream, stops tracing. */
void qjs_at_stop(void);

/* Writes a heap-snapshot marker ("H") into the stream. The live heap at each
   marker is computed offline (alloc_trace_flamegraph.py --metric retained,
   optionally --heap-at N). */
void qjs_at_dump_heap(void);

/* Called by the engine glue (quickjs.c) between qjs_at_begin/qjs_at_commit.
   [site] is the allocator call site (the hook's return address, 0 for frees)
   and [fp] the hook's frame pointer (a native stack depth proxy); the last
   native unwind is reused while [site], [js_depth] and [fp] are unchanged. */
void qjs_at_stack_push(const QjsAtJsFrame *frame);
void qjs_at_stack_pop(int n);
void qjs_at_event(int kind, const void *ptr, const void *ptr2, size_t size,
                  uintptr_t site, int js_depth, uintptr_t fp);

/* Push/pop/event calls between begin/commit take the stream lock once per
   recorded event instead of once per line. */
void qjs_at_begin(void);
void qjs_at_commit(void);

#ifdef __cplusplus
}
#endif

#endif
