package com.alick.rtmplib

import androidx.annotation.StringDef

/**
 * @author 崔兴旺
 * @description
 * @date 2022/5/15 11:57
 */
object RtmpManager {


    init {
        System.loadLibrary("rtmplibrary")
    }

    /**
     * NV21转换成I420
     */
    external fun nv21ToI420(src_nv21_data: ByteArray, width: Int, height: Int, dst_i420_data: ByteArray)

    /**
     * I420旋转
     */
    external fun rotateI420(src_i420_data: ByteArray, width: Int, height: Int, dst_i420_data: ByteArray, degree: Int)

    /**
     * I420转换成NV12
     */
    external fun i420ToNv12(src_i420_data: ByteArray, width: Int, height: Int, dst_nv12_data: ByteArray)


    interface OnProcessing {
        @kotlin.annotation.Retention(AnnotationRetention.SOURCE)
        @StringDef(value = [callbackType_nv12ToI420, callbackType_rotateI420, callbackType_i420Mirror, callbackType_i420ToNv12])
        annotation class CallbackTypeAnnotation {

        }

        companion object {
            const val callbackType_nv12ToI420: String = "nv12ToI420"
            const val callbackType_rotateI420: String = "rotateI420"
            const val callbackType_i420Mirror: String = "i420Mirror"
            const val callbackType_i420ToNv12: String = "i420ToNv12"
        }

        fun callback(@CallbackTypeAnnotation callbackType: String, result: ByteArray)

    }

    /**
     * nv12旋转
     */
    external fun nv12Rotate(src_nv12_data: ByteArray, width: Int, height: Int, dst_nv12_rotated_data: ByteArray, degree: Int, mirror: Boolean, onProcessing: OnProcessing? = null)

    external fun connect(url: String): Boolean


    external fun close()

    /**
     * 发送视频数据
     */
    external fun sendVideo(data: ByteArray, l: Long): Boolean

    /**
     * 发送音频数据
     */
    external fun sendAudio(data: ByteArray, l: Long, isHeader: Boolean): Boolean

//    external fun setVideoEncInfo(width: Int, height: Int, fps: Int, bitrate: Int)
//    external fun sendNV12(nv12: ByteArray,nv12Length:Int): Boolean
}