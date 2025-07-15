package com.alick.livertmp

import android.app.Activity
import android.app.ProgressDialog
import android.graphics.Bitmap
import android.graphics.Bitmap.CompressFormat
import android.graphics.Rect
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.PixelCopy
import android.view.PixelCopy.SUCCESS
import android.view.View
import androidx.lifecycle.lifecycleScope
import com.alick.livertmp.base.BaseLiveActivity
import com.alick.livertmp.databinding.ActivityScreenShotBinding
import com.alick.livertmp.utils.ImageUtil
import com.alick.livertmp.utils.ThreadPoolManager
import com.alick.livertmp.utils.YUVConverter
import com.alick.rtmplib.RtmpManager
import com.alick.utilslibrary.BLog
import com.alick.utilslibrary.FileUtils
import com.alick.utilslibrary.T
import com.alick.utilslibrary.TimeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingDeque


class ScreenShotActivity : BaseLiveActivity<ActivityScreenShotBinding>() {
    private val TAG = "ScreenShotActivity"

    //是否正在截屏
    private var isScreeningShot = false

    private var queue: LinkedBlockingDeque<Any> = LinkedBlockingDeque(Int.MAX_VALUE)


    private var videoMediaCodec: MediaCodec? = null

    private val FRAME_RATE = 24         //帧率24

    private var startNanoTime = 0L          //开始时间,单位:纳秒

    private val bufferInfo = MediaCodec.BufferInfo()


    private var width = 0
    private var height = 0

    private var mediaMuxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var isMuxerStarted = false
    private var outputMp4Path: String = ""
    private val yuvBuffer: ByteArray by lazy {
        ByteArray(width * height * 3 / 2)
    }

    /**
     * 初始化监听事件
     */
    override fun initListener() {
        binding.btnClear.setOnClickListener {
            binding.drawingView.clearCanvas()
        }

        binding.btnConnectRtmp.setOnClickListener {
            if (rtmpConnectState) {
                rtmpConnectState = false
                updateBtnConnectRtmp()
                T.show("断开rtmp服务器成功")
            } else {
                var progressDialog: ProgressDialog? = ProgressDialog(this)
                progressDialog?.setCancelable(false)
                progressDialog?.setMessage("正在连接rtmp服务器...")
                lifecycleScope.launch {
                    withContext(Dispatchers.Main) {
                        binding.btnConnectRtmp.postDelayed({
                            if (!rtmpConnectState) {
                                progressDialog?.show()
                            }
                        }, 500)
                    }
                    withContext(Dispatchers.IO) {
                        rtmpConnectState = RtmpManager.connect(liveRoomUrl.host)
                    }
                    withContext(Dispatchers.Main) {
                        if (rtmpConnectState) {
                            updateBtnConnectRtmp()
                            T.show("连接rtmp服务器成功")
                        } else {
                            T.show("连接rtmp服务器失败")
                        }
                        progressDialog?.dismiss()
                        progressDialog = null
                    }
                }
            }
        }

        binding.btnScreenShot.setOnClickListener {
            isScreeningShot = !isScreeningShot
            if (isScreeningShot) {
                startScreenShot()
            } else {
                stopScreenShot()
            }
            updateBtnScreenShot()
        }

        ThreadPoolManager.execute {
            while (!isDestroyed && !isFinishing) {
                queue.take()//从阻塞队列中取出元素,只要取出来即可,不用关心取出来的元素值是什么
                val rootView = window.decorView
                rootView.getBitmapFormView(this, callBack = { bitmap ->
                    width = bitmap.width
                    height = bitmap.height
                    YUVConverter.bitmapToNV12(bitmap,yuvBuffer,bitmap.width, bitmap.height)

                    encodeVideo(yuvBuffer, width, height)
                    if (binding.cbSaveFile.isChecked) {
                        saveBitmapToFile(bitmap)
                    }
                })
            }
        }

        binding.btnSaveNV12.setOnClickListener {
            saveNV12()
        }
    }

    private fun saveNV12() {
        val path = "nv12_${width}x${height}_${TimeUtils.getCurrentTime()}.yuv"
        val isSuccess = ImageUtil.saveYUV2File(yuvBuffer, File(getExternalFilesDir("nv12"), path))
        val log = if (isSuccess) {
            "保存NV12成功:${path}"
        } else {
            "保存NV12失败:${path}"
        }
        BLog.i(log)
        T.show(log)
    }

    var h264Data: ByteArray? = null
    private fun encodeVideo(nv12: ByteArray, width: Int, height: Int) {
        initMediaCodec(width, height)
        videoMediaCodec?.let { codec ->
            val inputIndex = codec.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.apply {
                    clear()
                    put(nv12)
                }

//                videoPts = computePresentationTime(generateIndex)
                //输入缓冲区归位
                codec.queueInputBuffer(inputIndex, 0, nv12.size, (System.nanoTime() - startNanoTime) / 1000, 0)
//                generateIndex++
//                BLog.i("generateIndex:${generateIndex}")
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                outputBuffer?.let { byteBuffer ->
                    val remaining = byteBuffer.remaining()
                    if (h264Data == null) {
                        h264Data = ByteArray(remaining)
                    } else if (h264Data!!.size != remaining) {
                        h264Data = ByteArray(remaining)
                    }
                    byteBuffer.get(h264Data!!)

                    if (binding.cbSaveMP4.isChecked) {
                        // 写入 MP4 文件
                        if (isMuxerStarted && bufferInfo.size > 0) {
                            mediaMuxer?.writeSampleData(videoTrackIndex, byteBuffer, bufferInfo)
                        }
                    }

                    if (rtmpConnectState) {
                        RtmpManager.sendVideo(h264Data!!, bufferInfo.presentationTimeUs / 1000)
                    } else {
                        BLog.e("rtmp未连接成功,因此不发送视频数据")
                    }
                }
                //输出缓冲区归位
                codec.releaseOutputBuffer(outputIndex, false)
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val spsByteBuffer = codec.outputFormat.getByteBuffer("csd-0")
                val ppsByteBuffer = codec.outputFormat.getByteBuffer("csd-1")

                val sps = spsByteBuffer?.let {
                    ByteArray(it.remaining()).apply {
                        it.get(this)
                    }
                }
                val pps = ppsByteBuffer?.let {
                    ByteArray(it.remaining()).apply {
                        it.get(this)
                    }
                }

                BLog.i("SPS数据:${FileUtils.byteArray2Hex(sps)}")
                BLog.i("PPS数据:${FileUtils.byteArray2Hex(pps)}")


                val format = codec.outputFormat
                // 初始化 MediaMuxer
                outputMp4Path = "${getExternalFilesDir("mp4")}/output_${System.currentTimeMillis()}.mp4"
                try {
                    mediaMuxer = MediaMuxer(outputMp4Path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                    videoTrackIndex = mediaMuxer!!.addTrack(format)
                    mediaMuxer!!.start()
                    isMuxerStarted = true
                    BLog.i("MediaMuxer 初始化成功，文件路径：$outputMp4Path")
                } catch (e: IOException) {
                    BLog.e("MediaMuxer 初始化失败", Log.getStackTraceString(e))
                }


            }
        }
    }

    fun isH264NALUValid(data: ByteArray): Boolean {
        // 检查起始码：0x00000001 或 0x000001
        if (data.size >= 4 && data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
            data[2] == 0x00.toByte() && data[3] == 0x01.toByte()
        ) {
            return true
        }
        if (data.size >= 3 && data[0] == 0x00.toByte() && data[1] == 0x00.toByte() &&
            data[2] == 0x01.toByte()
        ) {
            return true
        }
        return false
    }

    private fun initMediaCodec(width: Int, height: Int) {
        if (videoMediaCodec == null) {
            try {
                videoMediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .apply {
                        val capabilitiesForType = codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        capabilitiesForType.colorFormats.forEach {
                            BLog.i("colorFormats:${it}")
                        }
                        val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 8)//比特率
                        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)//帧率(fps):24
                        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)//每隔2秒一个I帧
                        configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        start()
                    }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap) {
        val screenShotDir = this@ScreenShotActivity.getExternalFilesDir("screenShot")
        val jpgPath = File(screenShotDir, File.separator + "screenShotDir-:${System.currentTimeMillis()}.jpg")

        var os: OutputStream? = null
        var isSaveSuccess = false
        try {
            os = BufferedOutputStream(FileOutputStream(jpgPath))
            isSaveSuccess = bitmap.compress(CompressFormat.JPEG, 5, os)
        } catch (e: IOException) {
            e.printStackTrace()
        } finally {
            try {
                os?.close()
            } catch (e: IOException) {
                e.printStackTrace()
            }
        }
        if (isSaveSuccess) {
            BLog.i("保存图片成功:${jpgPath.absolutePath}")
        }
    }

    private fun startScreenShot() {
        startNanoTime = System.nanoTime()
        ThreadPoolManager.execute {
            while (isScreeningShot) {
                queue.put("")
                Thread.sleep((1000/FRAME_RATE).toLong())
            }
        }
    }

    private fun stopScreenShot() {
        isScreeningShot = false

        isScreeningShot = false
        // 停止 MediaMuxer
        mediaMuxer?.apply {
            if (isMuxerStarted) {
                stop()
                BLog.i("MP4 文件生成完成：$outputMp4Path")
            }
            release()
        }
        mediaMuxer = null
        isMuxerStarted = false
    }

    /**
     * 初始化数据
     */
    override fun initData() {

    }

    /**
     * 更新连接rtmp服务器按钮UI
     */
    private fun updateBtnConnectRtmp() {
        binding.btnConnectRtmp.text = if (rtmpConnectState) {
            "断开rtmp服务器"
        } else {
            "连接rtmp服务器"
        }
    }


    /**
     * 更新截屏按钮UI
     */
    private fun updateBtnScreenShot() {
        binding.btnScreenShot.text = if (isScreeningShot) {
            "停止截屏"
        } else {
            "开始截屏"
        }
    }
    // 在类中定义全局 HandlerThread

    private val pixelCopyThread = HandlerThread("PixelCopyThread").apply { start() }
    private val mHandler = Handler(pixelCopyThread.looper)

    // 全局复用 Bitmap（避免频繁创建）
    private var reusableBitmap: Bitmap? = null

    private fun View.getBitmapFormView(activity: Activity, callBack: (bitmap: Bitmap) -> Unit) {

        if (reusableBitmap == null || reusableBitmap!!.width != width || reusableBitmap!!.height != height) {
            reusableBitmap?.recycle() // 释放旧 Bitmap
            reusableBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            BLog.i("创建bitmap,宽:${width},高:${height}")
        }

        var locations = IntArray(2)
        getLocationInWindow(locations)
        val rect = Rect(locations[0], locations[1], locations[0] + width, locations[1] + height)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                PixelCopy.request(
                    activity.window, rect, reusableBitmap!!, {
                        when (it) {
                            SUCCESS -> callBack(reusableBitmap!!)
                        }
                    }, mHandler
                )
            } catch (e: IllegalArgumentException) {
                e.printStackTrace()
            }
        }
    }


}