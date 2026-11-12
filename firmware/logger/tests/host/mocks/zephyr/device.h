#pragma once
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>
struct device { int x; };
#define DT_NODELABEL(x) x
#define DT_PROP(n, p) (2*1024*1024*8)
#define DEVICE_DT_GET(n) (&fake_flash_dev)
extern const struct device fake_flash_dev;
static inline bool device_is_ready(const struct device *d) { (void)d; return true; }
#define MIN(a,b) ((a)<(b)?(a):(b))
#include "toolchain.h"
