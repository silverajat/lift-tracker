/* Host test for storage.c: runs against a simulated 2 MB NOR flash that,
 * like the real chip, can only change bits from 1 to 0 without an erase. */
#include <assert.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include "storage.h"
#include <zephyr/drivers/flash.h>

#define SZ (2*1024*1024)
const struct device fake_flash_dev;
int64_t fake_uptime_ms = 5000;
static uint8_t mem[SZ];
static long writes;
int flash_read(const struct device *d, uint32_t off, void *buf, size_t len) {
  assert(off + len <= SZ); memcpy(buf, mem + off, len); return 0; }
int flash_write(const struct device *d, uint32_t off, const void *buf, size_t len) {
  assert(off % 4 == 0); assert(off + len <= SZ);
  const uint8_t *b = buf;
  for (size_t i = 0; i < len; i++) { mem[off+i] &= b[i]; } /* NOR: 1->0 only */
  writes++; return 0; }
int flash_erase(const struct device *d, uint32_t off, size_t len) {
  memset(mem + off, 0xff, len); return 0; }

static struct sample mk(uint32_t k) {
  struct sample s = { .t_ms = (uint16_t)(k*10) };
  for (int i=0;i<3;i++){ s.a_mg[i]=(int16_t)(k*3+i); s.g_ddps[i]=(int16_t)-(int)(k*7+i); }
  return s; }
static void check(uint32_t idx, uint32_t n, uint32_t base) {
  struct rec_info r; assert(storage_get(idx,&r)==0); assert(r.n_samples==n);
  for (uint32_t i=0;i<n;i++){ struct sample s,e=mk(base+i); assert(storage_read_sample(&r,i,&s)==0);
    if (memcmp(&s,&e,sizeof s)) { printf("MISMATCH rec %u sample %u: got t=%u a0=%d want t=%u a0=%d (addr 0x%x)\n", idx, i, s.t_ms, s.a_mg[0], e.t_ms, e.a_mg[0], r.addr); exit(1);} } }

int main(void) {
  memset(mem, 0x5a, SZ);                 /* factory junk, not erased */
  assert(storage_init()==0);
  assert(storage_begin(100,0,NULL) == -EIO);  /* refuses un-erased flash */
  assert(storage_erase_all()==0);
  assert(storage_init()==0 && storage_count()==0);

  /* rec 0: 37 samples, so the header + samples straddle page boundaries */
  assert(storage_begin(100,5,NULL)==0);
  for (uint32_t i=0;i<37;i++) { struct sample s=mk(i); assert(storage_append(&s)==0); }
  assert(storage_end()==0);
  /* rec 1: 1000 samples, many pages */
  assert(storage_begin(100,0,NULL)==0);
  for (uint32_t i=0;i<1000;i++){ struct sample s=mk(1000+i); assert(storage_append(&s)==0); }
  assert(storage_end()==0);
  check(0,37,0); check(1,1000,1000);

  /* power loss mid-recording: rec 2 never closed */
  assert(storage_begin(100,0,NULL)==0);
  for (uint32_t i=0;i<500;i++){ struct sample s=mk(5000+i); storage_append(&s); }
  /* simulate reboot without storage_end: only full pages reached flash */
  assert(storage_init()==0);
  assert(storage_count()==3);
  struct rec_info r2; storage_get(2,&r2);
  printf("recovered %u of 500 (rest was still in the RAM page buffer)\n", r2.n_samples);
  assert(r2.n_samples <= 500 && r2.n_samples >= 500 - 256/14 - 3);
  check(2, r2.n_samples, 5000);
  /* and a new recording after recovery works and lands after it */
  assert(storage_begin(100,0,NULL)==0);
  for (uint32_t i=0;i<10;i++){ struct sample s=mk(9000+i); assert(storage_append(&s)==0); }
  assert(storage_end()==0);
  assert(storage_init()==0 && storage_count()==4);
  check(0,37,0); check(1,1000,1000); check(3,10,9000);

  /* fill the chip: must stop cleanly with -ENOSPC, no overrun */
  assert(storage_begin(100,0,NULL)==0);
  uint32_t n=0; int err=0;
  while ((err = storage_append(&(struct sample){ .t_ms=1 })) == 0) n++;
  assert(err==-ENOSPC);
  assert(storage_end()==0);
  printf("filled with %u samples (~%u min at 100 Hz)\n", n, n/100/60);
  assert(storage_init()==0); assert(storage_count()==5);
  struct rec_info r4; storage_get(4,&r4); assert(r4.n_samples==n);
  assert(storage_begin(100,0,NULL)==-ENOSPC);
  /* labels + rep counts on a fresh chip */
  assert(storage_erase_all()==0); assert(storage_init()==0);
  struct set_label L = { .unix_time=1794500000u, .session_id=1794499000u, .weight_cg=5500,
                         .exercise=1, .day=0, .set_no=2, .flags=LABEL_FROM_APP, .rsvd=0xffff };
  assert(storage_begin(100,0,&L)==0);
  for (uint32_t i=0;i<300;i++){ struct sample s=mk(i); assert(storage_append(&s)==0); }
  assert(storage_end()==0);
  assert(storage_set_reps(0, 9, 0xff)==0);          /* live count at Stop */
  assert(storage_set_reps(0, 0xff, 10)==0);         /* confirmed later */
  assert(storage_set_reps(0, 0xff, 11)==-EALREADY); /* can't rewrite NOR */
  assert(storage_begin(100,0,NULL)==0);
  for (uint32_t i=0;i<5;i++){ struct sample s=mk(700+i); assert(storage_append(&s)==0); }
  assert(storage_end()==0);
  assert(storage_init()==0 && storage_count()==2);  /* survives a reboot */
  struct set_label l; struct set_result r;
  assert(storage_get_label(0,&l,&r)==0);
  assert(l.weight_cg==5500 && l.exercise==1 && l.set_no==2 && l.unix_time==1794500000u);
  assert(r.reps_live==9 && r.reps_confirmed==10);
  assert(storage_get_label(1,&l,&r)==0 && l.exercise==0xff && r.reps_live==0xff);
  check(0,300,0); check(1,5,700);
  printf("labels OK\n");

  printf("ALL STORAGE TESTS PASSED (%ld flash writes)\n", writes);
  return 0; }
