package com.alick.livertmp.live

import android.app.AlertDialog
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.util.Size
import android.view.LayoutInflater
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.alick.commonlibrary.BaseActivity
import com.alick.livertmp.bean.RtmpServer
import com.alick.livertmp.constant.LiveConstant
import com.alick.livertmp.databinding.ActivityCameraLiveBinding
import com.alick.livertmp.databinding.DialogSelectBinding
import com.alick.livertmp.utils.ExecutorUtils
import com.alick.livertmp.utils.ImageUtil
import com.alick.rtmplib.RtmpManager
import com.alick.utilslibrary.BLog
import com.alick.utilslibrary.FileUtils
import com.alick.utilslibrary.T
import com.alick.utilslibrary.TimeUtils
import com.google.common.util.concurrent.ListenableFuture
import java.io.File
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread


class CameraLiveActivity : BaseActivity<ActivityCameraLiveBinding>() {
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>

    private val liveRoomUrl: RtmpServer by lazy {
        intent.getSerializableExtra(LiveConstant.INTENT_KEY_LIVE_ROOM_URL) as RtmpServer
    }

    private val lock = ReentrantLock()
    private var mediaCodec: MediaCodec? = null

    private var isInitYUV: Boolean = false
    private lateinit var y: ByteArray
    private lateinit var u: ByteArray
    private lateinit var v: ByteArray

    private var nv21: ByteArray? = null
    private var nv21_rotated: ByteArray? = null
    private var nv12: ByteArray? = null
    private var lastTs = 0L
    private var width = 0
    private var height = 0
    private var rotationDegrees = 0

    private val bufferInfo = MediaCodec.BufferInfo()
    private var isLiving = false  //是否正在直播中
    private var h264File: File? = null
    private var isRecordH264File = false  //是否记录h264数据到文件

    private var lastIFrameTs = 0L       //关键帧时间戳,单位毫秒
    private val I_FRAME_INTERVAL = 2    //关键帧间隔为2秒
    private val FRAME_RATE = 24         //帧率24
    private var pts = 0L                //显示时间戳
    private var generateIndex = 0L        //生成的帧索引
    private var rtmpConnectState = false  //是否连接rtmp成功
    private var isDestroy = false         //是否已销毁Activity
    private var isEncoding = false        //是否正在编码

    var previewWidth = 480
    var previewHeight = 640

    private var isSoftCoding = false      //是否采用软编码

    private val queue: ArrayBlockingQueue<BufferTask> by lazy {
        ArrayBlockingQueue(100)
    }

    data class BufferTask(val y: ByteArray, val u: ByteArray, val v: ByteArray)

    override fun initListener() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            bindPreview(cameraProvider)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun initData() {
        viewBinding.previewView.setOnClickListener {
            val ts = System.currentTimeMillis()
            if (ts - lastTs < 300) {
                //执行双击逻辑
                doubleClick()
            }
            lastTs = ts
        }

        startQueue()
    }

    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        val preview: Preview = Preview.Builder()
            .setTargetResolution(Size(700, 700))
            .build()

        val cameraSelector: CameraSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        viewBinding.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        preview.setSurfaceProvider(viewBinding.previewView.surfaceProvider)

        val imageAnalysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetResolution(Size(700, 700))
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        imageAnalysis.setAnalyzer(ExecutorUtils.getExecutor2()) { imageProxy ->
            //旋转角度
            lock.lock()
            rotationDegrees = imageProxy.imageInfo.rotationDegrees

//            BLog.i("旋转角度:${rotationDegrees}")

            if (!isInitYUV) {
                y = ByteArray(imageProxy.planes[0].buffer.remaining())
                u = ByteArray(imageProxy.planes[1].buffer.remaining())
                v = ByteArray(imageProxy.planes[2].buffer.remaining())
                //初始化软编码信息
//                RtmpManager.setVideoEncInfo(imageProxy.width,imageProxy.height,10, 640000)
                isInitYUV = true
            }
            imageProxy.planes[0].buffer.get(y)
            imageProxy.planes[1].buffer.get(u)
            imageProxy.planes[2].buffer.get(v)

            width = imageProxy.width
            height = imageProxy.height

            val size = width * height * 3 / 2
            if (nv21 == null) {
                nv21 = ByteArray(size)
                nv21_rotated = ByteArray(size)
                nv12 = ByteArray(size)
            }

            addBufferTask(BufferTask(y, u, v))

            imageProxy.close()
            lock.unlock()
        }

        var camera = cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, preview, imageAnalysis)
    }


    private fun initMediaCodec(width: Int, height: Int) {
        if (mediaCodec == null) {
            try {
                mediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .apply {
                        val mediaFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height)
                        mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, 400_000)//比特率
                        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)//帧率(fps):15
                        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)//每隔2秒一个I帧
                        configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                        start()
                    }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }


    private fun encode(nv12: ByteArray) {
        initMediaCodec(height, width)
        mediaCodec?.let { codec ->
            val inputIndex = codec.dequeueInputBuffer(0)
            if (inputIndex >= 0) {
                val inputBuffer = codec.getInputBuffer(inputIndex)
                inputBuffer?.apply {
                    clear()
                    put(nv12)
                }

                pts = computePresentationTime(generateIndex)
                //输入缓冲区归位
                codec.queueInputBuffer(inputIndex, 0, nv12.size, pts, 0)
                generateIndex++
//                BLog.i("generateIndex:${generateIndex}")
            }

            val outputIndex = codec.dequeueOutputBuffer(bufferInfo, 0)
            if (outputIndex >= 0) {
                val outputBuffer = codec.getOutputBuffer(outputIndex)
                outputBuffer?.let { byteBuffer ->
                    val byteArray = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(byteArray)
                    if (isRecordH264File) {
                        FileUtils.writeBytes(h264File, true, byteArray)
                        BLog.i("写入H264文件,16进制内容:${FileUtils.byteArray2Hex(byteArray)}")
                    }

//                    BLog.i("presentationTimeUs:${bufferInfo.presentationTimeUs}")

                    if (rtmpConnectState) {
                        RtmpManager.sendVideo(byteArray, byteArray.size, bufferInfo.presentationTimeUs / 1000)
                    } else {
                        BLog.e("rtmp未连接成功,因此不发送数据")
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
            }
        }
    }


    private fun doubleClick() {
        val binding = DialogSelectBinding.inflate(LayoutInflater.from(this))
        val alertDialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        binding.rvServerValue.text = liveRoomUrl.alias + "\n" + liveRoomUrl.host
        binding.tvInfo.text = "旋转角度:${rotationDegrees}"
        binding.btnLive.text = if (isLiving) {
            "停止直播"
        } else {
            "开始直播"
        }

        binding.cbRecordH264File.isChecked = isRecordH264File

        binding.btnSaveNV21.setOnClickListener {
            val isSuccess = ImageUtil.saveYUV2File(nv21, File(getExternalFilesDir("nv21"), "nv21_${width}x${height}_${TimeUtils.getCurrentTime()}.yuv"))
            T.show(
                if (isSuccess) {
                    "保存成功"
                } else {
                    "保存失败"
                }
            )
            alertDialog.dismiss()
        }

        binding.btnSaveNV21Rotate.setOnClickListener {
            val isSuccess = ImageUtil.saveYUV2File(nv21_rotated, File(getExternalFilesDir("nv21rotated"), "nv21rotated_${height}x${width}_${TimeUtils.getCurrentTime()}.yuv"))
            T.show(
                if (isSuccess) {
                    "保存成功"
                } else {
                    "保存失败"
                }
            )
            alertDialog.dismiss()
        }

        binding.btnSaveNV12.setOnClickListener {
            val isSuccess = ImageUtil.saveYUV2File(nv12, File(getExternalFilesDir("nv12"), "nv12_${height}x${width}_${TimeUtils.getCurrentTime()}.yuv"))
            T.show(
                if (isSuccess) {
                    "保存成功"
                } else {
                    "保存失败"
                }
            )
            alertDialog.dismiss()
        }

        binding.btnLive.setOnClickListener {
            if (!isLiving) {
                h264File = File(getExternalFilesDir("h264"), "live_${TimeUtils.getCurrentTime()}.h264")
                isLiving = true
                T.show("开始直播")
            } else {
                isLiving = false
                T.show("停止直播")
            }
            alertDialog.dismiss()
        }

        binding.cbRecordH264File.setOnCheckedChangeListener { _, isChecked ->
            isRecordH264File = isChecked
        }

        binding.btnConnectRtmp.setOnClickListener {
            val connect = RtmpManager.connect(liveRoomUrl.host)
            if (connect) {
                rtmpConnectState = true
                T.show("连接rtmp成功")
            } else {
                rtmpConnectState = false
                T.show("连接rtmp失败")
            }
        }

        BLog.i("宽:${width},高:${height}")
        alertDialog.show()
    }

    private fun addBufferTask(bufferTask: BufferTask) {
        queue.put(bufferTask)
    }

    private fun startQueue() {
        thread {
            while (!isDestroy) {
                val bufferTask = queue.take()
                //YUV写入NV21
                ImageUtil.yuvToNv21(bufferTask.y, bufferTask.u, bufferTask.v, nv21, width, height)
                //NV21顺时针旋转90度
                ImageUtil.nv21_rotate_to_90(nv21, nv21_rotated, width, height)

                //NV21(旋转90度后的)转NV12
                ImageUtil.nv21toNV12(nv21_rotated, nv12)

                if (isLiving) {
                    if (isSoftCoding) {
//                        RtmpManager.sendNV12(nv12!!, nv12!!.size)
                    } else {
                        encode(nv12!!)
                    }
                } else {
                    mediaCodec?.apply {
                        stop()
                        release()
                        mediaCodec = null
                        BLog.i("mediaCodec已停止,已释放")
                    }
                }

            }
        }
    }

    /**
     * Generates the presentation time for frame N, in microseconds.
     */
    private fun computePresentationTime(frameIndex: Long): Long {
        return 132 + frameIndex * 1000000 / FRAME_RATE
    }

    override fun onDestroy() {
        super.onDestroy()
        isDestroy = true
        mediaCodec?.apply {
            stop()
            release()
            mediaCodec = null
        }
    }
}