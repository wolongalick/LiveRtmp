package com.alick.rtmplib

/**
 * @author 崔兴旺
 * @description
 * @date 2022/5/15 11:57
 */
object RtmpManager {


    init {
        System.loadLibrary("rtmplibrary")
    }

    external fun connect(url: String): Boolean

    external fun sendVideo(data: ByteArray,frameLength:Int, l: Long): Boolean

//    external fun setVideoEncInfo(width: Int, height: Int, fps: Int, bitrate: Int)
//    external fun sendNV12(nv12: ByteArray,nv12Length:Int): Boolean
}