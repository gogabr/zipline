#include <stdbool.h>
#include "quickjs.h"

#ifdef __cplusplus
extern "C" {
#endif

bool mimalloc_setup(void);
JSRuntime *JS_NewRuntimeMimalloc(void);

#ifdef __cplusplus
} /* extern "C" { */
#endif
