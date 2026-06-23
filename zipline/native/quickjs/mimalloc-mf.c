#include <assert.h>
#include <stdio.h>
#include <stdlib.h>

#ifdef _WIN32
#  include <windows.h>
#  define getpid() 0
#  define pthread_self() NULL
#else /* unix */
#  include <sys/mman.h>
#  ifndef NAP_FIXED_NOREPLACE
#    define MAP_FIXED_NOREPLACE MAP_FIXED
#  endif
#endif

#include "mimalloc.h"

#include "cutils.h"
#include "quickjs.h"

#ifndef JS_BASE_ADDR
#define JS_BASE_ADDR 0x10000000
#endif
#ifndef JS_ARENA_SIZE
#define JS_ARENA_SIZE 0xC0000000
#endif

static inline int is_in_arena(const void *ptr)
{
    uintptr_t addr = (uintptr_t)ptr;
    return addr >= (uintptr_t)JS_BASE_ADDR &&
           addr < (uintptr_t)JS_BASE_ADDR + JS_ARENA_SIZE;
}

static void *mi_malloc_wrap(JSMallocState *s, size_t size)
{
  void *ptr;
  assert(size != 0);

  if (unlikely(s->malloc_size + size > s->malloc_limit))
        return NULL;

  ptr = mi_malloc(size);
  if (!ptr) {
      /* the managed arena may have been exhausted by abandoned pages;
         force a collection to reclaim them and retry once */
      mi_collect(true);
      ptr = mi_malloc(size);
  }
  if (!ptr)
      return NULL;

  /* mimalloc may fall back to OS allocations when the managed arena
     is exhausted, yielding pointers outside JS_BASE_ADDR..JS_BASE_ADDR+JS_ARENA_SIZE.
     Such pointers break the HeapPtr scheme.  Reject them so that the
     engine sees a proper allocation failure instead of a later crash. */
  if (unlikely(!is_in_arena(ptr))) {
      mi_free(ptr);
      return NULL;
  }

  s->malloc_count++;
  s->malloc_size += mi_malloc_usable_size(ptr);
  return ptr;
}

static void mi_free_wrap(JSMallocState *s, void *ptr)
{
    if (!ptr)
        return;

    s->malloc_count--;
    s->malloc_size -= mi_malloc_usable_size(ptr);
    mi_free(ptr);
}

static void *mi_realloc_wrap(JSMallocState *s, void *ptr, size_t size)
{
    size_t old_size;
    void *new_ptr;
    size_t copy_size;

    if (!ptr) {
        if (size == 0)
            return NULL;
        return mi_malloc_wrap(s, size);
    }
    old_size = mi_malloc_usable_size(ptr);
    if (size == 0) {
        s->malloc_count--;
        s->malloc_size -= old_size;
        mi_free(ptr);
        return NULL;
    }
    if (s->malloc_size + size - old_size > s->malloc_limit)
        return NULL;

    /* Use manual alloc+copy+free instead of mi_realloc.
       mi_realloc frees the old block on success, so if the new pointer
       is outside the managed arena we cannot safely reject it: the old
       data would already be lost, and the caller's pointer would dangle. */
    new_ptr = mi_malloc(size);
    if (!new_ptr) {
        mi_collect(true);
        new_ptr = mi_malloc(size);
    }
    if (!new_ptr)
        return NULL;

    if (unlikely(!is_in_arena(new_ptr))) {
        mi_free(new_ptr);
        return NULL;
    }

    copy_size = old_size < size ? old_size : size;
    memcpy(new_ptr, ptr, copy_size);
    mi_free(ptr);

    s->malloc_size += mi_malloc_usable_size(new_ptr) - old_size;
    return new_ptr;
}

/* default memory allocation functions with memory limitation */
const JSMallocFunctions mimalloc_mf = {
    mi_malloc_wrap,
    mi_free_wrap,
    mi_realloc_wrap,
    mi_malloc_usable_size,
};

bool mimalloc_setup()
{
#ifdef _WIN32
    /* NB! Never run, never debugged! */
    if (NULL == VirtualAlloc((void*)JS_BASE_ADDR, JS_ARENA_SIZE, MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE)) {
        fprintf(stderr, "mmap failed\n");
        return false;
    }
#else
    if (MAP_FAILED == mmap((void*)JS_BASE_ADDR, JS_ARENA_SIZE, PROT_READ | PROT_WRITE,
                           MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED_NOREPLACE, -1, 0)) {
        fprintf(stderr, "mmap failed\n");
        return false;
    }
#endif
    if (!mi_manage_os_memory((void*)JS_BASE_ADDR, JS_ARENA_SIZE, false, false, false, -1)) {
        fprintf(stderr, "mi_manage failed\n");
        return false;
    }
    /* prevent mimalloc from using OS allocations when the arena is exhausted */
    mi_option_set(mi_option_limit_os_alloc, 1);

    return true;
}

JSRuntime *JS_NewRuntimeMimalloc(void)
{
    return JS_NewRuntime2(&mimalloc_mf, NULL);
}
