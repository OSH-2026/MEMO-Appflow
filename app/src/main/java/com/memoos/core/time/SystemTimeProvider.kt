package com.memoos.core.time

class SystemTimeProvider : TimeProvider {
    override fun now(): Long = System.currentTimeMillis()
}
