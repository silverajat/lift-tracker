/*
 * storage.h - recordings stored on the XIAO's 2 MB external QSPI flash.
 *
 * Layout: recordings are written one after another from address 0.
 * Each recording starts on a 256-byte page boundary with a 32-byte header,
 * followed by packed 14-byte samples. Unwritten flash reads as 0xFF.
 *
 *   [hdr][s0][s1]...[sN-1] pad-to-page [hdr][s0]... 0xFF 0xFF ...
 *
 * NOR flash can only change bits from 1 to 0 without an erase. That lets us
 * leave the header's n_samples field as 0xFFFFFFFF while recording and
 * program the real count when the recording stops.
 */
#ifndef STORAGE_H_
#define STORAGE_H_

#include <stdbool.h>
#include <stdint.h>
#include <zephyr/toolchain.h>  /* __packed */

#define REC_MAGIC       0x5446494cu  /* "LIFT" in little-endian bytes */
#define REC_VERSION     1u
#define REC_UNFINISHED  0xffffffffu  /* n_samples before it is written */

/* One IMU sample, 14 bytes. Units chosen so int16 never overflows:
 * accel in milli-g (range +/-8000 at +/-8 g),
 * gyro in tenths of a degree per second (range +/-10000 at +/-1000 dps). */
struct __packed sample {
	uint16_t t_ms;    /* low 16 bits of uptime in ms: spots missed samples */
	int16_t a_mg[3];  /* accel x, y, z */
	int16_t g_ddps[3];/* gyro x, y, z, in 0.1 deg/s */
};

/* Recording header, 32 bytes, always at the start of a 256-byte page. */
struct __packed rec_header {
	uint32_t magic;        /* REC_MAGIC */
	uint16_t version;      /* REC_VERSION */
	uint16_t header_size;  /* sizeof(struct rec_header) */
	uint32_t rec_id;       /* 0, 1, 2, ... since the last erase */
	uint16_t sample_hz;    /* nominal sample rate */
	uint16_t sample_size;  /* sizeof(struct sample) */
	uint32_t start_uptime_s; /* seconds since power-on when it started */
	uint32_t pretrigger;   /* samples recorded before motion was detected */
	uint32_t n_samples;    /* REC_UNFINISHED until the recording stops */
	uint32_t reserved;
};

struct rec_info {
	uint32_t rec_id;
	uint32_t addr;        /* flash address of the header */
	uint32_t n_samples;
	uint32_t start_uptime_s;
	uint16_t sample_hz;
	uint32_t pretrigger;
};

/* Scan the flash, find existing recordings and the next free address. */
int storage_init(void);

/* Start a new recording. Fails if one is already open or flash is full. */
int storage_begin(uint16_t sample_hz, uint32_t pretrigger);

/* Append one sample to the open recording. Returns -ENOSPC when full. */
int storage_append(const struct sample *s);

/* Flush buffered samples and write the final sample count. */
int storage_end(void);

bool storage_recording(void);
uint32_t storage_count(void);              /* number of recordings */
int storage_get(uint32_t index, struct rec_info *out);
int storage_read_sample(const struct rec_info *rec, uint32_t i,
			struct sample *out);
uint32_t storage_bytes_used(void);
uint32_t storage_bytes_total(void);

/* Erase the whole flash chip. Takes several seconds. */
int storage_erase_all(void);

#endif /* STORAGE_H_ */
