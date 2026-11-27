/*
 * ble.c - "Lift Pod" GATT service for pod v2. See ble.h for the protocol.
 *
 * Writes from the phone go into a queue that the command thread (main.c) drains.
 * Status and the live stream go out from a low-priority thread, so the radio never delays
 * sampling. Recordings go out on Data from the command thread, a few notifications in flight
 * at a time, so a long download can't starve the stream or status.
 */
#include "ble.h"

#include <string.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>
#include <zephyr/sys/byteorder.h>

LOG_MODULE_REGISTER(ble, LOG_LEVEL_INF);

#define LT_UUID(n) BT_UUID_128_ENCODE(0x4c540000 + (n), 0x7c3e, 0x4f6b, 0x9a1e, 0x5f2b6c0d4e11)

static const struct bt_uuid_128 svc_uuid = BT_UUID_INIT_128(LT_UUID(1));
static const struct bt_uuid_128 ctrl_uuid = BT_UUID_INIT_128(LT_UUID(2));
static const struct bt_uuid_128 status_uuid = BT_UUID_INIT_128(LT_UUID(3));
static const struct bt_uuid_128 stream_uuid = BT_UUID_INIT_128(LT_UUID(4));
static const struct bt_uuid_128 data_uuid = BT_UUID_INIT_128(LT_UUID(5));

K_MSGQ_DEFINE(cmd_q, sizeof(struct app_cmd), 8, 4);
K_MSGQ_DEFINE(stream_q, sizeof(struct stream_pkt), 16, 2);
K_SEM_DEFINE(status_sem, 0, 1);
K_SEM_DEFINE(data_credits, 4, 4);        /* notifications in flight on Data */

static struct bt_conn *conn;
static struct k_spinlock conn_lock;
static volatile bool status_on, stream_on, data_on;
static uint16_t stream_seq;
static uint32_t stream_div;
static enum adv_mode adv_want = ADV_FAST;

/* ---- Control ------------------------------------------------------------------ */
static ssize_t ctrl_write(struct bt_conn *c, const struct bt_gatt_attr *attr,
			  const void *buf, uint16_t len, uint16_t offset, uint8_t flags)
{
	const uint8_t *p = buf;
	struct app_cmd cmd = { 0 };

	if (offset != 0 || len < 1) {
		return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
	}
	cmd.op = p[0];
	switch (p[0]) {
	case OP_START: {
		struct op_start s;

		if (len < sizeof(s)) {
			return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
		}
		memcpy(&s, p, sizeof(s));
		cmd.label = (struct set_label){
			.unix_time = s.unix_time,
			.session_id = s.session_id,
			.weight_cg = s.weight_cg,
			.exercise = s.exercise,
			.day = s.day,
			.set_no = s.set_no,
			.flags = (s.flags & (LABEL_WARMUP | LABEL_CALIB)) | LABEL_FROM_APP,
			.rsvd = 0xffff,
		};
		break;
	}
	case OP_STOP:
		cmd.reps = len >= 2 ? p[1] : 0xff;
		break;
	case OP_CONFIRM:
		if (len < 4) {
			return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
		}
		cmd.rec_id = sys_get_le16(&p[1]);
		cmd.reps = p[3];
		break;
	case OP_AUTO:
	case OP_GYM:
		if (len < 2) {
			return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
		}
		cmd.on = p[1] != 0;
		break;
	case OP_BUZZ:
		cmd.reps = len >= 2 ? MIN(p[1], 5) : 1;
		break;
	case OP_READ:
		if (len < 7) {
			return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
		}
		cmd.rec_id = sys_get_le16(&p[1]);
		cmd.from = sys_get_le32(&p[3]);
		break;
	case OP_ERASE:
		if (len < 2 || p[1] != 0xE5) {     /* a magic byte, so a stray write can't wipe it */
			return BT_GATT_ERR(BT_ATT_ERR_VALUE_NOT_ALLOWED);
		}
		break;
	default:
		return BT_GATT_ERR(BT_ATT_ERR_NOT_SUPPORTED);
	}
	if (k_msgq_put(&cmd_q, &cmd, K_NO_WAIT) != 0) {
		return BT_GATT_ERR(BT_ATT_ERR_PREPARE_QUEUE_FULL);
	}
	return len;
}

static ssize_t status_read(struct bt_conn *c, const struct bt_gatt_attr *attr,
			   void *buf, uint16_t len, uint16_t offset)
{
	struct pod_status st;

	pod_fill_status(&st);
	return bt_gatt_attr_read(c, attr, buf, len, offset, &st, sizeof(st));
}

static void status_ccc(const struct bt_gatt_attr *attr, uint16_t value)
{
	status_on = value == BT_GATT_CCC_NOTIFY;
	ble_status_changed();
}

static void stream_ccc(const struct bt_gatt_attr *attr, uint16_t value)
{
	stream_on = value == BT_GATT_CCC_NOTIFY;
}

static void data_ccc(const struct bt_gatt_attr *attr, uint16_t value)
{
	data_on = value == BT_GATT_CCC_NOTIFY;
}

/* attrs: 0 service | 1,2 control | 3,4,5 status | 6,7,8 stream | 9,10,11 data */
BT_GATT_SERVICE_DEFINE(pod_svc,
	BT_GATT_PRIMARY_SERVICE(&svc_uuid),
	BT_GATT_CHARACTERISTIC(&ctrl_uuid.uuid,
			       BT_GATT_CHRC_WRITE | BT_GATT_CHRC_WRITE_WITHOUT_RESP,
			       BT_GATT_PERM_WRITE, NULL, ctrl_write, NULL),
	BT_GATT_CHARACTERISTIC(&status_uuid.uuid,
			       BT_GATT_CHRC_READ | BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_READ, status_read, NULL, NULL),
	BT_GATT_CCC(status_ccc, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	BT_GATT_CHARACTERISTIC(&stream_uuid.uuid, BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_NONE, NULL, NULL, NULL),
	BT_GATT_CCC(stream_ccc, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
	BT_GATT_CHARACTERISTIC(&data_uuid.uuid, BT_GATT_CHRC_NOTIFY,
			       BT_GATT_PERM_NONE, NULL, NULL, NULL),
	BT_GATT_CCC(data_ccc, BT_GATT_PERM_READ | BT_GATT_PERM_WRITE),
);

/* ---- Advertising ---------------------------------------------------------------- */
static const struct bt_data ad[] = {
	BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
	BT_DATA_BYTES(BT_DATA_UUID128_ALL, LT_UUID(1)),
};

static const struct bt_data sd[] = {
	BT_DATA(BT_DATA_NAME_COMPLETE, CONFIG_BT_DEVICE_NAME, sizeof(CONFIG_BT_DEVICE_NAME) - 1),
};

/* Slow: one advert a second (~10-20 uA): the app still finds it, it costs almost nothing. */
#define ADV_SLOW_PARAM BT_LE_ADV_PARAM(BT_LE_ADV_OPT_CONN, BT_GAP_ADV_SLOW_INT_MIN, \
				       BT_GAP_ADV_SLOW_INT_MAX, NULL)

static void adv_apply(struct k_work *w)
{
	bt_le_adv_stop();
	if (conn || adv_want == ADV_OFF) {
		return;
	}
	int err = bt_le_adv_start(adv_want == ADV_FAST ? BT_LE_ADV_CONN_FAST_2 : ADV_SLOW_PARAM,
				  ad, ARRAY_SIZE(ad), sd, ARRAY_SIZE(sd));

	if (err && err != -EALREADY) {
		LOG_ERR("advertising failed (%d)", err);
	} else {
		LOG_INF("advertising (%s)", adv_want == ADV_FAST ? "fast" : "slow");
	}
}
K_WORK_DEFINE(adv_work, adv_apply);

void ble_advertise(enum adv_mode m)
{
	if (adv_want != m) {
		adv_want = m;
		k_work_submit(&adv_work);
	}
}

/* ---- Connections ------------------------------------------------------------------ */
static struct bt_conn *conn_get(void)
{
	k_spinlock_key_t key = k_spin_lock(&conn_lock);
	struct bt_conn *c = conn ? bt_conn_ref(conn) : NULL;

	k_spin_unlock(&conn_lock, key);
	return c;
}

static void on_connected(struct bt_conn *c, uint8_t err)
{
	if (err) {
		return;
	}
	k_spinlock_key_t key = k_spin_lock(&conn_lock);

	conn = bt_conn_ref(c);
	k_spin_unlock(&conn_lock, key);
	LOG_INF("app connected");
	ble_status_changed();
}

static void on_disconnected(struct bt_conn *c, uint8_t reason)
{
	LOG_INF("app disconnected (0x%02x)", reason);
	k_spinlock_key_t key = k_spin_lock(&conn_lock);
	struct bt_conn *old = conn;

	conn = NULL;
	status_on = stream_on = data_on = false;
	k_spin_unlock(&conn_lock, key);
	if (old) {
		bt_conn_unref(old);
	}
	/* free any download that was waiting on credits */
	for (int i = 0; i < 4; i++) {
		k_sem_give(&data_credits);
	}
}

static void on_recycled(void)
{
	k_work_submit(&adv_work);
}

BT_CONN_CB_DEFINE(conn_cbs) = {
	.connected = on_connected,
	.disconnected = on_disconnected,
	.recycled = on_recycled,
};

void ble_fast_link(bool fast)
{
	struct bt_conn *c = conn_get();

	if (!c) {
		return;
	}
	/* 15-30 ms for the live stream and downloads; 100-200 ms with latency 4 otherwise. */
	const struct bt_le_conn_param *p = fast ? BT_LE_CONN_PARAM(12, 24, 0, 400)
						: BT_LE_CONN_PARAM(80, 160, 4, 600);

	bt_conn_le_param_update(c, p);
	bt_conn_unref(c);
}

/* ---- Public API ------------------------------------------------------------------- */
bool ble_connected(void)
{
	return conn != NULL;
}

bool ble_get_cmd(struct app_cmd *out, k_timeout_t wait)
{
	return k_msgq_get(&cmd_q, out, wait) == 0;
}

void ble_stream_sample(const struct sample *s)
{
	if (!stream_on || ++stream_div < 4) {
		return;
	}
	stream_div = 0;

	struct stream_pkt p = { .seq = stream_seq++ };

	memcpy(p.a_mg, s->a_mg, sizeof(p.a_mg));
	memcpy(p.g_ddps, s->g_ddps, sizeof(p.g_ddps));
	k_msgq_put(&stream_q, &p, K_NO_WAIT);
}

void ble_status_changed(void)
{
	k_sem_give(&status_sem);
}

/* ---- Recordings to the phone ------------------------------------------------------ */
static void data_sent(struct bt_conn *c, void *user_data)
{
	k_sem_give(&data_credits);
}

static int data_notify(struct bt_conn *c, const void *buf, uint16_t len)
{
	if (k_sem_take(&data_credits, K_SECONDS(5)) != 0 || !data_on) {
		return -ETIMEDOUT;
	}
	struct bt_gatt_notify_params np = {
		.attr = &pod_svc.attrs[10],
		.data = buf,
		.len = len,
		.func = data_sent,
	};
	int err;

	/* the stack can be short of buffers for a moment: retry briefly */
	for (int i = 0; i < 50; i++) {
		err = bt_gatt_notify_cb(c, &np);
		if (err != -ENOMEM) {
			break;
		}
		k_msleep(5);
	}
	if (err) {
		k_sem_give(&data_credits);
	}
	return err;
}

/* storage.c, guarded by main.c's lock */
extern struct k_mutex store_lock;

int ble_send_recording(uint16_t rec_id, uint32_t from)
{
	static uint8_t buf[247];
	struct data_pkt *pk = (struct data_pkt *)buf;
	struct bt_conn *c = conn_get();
	struct rec_info r;
	int err = 0;

	if (!c) {
		return -ENOTCONN;
	}
	uint16_t mtu = bt_gatt_get_mtu(c);
	uint16_t room = MIN((uint16_t)(mtu - 3), sizeof(buf)) - sizeof(struct data_pkt);
	uint8_t per = MAX(1, room / sizeof(struct sample));

	k_mutex_lock(&store_lock, K_FOREVER);
	err = storage_get(rec_id, &r);
	struct data_info info = { 0 };

	if (!err) {
		info = (struct data_info){
			.rec_id = r.rec_id, .n_samples = r.n_samples, .pretrigger = r.pretrigger,
			.start_uptime_s = r.start_uptime_s, .sample_hz = r.sample_hz,
		};
		storage_get_label(rec_id, &info.label, &info.result);
	}
	k_mutex_unlock(&store_lock);

	*pk = (struct data_pkt){ .rec_id = rec_id, .kind = err ? DATA_ERROR : DATA_HEADER, .index = 0 };
	memcpy(pk->payload, &info, sizeof(info));
	err = data_notify(c, buf, sizeof(*pk) + (pk->kind == DATA_HEADER ? sizeof(info) : 0)) ?: err;
	if (err || pk->kind == DATA_ERROR) {
		bt_conn_unref(c);
		return err ? err : -ENOENT;
	}

	uint32_t i = from;

	while (i < r.n_samples) {
		pod_wdt_feed();
		uint8_t n = (uint8_t)MIN(per, r.n_samples - i);

		*pk = (struct data_pkt){ .rec_id = rec_id, .kind = DATA_SAMPLES, .n = n, .index = i };
		k_mutex_lock(&store_lock, K_FOREVER);
		for (uint8_t k = 0; k < n && !err; k++) {
			err = storage_read_sample(&r, i + k, (struct sample *)&pk->payload[k * sizeof(struct sample)]);
		}
		k_mutex_unlock(&store_lock);
		if (err || (err = data_notify(c, buf, sizeof(*pk) + n * sizeof(struct sample))) != 0) {
			break;
		}
		i += n;
	}
	*pk = (struct data_pkt){ .rec_id = rec_id, .kind = err ? DATA_ERROR : DATA_END, .index = i };
	data_notify(c, buf, sizeof(*pk));
	bt_conn_unref(c);
	return err;
}

/* ---- Status and stream thread ------------------------------------------------------ */
static void ble_thread(void)
{
	int64_t next_status = 0;

	while (true) {
		struct stream_pkt p;

		if (!conn) {      /* nothing to send: sleep until something pokes us (a connection does) */
			k_sem_take(&status_sem, K_FOREVER);
			next_status = 0;
			continue;
		}
		bool got = k_msgq_get(&stream_q, &p, K_MSEC(250)) == 0;
		bool poke = k_sem_take(&status_sem, K_NO_WAIT) == 0;
		struct bt_conn *c = conn_get();

		if (!c) {
			continue;
		}
		if (got && stream_on) {
			bt_gatt_notify(c, &pod_svc.attrs[7], &p, sizeof(p));
		}
		if ((poke || k_uptime_get() >= next_status) && status_on) {
			struct pod_status st;

			pod_fill_status(&st);
			bt_gatt_notify(c, &pod_svc.attrs[4], &st, sizeof(st));
			next_status = k_uptime_get() + 1000;
		}
		bt_conn_unref(c);
	}
}

K_THREAD_DEFINE(ble_tx, 1536, ble_thread, NULL, NULL, NULL, K_PRIO_PREEMPT(8), 0, 2000);

int ble_init(void)
{
	int err = bt_enable(NULL);

	if (err) {
		LOG_ERR("bluetooth init failed (%d)", err);
		return err;
	}
	k_work_submit(&adv_work);
	return 0;
}
