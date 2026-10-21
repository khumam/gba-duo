#ifndef FAKE_BUS_H
#define FAKE_BUS_H

#include <stdint.h>

extern int fake_platform;
/** Bytes that reached gbaduo_bus_write8 since fake_reset. */
extern unsigned fake_writes;

void fake_reset(int platform);
uint8_t fake_peek(uint32_t address);

#endif
