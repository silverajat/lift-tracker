# Lift pod v2 firmware. Build (from a nRF Connect SDK workspace with hal_st):
#   west build -b liftpod_v2 firmware/pod -- -DBOARD_ROOT=<repo>/firmware/pod
# The recording format (storage.c) is shared with the XIAO prototype's logger.
cmake_minimum_required(VERSION 3.20.0)
find_package(Zephyr REQUIRED HINTS $ENV{ZEPHYR_BASE})
project(lift_pod)

target_sources(app PRIVATE src/main.c src/imu.c src/ble.c ../logger/src/storage.c)
target_include_directories(app PRIVATE src ../logger/src)
