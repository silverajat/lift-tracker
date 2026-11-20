package com.silverajat.lifttracker.data

import com.silverajat.lifttracker.ble.PodRecInfo
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Full-rate recordings downloaded from pod v2 (100 Hz, accel + gyro), kept on the phone.
 *
 * The folder is laid out exactly like python/tools/dump_logs.py writes it from the prototype over USB:
 * rec_000.csv, rec_001.csv ... (i,t_ms,ax_mg,ay_mg,az_mg,gx_ddps,gy_ddps,gz_ddps) plus recordings.csv
 * listing them with their set labels. So an exported zip drops straight into data/raw/ and the existing
 * Python tools and the training data from the prototype carry on unchanged.
 *
 * Ids here are the phone's own running number (the pod's restart at 0 after every erase); recordings.csv
 * adds two columns at the end: the pod's id and a key that names the set, so nothing is saved twice.
 */
class PodRecStore(filesDir: File) {
    val dir = File(filesDir, "pod_recordings")
    private val index = File(dir, "recordings.csv")

    private fun rows(): List<List<String>> =
        if (!index.exists()) emptyList()
        else index.readLines().drop(1).filter { it.isNotBlank() }.map { it.split(",") }

    @Synchronized fun count(): Int = rows().size

    @Synchronized fun has(key: String): Boolean = rows().any { it.lastOrNull() == key }

    /** Save one recording (does nothing if that set is already here). */
    @Synchronized fun save(info: PodRecInfo, samples: List<ShortArray>) {
        dir.mkdirs()
        val rs = rows()
        if (rs.any { it.lastOrNull() == info.key }) return
        val id = (rs.maxOfOrNull { it[0].toIntOrNull() ?: -1 } ?: -1) + 1
        val f = File(dir, "rec_%03d.csv".format(id))
        val tmp = File(dir, f.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            w.write(SAMPLE_HEADER); w.write("\n")
            samples.forEachIndexed { i, s ->
                w.write("$i,${s[0].toInt() and 0xffff},${s[1]},${s[2]},${s[3]},${s[4]},${s[5]},${s[6]}\n")
            }
        }
        if (!tmp.renameTo(f)) { tmp.delete(); error("couldn't save ${f.name}") }
        if (!index.exists()) index.writeText(INDEX_HEADER + "\n")
        index.appendText(line(id, info, samples.size) + "\n")
    }

    /** Everything as one zip (a dump_logs folder). */
    @Synchronized fun zip(out: OutputStream) {
        ZipOutputStream(out).use { z ->
            dir.listFiles().orEmpty().filter { it.isFile && !it.name.endsWith(".tmp") }.sortedBy { it.name }.forEach { f ->
                z.putNextEntry(ZipEntry("pod_recordings/${f.name}"))
                f.inputStream().use { it.copyTo(z) }
                z.closeEntry()
            }
        }
    }

    companion object {
        const val SAMPLE_HEADER = "i,t_ms,ax_mg,ay_mg,az_mg,gx_ddps,gy_ddps,gz_ddps"
        const val INDEX_HEADER = "id,start_s,seconds,samples,pretrigger,exercise,day,set,warmup," +
            "weight_kg,unix_time,session,reps_live,reps_confirmed,calibration,pod_rec,key"

        /** One recordings.csv row, in the firmware shell's "rec list" format (label columns blank if unlabelled). */
        fun line(id: Int, r: PodRecInfo, n: Int): String {
            val hz = if (r.sampleHz > 0) r.sampleHz else 100
            val secs = "%d.%02d".format(n / hz, (n % hz) * 100 / hz)
            val label = if (r.exercise == null) ",,,,,,,,,"
            else listOf(
                r.exercise, r.day ?: "", r.setNo ?: "", if (r.warmup) 1 else 0,
                "%.2f".format(java.util.Locale.ROOT, r.weightKg ?: 0.0), r.unixTime, r.sessionId,
                r.repsLive ?: "", r.repsConfirmed ?: "", if (r.calibration) 1 else 0,
            ).joinToString(",")
            return "$id,${r.startUptimeS},$secs,$n,${r.pretrigger},$label,${r.recId},${r.key}"
        }
    }
}
