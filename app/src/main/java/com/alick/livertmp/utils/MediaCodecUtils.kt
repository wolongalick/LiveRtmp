package com.alick.livertmp.utils

import java.nio.ByteBuffer

/**
 * @author 崔兴旺
 * @description
 * @date 2022/5/14 15:34
 */
class MediaCodecUtils {
    companion object{
        fun clone(original: ByteBuffer): ByteBuffer {
            val clone = ByteBuffer.allocate(original.remaining())
            original.rewind() //copy from the beginning
            clone.put(original)
            original.rewind()
            clone.flip()
            return clone
        }
    }
}