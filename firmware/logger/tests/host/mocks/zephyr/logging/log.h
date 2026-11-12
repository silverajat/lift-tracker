#pragma once
#include <stdio.h>
#define LOG_MODULE_REGISTER(...)
#define LOG_INF(...) (printf("INF: " __VA_ARGS__), printf("\n"))
#define LOG_WRN(...) (printf("WRN: " __VA_ARGS__), printf("\n"))
#define LOG_ERR(...) (printf("ERR: " __VA_ARGS__), printf("\n"))
