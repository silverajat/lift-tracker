#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
struct device { int x; };
#define DT_NODELABEL(x) x
#define DT_ALIAS(x) x
#define DT_NODE_EXISTS(x) 0
#define DT_PROP(n, p) (2*1024*1024*8)
#define DEVICE_DT_GET(n) (&fake_flash_dev)
extern const struct device fake_flash_dev;
static inline bool device_is_ready(const struct device *d) { (void)d; return true; }
#define MIN(a,b) ((a)<(b)?(a):(b))
#ifndef ROUND_UP
#define ROUND_UP(x, a) ((((x) + (a) - 1) / (a)) * (a))
#endif
#ifndef __weak
#define __weak __attribute__((weak))
#endif
#include "toolchain.h"
