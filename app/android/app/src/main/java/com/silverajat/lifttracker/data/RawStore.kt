package com.silverajat.lifttracker.data

import android.content.Context
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The 25 Hz pod data of every set you save (files/raw/<session>_<exercise>_<set>.bin,
 * 6 little-endian floats per sample: ax ay az in g, gx gy gz in deg/s).
 * With the rep count you confirmed, that's the training data the rep
 * counter learns from. About 25 KB per set.
 */
class RawStore(context: Context) {
    private val dir = File(context.filesDir, "raw").apply { mkdirs() }

    private fun file(sessionId: Long, exerciseId: Int, setNo: Int) = File(dir, "${sessionId}_${exerciseId}_$setNo.bin")

    fun save(sessionId: Long, exerciseId: Int, setNo: Int, data: List<FloatArray>) {
        val bb = ByteBuffer.allocate(data.size * 24).order(ByteOrder.LITTLE_ENDIAN)
        data.forEach { s -> for (c in 0 until 6) bb.putFloat(s[c]) }
        val tmp = File(dir, "${sessionId}_${exerciseId}_$setNo.tmp")
        tmp.writeBytes(bb.array())
        tmp.renameTo(file(sessionId, exerciseId, setNo))
    }

    fun load(sessionId: Long, exerciseId: Int, setNo: Int): List<FloatArray>? {
        val f = file(sessionId, exerciseId, setNo)
        if (!f.exists()) return null
        val bb = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        return List(bb.remaining() / 24) { FloatArray(6) { bb.float } }
    }

    fun delete(sessionId: Long, exerciseId: Int, setNo: Int) {
        file(sessionId, exerciseId, setNo).delete()
    }

    fun deleteSession(sessionId: Long) {
        dir.listFiles { f -> f.name.startsWith("${sessionId}_") }?.forEach { it.delete() }
    }
}
