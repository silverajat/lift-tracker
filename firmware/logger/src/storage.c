/*
 * storage.c - append-only recording log on the external QSPI NOR flash.
 * See storage.h for the on-flash layout.
 */
#include "storage.h"

#include <errno.h>
#include <string.h>
#include <zephyr/device.h>
#include <zephyr/drivers/flash.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>

LOG_MODULE_REGISTER(storage, LOG_LEVEL_INF);

#define FLASH_NODE  DT_NODELABEL(p25q16h)
#define FLASH_SIZE  (DT_PROP(FLASH_NODE, size) / 8)  /* DT size is in bits */
#define PAGE_SIZE   256u
#define MAX_RECS    512u

BUILD_ASSERT(sizeof(struct rec_header) == 32, "header must be 32 bytes");
BUILD_ASSERT(sizeof(struct sample) == 14, "sample must be 14 bytes");
BUILD_ASSERT(offsetof(struct rec_header, n_samples) % 4 == 0,
	     "QSPI writes must be 4-byte aligned");

static const struct device *const flash = DEVICE_DT_GET(FLASH_NODE);

static struct rec_info recs[MAX_RECS];
static uint32_t n_recs;
static uint32_t next_free;        /* page-aligned address for the next header */

/* The open recording, if any. Samples are collected into a page-sized
 * buffer and written one full page at a time. */
static bool open;
static uint32_t write_addr;       /* page-aligned address of page_buf */
static uint32_t open_samples;
static uint8_t __aligned(4) page_buf[PAGE_SIZE];
static uint32_t page_fill;

static uint32_t align_up(uint32_t x, uint32_t a)
{
	return (x + a - 1) / a * a;
}

static bool all_ff(const uint8_t *p, size_t n)
{
	for (size_t i = 0; i < n; i++) {
		if (p[i] != 0xff) {
			return false;
		}
	}
	return true;
}

/* A recording that never got its count written (power was lost mid-set).
 * Samples reach flash only as whole 256-byte pages, so the data ends at the
 * first completely blank page. Count only the samples that fit entirely
 * before it: the last one may have been cut in half by the page boundary. */
static uint32_t recover_count(uint32_t hdr_addr)
{
	static uint8_t __aligned(4) page[PAGE_SIZE];
	uint32_t end = hdr_addr;

	while (end + PAGE_SIZE <= FLASH_SIZE) {
		if (flash_read(flash, end, page, PAGE_SIZE) != 0 ||
		    all_ff(page, PAGE_SIZE)) {
			break;
		}
		/* Stop at the next recording's header too. */
		uint32_t first_word;

		memcpy(&first_word, page, sizeof(first_word));
		if (end != hdr_addr && first_word == REC_MAGIC) {
			break;
		}
		end += PAGE_SIZE;
	}

	uint32_t data_start = hdr_addr + sizeof(struct rec_header);

	return end > data_start ? (end - data_start) / sizeof(struct sample) : 0;
}

int storage_init(void)
{
	if (!device_is_ready(flash)) {
		LOG_ERR("QSPI flash not ready");
		return -ENODEV;
	}

	n_recs = 0;
	next_free = 0;
	open = false;
	page_fill = 0;

	while (next_free + PAGE_SIZE <= FLASH_SIZE && n_recs < MAX_RECS) {
		struct rec_header h;

		if (flash_read(flash, next_free, &h, sizeof(h)) != 0) {
			return -EIO;
		}
		if (h.magic != REC_MAGIC) {
			break;  /* blank flash: this is where the next one goes */
		}

		uint32_t n = h.n_samples;

		if (n == REC_UNFINISHED) {
			n = recover_count(next_free);
			LOG_WRN("rec %u was not closed, recovered %u samples",
				h.rec_id, n);
			/* Write the count now so this repair only happens once. */
			uint32_t __aligned(4) count = n;

			flash_write(flash, next_free + offsetof(struct rec_header, n_samples),
				    &count, sizeof(count));
		}

		recs[n_recs] = (struct rec_info){
			.rec_id = h.rec_id,
			.addr = next_free,
			.n_samples = n,
			.start_uptime_s = h.start_uptime_s,
			.sample_hz = h.sample_hz,
			.pretrigger = h.pretrigger,
		};
		n_recs++;
		next_free = align_up(next_free + sizeof(h) + n * sizeof(struct sample),
				     PAGE_SIZE);
	}

	LOG_INF("%u recordings, %u of %u bytes used", n_recs, next_free,
		(uint32_t)FLASH_SIZE);
	return 0;
}

static int flush_page(void)
{
	if (page_fill == 0) {
		return 0;
	}
	/* The unused tail of the page stays 0xFF, which reads as "blank". */
	memset(page_buf + page_fill, 0xff, PAGE_SIZE - page_fill);
	int err = flash_write(flash, write_addr, page_buf, PAGE_SIZE);

	if (err) {
		LOG_ERR("flash write failed at 0x%06x (%d)", write_addr, err);
	}
	return err;
}

int storage_begin(uint16_t sample_hz, uint32_t pretrigger)
{
	if (open) {
		return -EALREADY;
	}
	if (n_recs >= MAX_RECS || next_free + 2 * PAGE_SIZE > FLASH_SIZE) {
		return -ENOSPC;
	}

	/* Flash can only be written where it has been erased. Check the first
	 * page is blank, so a never-erased chip gives a clear error instead of
	 * silently corrupted recordings. Run "rec erase" once to fix. */
	static uint8_t __aligned(4) probe[PAGE_SIZE];

	if (flash_read(flash, next_free, probe, sizeof(probe)) != 0) {
		return -EIO;
	}
	if (!all_ff(probe, sizeof(probe))) {
		LOG_ERR("flash not erased at 0x%06x: run 'rec erase'", next_free);
		return -EIO;
	}

	struct rec_header h = {
		.magic = REC_MAGIC,
		.version = REC_VERSION,
		.header_size = sizeof(struct rec_header),
		.rec_id = n_recs,
		.sample_hz = sample_hz,
		.sample_size = sizeof(struct sample),
		.start_uptime_s = (uint32_t)(k_uptime_get() / 1000),
		.pretrigger = pretrigger,
		.n_samples = REC_UNFINISHED,
		.reserved = 0xffffffff,
	};

	/* The header goes into the first page buffer with the first samples. */
	memset(page_buf, 0xff, sizeof(page_buf));
	memcpy(page_buf, &h, sizeof(h));
	page_fill = sizeof(h);
	write_addr = next_free;
	open_samples = 0;
	open = true;
	return 0;
}

int storage_append(const struct sample *s)
{
	if (!open) {
		return -EINVAL;
	}

	/* Never let a sample run past the end of the chip. */
	if (write_addr + page_fill + sizeof(*s) > FLASH_SIZE) {
		return -ENOSPC;
	}

	const uint8_t *src = (const uint8_t *)s;
	size_t left = sizeof(*s);

	/* A 14-byte sample may straddle two pages. */
	while (left > 0) {
		size_t room = PAGE_SIZE - page_fill;
		size_t n = MIN(room, left);

		memcpy(page_buf + page_fill, src, n);
		page_fill += n;
		src += n;
		left -= n;

		if (page_fill == PAGE_SIZE) {
			int err = flush_page();

			if (err) {
				return err;
			}
			write_addr += PAGE_SIZE;
			page_fill = 0;
		}
	}
	open_samples++;
	return 0;
}

int storage_end(void)
{
	if (!open) {
		return -EINVAL;
	}

	uint32_t hdr_addr = next_free;
	int err = flush_page();

	/* Program the real sample count into the header (0xFF -> value). */
	uint32_t __aligned(4) count = open_samples;

	if (!err) {
		err = flash_write(flash, hdr_addr + offsetof(struct rec_header, n_samples),
				  &count, sizeof(count));
	}

	recs[n_recs] = (struct rec_info){
		.rec_id = n_recs,
		.addr = hdr_addr,
		.n_samples = open_samples,
		.start_uptime_s = 0,
		.sample_hz = 0,
		.pretrigger = 0,
	};
	/* Re-read the header so the RAM table matches what's on flash. */
	struct rec_header h;

	if (flash_read(flash, hdr_addr, &h, sizeof(h)) == 0) {
		recs[n_recs].start_uptime_s = h.start_uptime_s;
		recs[n_recs].sample_hz = h.sample_hz;
		recs[n_recs].pretrigger = h.pretrigger;
	}
	n_recs++;
	next_free = align_up(hdr_addr + sizeof(struct rec_header) +
			     open_samples * sizeof(struct sample), PAGE_SIZE);
	open = false;
	page_fill = 0;

	LOG_INF("rec %u closed: %u samples", n_recs - 1, open_samples);
	return err;
}

bool storage_recording(void)
{
	return open;
}

uint32_t storage_count(void)
{
	return n_recs;
}

int storage_get(uint32_t index, struct rec_info *out)
{
	if (index >= n_recs) {
		return -ENOENT;
	}
	*out = recs[index];
	return 0;
}

int storage_read_sample(const struct rec_info *rec, uint32_t i,
			struct sample *out)
{
	if (i >= rec->n_samples) {
		return -EINVAL;
	}
	return flash_read(flash, rec->addr + sizeof(struct rec_header) +
			  i * sizeof(struct sample), out, sizeof(*out));
}

uint32_t storage_bytes_used(void)
{
	return open ? write_addr + page_fill : next_free;
}

uint32_t storage_bytes_total(void)
{
	return FLASH_SIZE;
}

int storage_erase_all(void)
{
	if (open) {
		return -EBUSY;
	}
	int err = flash_erase(flash, 0, FLASH_SIZE);

	if (!err) {
		n_recs = 0;
		next_free = 0;
	}
	return err;
}
