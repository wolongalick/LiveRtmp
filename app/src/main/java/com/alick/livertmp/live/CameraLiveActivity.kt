package com.alick.livertmp.live

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
import android.view.LayoutInflater
import android.view.Surface
import androidx.camera.core.AspectRatio
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.alick.commonlibrary.BaseActivity
import com.alick.livertmp.bean.RtmpServer
import com.alick.livertmp.constant.LiveConstant
import com.alick.livertmp.databinding.ActivityCameraLiveBinding
import com.alick.livertmp.databinding.DialogSelectBinding
import com.alick.livertmp.utils.ExecutorUtils
import com.alick.livertmp.utils.ImageUtil
import com.alick.livertmp.utils.LiveTaskManager
import com.alick.rtmplib.RtmpManager
import com.alick.utilslibrary.BLog
import com.alick.utilslibrary.FileUtils
import com.alick.utilslibrary.T
import com.alick.utilslibrary.TimeUtils
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.locks.ReentrantLock
import kotlin.math.min


class CameraLiveActivity : BaseActivity<ActivityCameraLiveBinding>() {
    private lateinit var cameraProviderFuture: ListenableFuture<ProcessCameraProvider>

    private val liveRoomUrl: RtmpServer by lazy {
        intent.getSerializableExtra(LiveConstant.INTENT_KEY_LIVE_ROOM_URL) as RtmpServer
    }

    private val lock = ReentrantLock()
    private var videoMediaCodec: MediaCodec? = null
    private var audioMediaCodec: MediaCodec? = null

    private var isInitYUV: Boolean = false
    private lateinit var y: ByteArray
    private lateinit var u: ByteArray
    private lateinit var v: ByteArray

    private var nv12: ByteArray? = null
    private var i420: ByteArray? = null
    private var i420_rotated: ByteArray? = null
    private var nv12_rotated: ByteArray? = null
    private var lastTs = 0L
    private var rowStride = 0
    private var width = 0
    private var height = 0
    private var rotationDegrees = 0

    private val bufferInfo = MediaCodec.BufferInfo()
    private var h264File: File? = null
    private var pcmFile: File? = null
    private var isRecordH264File = false  //是否记录h264数据到文件
    private var isRecordPcmFile = false  //是否记录pcm数据到文件

    private val FRAME_RATE = 24         //帧率24
    private var rtmpConnectState = false  //是否连接rtmp成功
    private var startNanoTime = 0L          //开始时间,单位:纳秒
    private var minBufferSize = 0

    private val sampleRateInHz = 44100                          //采样率44100
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO   //立体声
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT    //音频数据格式,PCM 16 bit per sample
    private val channelCount = 2                                //双声道

    private var audioRecord: AudioRecord? = null

    private var isLiving = false  //是否正在直播中
    private var isDestroy = false         //是否已销毁Activity
    private var isSoftCoding = false      //是否采用软编码
    private var isUseFrontCamera = true    //是否使用前置摄像头

    private val queue: LinkedBlockingQueue<BufferTask> by lazy {
        LinkedBlockingQueue()
    }

    data class BufferTask(val y: ByteArray, val u: ByteArray, val v: ByteArray, val yPixelStride: Int = 0, val uPixelStride: Int = 0, val vPixelStride: Int = 0)

    override fun initListener() {
        cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            bindPreview(cameraProviderFuture.get())
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
        cameraProviderFuture.get().unbindAll()

        val rotation = viewBinding.previewView.display.rotation
        BLog.i("预览时设置的TargetRotation:${rotation}")
        val preview: Preview = Preview.Builder()
//            .setTargetResolution(Size(viewBinding.previewView.width, viewBinding.previewView.height))
//            .setTargetAspectRatio(AspectRatio.RATIO_16_9)//导致闪退
//            .setTargetRotation(rotation)
//            .setTargetRotation(
//                if (isUseFrontCamera) {
//                    Surface.ROTATION_270
//                } else {
//                    Surface.ROTATION_90
//                }
//            )//没作用
            .build()

        val cameraSelector: CameraSelector = CameraSelector.Builder()
            .requireLensFacing(
                if (isUseFrontCamera) {
                    CameraSelector.LENS_FACING_FRONT
                } else {
                    CameraSelector.LENS_FACING_BACK
                }
            )
            .build()

//        viewBinding.previewView.scaleType = PreviewView.ScaleType.FIT_CENTER
        preview.setSurfaceProvider(viewBinding.previewView.surfaceProvider)

        val imageAnalysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
//            .setTargetResolution(Size(viewBinding.previewView.width, viewBinding.previewView.height))
//            .setTargetAspectRatio(AspectRatio.RATIO_16_9)//导致闪退
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        imageAnalysis.setAnalyzer(ExecutorUtils.getExecutor2()) { imageProxy ->
            //旋转角度
            lock.lock()
            rotationDegrees = imageProxy.imageInfo.rotationDegrees

            width = imageProxy.width
            height = imageProxy.height
            rowStride = imageProxy.planes[0].rowStride

            if (!isInitYUV) {
                val ySize = imageProxy.planes[0].buffer.remaining()//实际包含了
                val uSize = imageProxy.planes[1].buffer.remaining()
                val vSize = imageProxy.planes[2].buffer.remaining()

                val yPixelStride = imageProxy.planes[0].pixelStride
                val yRowStride = imageProxy.planes[0].rowStride
                val uPixelStride = imageProxy.planes[1].pixelStride
                val uRowStride = imageProxy.planes[1].rowStride
                val vPixelStride = imageProxy.planes[2].pixelStride
                val vRowStride = imageProxy.planes[2].rowStride

                BLog.i("旋转角度:${rotationDegrees}")
                BLog.i("ySize:${ySize},uSize:${uSize},vSize:${vSize}")
                BLog.i("width:${width},height:${height}")
                BLog.i("yPixelStride:${yPixelStride},yRowStride:${yRowStride},uPixelStride:${uPixelStride},uRowStride:${uRowStride},vPixelStride:${vPixelStride},vRowStride:${vRowStride}")
                y = ByteArray(ySize)
                u = ByteArray(uSize)
                v = ByteArray(vSize)
                //初始化软编码信息
//                RtmpManager.setVideoEncInfo(imageProxy.width,imageProxy.height,10, 640000)
                val size = width * height * 3 / 2
                nv12 = ByteArray(size)
                i420 = ByteArray(size)
                i420_rotated = ByteArray(size)
                nv12_rotated = ByteArray(size)
                isInitYUV = true
            }
            imageProxy.planes[0].buffer.get(y)
            imageProxy.planes[1].buffer.get(u)
            imageProxy.planes[2].buffer.get(v)


            addBufferTask(BufferTask(y, u, v))

            imageProxy.close()
            lock.unlock()
        }

        var camera = cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, preview, imageAnalysis)

    }


    private fun initMediaCodec(width: Int, height: Int) {
        if (videoMediaCodec == null) {
            try {
                videoMediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .apply {
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

    private fun startAudioRecord() {
        minBufferSize = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
        BLog.i("minBufferSize:${minBufferSize}")
        if (audioRecord == null) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                T.show("缺少录音权限")
                return
            }
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRateInHz, channelConfig, audioFormat, minBufferSize)
        }

        if (audioMediaCodec == null) {
            val audioFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRateInHz, channelCount)
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96000)
            audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRateInHz)
            audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
            audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_STEREO)
            audioFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            audioFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, minBufferSize)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                audioFormat.setInteger(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            }

            audioMediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            audioMediaCodec?.configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        audioMediaCodec?.start()

        LiveTaskManager.getInstance().execute {
            try {
                RtmpManager.sendAudio(byteArrayOf(0x12, 0x08), 0, true)

                val audioData = ByteArray(minBufferSize)
                val bufferInfo = MediaCodec.BufferInfo()
                audioRecord?.startRecording()

                while (isLiving) {
                    val requiredBufferSize = audioRecord?.read(audioData, 0, audioData.size)
                    if (requiredBufferSize == null || requiredBufferSize <= 0) {
                        BLog.e("audioData读取数据个数:${requiredBufferSize}")
                        continue
                    }
                    if (isRecordPcmFile) {
                        FileUtils.writeBytes(pcmFile, true, audioData, requiredBufferSize)
                        BLog.i("写入pcm文件,16进制内容:${FileUtils.byteArray2Hex(audioData)}")
                    }
                    audioMediaCodec?.let { mediaCodec ->
                        val inputIndex = mediaCodec.dequeueInputBuffer(0)
                        if (inputIndex >= 0) {
                            val inputBuffer = mediaCodec.getInputBuffer(inputIndex)!!
                            inputBuffer.clear()
                            val remaining = inputBuffer.remaining()
                            val actualPutSize = min(remaining, requiredBufferSize)
//                            BLog.i("缓冲区容量:${remaining},需要编码长度:${requiredBufferSize},实际编码长度:${actualPutSize}")
                            inputBuffer.put(audioData, 0, actualPutSize)
                            mediaCodec.queueInputBuffer(inputIndex, 0, actualPutSize, (System.nanoTime() - startNanoTime) / 1000, 0)
                        }
                        var outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)
                        while (outputIndex >= 0) {
                            val outputBuffer = mediaCodec.getOutputBuffer(outputIndex)
                            outputBuffer?.let {
                                if (rtmpConnectState) {
                                    val byteArray = ByteArray(bufferInfo.size)
                                    it.get(byteArray)

                                    RtmpManager.sendAudio(byteArray, bufferInfo.presentationTimeUs / 1000, false)//由于rtmp需要的时间单位是毫秒,因此需要微秒除以1000转成毫秒
                                } else {
                                    BLog.e("rtmp未连接成功,因此不发送音频数据")
                                }
                            }
                            mediaCodec.releaseOutputBuffer(outputIndex, false)
                            outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * 停止并释放录音
     */
    private fun stopReleaseRecordAudio() {
        audioMediaCodec?.apply {
            stop()
            release()
            audioMediaCodec = null
        }

        audioRecord?.apply {
            stop()
            release()
            audioRecord = null
        }
    }


    private fun encodeVideo(nv12: ByteArray) {
        initMediaCodec(height, width)
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
                    val byteArray = ByteArray(byteBuffer.remaining())
                    byteBuffer.get(byteArray)
                    if (isRecordH264File) {
                        FileUtils.writeBytes(h264File, true, byteArray)
                        BLog.i("写入H264文件,16进制内容:${FileUtils.byteArray2Hex(byteArray)}")
                    }

//                    BLog.i("presentationTimeUs:${bufferInfo.presentationTimeUs}")

                    if (rtmpConnectState) {
                        RtmpManager.sendVideo(byteArray, bufferInfo.presentationTimeUs / 1000)
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
            }
        }
    }


    @SuppressLint("SetTextI18n")
    private fun doubleClick() {
        val binding = DialogSelectBinding.inflate(LayoutInflater.from(this))
        val alertDialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        val sb = StringBuilder()
        sb.append(liveRoomUrl.alias + "\n" + liveRoomUrl.host + "\n")
        sb.append("旋转角度:${rotationDegrees}\n")
        sb.append("预览布局宽x高:${viewBinding.previewView.width}x${viewBinding.previewView.height}\n")
        sb.append("传输图像宽x高:${height}x${width}\n")//由于摄像头是横着的,所以宽高要互换位置
        sb.append("音频minBufferSize:${minBufferSize}\n")
        binding.tvInfo.text = sb.toString()
        updateBtnLive(binding)

        updateBtnConnectRtmp(binding)


        binding.cbRecordH264File.isChecked = isRecordH264File
        binding.cbRecordPcmFile.isChecked = isRecordPcmFile
        binding.cbUseFrontCamera.isChecked = isUseFrontCamera

        binding.btnSaveNV12.setOnClickListener {
            val isSuccess = ImageUtil.saveYUV2File(nv12, File(getExternalFilesDir("nv12"), "nv12_${width}x${height}_${TimeUtils.getCurrentTime()}.yuv"))
            T.show(
                if (isSuccess) {
                    "保存成功"
                } else {
                    "保存失败"
                }
            )
            alertDialog.dismiss()
        }

        binding.btnI420.setOnClickListener {
            val isSuccess = ImageUtil.saveYUV2File(i420, File(getExternalFilesDir("i420"), "i420_${width}x${height}_${TimeUtils.getCurrentTime()}.yuv"))
            T.show(
                if (isSuccess) {
                    "保存成功"
                } else {
                    "保存失败"
                }
            )
            alertDialog.dismiss()
        }


        binding.btnI420rotated.setOnClickListener {
            val isSuccess = ImageUtil.saveYUV2File(i420_rotated, File(getExternalFilesDir("i420_rotated"), "i420_rotated_${height}x${width}_${TimeUtils.getCurrentTime()}.yuv"))
            T.show(
                if (isSuccess) {
                    "保存成功"
                } else {
                    "保存失败"
                }
            )
            alertDialog.dismiss()
        }

        binding.btnSaveNV12Rotate.setOnClickListener {
            val isSuccess = ImageUtil.saveYUV2File(nv12_rotated, File(getExternalFilesDir("nv12_rotated"), "nv12_rotated_${height}x${width}_${TimeUtils.getCurrentTime()}.yuv"))
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
            if (!rtmpConnectState) {
                T.show("请先连接rtmp服务器")
                return@setOnClickListener
            }
            if (!isLiving) {
                val currentTime = TimeUtils.getCurrentTime()
                h264File = File(getExternalFilesDir("h264"), "live_${currentTime}.h264")
                pcmFile = File(getExternalFilesDir("pcm"), "live_${currentTime}.pcm")
                startNanoTime = System.nanoTime()
                isLiving = true
                startAudioRecord()
                T.show("开始直播")
            } else {
                stopLive()
                updateBtnLive(binding)
            }
            alertDialog.dismiss()
        }

        binding.cbRecordH264File.setOnCheckedChangeListener { _, isChecked ->
            isRecordH264File = isChecked
        }
        binding.cbRecordPcmFile.setOnCheckedChangeListener { _, isChecked ->
            isRecordPcmFile = isChecked
        }
        binding.cbUseFrontCamera.setOnCheckedChangeListener { _, isChecked ->
            isUseFrontCamera = isChecked
            swapCamera()
        }

        binding.btnConnectRtmp.setOnClickListener {
            if (rtmpConnectState) {
                stopLive()
                updateBtnLive(binding)
                RtmpManager.close()
                rtmpConnectState = false
                updateBtnConnectRtmp(binding)
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
                            updateBtnConnectRtmp(binding)
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

        BLog.i("宽:${height},高:${width}")
        alertDialog.show()
    }

    private fun swapCamera() {
        isInitYUV = false
        bindPreview(cameraProviderFuture.get())
    }

    /**
     * 更新直播按钮UI
     */
    private fun updateBtnLive(binding: DialogSelectBinding) {
        binding.btnLive.text = if (isLiving) {
            "停止直播"
        } else {
            "开始直播"
        }
    }

    /**
     * 更新连接rtmp服务器按钮UI
     */
    private fun updateBtnConnectRtmp(binding: DialogSelectBinding) {
        binding.btnConnectRtmp.text = if (rtmpConnectState) {
            "断开rtmp服务器"
        } else {
            "连接rtmp服务器"
        }
    }

    private fun stopLive() {
        isLiving = false
        T.show("停止直播")
        stopReleaseRecordAudio()
    }

    private fun addBufferTask(bufferTask: BufferTask) {
        queue.put(bufferTask)
    }


    private fun startQueue() {
        LiveTaskManager.getInstance().execute {
            while (!isDestroy) {
                val bufferTask = queue.take()
                //YUV写入NV21
                ImageUtil.yuvToNv12_or_Nv21(bufferTask.y, bufferTask.u, bufferTask.v, nv12, rowStride, 2, width, height, ImageUtil.DST_TYPE_NV12)
                RtmpManager.nv12Rotate(nv12!!, width, height, i420!!, i420_rotated!!, nv12_rotated!!, rotationDegrees)

                if (isLiving) {
                    if (isSoftCoding) {
//                        RtmpManager.sendNV12(nv12!!, nv12!!.size)
                    } else {
                        encodeVideo(nv12_rotated!!)
                    }
                } else {
                    videoMediaCodec?.apply {
                        stop()
                        release()
                        videoMediaCodec = null
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
        isLiving = false
        videoMediaCodec?.apply {
            stop()
            release()
            videoMediaCodec = null
        }
        audioMediaCodec?.apply {
            stop()
            release()
            audioMediaCodec = null
        }
        audioRecord?.apply {
            stop()
            release()
            audioRecord = null
        }
        RtmpManager.close()
    }
}