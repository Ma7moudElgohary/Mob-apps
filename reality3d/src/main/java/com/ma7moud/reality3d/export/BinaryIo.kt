package com.ma7moud.reality3d.export

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun floatBytes(values: FloatArray): ByteArray = ByteBuffer.allocate(values.size * 4)
    .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putFloat) }.array()

internal fun intBytes(values: IntArray): ByteArray = ByteBuffer.allocate(values.size * 4)
    .order(ByteOrder.LITTLE_ENDIAN).apply { values.forEach(::putInt) }.array()

internal fun ByteArrayOutputStream.writeAligned(bytes: ByteArray, pad: Byte = 0): Pair<Int, Int> {
    val offset = size()
    write(bytes)
    while (size() % 4 != 0) write(pad.toInt())
    return offset to bytes.size
}

internal fun littleEndianInt(value: Int): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array()
