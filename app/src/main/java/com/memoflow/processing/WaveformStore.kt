package com.memoflow.processing

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.abs

object WaveformStore {
    private const val MAGIC = 0x4D465756
    private const val VERSION = 1

    fun write(file: File, values: List<Float>) {
        file.parentFile?.mkdirs()
        DataOutputStream(FileOutputStream(file)).use { out ->
            out.writeInt(MAGIC)
            out.writeInt(VERSION)
            out.writeInt(values.size)
            values.forEach { out.writeFloat(it.coerceIn(0f, 1f)) }
        }
    }

    fun read(file: File): List<Float> {
        if (!file.exists()) return emptyList()
        return runCatching {
            DataInputStream(FileInputStream(file)).use { input ->
                if (input.readInt() != MAGIC) return@use emptyList()
                if (input.readInt() != VERSION) return@use emptyList()
                val count = input.readInt().coerceIn(0, 100_000)
                List(count) { input.readFloat().coerceIn(0f, 1f) }
            }
        }.getOrDefault(emptyList())
    }
}

class WaveformAccumulator(
    private val bucketSamples: Int = 800,
) {
    private val values = mutableListOf<Float>()
    private var peak = 0
    private var samples = 0
    private var pendingLowByte: Int? = null

    fun accept(pcm: ByteArray) {
        var index = 0

        pendingLowByte?.let { low ->
            if (pcm.isNotEmpty()) {
                acceptSample((low or (pcm[0].toInt() shl 8)).toShort().toInt())
                index = 1
            }
            pendingLowByte = null
        }

        while (index + 1 < pcm.size) {
            val sample =
                ((pcm[index].toInt() and 0xff) or (pcm[index + 1].toInt() shl 8))
                    .toShort()
                    .toInt()
            acceptSample(sample)
            index += 2
        }

        if (index < pcm.size) {
            pendingLowByte = pcm[index].toInt() and 0xff
        }
    }

    fun finish(): List<Float> {
        if (samples > 0) flushBucket()
        return values.toList()
    }

    private fun acceptSample(sample: Int) {
        peak = maxOf(peak, abs(sample))
        samples++
        if (samples >= bucketSamples) flushBucket()
    }

    private fun flushBucket() {
        values += (peak / 32768f).coerceIn(0f, 1f)
        peak = 0
        samples = 0
    }
}
