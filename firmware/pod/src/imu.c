/*
 * imu.c - LSM6DSV16X driver for the pod, on ST's register library (lsm6dsv16x_reg.c).
 * See imu.h.
 */
#include "imu.h"

#include <zephyr/device.h>
#include <zephyr/drivers/gpio.h>
#include <zephyr/drivers/spi.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/util.h>

#include "lsm6dsv16x_reg.h"
#include "ble.h"

LOG_MODULE_REGISTER(imu, LOG_LEVEL_INF);

#define IMU_NODE DT_NODELABEL(imu)

/* ST's IMUs talk SPI mode 3 (they also accept mode 0). */
static const struct spi_dt_spec bus = SPI_DT_SPEC_GET(IMU_NODE,
	SPI_OP_MODE_MASTER | SPI_WORD_SET(8) | SPI_TRANSFER_MSB | SPI_MODE_CPOL | SPI_MODE_CPHA);
static const struct gpio_dt_spec int1 = GPIO_DT_SPEC_GET(DT_PATH(zephyr_user), imu_int_gpios);
static struct gpio_callback int1_cb;
static atomic_t woke;
static atomic_t taps;                /* double taps since boot (wraps at 256 in the status) */
static atomic_t gym_on;

/* ---- SPI glue for ST's library ------------------------------------------------ */
static int32_t plat_write(void *h, uint8_t reg, const uint8_t *buf, uint16_t len)
{
	uint8_t r = reg & 0x7f;
	const struct spi_buf tx[2] = { { .buf = &r, .len = 1 }, { .buf = (void *)buf, .len = len } };
	const struct spi_buf_set txs = { .buffers = tx, .count = 2 };

	ARG_UNUSED(h);
	return spi_write_dt(&bus, &txs);
}

static int32_t plat_read(void *h, uint8_t reg, uint8_t *buf, uint16_t len)
{
	uint8_t r = reg | 0x80;      /* read */
	const struct spi_buf tx[1] = { { .buf = &r, .len = 1 } };
	const struct spi_buf rx[2] = { { .buf = NULL, .len = 1 }, { .buf = buf, .len = len } };
	const struct spi_buf_set txs = { .buffers = tx, .count = 1 };
	const struct spi_buf_set rxs = { .buffers = rx, .count = 2 };

	ARG_UNUSED(h);
	return spi_transceive_dt(&bus, &txs, &rxs);
}

static void plat_delay(uint32_t ms)
{
	k_msleep(ms);
}

static stmdev_ctx_t ctx = {
	.write_reg = plat_write,
	.read_reg = plat_read,
	.mdelay = plat_delay,
};

/* INT1 is latched: read what fired (that clears it) off the interrupt, in the work queue. */
static void int1_work_fn(struct k_work *w)
{
	lsm6dsv16x_all_sources_t src;

	ARG_UNUSED(w);
	if (lsm6dsv16x_all_sources_get(&ctx, &src) != 0) {
		return;
	}
	if (src.double_tap && atomic_get(&gym_on)) {
		atomic_inc(&taps);
		ble_status_changed();        /* the app hears about it now, not on the next tick */
	}
	if (src.wake_up) {
		atomic_set(&woke, 1);
	}
}
static K_WORK_DEFINE(int1_work, int1_work_fn);

static void int1_fired(const struct device *dev, struct gpio_callback *cb, uint32_t pins)
{
	k_work_submit(&int1_work);
}

int imu_who_am_i(void)
{
	uint8_t id;

	if (lsm6dsv16x_device_id_get(&ctx, &id) != 0) {
		return -EIO;
	}
	return id;
}

/* Low-power wake: gyro off, accel low-power at 15 Hz, wake-up event latched on INT1.
 * Threshold 12 x 7.8 mg (~94 mg, high-passed): picking the pod up or strapping it on wakes it,
 * lying on a table doesn't. */
static int wake_mode(void)
{
	int err = 0;
	lsm6dsv16x_act_thresholds_t th = { 0 };
	lsm6dsv16x_act_wkup_time_windows_t win = { .shock = 0, .quiet = 0 };
	lsm6dsv16x_pin_int_route_t route = { 0 };
	lsm6dsv16x_interrupt_mode_t irq = { .enable = 1, .lir = 1 };

	err |= lsm6dsv16x_gy_data_rate_set(&ctx, LSM6DSV16X_ODR_OFF);
	err |= lsm6dsv16x_xl_mode_set(&ctx, LSM6DSV16X_XL_LOW_POWER_2_AVG_MD);
	err |= lsm6dsv16x_xl_data_rate_set(&ctx, LSM6DSV16X_ODR_AT_15Hz);
	th.inactivity_cfg.wu_inact_ths_w = 0;  /* 7.8 mg per step */
	th.threshold = 12;
	th.duration = 0;
	err |= lsm6dsv16x_act_thresholds_set(&ctx, &th);
	err |= lsm6dsv16x_act_wkup_time_windows_set(&ctx, win);
	err |= lsm6dsv16x_interrupt_enable_set(&ctx, irq);
	route.wakeup = 1;
	err |= lsm6dsv16x_pin_int1_route_set(&ctx, &route);
	return err ? -EIO : 0;
}

/* Full rate for the gym: same ranges as the prototype. INT1 = double tap (tap the pod twice to end
 * a set). Tap detection wants a fast accelerometer, so it runs at 480 Hz (sampled at 100 Hz all the same).
 * Threshold TAP_THS x 250 mg (8 g / 32) after the chip's high-pass: a sharp knock on the case, not a rep.
 * Windows at 480 Hz: shock up to 3 x 8 / ODR = 50 ms, quiet 3 x 4 / ODR = 25 ms after each tap,
 * second tap within TAP_GAP x 32 / ODR = ~470 ms. To be tuned on the real board. */
#define TAP_THS  8          /* 2 g */
#define TAP_GAP  7
static int gym_mode(void)
{
	int err = 0;
	lsm6dsv16x_pin_int_route_t route = { 0 };
	lsm6dsv16x_tap_detection_t det = { .tap_x_en = 1, .tap_y_en = 1, .tap_z_en = 1 };
	lsm6dsv16x_tap_thresholds_t ths = { .x = TAP_THS, .y = TAP_THS, .z = TAP_THS };
	lsm6dsv16x_tap_time_windows_t win = { .shock = 3, .quiet = 3, .tap_gap = TAP_GAP };
	lsm6dsv16x_interrupt_mode_t irq = { .enable = 1, .lir = 1 };

	err |= lsm6dsv16x_pin_int1_route_set(&ctx, &route);
	err |= lsm6dsv16x_xl_mode_set(&ctx, LSM6DSV16X_XL_HIGH_PERFORMANCE_MD);
	err |= lsm6dsv16x_gy_mode_set(&ctx, LSM6DSV16X_GY_HIGH_PERFORMANCE_MD);
	err |= lsm6dsv16x_xl_data_rate_set(&ctx, LSM6DSV16X_ODR_AT_480Hz);
	err |= lsm6dsv16x_gy_data_rate_set(&ctx, LSM6DSV16X_ODR_AT_240Hz);
	err |= lsm6dsv16x_tap_detection_set(&ctx, det);
	err |= lsm6dsv16x_tap_thresholds_set(&ctx, ths);
	err |= lsm6dsv16x_tap_axis_priority_set(&ctx, LSM6DSV16X_ZYX);
	err |= lsm6dsv16x_tap_time_windows_set(&ctx, win);
	err |= lsm6dsv16x_tap_mode_set(&ctx, LSM6DSV16X_BOTH_SINGLE_DOUBLE);
	err |= lsm6dsv16x_interrupt_enable_set(&ctx, irq);
	route.double_tap = 1;
	err |= lsm6dsv16x_pin_int1_route_set(&ctx, &route);
	return err ? -EIO : 0;
}

int imu_init(void)
{
	uint8_t id = 0;
	int err = 0;

	if (!spi_is_ready_dt(&bus) || !gpio_is_ready_dt(&int1)) {
		return -ENODEV;
	}
	k_msleep(10);                        /* boot time after power-up */
	if (lsm6dsv16x_device_id_get(&ctx, &id) != 0 || id != LSM6DSV16X_ID) {
		LOG_ERR("no LSM6DSV16X (id 0x%02x)", id);
		return -ENODEV;
	}
	err |= lsm6dsv16x_sw_reset(&ctx);    /* waits for the reset to finish */
	err |= lsm6dsv16x_block_data_update_set(&ctx, 1);
	err |= lsm6dsv16x_xl_full_scale_set(&ctx, LSM6DSV16X_8g);
	err |= lsm6dsv16x_gy_full_scale_set(&ctx, LSM6DSV16X_1000dps);
	if (err) {
		return -EIO;
	}

	gpio_pin_configure_dt(&int1, GPIO_INPUT);
	gpio_init_callback(&int1_cb, int1_fired, BIT(int1.pin));
	gpio_add_callback(int1.port, &int1_cb);
	gpio_pin_interrupt_configure_dt(&int1, GPIO_INT_EDGE_TO_ACTIVE);
	return wake_mode();
}

int imu_active(bool on)
{
	lsm6dsv16x_tap_detection_t off = { 0 };

	atomic_set(&gym_on, on);
	if (!on) {
		lsm6dsv16x_tap_detection_set(&ctx, off);
	}
	return on ? gym_mode() : wake_mode();
}

/* INT1 is latched and the GPIO interrupt is edge-triggered: if an edge is ever missed (an event
 * latched while the pin was being set up or the routing changed), the pin stays high and no new
 * edge comes, so wake-on-motion and taps would stop for good. Checked once a second as a fallback. */
void imu_poll(void)
{
	if (gpio_pin_get_dt(&int1) > 0) {
		k_work_submit(&int1_work);
	}
}

bool imu_take_wake(void)
{
	return atomic_cas(&woke, 1, 0);
}

uint8_t imu_taps(void)
{
	return (uint8_t)atomic_get(&taps);
}

static int16_t clamp16(float v)
{
	return (int16_t)CLAMP(v, INT16_MIN, INT16_MAX);
}

int imu_read(struct sample *s)
{
	int16_t a[3], g[3];

	if (lsm6dsv16x_acceleration_raw_get(&ctx, a) != 0 ||
	    lsm6dsv16x_angular_rate_raw_get(&ctx, g) != 0) {
		return -EIO;
	}
	s->t_ms = (uint16_t)k_uptime_get_32();
	for (int i = 0; i < 3; i++) {
		s->a_mg[i] = clamp16(lsm6dsv16x_from_fs8_to_mg(a[i]));
		s->g_ddps[i] = clamp16(lsm6dsv16x_from_fs1000_to_mdps(g[i]) / 100.0f);
	}
	return 0;
}
