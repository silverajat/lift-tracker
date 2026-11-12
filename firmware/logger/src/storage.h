/*
 * storage.h - recordings stored on the XIAO's 2 MB external QSPI flash.
 *
 * Layout: recordings are written one after another from address 0.
 * Each recording starts on a 256-byte page boundary with a header (64 bytes
 * since version 2, 32 bytes in version 1), followed by packed 14-byte
 * samples. Unwritten flash reads as 0xFF.
 *
 *   [hdr][s0][s1]...[sN-1] pad-to-page [hdr][s0]... 0xFF 0xFF ...
 *
 * NOR flash can only change bits from 1 to 0 without an erase. That lets us
 * leave the header's n_samples field as 0xFFFFFFFF while recording and
 * program the real count when the recording stops. The rep counts in the
 * set label work the same way: 0xFF until the app sends them.
 */
#ifndef STORAGE_H_
#define STORAGE_H_

#include <stdbool.h>
#include <stdint.h>
#include <zephyr/toolchain.h>  /* __packed */
#include <zephyr/sys/util.h>   /* BIT() */

#define REC_MAGIC       0x5446494cu  /* "LIFT" in little-endian bytes */
#define REC_VERSION     2u
#define REC_UNFINISHED  0xffffffffu  /* n_samples before it is written */

/* One IMU sample, 14 bytes. Units chosen so int16 never overflows:
 * accel in milli-g (range +/-8000 at +/-8 g),
 * gyro in tenths of a degree per second (range +/-10000 at +/-1000 dps). */
struct __packed sample {
	uint16_t t_ms;    /* low 16 bits of uptime in ms: spots missed samples */
	int16_t a_mg[3];  /* accel x, y, z */
	int16_t g_ddps[3];/* gyro x, y, z, in 0.1 deg/s */
};

/* What the phone app says this recording is. All 0xFF = no label (an
 * automatic or shell-started recording). Weight is in 0.01 kg, so 6.25 kg
 * is exact and the limit is 655 kg. */
#define LABEL_NONE      0xffu
#define LABEL_WARMUP    BIT(0)  /* flags: warm-up set */
#define LABEL_FROM_APP  BIT(1)  /* flags: started from the phone app */
#define LABEL_CALIB     BIT(2)  /* flags: ROM calibration set (light, full range) */

struct __packed set_label {
	uint32_t unix_time;    /* phone clock at Start (UTC seconds) */
	uint32_t session_id;   /* app session: unix time the workout began */
	uint16_t weight_cg;    /* weight in 0.01 kg */
	uint8_t exercise;      /* app exercise id (see app/README.md) */
	uint8_t day;           /* 0 = upper, 1 = lower */
	uint8_t set_no;        /* 1, 2, 3 ... within the exercise */
	uint8_t flags;         /* LABEL_WARMUP | LABEL_FROM_APP | LABEL_CALIB */
	uint16_t rsvd;
};

/* Rep counts, programmed into the header after the set (4-byte word so the
 * QSPI write is aligned). Each byte is 0xFF until it is written. */
struct __packed set_result {
	uint8_t reps_live;      /* app's live count when Stop was pressed */
	uint8_t reps_confirmed; /* what you confirmed/edited on the phone */
	uint16_t rsvd;
};

/* Recording header, 64 bytes, always at the start of a 256-byte page.
 * Version 1 headers were the first 32 bytes only (no label). */
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
	struct set_label label;   /* version 2+ */
	struct set_result result; /* version 2+, written after the set */
	uint32_t rsvd2[3];
};

#define REC_HEADER_V1_SIZE 32u

struct rec_info {
	uint32_t rec_id;
	uint32_t addr;        /* flash address of the header */
	uint32_t n_samples;
	uint32_t start_uptime_s;
	uint16_t sample_hz;
	uint16_t header_size;
	uint32_t pretrigger;
};

/* Scan the flash, find existing recordings and the next free address. */
int storage_init(void);

/* Start a new recording. Fails if one is already open or flash is full.
 * label may be NULL (no label). */
int storage_begin(uint16_t sample_hz, uint32_t pretrigger,
		  const struct set_label *label);

/* Append one sample to the open recording. Returns -ENOSPC when full. */
int storage_append(const struct sample *s);

/* Flush buffered samples and write the final sample count. */
int storage_end(void);

bool storage_recording(void);
uint32_t storage_count(void);              /* number of recordings */
int storage_get(uint32_t index, struct rec_info *out);

/* Label and rep counts of a recording (all 0xFF for version 1 / unlabelled). */
int storage_get_label(uint32_t index, struct set_label *label,
		      struct set_result *result);

/* Write rep counts into a recording's header. A byte can be written once:
 * pass 0xFF to leave one alone. -EALREADY if it already holds another value. */
int storage_set_reps(uint32_t index, uint8_t reps_live, uint8_t reps_confirmed);
int storage_read_sample(const struct rec_info *rec, uint32_t i,
			struct sample *out);
uint32_t storage_bytes_used(void);
uint32_t storage_bytes_total(void);

/* Erase the whole flash chip. Takes several seconds. */
int storage_erase_all(void);

#endif /* STORAGE_H_ */
