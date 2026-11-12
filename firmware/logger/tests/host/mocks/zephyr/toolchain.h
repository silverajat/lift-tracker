#pragma once
#define __packed __attribute__((packed))
#define __aligned(x) __attribute__((aligned(x)))
#define BUILD_ASSERT(c, m) _Static_assert(c, m)
