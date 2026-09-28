package com.ma7moud.reality3d.scan

/** Growable float array without boxing. */
internal class FloatList(capacity: Int = 1024) {
    private var data = FloatArray(capacity)
    var size = 0
        private set

    fun add(value: Float) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Float = data[index]

    fun toArray(): FloatArray = data.copyOf(size)
}

/** Growable int array without boxing. */
internal class IntList(capacity: Int = 1024) {
    private var data = IntArray(capacity)
    var size = 0
        private set

    fun add(value: Int) {
        if (size == data.size) data = data.copyOf(data.size * 2)
        data[size++] = value
    }

    operator fun get(index: Int): Int = data[index]

    fun toArray(): IntArray = data.copyOf(size)
}

/** Open-addressing map from non-negative int keys to int values. */
internal class IntIntMap(capacity: Int = 1 shl 16) {
    private var keys = IntArray(capacity) { EMPTY }
    private var values = IntArray(capacity)
    private var count = 0

    inline fun getOrPut(key: Int, create: () -> Int): Int {
        val found = get(key)
        if (found != EMPTY) return found
        val value = create()
        put(key, value)
        return value
    }

    fun get(key: Int): Int {
        var slot = mix(key) and (keys.size - 1)
        while (true) {
            val existing = keys[slot]
            if (existing == EMPTY) return EMPTY
            if (existing == key) return values[slot]
            slot = (slot + 1) and (keys.size - 1)
        }
    }

    fun put(key: Int, value: Int) {
        if ((count + 1) * 2 > keys.size) grow()
        var slot = mix(key) and (keys.size - 1)
        while (keys[slot] != EMPTY && keys[slot] != key) slot = (slot + 1) and (keys.size - 1)
        if (keys[slot] == EMPTY) count++
        keys[slot] = key
        values[slot] = value
    }

    private fun grow() {
        val oldKeys = keys
        val oldValues = values
        keys = IntArray(oldKeys.size * 2) { EMPTY }
        values = IntArray(oldKeys.size * 2)
        count = 0
        for (i in oldKeys.indices) if (oldKeys[i] != EMPTY) put(oldKeys[i], oldValues[i])
    }

    private fun mix(key: Int): Int {
        var h = key * -0x61c88647
        h = h xor (h ushr 16)
        return h
    }

    companion object {
        const val EMPTY = -1
    }
}
