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
#include "alloc-trace.h"

#include "qjs-at-mutex.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#if QJS_ALLOC_TRACE
#if defined(_WIN32)
#define QJS_AT_HAVE_UNWIND 0
#else
#define QJS_AT_HAVE_UNWIND 1
#include <dlfcn.h>
#include <unistd.h>
#include <unwind.h>
#endif
#else
#define QJS_AT_HAVE_UNWIND 0
#endif

volatile int qjs_at_enabled = 0;
volatile unsigned int qjs_at_sample_rate = 10;
volatile unsigned int qjs_at_generation = 0;

void qjs_at_set_sample_rate(unsigned int rate) {
  qjs_at_sample_rate = rate == 0 ? 1 : rate;
}

#if QJS_ALLOC_TRACE
static QjsAtMutex qjs_at_mutex = QJS_AT_MUTEX_INIT;

/* Stream state + per-session counters + the last native unwind, reused while
   the allocator call site, JS stack depth and frame pointer are unchanged. */
typedef struct {
  FILE *out;
  uintptr_t base;
  uint64_t alloc_count;
  uint64_t alloc_bytes;
  uint64_t free_count;
  uint64_t free_bytes;
  uint64_t realloc_count;
  uintptr_t native_cache[QJS_AT_MAX_NATIVE_FRAMES];
  uintptr_t native_cache_site;
  uintptr_t native_cache_fp;
  int native_cache_js_depth;
  int native_cache_count;
  uint64_t native_hits;
  uint64_t native_misses;
} QjsAtStream;

static QjsAtStream qjs_at_stream = {
  NULL, 0, 0, 0, 0, 0, 0, {0}, 0, 0, -1, -1, 0, 0,
};

/* Frame interning: a pushed frame's text is emitted once ("D <id> ..."),
   later pushes reference it as "+ <id>". */
typedef struct {
  QjsAtJsFrame *frames;
  size_t frames_count;
  size_t frames_cap;
  uint32_t *intern;
} QjsAtIntern;

static QjsAtIntern qjs_at_intern = { NULL, 0, 0, NULL };

#define QJS_AT_INTERN_CAP (1 << 16) /* hash -> frame id + 1 */

#if QJS_AT_HAVE_UNWIND
typedef struct {
  uintptr_t *pcs;
  int count;
  int max;
  uintptr_t base;
} QjsAtUnwindContext;

static _Unwind_Reason_Code qjs_at_unwind_callback(struct _Unwind_Context *context, void *arg) {
  QjsAtUnwindContext *u = (QjsAtUnwindContext *)arg;
  uintptr_t pc = (uintptr_t)_Unwind_GetIP(context);
  if (pc != 0) {
    if (u->count < u->max) {
      u->pcs[u->count] = pc >= u->base ? pc - u->base : pc;
      u->count++;
    } else {
      return _URC_END_OF_STACK;
    }
  }
  return _URC_NO_REASON;
}
#endif

static uintptr_t qjs_at_library_base(void) {
#if QJS_AT_HAVE_UNWIND && QJS_ALLOC_TRACE
  Dl_info info;
  if (dladdr((void *)&qjs_at_library_base, &info) && info.dli_fbase) {
    return (uintptr_t)info.dli_fbase;
  }
#endif
  return 0;
}

#if defined(QJS_AT_FP_WALK)
/* Frame-pointer chain walk: [fp] = previous frame record, [fp + 8] = saved
   return address (arm64 x29 and x86_64 rbp layouts agree). Much cheaper than
   DWARF unwinding, but only valid while every frame on the chain has a
   frame pointer (build with -fno-omit-frame-pointer). */
static int qjs_at_capture_native_fp(uintptr_t *pcs, int max) {
  void **fp = (void **)__builtin_frame_address(0);
  void **prev = NULL;
  int count = 0;
  while (fp != NULL && count < max) {
    /* The stack grows down: the caller's frame record must sit at a higher,
       16-byte-aligned address; anything else means a broken chain. */
    if (fp <= prev || ((uintptr_t)fp & 15) != 0) {
      break;
    }
    /* Saved return addresses point to the instruction AFTER the call; match
       libunwind's non-top-frame semantics and attribute to the call site. */
    uintptr_t pc = (uintptr_t)fp[1] - 1;
    pcs[count++] = pc >= qjs_at_stream.base ? pc - qjs_at_stream.base : pc;
    prev = fp;
    fp = (void **)*fp;
  }
  return count;
}
#endif

static int qjs_at_capture_native(uintptr_t *pcs, int max) {
#if defined(QJS_AT_FP_WALK)
  int fp_count = qjs_at_capture_native_fp(pcs, max);
  if (fp_count >= 3) {
    return fp_count;
  }
  /* Chain broke too early (a frame without FP in the middle): fall back. */
#endif
#if QJS_AT_HAVE_UNWIND
  QjsAtUnwindContext u;
  u.pcs = pcs;
  u.count = 0;
  u.max = max;
  u.base = qjs_at_stream.base;
  _Unwind_Backtrace(qjs_at_unwind_callback, &u);
  return u.count;
#else
  (void)pcs;
  (void)max;
  return 0;
#endif
}

static void qjs_at_print_name(FILE *out, const char *name) {
  for (const char *p = name; *p; p++) {
    char c = *p;
    if (c == ';' || c == ',' || c == '=' || c == '\n' || c == '\r') {
      fputc('_', out);
    } else {
      fputc(c, out);
    }
  }
}

static void qjs_at_print_native_stack(FILE *out, int n, const uintptr_t *pcs) {
  for (int i = 0; i < n; i++) {
    if (i > 0) {
      fputc(',', out);
    }
    fprintf(out, "%llx", (unsigned long long)pcs[i]);
  }
}


static uint64_t qjs_at_fnv(const unsigned char *data, size_t len, uint64_t hash) {
  for (size_t i = 0; i < len; i++) {
    hash ^= data[i];
    hash *= 1099511628211ULL;
  }
  return hash;
}

/* Interns [frame]; returns its id, or UINT32_MAX when the arena grows failed. */
static uint32_t qjs_at_intern_frame(const QjsAtJsFrame *frame) {
  uint64_t hash = qjs_at_fnv((const unsigned char *)frame, sizeof(*frame),
                             1469598103934665603ULL);
  size_t index = (size_t)(hash % QJS_AT_INTERN_CAP);
  for (size_t probe = 0; probe < QJS_AT_INTERN_CAP; probe++) {
    uint32_t slot = qjs_at_intern.intern[index];
    if (slot == 0) {
      if (qjs_at_intern.frames_count == qjs_at_intern.frames_cap) {
        size_t new_cap = qjs_at_intern.frames_cap == 0 ? 256 : qjs_at_intern.frames_cap * 2;
        QjsAtJsFrame *grown =
            (QjsAtJsFrame *)realloc(qjs_at_intern.frames, new_cap * sizeof(*grown));
        if (!grown) {
          return UINT32_MAX;
        }
        qjs_at_intern.frames = grown;
        qjs_at_intern.frames_cap = new_cap;
      }
      uint32_t id = (uint32_t)qjs_at_intern.frames_count++;
      qjs_at_intern.frames[id] = *frame;
      qjs_at_intern.intern[index] = id + 1;
      return id;
    }
    if (memcmp(&qjs_at_intern.frames[slot - 1], frame, sizeof(*frame)) == 0) {
      return slot - 1;
    }
    index = (index + 1) % QJS_AT_INTERN_CAP;
  }
  return UINT32_MAX;
}

int qjs_at_start(const char *path) {
  qjs_at_mutex_lock(&qjs_at_mutex);
  if (qjs_at_stream.out) {
    fclose(qjs_at_stream.out);
    qjs_at_stream.out = NULL;
  }
  qjs_at_stream.alloc_count = 0;
  qjs_at_stream.alloc_bytes = 0;
  qjs_at_stream.free_count = 0;
  qjs_at_stream.free_bytes = 0;
  qjs_at_stream.realloc_count = 0;
  qjs_at_intern.frames_count = 0;
  qjs_at_stream.native_cache_site = 0;
  qjs_at_stream.native_cache_fp = 0;
  qjs_at_stream.native_cache_js_depth = -1;
  qjs_at_stream.native_cache_count = -1;
  if (qjs_at_intern.intern) {
    memset(qjs_at_intern.intern, 0, QJS_AT_INTERN_CAP * sizeof(*qjs_at_intern.intern));
  } else {
    qjs_at_intern.intern = (uint32_t *)calloc(QJS_AT_INTERN_CAP, sizeof(uint32_t));
  }
  qjs_at_stream.base = qjs_at_library_base();
  qjs_at_stream.out = fopen(path, "w");
  if (qjs_at_stream.out) {
    /* 1 MB stream buffer: events are small and hot-path, so a large buffer
       keeps flush/syscall frequency low. */
    setvbuf(qjs_at_stream.out, NULL, _IOFBF, 1 << 20);
  }
  qjs_at_generation++;
  qjs_at_enabled = qjs_at_stream.out != NULL;
  if (qjs_at_stream.out) {
    fprintf(qjs_at_stream.out, "# qjs-alloc-trace v2 sample_rate=%u base=0x%llx\n",
            qjs_at_sample_rate, (unsigned long long)qjs_at_stream.base);
  }
  qjs_at_mutex_unlock(&qjs_at_mutex);
  return qjs_at_stream.out ? 0 : -1;
}

/* Writes a heap-snapshot marker ("H") into the stream; the live heap at each
   marker is computed offline from the event stream
   (alloc_trace_flamegraph.py --metric retained [--heap-at N]). */
void qjs_at_dump_heap(void) {
  qjs_at_mutex_lock(&qjs_at_mutex);
  if (qjs_at_stream.out) {
    fputs("H\n", qjs_at_stream.out);
    fflush(qjs_at_stream.out);
  }
  qjs_at_mutex_unlock(&qjs_at_mutex);
}

void qjs_at_stop(void) {
  qjs_at_mutex_lock(&qjs_at_mutex);
  qjs_at_enabled = 0;
  if (qjs_at_stream.out) {
    fprintf(qjs_at_stream.out,
            "# totals allocs=%llu alloc_bytes=%llu frees=%llu free_bytes=%llu reallocs=%llu\n",
            (unsigned long long)qjs_at_stream.alloc_count,
            (unsigned long long)qjs_at_stream.alloc_bytes,
            (unsigned long long)qjs_at_stream.free_count,
            (unsigned long long)qjs_at_stream.free_bytes,
            (unsigned long long)qjs_at_stream.realloc_count);
    fflush(qjs_at_stream.out);
#if !defined(_WIN32)
    fsync(fileno(qjs_at_stream.out));
#endif
    fclose(qjs_at_stream.out);
    qjs_at_stream.out = NULL;
  }
  qjs_at_mutex_unlock(&qjs_at_mutex);
}

void qjs_at_begin(void) {
  qjs_at_mutex_lock(&qjs_at_mutex);
}

void qjs_at_commit(void) {
  qjs_at_mutex_unlock(&qjs_at_mutex);
}

/* Frame text is defined once as "D <id> ..."; pushes reference the id. */
void qjs_at_stack_push(const QjsAtJsFrame *frame) {
  if (qjs_at_stream.out) {
    uint32_t id = qjs_at_intern_frame(frame);
    if (id == UINT32_MAX) {
      return;
    }
    if (id + 1 == qjs_at_intern.frames_count) {
      fprintf(qjs_at_stream.out, "D %u ", id);
      if (frame->is_native) {
        fputs("<native>", qjs_at_stream.out);
      } else {
        qjs_at_print_name(qjs_at_stream.out, frame->func_name[0] ? frame->func_name : "<anonymous>");
        fputc('@', qjs_at_stream.out);
        qjs_at_print_name(qjs_at_stream.out, frame->filename[0] ? frame->filename : "?");
        if (frame->line_num) {
          fprintf(qjs_at_stream.out, ":%u", frame->line_num);
        }
      }
      fputc('\n', qjs_at_stream.out);
    }
    fprintf(qjs_at_stream.out, "+ %u\n", id);
  }
}

void qjs_at_stack_pop(int n) {
  if (qjs_at_stream.out) {
    fprintf(qjs_at_stream.out, "- %d\n", n);
  }
}

/* Must be called between qjs_at_begin/qjs_at_commit (lock held). */
void qjs_at_event(int kind, const void *ptr, const void *ptr2, size_t size,
                  uintptr_t site, int js_depth, uintptr_t fp) {
  uintptr_t native_pcs[QJS_AT_MAX_NATIVE_FRAMES];
  int n_native;

  if (!qjs_at_enabled || !qjs_at_stream.out) {
    return;
  }
  /* The stack of a free carries no information: the event is attributed to
     the allocation's stack when replaying the stream. */
  if (kind == QJS_AT_FREE) {
    n_native = 0;
  } else if (site != 0 && site == qjs_at_stream.native_cache_site &&
             js_depth == qjs_at_stream.native_cache_js_depth &&
             fp == qjs_at_stream.native_cache_fp) {
    n_native = qjs_at_stream.native_cache_count;
    memcpy(native_pcs, qjs_at_stream.native_cache, (size_t)n_native * sizeof(uintptr_t));
  } else {
    n_native = qjs_at_capture_native(native_pcs, QJS_AT_MAX_NATIVE_FRAMES);
    memcpy(qjs_at_stream.native_cache, native_pcs, (size_t)n_native * sizeof(uintptr_t));
    qjs_at_stream.native_cache_site = site;
    qjs_at_stream.native_cache_js_depth = js_depth;
    qjs_at_stream.native_cache_fp = fp;
    qjs_at_stream.native_cache_count = n_native;
  }

  if (kind == QJS_AT_ALLOC) {
    qjs_at_stream.alloc_count++;
    qjs_at_stream.alloc_bytes += size;
    fprintf(qjs_at_stream.out, "A %p %llu native=", ptr, (unsigned long long)size);
    qjs_at_print_native_stack(qjs_at_stream.out, n_native, native_pcs);
    fputc('\n', qjs_at_stream.out);
  } else if (kind == QJS_AT_FREE) {
    qjs_at_stream.free_count++;
    qjs_at_stream.free_bytes += size;
    fprintf(qjs_at_stream.out, "F %p\n", ptr);
  } else if (kind == QJS_AT_REALLOC) {
    qjs_at_stream.realloc_count++;
    fprintf(qjs_at_stream.out, "R %p %p %llu native=", ptr, ptr2, (unsigned long long)size);
    qjs_at_print_native_stack(qjs_at_stream.out, n_native, native_pcs);
    fputc('\n', qjs_at_stream.out);
  }
}

#else  /* !QJS_ALLOC_TRACE */

/* Profiling disabled: no-op stubs so the JNI surface links and inits stay 0. */
int qjs_at_start(const char *path) { return -1; }
void qjs_at_stop(void) {}
void qjs_at_dump_heap(void) {}
void qjs_at_stack_push(const QjsAtJsFrame *frame) {}
void qjs_at_stack_pop(int n) {}
void qjs_at_event(int kind, const void *ptr, const void *ptr2, size_t size,
                  uintptr_t site, int js_depth, uintptr_t fp) {}
void qjs_at_begin(void) {}
void qjs_at_commit(void) {}
#endif
