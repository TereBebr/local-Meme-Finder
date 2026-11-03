package dev.memefinder.core

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal object OrtUtil {
    fun floatTensor(env: OrtEnvironment, data: FloatArray, shape: LongArray): OnnxTensor {
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        val fb = bb.asFloatBuffer()
        fb.put(data)
        fb.rewind()
        return OnnxTensor.createTensor(env, fb, shape)
    }

    fun intTensor(env: OrtEnvironment, data: IntArray, shape: LongArray): OnnxTensor {
        val bb = ByteBuffer.allocateDirect(data.size * 4).order(ByteOrder.nativeOrder())
        val ib = bb.asIntBuffer()
        ib.put(data)
        ib.rewind()
        return OnnxTensor.createTensor(env, ib, shape)
    }

    fun readFloats(t: OnnxTensor): FloatArray {
        val fb = t.floatBuffer
        val out = FloatArray(fb.remaining())
        fb.get(out)
        return out
    }
}
