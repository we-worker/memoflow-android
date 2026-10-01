package com.memoflow.benchmark

class AAudioPowerProbe {
    init {
        System.loadLibrary("memoflow_firered")
    }

    fun start(): Long = nativeStart()
    fun isMMapUsed(handle: Long): Boolean = nativeIsMMapUsed(handle)
    fun sampleRate(handle: Long): Int = nativeSampleRate(handle)
    fun framesRead(handle: Long): Long = nativeFramesRead(handle)
    fun stop(handle: Long) = nativeStop(handle)

    private external fun nativeStart(): Long
    private external fun nativeIsMMapUsed(handle: Long): Boolean
    private external fun nativeSampleRate(handle: Long): Int
    private external fun nativeFramesRead(handle: Long): Long
    private external fun nativeStop(handle: Long)
}
