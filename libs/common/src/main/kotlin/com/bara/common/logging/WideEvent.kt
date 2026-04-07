package com.bara.common.logging

object WideEvent {

    private val holder = ThreadLocal<MutableMap<String, Any?>>()

    fun put(key: String, value: Any?) {
        getOrCreate()[key] = value
    }

    fun getAll(): Map<String, Any?> = getOrCreate().toMap()

    fun clear() {
        holder.remove()
    }

    private fun getOrCreate(): MutableMap<String, Any?> {
        var map = holder.get()
        if (map == null) {
            map = mutableMapOf()
            holder.set(map)
        }
        return map
    }
}
