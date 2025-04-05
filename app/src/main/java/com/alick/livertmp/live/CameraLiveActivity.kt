package com.alick.livertmp.live

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.*
import android.os.Build
import android.util.Log
import android.util.Size
import android.view.LayoutInflater
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
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
import kotlin.math.abs
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
    private var isUseFrontCamera = false    //是否使用前置摄像头
    private var debugOutYUV = false
    private lateinit var alertDialog: AlertDialog

    @RtmpManager.OnProcessing.CallbackTypeAnnotation
    private var currentCallbackType = RtmpManager.OnProcessing.callbackType_nv12ToI420

    private var errorCount = 0  // 错误计数，如果多少次发送失败，则需要重启视频流
    private var reconnecting = false  // 是否正在重连中

    private val queue: LinkedBlockingQueue<BufferTask> by lazy {
        LinkedBlockingQueue()
    }

    data class BufferTask(
        val y: ByteArray,
        val u: ByteArray,
        val v: ByteArray,
        val yPixelStride: Int = 0,
        val uPixelStride: Int = 0,
        val vPixelStride: Int = 0
    )

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


    private fun getMatchingSize2(): Size? {
        var selectSize: Size? = null
        try {
            val mCameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
            for (cameraId in mCameraManager.cameraIdList) {
                val cameraCharacteristics = mCameraManager.getCameraCharacteristics(cameraId)
                val streamConfigurationMap =
                    cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                val sizes = streamConfigurationMap!!.getOutputSizes(ImageFormat.YUV_420_888)
                val displayMetrics = resources.displayMetrics //因为我这里是将预览铺满屏幕,所以直接获取屏幕分辨率
                val deviceWidth = displayMetrics.widthPixels //屏幕分辨率宽
                val deviceHeigh = displayMetrics.heightPixels //屏幕分辨率高
                BLog.i("getMatchingSize2: 屏幕密度宽度=$deviceWidth")
                BLog.i("getMatchingSize2: 屏幕密度高度=$deviceHeigh")
                /**
                 * 循环40次,让宽度范围从最小逐步增加,找到最符合屏幕宽度的分辨率,
                 * 你要是不放心那就增加循环,肯定会找到一个分辨率,不会出现此方法返回一个null的Size的情况
                 * ,但是循环越大后获取的分辨率就越不匹配
                 */
//				for (int j = 1; j < 41; j++) {
                for (i in sizes.indices) { //遍历所有Size
                    val itemSize = sizes[i]
                    BLog.i("当前itemSize 宽=" + itemSize.width + "高=" + itemSize.height)
                    //判断当前Size高度小于屏幕宽度+j*5  &&  判断当前Size高度大于屏幕宽度-j*5  &&  判断当前Size宽度小于当前屏幕高度
                    if (selectSize != null) { //如果之前已经找到一个匹配的宽度
                        if (itemSize.width > selectSize.width) { //求绝对值算出最接近设备高度的尺寸
                            selectSize = itemSize
                            continue
                        }
                    } else {
                        selectSize = itemSize
                    }
                }
                if (selectSize != null) { //如果不等于null 说明已经找到了 跳出循环
                    break
                }
                //				}
            }
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }
        BLog.e("getMatchingSize2: 选择的分辨率宽度=" + selectSize!!.width)
        BLog.e("getMatchingSize2: 选择的分辨率高度=" + selectSize!!.height)
        return selectSize
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

//        val sendsize = getMatchingSize2() ?: Size(
//            viewBinding.previewView.width,
//            viewBinding.previewView.height
//        )
        val sendsize = Size(
            viewBinding.previewView.width,
            viewBinding.previewView.height
        )
        val imageAnalysis = ImageAnalysis.Builder()
            .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
            .setTargetResolution(sendsize)
//            .setTargetAspectRatio(AspectRatio.RATIO_16_9)//导致闪退
//            .setTargetRotation(
//                if (isUseFrontCamera) {
//                    Surface.ROTATION_270
//                } else {
//                    Surface.ROTATION_90
//                }
//            )
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

        var camera = cameraProvider.bindToLifecycle(
            this as LifecycleOwner,
            cameraSelector,
            preview,
            imageAnalysis
        )

    }


    private fun initMediaCodec(width: Int, height: Int) {
        if (videoMediaCodec == null) {
            try {
                videoMediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                    .apply {
                        val capabilitiesForType =
                            codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        capabilitiesForType.colorFormats.forEach {
                            BLog.i("colorFormats:${it}")
                        }
                        val mediaFormat = MediaFormat.createVideoFormat(
                            MediaFormat.MIMETYPE_VIDEO_AVC,
                            width,
                            height
                        )
                        mediaFormat.setInteger(
                            MediaFormat.KEY_COLOR_FORMAT,
                            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
                        )
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
            if (ActivityCompat.checkSelfPermission(
                    this,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                T.show("缺少录音权限")
                return
            }
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRateInHz,
                channelConfig,
                audioFormat,
                minBufferSize
            )
        }

        if (audioMediaCodec == null) {
            val audioFormat = MediaFormat.createAudioFormat(
                MediaFormat.MIMETYPE_AUDIO_AAC,
                sampleRateInHz,
                channelCount
            )
            audioFormat.setInteger(MediaFormat.KEY_BIT_RATE, 96000)
            audioFormat.setInteger(MediaFormat.KEY_SAMPLE_RATE, sampleRateInHz)
            audioFormat.setInteger(MediaFormat.KEY_CHANNEL_COUNT, channelCount)
            audioFormat.setInteger(MediaFormat.KEY_CHANNEL_MASK, AudioFormat.CHANNEL_IN_STEREO)
            audioFormat.setInteger(
                MediaFormat.KEY_AAC_PROFILE,
                MediaCodecInfo.CodecProfileLevel.AACObjectLC
            )
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
                            mediaCodec.queueInputBuffer(
                                inputIndex,
                                0,
                                actualPutSize,
                                (System.nanoTime() - startNanoTime) / 1000,
                                0
                            )
                        }
                        var outputIndex = mediaCodec.dequeueOutputBuffer(bufferInfo, 0)
                        while (outputIndex >= 0) {
                            val outputBuffer = mediaCodec.getOutputBuffer(outputIndex)
                            outputBuffer?.let {
                                if (rtmpConnectState && reconnecting.not()) {
                                    val byteArray = ByteArray(bufferInfo.size)
                                    it.get(byteArray)

                                    val success = RtmpManager.sendAudio(
                                        byteArray,
                                        bufferInfo.presentationTimeUs / 1000,
                                        false
                                    )//由于rtmp需要的时间单位是毫秒,因此需要微秒除以1000转成毫秒

                                    if (success) {
                                        errorCount = 0
                                    } else {
                                        errorCount += 1
                                    }
                                    if (errorCount > 10) {
                                        restartRtmp()
                                    }
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
                codec.queueInputBuffer(
                    inputIndex,
                    0,
                    nv12.size,
                    (System.nanoTime() - startNanoTime) / 1000,
                    0
                )
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

                    if (rtmpConnectState && reconnecting.not()) {
                        val success =
                            RtmpManager.sendVideo(byteArray, bufferInfo.presentationTimeUs / 1000)
                        if (success) {
                            errorCount = 0
                        } else {
                            errorCount += 1
                        }
                        if (errorCount > 10) {
                            restartRtmp()
                        }
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
        alertDialog =
            AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
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
            val isSuccess = ImageUtil.saveYUV2File(
                nv12,
                File(
                    getExternalFilesDir("nv12"),
                    "nv12_${width}x${height}_${TimeUtils.getCurrentTime()}.yuv"
                )
            )
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
            debugOutYUV = true
            currentCallbackType = RtmpManager.OnProcessing.callbackType_nv12ToI420
        }


        binding.btnI420rotated.setOnClickListener {
            debugOutYUV = true
            currentCallbackType = RtmpManager.OnProcessing.callbackType_rotateI420
        }

        binding.btnI420mirror.setOnClickListener {
            if (!isUseFrontCamera) {
                T.show("请切换至前置摄像头,再使用此功能")
                return@setOnClickListener
            }
            debugOutYUV = true
            currentCallbackType = RtmpManager.OnProcessing.callbackType_i420Mirror
        }

        binding.btnSaveNV12Rotate.setOnClickListener {
            val isSuccess = ImageUtil.saveYUV2File(
                nv12_rotated,
                File(
                    getExternalFilesDir("nv12_rotated"),
                    "nv12_rotated_${height}x${width}_${TimeUtils.getCurrentTime()}.yuv"
                )
            )
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

    /**
     * 网络异常断开或端口断开时，需要自动重启视频流
     */
    private fun restartRtmp() {
        if (reconnecting || rtmpConnectState.not()) {
            return
        }
        reconnecting = true
        lifecycleScope.launch {
            BLog.i("检测到断开，准备自动重连")
            // 释放 mediacodec， 使其下次重新初始化
            stopLive()
            rtmpConnectState = false
            // 直接断开rtmp
            RtmpManager.close()

            // 重新链接 rtmp
            withContext(Dispatchers.IO) {
                val newConnectState = RtmpManager.connect(liveRoomUrl.host)
                reconnecting = false
                errorCount = 0
                // 如果重连失败，则更新界面
                withContext(Dispatchers.Main) {
                    if (newConnectState) {
                        BLog.i("检测到断开，自动重连成功")
                        T.show("检测到rtmp断开，已自动重连")
                        rtmpConnectState = true
                        startNanoTime = System.nanoTime()
                        isLiving = true
                        startAudioRecord()
                        T.show("开始直播")
                    } else {
                        stopLive()
                        RtmpManager.close()
                        rtmpConnectState = false
                    }
                }
                BLog.i("重连成功准备发送数据")
            }
        }
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
                //YUV写入NV12
                ImageUtil.yuvToNv12_or_Nv21(
                    bufferTask.y,
                    bufferTask.u,
                    bufferTask.v,
                    nv12,
                    rowStride,
                    2,
                    width,
                    height,
                    ImageUtil.DST_TYPE_NV12
                )

                val onProcessing = object : RtmpManager.OnProcessing {
                    override fun callback(
                        @RtmpManager.OnProcessing.CallbackTypeAnnotation callbackType: String,
                        result: ByteArray
                    ) {
                        debugOutYUV = false
                        var isSuccess = false
                        if (currentCallbackType == callbackType) {
                            when (callbackType) {
                                RtmpManager.OnProcessing.callbackType_nv12ToI420 -> {
                                    BLog.i("完成回调:nv12转i420")
                                    isSuccess = ImageUtil.saveYUV2File(
                                        result,
                                        File(
                                            getExternalFilesDir("i420"),
                                            "i420_${width}x${height}_${TimeUtils.getCurrentTime()}.yuv"
                                        )
                                    )
                                }

                                RtmpManager.OnProcessing.callbackType_rotateI420 -> {
                                    BLog.i("完成回调:i420旋转")
                                    isSuccess = ImageUtil.saveYUV2File(
                                        result,
                                        File(
                                            getExternalFilesDir("i420_rotated"),
                                            "i420_rotated_${height}x${width}_${TimeUtils.getCurrentTime()}.yuv"
                                        )
                                    )
                                }

                                RtmpManager.OnProcessing.callbackType_i420Mirror -> {
                                    BLog.i("完成回调:i420镜像翻转")
                                    isSuccess = ImageUtil.saveYUV2File(
                                        result,
                                        File(
                                            getExternalFilesDir("i420_mirror"),
                                            "i420_mirror_${height}x${width}_${TimeUtils.getCurrentTime()}.yuv"
                                        )
                                    )
                                }
                            }
                            T.show(
                                if (isSuccess) {
                                    "保存成功"
                                } else {
                                    "保存失败"
                                }
                            )
                            alertDialog.dismiss()
                            currentCallbackType = ""
                        }
                    }

                }
                RtmpManager.nv12Rotate(
                    nv12!!,
                    width,
                    height,
                    nv12_rotated!!,
                    rotationDegrees,
                    isUseFrontCamera,
                    if (debugOutYUV) {
                        onProcessing
                    } else {
                        null
                    }
                )

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