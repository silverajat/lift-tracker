#pragma once
#include <stdint.h>
extern int64_t fake_uptime_ms;
static inline int64_t k_uptime_get(void) { return fake_uptime_ms; }
