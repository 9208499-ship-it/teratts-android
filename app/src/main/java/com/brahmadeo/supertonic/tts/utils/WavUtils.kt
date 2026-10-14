package com.brahmadeo.supertonic.tts.utils

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

object WavUtils {
    /** The 44-byte header of a file whose PCM ([dataSize] bytes) was written after a blank one. */
    fun writeHeader(file: File, dataSize: Long, sampleRate: Int = 44100, channels: Int = 1, bitsPerSample: Int = 16) {
        val size = dataSize.coerceAtMost(0xFFFFFFFFL - 36)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray()).putInt((36 + size).toInt()).put("WAVE".toByteArray())
        header.put("fmt ".toByteArray()).putInt(16).putShort(1.toShort()).putShort(channels.toShort())
            .putInt(sampleRate).putInt(sampleRate * channels * bitsPerSample / 8)
            .putShort((channels * bitsPerSample / 8).toShort()).putShort(bitsPerSample.toShort())
        header.put("data".toByteArray()).putInt(size.toInt())
        java.io.RandomAccessFile(file, "rw").use { it.seek(0); it.write(header.array()) }
    }

    fun saveWav(file: File, pcmData: ByteArray, sampleRate: Int = 44100, channels: Int = 1, bitsPerSample: Int = 16) {
        val dataSize = pcmData.size
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        
        val header = ByteArray(44)
        val buffer = ByteBuffer.wrap(header)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        
        buffer.put("RIFF".toByteArray())
        buffer.putInt(36 + dataSize) // ChunkSize
        buffer.put("WAVE".toByteArray())
        
        buffer.put("fmt ".toByteArray())
        buffer.putInt(16) // Subchunk1Size
        buffer.putShort(1.toShort()) // AudioFormat (PCM)
        buffer.putShort(channels.toShort())
        buffer.putInt(sampleRate)
        buffer.putInt(byteRate)
        buffer.putShort(blockAlign.toShort())
        buffer.putShort(bitsPerSample.toShort())
        
        buffer.put("data".toByteArray())
        buffer.putInt(dataSize)
        
        FileOutputStream(file).use { fos ->
            fos.write(header)
            fos.write(pcmData)
        }
    }
}
