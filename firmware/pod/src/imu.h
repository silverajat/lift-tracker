/*
 * imu.h - ST LSM6DSV16X on SPI, through ST's register library (hal_st).
 *
 * Two modes:
 *   imu_active(true)   accel +/-8 g and gyro +/-1000 dps, both 240 Hz high-performance.
 *                      Sampled at 100 Hz by the firmware: same rate, ranges and units as the
 *                      XIAO prototype's LSM6DS3TR-C (0.244 mg and 35 mdps per bit on both),
 *                      so recordings and the app's rep counter carry straight over.
 *   imu_active(false)  gyro off, accel in low-power mode at 15 Hz with the wake-up interrupt
 *                      on INT1: a few microamps until the pod is moved.
 */
#ifndef IMU_H_
#define IMU_H_

#include <stdbool.h>
#include <stdint.h>
#include "storage.h"

int imu_init(void);                  /* reset, check the chip, start in low-power wake mode */
int imu_active(bool on);
int imu_read(struct sample *s);      /* milli-g and 0.1 deg/s, like the prototype */
bool imu_take_wake(void);            /* true once after each wake-up interrupt */
int imu_who_am_i(void);              /* 0x70 for the LSM6DSV16X, negative if no answer */

#endif /* IMU_H_ */
