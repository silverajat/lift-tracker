/*
 * Lift tracker - D1 logger firmware (Seeed XIAO nRF52840 Sense)
 *
 * What it does
 *   - Reads the on-board IMU (LSM6DS3TR-C: accelerometer + gyroscope)
 *     at SAMPLE_HZ, all the time.
 *   - On battery (no USB cable): starts a recording automatically when you
 *     start moving, and stops after STOP_STILL_S seconds of stillness.
 *     So each set becomes its own recording, with no button to press.
 *     The last PRETRIGGER_S seconds before the motion are kept too, so the
 *     first rep is never cut off.
 *   - On USB: never records by itself. A shell on the USB serial port lets
 *     the laptop list, dump (as CSV) and erase recordings, or start and
 *     stop a recording by hand for desk tests.
 *
 * LED
 *   green blink (fast)      recording
 *   green blip every 3 s    on battery, waiting for motion
 *   blue                    on USB, idle
 *   red                     flash full, IMU missing or error (rec status / rec imu)
 *
 * Shell commands (open the serial port at any baud rate, press Enter)
 *   rec status | rec list | rec dump <id> | rec start | rec stop | rec erase | rec imu | rec batt | rec dfu
 */
#include <stdlib.h>
#include <zephyr/device.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/i2c.h>
#include <zephyr/drivers/sensor.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/shell/shell.h>
#include <zephyr/sys/reboot.h>
#include <zephyr/sys/util.h>
#include <hal/nrf_power.h>

#include "storage.h"

LOG_MODULE_REGISTER(logger, LOG_LEVEL_INF);

/* ---- Tunable settings --------------------------------------------------
 * 100 Hz, not 200 Hz: rep motion has almost nothing above ~10 Hz, and at
 * 14 bytes per sample the 2 MB flash holds ~25 min of recording at 100 Hz
 * versus ~12 min at 200 Hz. The IMU itself runs at 208 Hz (prj.conf), so
 * every reading is fresh. */
#define SAMPLE_HZ        100
#define PRETRIGGER_S     2     /* seconds kept from before motion started */
#define START_MOVE_MS    300   /* this much movement starts a recording */
#define STOP_STILL_S     12    /* this much stillness ends it */
#define MAX_REC_S        300   /* safety cap: 5 minutes per recording */

/* A sample counts as "moving" if either is true:
 *   gyro speed above MOVE_GYRO_DPS, or
 *   total acceleration more than MOVE_ACCEL_MG away from 1 g. */
#define MOVE_GYRO_DPS    40
#define MOVE_ACCEL_MG    150

#define PRE_SAMPLES      (PRETRIGGER_S * SAMPLE_HZ)
#define START_SAMPLES    (START_MOVE_MS * SAMPLE_HZ / 1000)
#define STOP_SAMPLES     (STOP_STILL_S * SAMPLE_HZ)
#define MAX_SAMPLES      (MAX_REC_S * SAMPLE_HZ)

/* ---- Hardware ---------------------------------------------------------- */
static const struct device *const imu = DEVICE_DT_GET(DT_NODELABEL(lsm6ds3tr_c));
static const struct device *const imu_bus = DEVICE_DT_GET(DT_NODELABEL(i2c0));
static const struct adc_dt_spec batt_adc = ADC_DT_SPEC_GET_BY_IDX(DT_PATH(zephyr_user), 0);
static const struct device *const gpio0 = DEVICE_DT_GET(DT_NODELABEL(gpio0));
#define VBAT_ENABLE_PIN  14    /* P0.14: low = battery divider on */
#define CHG_PIN          17    /* P0.17: BQ25101 ~CHG, low while charging */
static const struct gpio_dt_spec led_red = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static const struct gpio_dt_spec led_green = GPIO_DT_SPEC_GET(DT_ALIAS(led1), gpios);
static const struct gpio_dt_spec led_blue = GPIO_DT_SPEC_GET(DT_ALIAS(led2), gpios);

/* ---- Shared state (sampling thread <-> shell <-> LED loop) ------------- */
K_MUTEX_DEFINE(store_lock);          /* guards every storage_* call */

enum rec_mode { REC_AUTO, REC_MANUAL };

static volatile bool usb_power;       /* USB cable plugged in (VBUS present) */
static volatile bool fault;           /* flash full or write error */
static volatile bool imu_ok;          /* IMU started (see imu_start) */
static int imu_err = -EAGAIN;         /* last IMU start error */
static int imu_tries;                 /* attempts used to start the IMU */
static int imu_whoami = -1;           /* WHO_AM_I read straight off I2C */
static uint8_t imu_addr;              /* I2C address that answered */
static struct sample last_sample;     /* newest reading, for 'rec imu' */
static volatile bool manual_start_req;
static volatile bool manual_stop_req;
static enum rec_mode mode;
static uint32_t rec_len;              /* samples in the open recording */

/* Pre-trigger ring buffer: always holds the last PRE_SAMPLES samples. */
static struct sample ring[PRE_SAMPLES];
static uint32_t ring_head;            /* next slot to write */
static uint32_t ring_count;

static int16_t clamp16(int32_t v)
{
	return (int16_t)CLAMP(v, INT16_MIN, INT16_MAX);
}

/* Read one sample from the IMU, converted to milli-g and 0.1 deg/s. */
static int read_imu(struct sample *s)
{
	struct sensor_value a[3], g[3];
	int err = sensor_sample_fetch(imu);

	if (!err) {
		err = sensor_channel_get(imu, SENSOR_CHAN_ACCEL_XYZ, a);
	}
	if (!err) {
		err = sensor_channel_get(imu, SENSOR_CHAN_GYRO_XYZ, g);
	}
	if (err) {
		return err;
	}

	s->t_ms = (uint16_t)k_uptime_get_32();
	for (int i = 0; i < 3; i++) {
		s->a_mg[i] = clamp16(sensor_ms2_to_mg(&a[i]));
		/* rad/s -> units of 10 micro-degrees/s -> units of 0.1 deg/s */
		s->g_ddps[i] = clamp16(sensor_rad_to_10udegrees(&g[i]) / 10000);
	}
	return 0;
}

static bool is_moving(const struct sample *s)
{
	int32_t g2 = 0, a2 = 0;

	for (int i = 0; i < 3; i++) {
		g2 += (int32_t)s->g_ddps[i] * s->g_ddps[i];
		a2 += (int32_t)s->a_mg[i] * s->a_mg[i];
	}
	/* Compare squared magnitudes so no square root is needed. */
	const int32_t gyro_lim = MOVE_GYRO_DPS * 10;  /* in 0.1 deg/s */
	const int32_t lo = 1000 - MOVE_ACCEL_MG, hi = 1000 + MOVE_ACCEL_MG;

	return g2 > gyro_lim * gyro_lim || a2 < lo * lo || a2 > hi * hi;
}

static void ring_push(const struct sample *s)
{
	ring[ring_head] = *s;
	ring_head = (ring_head + 1) % PRE_SAMPLES;
	if (ring_count < PRE_SAMPLES) {
		ring_count++;
	}
}

/* Open a recording and copy the pre-trigger buffer into it, oldest first. */
static int start_recording(enum rec_mode m)
{
	uint32_t pre = (m == REC_AUTO) ? ring_count : 0;
	int err;

	k_mutex_lock(&store_lock, K_FOREVER);
	err = storage_begin(SAMPLE_HZ, pre);
	if (!err) {
		uint32_t first = (ring_head + PRE_SAMPLES - ring_count) % PRE_SAMPLES;

		for (uint32_t i = 0; i < pre && !err; i++) {
			err = storage_append(&ring[(first + i) % PRE_SAMPLES]);
		}
	}
	k_mutex_unlock(&store_lock);

	if (err) {
		LOG_ERR("could not start recording (%d)", err);
		fault = true;
		return err;
	}
	mode = m;
	rec_len = pre;
	LOG_INF("recording started (%s)", m == REC_AUTO ? "auto" : "manual");
	return 0;
}

static void stop_recording(void)
{
	k_mutex_lock(&store_lock, K_FOREVER);
	int err = storage_end();
	k_mutex_unlock(&store_lock);

	if (err) {
		fault = true;
	}
}

/* ---- IMU start-up -------------------------------------------------------
 * The IMU's boot-time init is deferred (app.overlay). Here: wait for it to
 * power up, read its ID straight off the I2C bus (so a dead or different
 * chip shows up in 'rec imu'), then start the driver, retrying a few times. */
static void imu_start(void)
{
	k_msleep(100);
	for (imu_tries = 1; imu_tries <= 10; imu_tries++) {
		uint8_t id;

		if (device_is_ready(imu_bus)) {
			if (i2c_reg_read_byte(imu_bus, 0x6A, 0x0F, &id) == 0) {
				imu_whoami = id; imu_addr = 0x6A;
			} else if (i2c_reg_read_byte(imu_bus, 0x6B, 0x0F, &id) == 0) {
				imu_whoami = id; imu_addr = 0x6B;
			}
		}
		imu_err = device_init(imu);
		if (imu_err == 0 || imu_err == -EALREADY) {
			imu_err = device_is_ready(imu) ? 0 : -ENODEV;
		}
		if (imu_err == 0) {
			imu_ok = true;
			LOG_INF("IMU started (try %d, id 0x%02x)", imu_tries, imu_whoami);
			return;
		}
		LOG_WRN("IMU start failed (%d), try %d", imu_err, imu_tries);
		k_msleep(200);
	}
	LOG_ERR("IMU did not start");
}

/* ---- Sampling thread --------------------------------------------------- */
K_TIMER_DEFINE(sample_timer, NULL, NULL);

static void sampling_thread(void)
{
	uint32_t move_score = 0;   /* leaky counter of recent movement */
	uint32_t still_run = 0;    /* consecutive still samples */

	imu_start();
	if (!imu_ok) {
		return;          /* LED goes red; 'rec imu' shows why */
	}

	k_timer_start(&sample_timer, K_USEC(1000000 / SAMPLE_HZ),
		      K_USEC(1000000 / SAMPLE_HZ));

	while (true) {
		k_timer_status_sync(&sample_timer);   /* wait for the next tick */

		struct sample s;

		if (read_imu(&s) != 0) {
			continue;
		}
		last_sample = s;
		bool moving = is_moving(&s);

		move_score = moving ? move_score + 1 : (move_score ? move_score - 1 : 0);
		still_run = moving ? 0 : still_run + 1;

		bool recording;

		k_mutex_lock(&store_lock, K_FOREVER);
		recording = storage_recording();
		k_mutex_unlock(&store_lock);

		if (manual_start_req) {
			manual_start_req = false;
			if (!recording && start_recording(REC_MANUAL) == 0) {
				recording = true;
			}
		}
		if (manual_stop_req) {
			manual_stop_req = false;
			if (recording) {
				stop_recording();
				recording = false;
			}
		}

		if (recording) {
			k_mutex_lock(&store_lock, K_FOREVER);
			int err = storage_append(&s);
			k_mutex_unlock(&store_lock);
			rec_len++;

			bool auto_done = mode == REC_AUTO &&
					 (still_run >= STOP_SAMPLES || usb_power);

			if (err || auto_done || rec_len >= MAX_SAMPLES) {
				if (err == -ENOSPC) {
					LOG_WRN("flash full");
				}
				fault = fault || err != 0;
				stop_recording();
				move_score = 0;
			}
		} else if (!usb_power && !fault && move_score >= START_SAMPLES) {
			if (start_recording(REC_AUTO) == 0) {
				/* The sample that triggered it goes in right after
				 * the pre-trigger buffer. */
				k_mutex_lock(&store_lock, K_FOREVER);
				storage_append(&s);
				k_mutex_unlock(&store_lock);
				rec_len++;
				recording = true;
			}
			move_score = 0;
			still_run = 0;
		}

		/* Keep the pre-trigger buffer full while idle. */
		if (!recording) {
			ring_push(&s);
		} else {
			ring_count = 0;
		}
	}
}

K_THREAD_DEFINE(sampler, 2048, sampling_thread, NULL, NULL, NULL,
		K_PRIO_PREEMPT(2), 0, 1000);

/* ---- Battery ------------------------------------------------------------- */
static void batt_init(void)
{
	gpio_pin_configure(gpio0, VBAT_ENABLE_PIN, GPIO_OUTPUT_LOW);
	gpio_pin_configure(gpio0, CHG_PIN, GPIO_INPUT | GPIO_PULL_UP);
	adc_channel_setup_dt(&batt_adc);
}

/* Battery voltage in mV (BAT pad), or a negative error. */
static int batt_mv(void)
{
	int16_t raw;
	struct adc_sequence seq = { .buffer = &raw, .buffer_size = sizeof(raw) };
	int32_t mv;

	adc_sequence_init_dt(&batt_adc, &seq);
	if (adc_read_dt(&batt_adc, &seq) != 0) {
		return -EIO;
	}
	mv = raw;
	if (adc_raw_to_millivolts_dt(&batt_adc, &mv) != 0) {
		return -EIO;
	}
	return mv * 1510 / 510;      /* undo the 1 M / 510 k divider */
}

static bool charging(void)
{
	return gpio_pin_get(gpio0, CHG_PIN) == 0;
}

/* ---- Shell commands ---------------------------------------------------- */
static int cmd_status(const struct shell *sh, size_t argc, char **argv)
{
	k_mutex_lock(&store_lock, K_FOREVER);
	bool rec = storage_recording();
	uint32_t n = storage_count();
	uint32_t used = storage_bytes_used(), total = storage_bytes_total();
	k_mutex_unlock(&store_lock);

	uint32_t free_s = (total - used) / sizeof(struct sample) / SAMPLE_HZ;

	shell_print(sh, "power:      %s", usb_power ? "USB" : "battery");
	shell_print(sh, "recording:  %s", rec ? "yes" : "no");
	shell_print(sh, "recordings: %u", n);
	shell_print(sh, "flash:      %u / %u KB used (~%u min left)",
		    used / 1024, total / 1024, free_s / 60);
	shell_print(sh, "fault:      %s", fault ? "YES (run 'rec erase' if full)" : "no");
	shell_print(sh, "imu:        %s", imu_ok ? "ok" : "NOT STARTED (run 'rec imu')");
	int mv = batt_mv();
	shell_print(sh, "battery:    %d.%02d V%s", mv / 1000, (mv % 1000) / 10,
		    usb_power ? (charging() ? " (charging)" : " (not charging: full or no battery)") : "");
	return 0;
}

static int cmd_batt(const struct shell *sh, size_t argc, char **argv)
{
	int mv = batt_mv();

	shell_print(sh, "battery:  %d mV  (full ~4200, empty ~3300; 0 or <2500 = not connected / cut off)", mv);
	shell_print(sh, "usb:      %s", usb_power ? "yes" : "no");
	shell_print(sh, "charging: %s", charging() ? "yes" : "no");
	return 0;
}

static int cmd_imu(const struct shell *sh, size_t argc, char **argv)
{
	shell_print(sh, "imu started: %s", imu_ok ? "yes" : "no");
	shell_print(sh, "start error: %d (tries %d)", imu_err, imu_tries);
	if (imu_whoami < 0) {
		shell_print(sh, "who_am_i:    no answer at I2C 0x6A or 0x6B");
	} else {
		shell_print(sh, "who_am_i:    0x%02x at I2C 0x%02x (LSM6DS3TR-C = 0x6a)",
			    imu_whoami, imu_addr);
	}
	if (imu_ok) {
		struct sample s = last_sample;

		shell_print(sh, "accel mg:    %d %d %d", s.a_mg[0], s.a_mg[1], s.a_mg[2]);
		shell_print(sh, "gyro 0.1dps: %d %d %d", s.g_ddps[0], s.g_ddps[1], s.g_ddps[2]);
	}
	return 0;
}

static int cmd_list(const struct shell *sh, size_t argc, char **argv)
{
	k_mutex_lock(&store_lock, K_FOREVER);
	uint32_t n = storage_count();

	shell_print(sh, "id,start_s,seconds,samples,pretrigger");
	for (uint32_t i = 0; i < n; i++) {
		struct rec_info r;

		if (storage_get(i, &r) == 0) {
			uint32_t hz = r.sample_hz ? r.sample_hz : SAMPLE_HZ;

			shell_print(sh, "%u,%u,%u.%02u,%u,%u", r.rec_id, r.start_uptime_s,
				    r.n_samples / hz, (r.n_samples % hz) * 100 / hz,
				    r.n_samples, r.pretrigger);
		}
	}
	k_mutex_unlock(&store_lock);
	return 0;
}

/* Prints one recording as CSV. The laptop script (python/tools/dump_logs.py)
 * reads everything between "#begin" and "#end". */
static int cmd_dump(const struct shell *sh, size_t argc, char **argv)
{
	uint32_t id = strtoul(argv[1], NULL, 10);
	struct rec_info r;

	k_mutex_lock(&store_lock, K_FOREVER);
	if (storage_recording()) {
		k_mutex_unlock(&store_lock);
		shell_error(sh, "stop the recording first (rec stop)");
		return -EBUSY;
	}
	if (storage_get(id, &r) != 0) {
		k_mutex_unlock(&store_lock);
		shell_error(sh, "no recording %u", id);
		return -ENOENT;
	}

	shell_print(sh, "#begin,%u,%u,%u,%u", r.rec_id, r.sample_hz, r.n_samples,
		    r.pretrigger);
	shell_print(sh, "i,t_ms,ax_mg,ay_mg,az_mg,gx_ddps,gy_ddps,gz_ddps");
	for (uint32_t i = 0; i < r.n_samples; i++) {
		struct sample s;

		if (storage_read_sample(&r, i, &s) != 0) {
			break;
		}
		shell_print(sh, "%u,%u,%d,%d,%d,%d,%d,%d", i, s.t_ms,
			    s.a_mg[0], s.a_mg[1], s.a_mg[2],
			    s.g_ddps[0], s.g_ddps[1], s.g_ddps[2]);
	}
	shell_print(sh, "#end,%u", r.rec_id);
	k_mutex_unlock(&store_lock);
	return 0;
}

static int cmd_start(const struct shell *sh, size_t argc, char **argv)
{
	manual_start_req = true;
	shell_print(sh, "starting a manual recording ('rec stop' to end it)");
	return 0;
}

static int cmd_stop(const struct shell *sh, size_t argc, char **argv)
{
	manual_stop_req = true;
	shell_print(sh, "stopping");
	return 0;
}

static int cmd_erase(const struct shell *sh, size_t argc, char **argv)
{
	shell_print(sh, "erasing all recordings (takes up to ~30 s)...");
	k_mutex_lock(&store_lock, K_FOREVER);
	int err = storage_erase_all();
	k_mutex_unlock(&store_lock);

	if (err) {
		shell_error(sh, "erase failed (%d)", err);
		return err;
	}
	fault = false;
	shell_print(sh, "done");
	return 0;
}

/* Reboot into the XIAO's UF2 bootloader (the XIAO-SENSE drive appears),
 * so new firmware can be copied on without double-tapping reset.
 * 0x57 in GPREGRET is the Adafruit nRF52 bootloader's "UF2 mode" magic. */
static int cmd_dfu(const struct shell *sh, size_t argc, char **argv)
{
	k_mutex_lock(&store_lock, K_FOREVER);
	if (storage_recording()) {
		storage_end();
	}
	k_mutex_unlock(&store_lock);
	shell_print(sh, "rebooting into the UF2 bootloader...");
	k_msleep(200);
	nrf_power_gpregret_set(NRF_POWER, 0, 0x57);
	sys_reboot(SYS_REBOOT_WARM);
	return 0;
}

SHELL_STATIC_SUBCMD_SET_CREATE(rec_cmds,
	SHELL_CMD(status, NULL, "Power, flash use and recording state", cmd_status),
	SHELL_CMD(list, NULL, "List recordings", cmd_list),
	SHELL_CMD_ARG(dump, NULL, "Print a recording as CSV: rec dump <id>", cmd_dump, 2, 0),
	SHELL_CMD(start, NULL, "Start a manual recording", cmd_start),
	SHELL_CMD(stop, NULL, "Stop the current recording", cmd_stop),
	SHELL_CMD(erase, NULL, "Erase ALL recordings", cmd_erase),
	SHELL_CMD(batt, NULL, "Battery voltage and charging state", cmd_batt),
	SHELL_CMD(dfu, NULL, "Reboot into the UF2 bootloader for a firmware update", cmd_dfu),
	SHELL_CMD(imu, NULL, "IMU start-up state, chip ID and one live reading", cmd_imu),
	SHELL_SUBCMD_SET_END);
SHELL_CMD_REGISTER(rec, &rec_cmds, "Lift tracker recordings", NULL);

/* ---- main: storage setup, then the LED loop ---------------------------- */
static void leds(bool r, bool g, bool b)
{
	gpio_pin_set_dt(&led_red, r);
	gpio_pin_set_dt(&led_green, g);
	gpio_pin_set_dt(&led_blue, b);
}

int main(void)
{
	gpio_pin_configure_dt(&led_red, GPIO_OUTPUT_INACTIVE);
	gpio_pin_configure_dt(&led_green, GPIO_OUTPUT_INACTIVE);
	gpio_pin_configure_dt(&led_blue, GPIO_OUTPUT_INACTIVE);
	batt_init();

	k_mutex_lock(&store_lock, K_FOREVER);
	if (storage_init() != 0) {
		fault = true;
	}
	k_mutex_unlock(&store_lock);

	for (uint32_t t = 0;; t++) {   /* one step every 100 ms */
		bool rec;

		k_mutex_lock(&store_lock, K_FOREVER);
		rec = storage_recording();
		k_mutex_unlock(&store_lock);
		if (t % 10 == 0) {    /* USB cable check, once a second */
			usb_power = nrf_power_usbregstatus_vbusdet_get(NRF_POWER);
		}

		if (fault || (!imu_ok && imu_tries > 10)) {
			leds(true, false, false);
		} else if (rec) {
			leds(false, t % 5 == 0, false);       /* fast green blink */
		} else if (usb_power) {
			leds(false, false, true);             /* blue: on USB */
		} else {
			leds(false, t % 30 == 0, false);      /* blip every 3 s */
		}
		k_sleep(K_MSEC(100));
	}
	return 0;
}
