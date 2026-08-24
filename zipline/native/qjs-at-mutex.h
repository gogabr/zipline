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
#ifndef QJS_AT_MUTEX_H
#define QJS_AT_MUTEX_H

#include <stdint.h>

#if defined(__linux__)

#include <stdatomic.h>
#include <unistd.h>
#include <sys/syscall.h>
#include <linux/futex.h>

/* Futex-backed mutex: the uncontended fast path is a single atomic
   compare-exchange, no syscall. */
typedef struct {
  _Atomic uint32_t state; /* 0 = unlocked, 1 = locked, 2 = locked with waiters */
} QjsAtMutex;

#define QJS_AT_MUTEX_INIT { 0 }

static int qjs_at_futex(uint32_t *uaddr, int futex_op, uint32_t val) {
  return (int)syscall(SYS_futex, uaddr, futex_op, val, NULL, NULL, 0);
}

static inline void qjs_at_mutex_lock(QjsAtMutex *mutex) {
  uint32_t expected = 0;
  if (atomic_compare_exchange_strong(&mutex->state, &expected, 1)) {
    return;
  }
  do {
    if (expected == 2 ||
        atomic_compare_exchange_strong(&mutex->state, &expected, 2)) {
      qjs_at_futex((uint32_t *)&mutex->state, FUTEX_WAIT_PRIVATE, 2);
    }
    expected = 0;
  } while (!atomic_compare_exchange_strong(&mutex->state, &expected, 2));
}

static inline void qjs_at_mutex_unlock(QjsAtMutex *mutex) {
  if (atomic_exchange(&mutex->state, 0) == 2) {
    qjs_at_futex((uint32_t *)&mutex->state, FUTEX_WAKE_PRIVATE, 1);
  }
}

#else  /* !__linux__ */

#include <pthread.h>

typedef struct {
  pthread_mutex_t impl;
} QjsAtMutex;

#define QJS_AT_MUTEX_INIT { PTHREAD_MUTEX_INITIALIZER }

static inline void qjs_at_mutex_lock(QjsAtMutex *mutex) {
  pthread_mutex_lock(&mutex->impl);
}

static inline void qjs_at_mutex_unlock(QjsAtMutex *mutex) {
  pthread_mutex_unlock(&mutex->impl);
}

#endif

#endif
