#pragma once
#include <stddef.h>
#include <stdint.h>
#include "../device.h"
int flash_read(const struct device *d, uint32_t off, void *buf, size_t len);
int flash_write(const struct device *d, uint32_t off, const void *buf, size_t len);
int flash_erase(const struct device *d, uint32_t off, size_t len);
