package com.example.bangrecorder

import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Writes 16-bit mono PCM as a standard .wav that any phone or PC can play. */
object WavWriter {
    fun write(file: File, samples: ShortArray, sampleRate: Int) {
        val dataLen = samples.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + dataLen)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)                 // PCM chunk size
            putShort(1)                // format = PCM
            putShort(1)                // mono
            putInt(sampleRate)
            putInt(sampleRate * 2)     // byte rate
            putShort(2)                // block align
            putShort(16)               // bits per sample
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(dataLen)
        }
        val body = ByteBuffer.allocate(dataLen).order(ByteOrder.LITTLE_ENDIAN)
        body.asShortBuffer().put(samples)

        // Write to a temp file first so a half-written clip never shows up in the list.
        val tmp = File(file.parentFile, file.name + ".part")
        FileOutputStream(tmp).use {
            it.write(header.array())
            it.write(body.array())
        }
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }
}
