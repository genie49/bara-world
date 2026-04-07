package com.bara.common.logging

object WideEvent {

    private val holder = ThreadLocal<MutableMap<String, Any?>>()
    private val messageHolder = ThreadLocal<String>()

    fun put(key: String, value: Any?) {
        getOrCreate()[key] = value
    }

    fun message(msg: String) {
        messageHolder.set(msg)
    }

    fun getMessage(): String? = messageHolder.get()

    fun getAll(): Map<String, Any?> = getOrCreate().toMap()

    fun clear() {
        holder.remove()
        messageHolder.remove()
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
