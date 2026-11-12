/*
 * ble.h - Bluetooth LE link to the phone app.
 *
 * One GATT service, "Lift Pod", with three characteristics. All values are
 * little-endian. The phone app (app/android) is the only client.
 *
 *   Control  (write)   commands from the app: start/stop a labelled set,
 *                      confirm the rep count, auto-recording on/off
 *   Status   (read, notify ~1/s)  struct pod_status
 *   Stream   (notify)  live IMU at STREAM_HZ while the app listens:
 *                      struct stream_pkt, used for the live rep count
 *
 * UUIDs: 4c54000N-7c3e-4f6b-9a1e-5f2b6c0d4e11, N = 1 service, 2 control,
 * 3 status, 4 stream. Keep them in sync with app/android (PodProtocol.kt).
 */
#ifndef BLE_H_
#define BLE_H_

#include <stdbool.h>
#include <stdint.h>
#include "storage.h"

#define POD_PROTO_VERSION 1
#define STREAM_HZ         25     /* SAMPLE_HZ / 4 */

/* Control opcodes (first byte of every write). */
enum pod_op {
	OP_START   = 0x01,  /* struct op_start: begin a labelled recording */
	OP_STOP    = 0x02,  /* [op][reps_live]: end it (reps 0xFF = unknown) */
	OP_CONFIRM = 0x03,  /* [op][rec_id u16][reps]: rep count you confirmed */
	OP_AUTO    = 0x04,  /* [op][0|1]: motion-triggered recording off/on */
};

struct __packed op_start {
	uint8_t op;            /* OP_START */
	uint8_t exercise;
	uint8_t day;
	uint8_t set_no;
	uint8_t flags;         /* LABEL_WARMUP */
	uint16_t weight_cg;    /* 0.01 kg */
	uint32_t unix_time;
	uint32_t session_id;
};                             /* 15 bytes */

/* Status flags */
#define ST_RECORDING  BIT(0)
#define ST_USB        BIT(1)
#define ST_CHARGING   BIT(2)
#define ST_FAULT      BIT(3)
#define ST_IMU_OK     BIT(4)
#define ST_AUTO       BIT(5)   /* motion-triggered recording is on */
#define ST_APP_REC    BIT(6)   /* the open recording was started by the app */

struct __packed pod_status {
	uint8_t proto;         /* POD_PROTO_VERSION */
	uint8_t flags;         /* ST_* */
	uint16_t batt_mv;
	uint16_t n_recs;       /* recordings on flash */
	uint16_t last_rec;     /* id of the newest finished recording, 0xFFFF none */
	uint32_t rec_samples;  /* samples in the open recording */
	uint8_t flash_pct;     /* flash used, % */
	uint8_t fw;            /* firmware version */
	uint16_t rsvd;
};                             /* 16 bytes */

struct __packed stream_pkt {
	uint16_t seq;          /* +1 per packet: spots dropped packets */
	int16_t a_mg[3];
	int16_t g_ddps[3];
};                             /* 14 bytes */

/* A command from the app, handed to the sampling thread. */
struct app_cmd {
	uint8_t op;
	uint8_t reps;
	uint8_t on;
	uint16_t rec_id;
	struct set_label label;
};

int ble_init(void);
bool ble_connected(void);

/* Non-blocking: true and *out filled if the app sent a command. */
bool ble_get_cmd(struct app_cmd *out);

/* Call for every IMU sample (SAMPLE_HZ). Sends every 4th one if the app is
 * listening. Never blocks. */
void ble_stream_sample(const struct sample *s);

/* Ask for a status notification now (e.g. a recording just started). */
void ble_status_changed(void);

/* Implemented in main.c: current state for the Status characteristic. */
void pod_fill_status(struct pod_status *st);

#endif /* BLE_H_ */
