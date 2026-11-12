/*
 * ble.c - "Lift Pod" GATT service. See ble.h for the protocol.
 *
 * Writes from the phone land in a queue that the sampling thread drains,
 * so recordings are only ever started and stopped from one thread.
 * Notifications go out from a low-priority thread of their own, so a slow
 * radio link can never delay the 100 Hz sampling.
 */
#include "ble.h"

#include <string.h>
#include <zephyr/bluetooth/bluetooth.h>
#include <zephyr/bluetooth/conn.h>
#include <zephyr/bluetooth/gatt.h>
#include <zephyr/bluetooth/uuid.h>
#include <zephyr/kernel.h>
#include <zephyr/logging/log.h>

LOG_MODULE_REGISTER(ble, LOG_LEVEL_INF);

#define LT_UUID(n) BT_UUID_128_ENCODE(0x4c540000 + (n), 0x7c3e, 0x4f6b, 0x9a1e, 0x5f2b6c0d4e11)

static const struct bt_uuid_128 svc_uuid = BT_UUID_INIT_128(LT_UUID(1));
static const struct bt_uuid_128 ctrl_uuid = BT_UUID_INIT_128(LT_UUID(2));
static const struct bt_uuid_128 status_uuid = BT_UUID_INIT_128(LT_UUID(3));
static const struct bt_uuid_128 stream_uuid = BT_UUID_INIT_128(LT_UUID(4));

K_MSGQ_DEFINE(cmd_q, sizeof(struct app_cmd), 8, 4);
K_MSGQ_DEFINE(stream_q, sizeof(struct stream_pkt), 16, 2);
K_SEM_DEFINE(status_sem, 0, 1);

static struct bt_conn *conn;
static struct k_spinlock conn_lock;   /* conn is set/cleared by the BT host */
static volatile bool status_on, stream_on;
static uint16_t stream_seq;
static uint32_t stream_div;

/* ---- Control: writes from the app ---------------------------------------- */
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
		cmd.rec_id = p[1] | (p[2] << 8);
		cmd.reps = p[3];
		break;
	case OP_AUTO:
		if (len < 2) {
			return BT_GATT_ERR(BT_ATT_ERR_INVALID_ATTRIBUTE_LEN);
		}
		cmd.on = p[1] != 0;
		break;
	default:
		return BT_GATT_ERR(BT_ATT_ERR_NOT_SUPPORTED);
	}
	if (k_msgq_put(&cmd_q, &cmd, K_NO_WAIT) != 0) {
		return BT_GATT_ERR(BT_ATT_ERR_PREPARE_QUEUE_FULL);
	}
	return len;
}

/* ---- Status: read or notify ----------------------------------------------- */
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

/* attrs: 0 service | 1,2 control | 3,4,5 status (+CCC) | 6,7,8 stream (+CCC) */
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
);

/* ---- Advertising and connections ------------------------------------------ */
static const struct bt_data ad[] = {
	BT_DATA_BYTES(BT_DATA_FLAGS, (BT_LE_AD_GENERAL | BT_LE_AD_NO_BREDR)),
	BT_DATA_BYTES(BT_DATA_UUID128_ALL, LT_UUID(1)),
};

static const struct bt_data sd[] = {
	BT_DATA(BT_DATA_NAME_COMPLETE, CONFIG_BT_DEVICE_NAME,
		sizeof(CONFIG_BT_DEVICE_NAME) - 1),
};

static void adv_start(struct k_work *w)
{
	int err = bt_le_adv_start(BT_LE_ADV_CONN_FAST_2, ad, ARRAY_SIZE(ad),
				  sd, ARRAY_SIZE(sd));

	if (err && err != -EALREADY) {
		LOG_ERR("advertising failed (%d)", err);
	}
}
K_WORK_DEFINE(adv_work, adv_start);

static void on_connected(struct bt_conn *c, uint8_t err)
{
	if (err) {
		return;
	}
	k_spinlock_key_t key = k_spin_lock(&conn_lock);

	conn = bt_conn_ref(c);
	k_spin_unlock(&conn_lock, key);
	LOG_INF("app connected");
	/* The app is in charge now: no more motion-triggered recordings
	 * (walking between sets) until it says otherwise or the pod reboots. */
	struct app_cmd cmd = { .op = OP_AUTO, .on = 0 };

	k_msgq_put(&cmd_q, &cmd, K_NO_WAIT);
}

static void on_disconnected(struct bt_conn *c, uint8_t reason)
{
	LOG_INF("app disconnected (0x%02x)", reason);
	k_spinlock_key_t key = k_spin_lock(&conn_lock);
	struct bt_conn *old = conn;

	conn = NULL;
	status_on = stream_on = false;
	k_spin_unlock(&conn_lock, key);
	if (old) {
		bt_conn_unref(old);
	}
}

static void on_recycled(void)
{
	k_work_submit(&adv_work);   /* the connection object is free again */
}

BT_CONN_CB_DEFINE(conn_cbs) = {
	.connected = on_connected,
	.disconnected = on_disconnected,
	.recycled = on_recycled,
};

/* ---- Public API ------------------------------------------------------------ */
bool ble_connected(void)
{
	return conn != NULL;
}

bool ble_get_cmd(struct app_cmd *out)
{
	return k_msgq_get(&cmd_q, out, K_NO_WAIT) == 0;
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
	k_msgq_put(&stream_q, &p, K_NO_WAIT);   /* dropped if the radio is behind */
}

void ble_status_changed(void)
{
	k_sem_give(&status_sem);
}

/* A reference to the current connection, or NULL. Unref when done. */
static struct bt_conn *conn_get(void)
{
	k_spinlock_key_t key = k_spin_lock(&conn_lock);
	struct bt_conn *c = conn ? bt_conn_ref(conn) : NULL;

	k_spin_unlock(&conn_lock, key);
	return c;
}

static void ble_thread(void)
{
	int64_t next_status = 0;

	while (true) {
		struct stream_pkt p;

		/* Wake for a stream packet, a status poke, or every 100 ms. */
		bool got = k_msgq_get(&stream_q, &p, K_MSEC(100)) == 0;
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

K_THREAD_DEFINE(ble_tx, 1536, ble_thread, NULL, NULL, NULL,
		K_PRIO_PREEMPT(8), 0, 2000);

int ble_init(void)
{
	int err = bt_enable(NULL);

	if (err) {
		LOG_ERR("bluetooth init failed (%d)", err);
		return err;
	}
	k_work_submit(&adv_work);
	LOG_INF("advertising as \"%s\"", CONFIG_BT_DEVICE_NAME);
	return 0;
}
