package com.locket.gold

internal object ClassRegistry {
    private val hookedClasses = HashSet<String>()

    @Synchronized
    fun contains(className: String): Boolean {
        return hookedClasses.contains(className)
    }

    @Synchronized
    fun add(className: String): Boolean {
        return hookedClasses.add(className)
    }
}
