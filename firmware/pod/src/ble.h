/*
 * ble.h - Bluetooth LE link to the phone app (pod v2).
 *
 * Same "Lift Pod" GATT service as the XIAO prototype (firmware/logger/src/ble.h), so the app
 * talks to both; protocol version 2 adds the ops and the Data characteristic marked "v2".
 *
 *   Control  (write)          commands from the app
 *   Status   (read, notify)   struct pod_status, ~1/s while subscribed
 *   Stream   (notify)         live IMU at STREAM_HZ in gym mode: struct stream_pkt
 *   Data     (notify)  v2     a recording sent to the phone after OP_READ: struct data_pkt
 *
 * UUIDs: 4c54000N-7c3e-4f6b-9a1e-5f2b6c0d4e11, N = 1 service, 2 control, 3 status, 4 stream,
 * 5 data. Keep in sync with app/android (PodProtocol.kt). Firmware updates go through the
 * standard MCUmgr SMP service (MCUboot), next to this one.
 */
#ifndef BLE_H_
#define BLE_H_

#include <stdbool.h>
#include <stdint.h>
#include <zephyr/kernel.h>
#include "storage.h"

#define POD_PROTO_VERSION 2
#define POD_HW            2      /* status.hw: 0/1 = XIAO prototype, 2 = pod v2 */
#define STREAM_HZ         25     /* SAMPLE_HZ / 4 */

enum pod_op {
	OP_START   = 0x01,  /* struct op_start: begin a labelled recording (gym mode turns on) */
	OP_STOP    = 0x02,  /* [op][reps_live]: end it (reps 0xFF = unknown) */
	OP_CONFIRM = 0x03,  /* [op][rec_id u16][reps]: rep count you confirmed */
	OP_AUTO    = 0x04,  /* [op][0|1]: accepted, no effect: v2 only records when the app says */
	OP_GYM     = 0x05,  /* v2 [op][0|1]: full-rate sensor + fast link on / back to sleep */
	OP_BUZZ    = 0x06,  /* v2 [op][count]: buzz count times (1 = rest over, 2 = set starting) */
	OP_READ    = 0x07,  /* v2 [op][rec_id u16][from u32]: send recording rec_id on Data */
	OP_ERASE   = 0x08,  /* v2 [op][0xE5]: erase all recordings (after the phone saved them) */
};

struct __packed op_start {
	uint8_t op;            /* OP_START */
	uint8_t exercise;
	uint8_t day;
	uint8_t set_no;
	uint8_t flags;         /* LABEL_WARMUP | LABEL_CALIB */
	uint16_t weight_cg;    /* 0.01 kg */
	uint32_t unix_time;
	uint32_t session_id;
};                             /* 15 bytes */

#define ST_RECORDING  BIT(0)
#define ST_USB        BIT(1)   /* prototype only */
#define ST_CHARGING   BIT(2)
#define ST_FAULT      BIT(3)
#define ST_IMU_OK     BIT(4)
#define ST_AUTO       BIT(5)
#define ST_APP_REC    BIT(6)
#define ST_GYM        BIT(7)   /* v2: gym mode (full-rate sensor) */

struct __packed pod_status {
	uint8_t proto;         /* POD_PROTO_VERSION */
	uint8_t flags;         /* ST_* */
	uint16_t batt_mv;
	uint16_t n_recs;       /* recordings on flash */
	uint16_t last_rec;     /* id of the newest finished recording, 0xFFFF none */
	uint32_t rec_samples;  /* samples in the open recording */
	uint8_t flash_pct;     /* flash used, % */
	uint8_t fw;            /* firmware version */
	uint8_t hw;            /* v2: POD_HW (was a 0 reserved byte on the prototype) */
	uint8_t busy;          /* v2: 1 while erasing or sending a recording */
	uint8_t taps;          /* v2: double taps in gym mode, counting up (wraps at 256): end the set */
	uint32_t uptime_s;     /* v2.1: seconds since the pod last started */
	uint8_t reset_why;     /* v2.1: why it last started, enum pod_reset */
	uint8_t restarts;      /* v2.1: restarts since it was last powered up (saturates at 127) */
	uint8_t fault_kind;    /* v2.2: what ended the previous run: 0 nothing, 1 crash, 2 hang (watchdog) */
	uint8_t fault_thread;  /* v2.2: 1 main, 2 sampler, 3 status/stream, 4 work queue, 5 idle, 9 other */
	uint8_t fault_step;    /* v2.2: where the main loop was */
	uint8_t fault_reason;  /* v2.2: Zephyr's fatal error reason (crash) */
	int8_t fault_prio;     /* v2.2: priority of the thread that was running */
	uint16_t fault_up_s;   /* v2.2: how long that run had lasted */
	uint32_t fault_pc;     /* v2.2: code address it was at */
};                             /* 33 bytes */

enum pod_reset { RST_POWER = 0, RST_PIN = 1, RST_WATCHDOG = 2, RST_SOFT = 3, RST_LOCKUP = 4, RST_OTHER = 5 };

struct __packed stream_pkt {
	uint16_t seq;
	int16_t a_mg[3];
	int16_t g_ddps[3];
};                             /* 14 bytes */

/* Data packets for OP_READ: a header, then samples, then an end marker. */
enum data_kind { DATA_HEADER = 0, DATA_SAMPLES = 1, DATA_END = 2, DATA_ERROR = 3 };

struct __packed data_info {    /* payload of DATA_HEADER */
	uint32_t rec_id;
	uint32_t n_samples;
	uint32_t pretrigger;
	uint32_t start_uptime_s;
	uint16_t sample_hz;
	struct set_label label;
	struct set_result result;
};

struct __packed data_pkt {
	uint16_t rec_id;
	uint8_t kind;          /* enum data_kind */
	uint8_t n;             /* samples in this packet (DATA_SAMPLES) */
	uint32_t index;        /* first sample's index; DATA_END: total sent */
	uint8_t payload[];     /* struct data_info or n x struct sample */
};

struct app_cmd {
	uint8_t op;
	uint8_t reps;
	uint8_t on;
	uint16_t rec_id;
	uint32_t from;
	struct set_label label;
};

enum adv_mode { ADV_OFF, ADV_SLOW, ADV_FAST };

int ble_init(void);
bool ble_connected(void);
bool ble_get_cmd(struct app_cmd *out, k_timeout_t wait);
void ble_stream_sample(const struct sample *s);
void ble_status_changed(void);
void ble_advertise(enum adv_mode m);
void ble_fast_link(bool fast);      /* short connection interval (gym, downloads) or a lazy one */
int ble_send_recording(uint16_t rec_id, uint32_t from);
void pod_wdt_feed(void);   /* main.c: keep the watchdog happy during long work */   /* blocks until sent; runs in the cmd thread */

/* Implemented in main.c */
void pod_fill_status(struct pod_status *st);

#endif /* BLE_H_ */
