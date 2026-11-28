/*
 * Lift pod v2 firmware (hardware/pod-v2): the XIAO logger's recording format and Bluetooth
 * protocol, on the custom board, with power management.
 *
 * Asleep (most of the time)
 *   Sensor in low-power wake mode (gyro off, accel at 15 Hz), flash in deep power-down,
 *   CPU idle. Bluetooth advertises once a second so the app can always find it; after
 *   ADV_IDLE_MIN minutes with no movement and no app, it stops advertising too, and the
 *   next movement (picking it up, strapping it on) brings the adverts back.
 * Gym mode (the app sends OP_GYM 1 when a workout starts, or OP_START)
 *   Sensor at full rate, sampled at 100 Hz, live stream to the app, recordings on flash,
 *   fast Bluetooth link. Ends on OP_GYM 0 (workout finished), or GYM_LOST_MIN minutes after
 *   the app went away, or GYM_IDLE_MIN minutes without a set.
 * Always
 *   OP_BUZZ buzzes the motor; OP_READ sends a recording to the phone (no USB on this board);
 *   OP_ERASE clears the flash once the phone has everything. Firmware updates over Bluetooth
 *   (MCUboot + MCUmgr SMP), confirmed once this image has started cleanly.
 *
 * LED: one blink on connect and when gym mode starts, a short blink every 2 s while recording,
 * otherwise off. (The red LED is the charger's own.)
 */
#include <zephyr/device.h>
#include <zephyr/dfu/mcuboot.h>
#include <zephyr/drivers/adc.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/pm/device.h>
#include <zephyr/sys/util.h>

#include "ble.h"
#include "imu.h"
#include "storage.h"
#include <zephyr/drivers/watchdog.h>
#include <zephyr/drivers/hwinfo.h>
#include <hal/nrf_power.h>

LOG_MODULE_REGISTER(pod, LOG_LEVEL_INF);

/* Watchdog: if the firmware ever hangs (it's sealed, there's no reset button), the chip restarts
 * itself after WDT_TIMEOUT_MS. Keeps running while the CPU sleeps; paused while a debugger halts it. */
#define WDT_TIMEOUT_MS 60000
static const struct device *const wdt = DEVICE_DT_GET(DT_NODELABEL(wdt0));
static int wdt_ch = -1;

static void wdt_start(void)
{
	struct wdt_timeout_cfg cfg = {
		.window = { .min = 0, .max = WDT_TIMEOUT_MS },
		.flags = WDT_FLAG_RESET_SOC,
	};

	if (!device_is_ready(wdt)) {
		return;
	}
	wdt_ch = wdt_install_timeout(wdt, &cfg);
	if (wdt_ch < 0 || wdt_setup(wdt, WDT_OPT_PAUSE_HALTED_BY_DBG) != 0) {
		LOG_ERR("watchdog not started");
		wdt_ch = -1;
	}
}

void pod_wdt_feed(void)
{
	if (wdt_ch >= 0) {
		wdt_feed(wdt, wdt_ch);
	}
}

#define SAMPLE_HZ       100     /* same as the prototype: the training data carries over */
#define FW_VERSION      3       /* 3 = pod v2 */
#define STOP_STILL_S    12      /* app gone mid-set: close it after this much stillness */
#define MAX_REC_S       300     /* safety cap per recording */
#define ADV_FAST_S      30      /* fast adverts this long after waking, for a quick connect */
#define ADV_IDLE_MIN    20      /* no movement and no app this long: stop advertising */
#define GYM_LOST_MIN    5       /* in gym mode with the app gone this long: back to sleep */
#define GYM_IDLE_MIN    45      /* in gym mode with no set this long (finish forgotten): back to sleep */
#define MOVE_GYRO_DPS   40
#define MOVE_ACCEL_MG   150

/* ---- Hardware ------------------------------------------------------------------- */
static const struct gpio_dt_spec led = GPIO_DT_SPEC_GET(DT_ALIAS(led0), gpios);
static const struct gpio_dt_spec motor = GPIO_DT_SPEC_GET(DT_PATH(zephyr_user), motor_gpios);
static const struct gpio_dt_spec chg = GPIO_DT_SPEC_GET(DT_PATH(zephyr_user), chg_gpios);
static const struct adc_dt_spec batt_adc = ADC_DT_SPEC_GET_BY_IDX(DT_PATH(zephyr_user), 0);
static const struct device *const flash = DEVICE_DT_GET(DT_ALIAS(rec_flash));

/* ---- State ------------------------------------------------------------------------ */
K_MUTEX_DEFINE(store_lock);              /* every storage_* call (also used by ble.c) */
K_SEM_DEFINE(gym_sem, 0, 1);             /* wakes the sampler when gym mode starts */
K_MSGQ_DEFINE(rec_q, sizeof(struct app_cmd), 4, 4);   /* start/stop, handled by the sampler */

static volatile bool gym, fault, imu_ok, busy;
static volatile int batt_cache_mv;
static volatile uint32_t rec_len;
static volatile bool rec_from_app;

/* ---- Flash power: deep power-down whenever nothing needs it ------------------------- */
static void flash_power(bool on)
{
	if (on) {
		/* Always send the wake-up command, even if the driver thinks the chip is already awake: a chip left
		 * in deep power-down answers every status read with "busy", and the driver would wait forever. */
		pm_device_action_run(flash, PM_DEVICE_ACTION_SUSPEND);
		pm_device_action_run(flash, PM_DEVICE_ACTION_RESUME);
	} else {
		pm_device_action_run(flash, PM_DEVICE_ACTION_SUSPEND);
	}
}

void storage_yield(void)
{
	pod_wdt_feed();
}

/* ---- Recording (sampler thread only) ---------------------------------------------- */
static int start_recording(const struct set_label *label)
{
	k_mutex_lock(&store_lock, K_FOREVER);
	int err = storage_begin(SAMPLE_HZ, 0, label);

	k_mutex_unlock(&store_lock);
	if (err) {
		LOG_ERR("could not start recording (%d)", err);
		fault = true;
		return err;
	}
	rec_len = 0;
	rec_from_app = label != NULL;
	ble_status_changed();
	return 0;
}

static void stop_recording(void)
{
	k_mutex_lock(&store_lock, K_FOREVER);
	int err = storage_end();

	k_mutex_unlock(&store_lock);
	fault = fault || err != 0;
	ble_status_changed();
}

static void set_reps(uint32_t id, uint8_t live, uint8_t confirmed)
{
	k_mutex_lock(&store_lock, K_FOREVER);
	storage_set_reps(id, live, confirmed);
	k_mutex_unlock(&store_lock);
}

static bool is_moving(const struct sample *s)
{
	int32_t g2 = 0, a2 = 0;

	for (int i = 0; i < 3; i++) {
		g2 += (int32_t)s->g_ddps[i] * s->g_ddps[i];
		a2 += (int32_t)s->a_mg[i] * s->a_mg[i];
	}
	const int32_t gl = MOVE_GYRO_DPS * 10, lo = 1000 - MOVE_ACCEL_MG, hi = 1000 + MOVE_ACCEL_MG;

	return g2 > gl * gl || a2 < lo * lo || a2 > hi * hi;
}

/* Start/stop from the app, in the sampler so it never races the recording. */
static bool handle_rec_cmds(bool recording)
{
	struct app_cmd c;

	while (k_msgq_get(&rec_q, &c, K_NO_WAIT) == 0) {
		if (c.op == OP_START) {
			if (recording) {
				stop_recording();
				recording = false;
			}
			if (!fault && start_recording(&c.label) == 0) {
				recording = true;
			}
		} else if (c.op == OP_STOP && recording) {
			stop_recording();
			recording = false;
			k_mutex_lock(&store_lock, K_FOREVER);
			uint32_t n = storage_count();

			k_mutex_unlock(&store_lock);
			if (c.reps != 0xff && n > 0) {
				set_reps(n - 1, c.reps, 0xff);
			}
		}
	}
	return recording;
}

K_TIMER_DEFINE(sample_timer, NULL, NULL);

static void sampling_thread(void)
{
	while (true) {
		if (!gym) {
			k_sem_take(&gym_sem, K_FOREVER);
			continue;
		}
		uint32_t still_run = 0;
		bool recording = false;

		k_timer_start(&sample_timer, K_USEC(1000000 / SAMPLE_HZ), K_USEC(1000000 / SAMPLE_HZ));
		while (gym) {
			k_timer_status_sync(&sample_timer);
			struct sample s;

			recording = handle_rec_cmds(recording);
			if (imu_read(&s) != 0) {
				continue;
			}
			still_run = is_moving(&s) ? 0 : still_run + 1;
			ble_stream_sample(&s);
			if (!recording) {
				continue;
			}
			k_mutex_lock(&store_lock, K_FOREVER);
			int err = storage_append(&s);

			k_mutex_unlock(&store_lock);
			rec_len++;
			bool app_lost = !ble_connected() && still_run >= STOP_STILL_S * SAMPLE_HZ;

			if (err || app_lost || rec_len >= MAX_REC_S * SAMPLE_HZ) {
				fault = fault || err != 0;
				stop_recording();
				recording = false;
			}
		}
		k_timer_stop(&sample_timer);
		if (recording) {
			stop_recording();
		}
		k_msgq_purge(&rec_q);
	}
}

K_THREAD_DEFINE(sampler, 2048, sampling_thread, NULL, NULL, NULL, K_PRIO_PREEMPT(2), 0, 0);

/* ---- Battery and charging ------------------------------------------------------------ */
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
	return mv * 2;               /* 1M / 1M divider */
}

static bool charging(void)
{
	return gpio_pin_get_dt(&chg) == 1;   /* active low in the devicetree */
}

/* Why the pod last started, and how many times it has restarted since it was powered up. GPREGRET2 survives
 * every reset except losing power, so bit 7 marks "has been running" and bits 0-6 count the restarts. */
static uint8_t reset_why, restarts;

static void note_reset(void)
{
	uint32_t cause = 0;
	uint32_t reg = nrf_power_gpregret_get(NRF_POWER, 1);

	hwinfo_get_reset_cause(&cause);
	hwinfo_clear_reset_cause();
	if (cause & RESET_WATCHDOG) {
		reset_why = RST_WATCHDOG;
	} else if (cause & RESET_CPU_LOCKUP) {
		reset_why = RST_LOCKUP;
	} else if (cause & RESET_SOFTWARE) {
		reset_why = RST_SOFT;
	} else if (cause & RESET_PIN) {
		reset_why = RST_PIN;
	} else if (!(reg & 0x80)) {
		reset_why = RST_POWER;
	} else {
		reset_why = RST_OTHER;
	}
	restarts = (reg & 0x80) ? MIN((reg & 0x7f) + 1, 0x7f) : 0;
	nrf_power_gpregret_set(NRF_POWER, 1, 0x80 | restarts);
	LOG_INF("started: reason %u (cause 0x%x), restart %u", reset_why, cause, restarts);
}

void pod_fill_status(struct pod_status *st)
{
	k_mutex_lock(&store_lock, K_FOREVER);
	bool rec = storage_recording();
	uint32_t n = storage_count();
	uint32_t used = storage_bytes_used(), total = storage_bytes_total();

	k_mutex_unlock(&store_lock);
	*st = (struct pod_status){
		.proto = POD_PROTO_VERSION,
		.flags = (rec ? ST_RECORDING : 0) | (charging() ? ST_CHARGING : 0) |
			 (fault ? ST_FAULT : 0) | (imu_ok ? ST_IMU_OK : 0) |
			 (rec && rec_from_app ? ST_APP_REC : 0) | (gym ? ST_GYM : 0),
		.batt_mv = (uint16_t)MAX(batt_cache_mv, 0),
		.n_recs = (uint16_t)n,
		.last_rec = n ? (uint16_t)(n - 1) : 0xffff,
		.rec_samples = rec ? rec_len : 0,
		.flash_pct = total ? (uint8_t)(used * 100ull / total) : 0,
		.fw = FW_VERSION,
		.hw = POD_HW,
		.busy = busy,
		.taps = imu_taps(),
		.uptime_s = (uint32_t)(k_uptime_get() / 1000),
		.reset_why = reset_why,
		.restarts = restarts,
	};
}

/* ---- Modes ----------------------------------------------------------------------------- */
static void blink(int ms)
{
	gpio_pin_set_dt(&led, 1);
	k_msleep(ms);
	gpio_pin_set_dt(&led, 0);
}

static void buzz(int count)
{
	for (int i = 0; i < count; i++) {
		gpio_pin_set_dt(&motor, 1);
		k_msleep(180);
		gpio_pin_set_dt(&motor, 0);
		k_msleep(150);
	}
}

static void set_gym(bool on)
{
	if (on == gym) {
		return;
	}
	if (on) {
		flash_power(true);
		if (imu_ok) {
			imu_active(true);
		}
		gym = true;
		k_sem_give(&gym_sem);
		ble_fast_link(true);
		blink(60);
	} else {
		gym = false;                       /* the sampler closes any open recording */
		k_msleep(50);
		/* and don't put the flash to sleep under it while it's still closing one (up to 2 s) */
		for (int i = 0; i < 40; i++) {
			k_mutex_lock(&store_lock, K_FOREVER);
			bool open = storage_recording();

			k_mutex_unlock(&store_lock);
			if (!open) {
				break;
			}
			k_msleep(50);
		}
		if (imu_ok) {
			imu_active(false);
		}
		k_mutex_lock(&store_lock, K_FOREVER);
		flash_power(false);
		k_mutex_unlock(&store_lock);
		ble_fast_link(false);
	}
	LOG_INF("gym mode %s", on ? "on" : "off");
	ble_status_changed();
}

static int64_t last_set;          /* last start/stop/gym-on from the app */

/* Commands that aren't start/stop run here, in the main thread. */
static void handle_cmd(const struct app_cmd *c)
{
	if (c->op == OP_START || c->op == OP_STOP || (c->op == OP_GYM && c->on)) {
		last_set = k_uptime_get();
	}
	switch (c->op) {
	case OP_START:
		set_gym(true);
		k_msgq_put(&rec_q, c, K_NO_WAIT);
		break;
	case OP_STOP:
		k_msgq_put(&rec_q, c, K_NO_WAIT);
		break;
	case OP_CONFIRM:
		flash_power(true);
		set_reps(c->rec_id, 0xff, c->reps);
		if (!gym) {
			flash_power(false);
		}
		break;
	case OP_GYM:
		set_gym(c->on);
		break;
	case OP_BUZZ:
		buzz(c->reps);
		break;
	case OP_READ: {
		busy = true;
		ble_status_changed();
		flash_power(true);
		ble_fast_link(true);
		int err = ble_send_recording(c->rec_id, c->from);

		if (err) {
			LOG_WRN("sending recording %u failed (%d)", c->rec_id, err);
		}
		if (!gym) {
			flash_power(false);
			ble_fast_link(false);
		}
		busy = false;
		break;
	}
	case OP_ERASE:
		if (gym) {
			break;                         /* never while a workout could be recording */
		}
		busy = true;
		ble_status_changed();
		flash_power(true);
		k_mutex_lock(&store_lock, K_FOREVER);
		fault = storage_erase_all() != 0;
		k_mutex_unlock(&store_lock);
		flash_power(false);
		busy = false;
		break;
	default:
		break;                             /* OP_AUTO: no motion-triggered recording on v2 */
	}
	ble_status_changed();
}

int main(void)
{
	note_reset();
	gpio_pin_configure_dt(&led, GPIO_OUTPUT_INACTIVE);
	gpio_pin_configure_dt(&motor, GPIO_OUTPUT_INACTIVE);
	gpio_pin_configure_dt(&chg, GPIO_INPUT);
	adc_channel_setup_dt(&batt_adc);

	k_mutex_lock(&store_lock, K_FOREVER);
	fault = storage_init() != 0;
	k_mutex_unlock(&store_lock);
	flash_power(false);

	imu_ok = imu_init() == 0;
	if (!imu_ok) {
		LOG_ERR("IMU did not start (WHO_AM_I %d)", imu_who_am_i());
	}
	batt_cache_mv = batt_mv();
	int bt = ble_init();

	/* A fresh update is only kept once it has started cleanly; otherwise MCUboot rolls back. */
	bool new_image = !boot_is_img_confirmed();

	if (bt == 0 && imu_ok) {
		boot_write_img_confirmed();
	}
	/* Hello blink and buzz only when you'd expect one: first power-up, or a new firmware. A restart on its own
	 * (the watchdog, a crash) stays silent, so a fault can never turn into buzzing on your wrist; the reason
	 * and the count go to the app instead (Settings). And no buzz on a nearly flat cell, which could dip it
	 * enough to restart again. */
	if (reset_why == RST_POWER || reset_why == RST_PIN || new_image) {
		blink(200);
		if (batt_cache_mv == 0 || batt_cache_mv > 3500) {
			buzz(1);
		}
	}

	int64_t last_motion = k_uptime_get();
	int64_t last_app = k_uptime_get();
	int64_t fast_until = k_uptime_get() + ADV_FAST_S * 1000;
	bool was_connected = false;
	int64_t lazy_at = 0;   /* when to ask for the slow link after the app connects */
	uint8_t seen_taps = imu_taps();

	wdt_start();
	for (uint32_t t = 0;; t++) {
		struct app_cmd c;

		pod_wdt_feed();

		/* one tick a second, sooner when the app sends something */
		if (ble_get_cmd(&c, K_SECONDS(1))) {
			handle_cmd(&c);
			last_app = k_uptime_get();
			continue;
		}
		int64_t now = k_uptime_get();
		bool connected = ble_connected();

		if (connected) {
			last_app = now;
			if (!was_connected) {
				blink(40);
				lazy_at = now + 5000;
			}
		}
		was_connected = connected;
		/* the phone connects on its fastest link (7.5-15 ms), which costs ~1 mA all the time it
		 * stays connected; once it's set up, drop to the slow one unless we're in the gym */
		if (lazy_at && now >= lazy_at) {
			lazy_at = 0;
			if (connected && !gym) {
				ble_fast_link(false);
			}
		}
		imu_poll();
		/* tapped twice: tell the app now (it ends the set) and buzz once so you know it took */
		uint8_t taps = imu_taps();

		if (taps != seen_taps) {
			seen_taps = taps;
			ble_status_changed();
			gpio_pin_set_dt(&motor, 1);
			k_msleep(70);
			gpio_pin_set_dt(&motor, 0);
		}
		if (imu_take_wake() || charging()) {
			if (now - last_motion > ADV_FAST_S * 1000) {
				fast_until = now + ADV_FAST_S * 1000;
			}
			last_motion = now;
		}

		/* advertising: fast just after waking, then once a second, then off when idle */
		if (now < fast_until) {
			ble_advertise(ADV_FAST);
		} else if (now - MAX(last_motion, last_app) < ADV_IDLE_MIN * 60 * 1000LL) {
			ble_advertise(ADV_SLOW);
		} else {
			ble_advertise(ADV_OFF);
		}

		/* gym mode with the app gone: back to sleep */
		if (gym && !connected && now - last_app > GYM_LOST_MIN * 60 * 1000LL) {
			set_gym(false);
		}
		k_mutex_lock(&store_lock, K_FOREVER);
		bool rec = storage_recording();
		k_mutex_unlock(&store_lock);

		/* gym mode but no set for ages (workout never finished in the app): back to sleep */
		if (gym && !rec && now - last_set > GYM_IDLE_MIN * 60 * 1000LL) {
			set_gym(false);
		}

		/* battery: every 10 s in the gym, every minute otherwise */
		if (t % (gym ? 10 : 60) == 0) {
			batt_cache_mv = batt_mv();
		}
		if (rec && t % 2 == 0) {
			blink(15);
		}
		if ((fault || !imu_ok) && t % 5 == 0) {
			blink(15);
			k_msleep(150);
			blink(15);
		}
	}
	return 0;
}
