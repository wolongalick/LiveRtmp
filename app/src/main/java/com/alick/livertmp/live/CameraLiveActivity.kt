package com.alick.livertmp.live

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.ProgressDialog
import android.content.pm.PackageManager
import android.media.*
import android.os.Build
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
    private var isRecordH264File = false  //????????????h264???????????????
    private var isRecordPcmFile = false  //????????????pcm???????????????

    private val FRAME_RATE = 24         //??????24
    private var rtmpConnectState = false  //????????????rtmp??????
    private var startNanoTime = 0L          //????????????,??????:??????
    private var minBufferSize = 0

    private val sampleRateInHz = 44100                          //?????????44100
    private val channelConfig = AudioFormat.CHANNEL_IN_STEREO   //?????????
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT    //??????????????????,PCM 16 bit per sample
    private val channelCount = 2                                //?????????

    private var audioRecord: AudioRecord? = null

    private var isLiving = false  //?????????????????????
    private var isDestroy = false         //???????????????Activity
    private var isSoftCoding = false      //?????????????????????
    private var isUseFrontCamera = false    //???????????????????????????
    private var debugOutYUV = false
    private lateinit var alertDialog: AlertDialog

    @RtmpManager.OnProcessing.CallbackTypeAnnotation
    private var currentCallbackType = RtmpManager.OnProcessing.callbackType_nv12ToI420

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
                //??????????????????
                doubleClick()
            }
            lastTs = ts
        }

        startQueue()
    }

    private fun bindPreview(cameraProvider: ProcessCameraProvider) {
        cameraProviderFuture.get().unbindAll()

        val rotation = viewBinding.previewView.display.rotation
        BLog.i("??????????????????TargetRotation:${rotation}")
        val preview: Preview = Preview.Builder()
//            .setTargetResolution(Size(viewBinding.previewView.width, viewBinding.previewView.height))
//            .setTargetAspectRatio(AspectRatio.RATIO_16_9)//????????????
//            .setTargetRotation(rotation)
//            .setTargetRotation(
//                if (isUseFrontCamera) {
//                    Surface.ROTATION_270
//                } else {
//                    Surface.ROTATION_90
//                }
//            )//?????????
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
//            .setTargetAspectRatio(AspectRatio.RATIO_16_9)//????????????
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
            //????????????
            lock.lock()
            rotationDegrees = imageProxy.imageInfo.rotationDegrees

            width = imageProxy.width
            height = imageProxy.height
            rowStride = imageProxy.planes[0].rowStride

            if (!isInitYUV) {
                val ySize = imageProxy.planes[0].buffer.remaining()//???????????????
                val uSize = imageProxy.planes[1].buffer.remaining()
                val vSize = imageProxy.planes[2].buffer.remaining()

                val yPixelStride = imageProxy.planes[0].pixelStride
                val yRowStride = imageProxy.planes[0].rowStride
                val uPixelStride = imageProxy.planes[1].pixelStride
                val uRowStride = imageProxy.planes[1].rowStride
                val vPixelStride = imageProxy.planes[2].pixelStride
                val vRowStride = imageProxy.planes[2].rowStride

                BLog.i("????????????:${rotationDegrees}")
                BLog.i("ySize:${ySize},uSize:${uSize},vSize:${vSize}")
                BLog.i("width:${width},height:${height}")
                BLog.i("yPixelStride:${yPixelStride},yRowStride:${yRowStride},uPixelStride:${uPixelStride},uRowStride:${uRowStride},vPixelStride:${vPixelStride},vRowStride:${vRowStride}")
                y = ByteArray(ySize)
                u = ByteArray(uSize)
                v = ByteArray(vSize)
                //????????????????????????
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

        var camera = cameraProvider.bindToLifecycle(this as LifecycleOwner, cameraSelector, preview, imageAnalysis)

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
                        mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, width * height * 8)//?????????
                        mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)//??????(fps):24
                        mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)//??????2?????????I???
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
                T.show("??????????????????")
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
                        BLog.e("audioData??????????????????:${requiredBufferSize}")
                        continue
                    }
                    if (isRecordPcmFile) {
                        FileUtils.writeBytes(pcmFile, true, audioData, requiredBufferSize)
                        BLog.i("??????pcm??????,16????????????:${FileUtils.byteArray2Hex(audioData)}")
                    }
                    audioMediaCodec?.let { mediaCodec ->
                        val inputIndex = mediaCodec.dequeueInputBuffer(0)
                        if (inputIndex >= 0) {
                            val inputBuffer = mediaCodec.getInputBuffer(inputIndex)!!
                            inputBuffer.clear()
                            val remaining = inputBuffer.remaining()
                            val actualPutSize = min(remaining, requiredBufferSize)
//                            BLog.i("???????????????:${remaining},??????????????????:${requiredBufferSize},??????????????????:${actualPutSize}")
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

                                    RtmpManager.sendAudio(byteArray, bufferInfo.presentationTimeUs / 1000, false)//??????rtmp??????????????????????????????,????????????????????????1000????????????
                                } else {
                                    BLog.e("rtmp???????????????,???????????????????????????")
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
     * ?????????????????????
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
                //?????????????????????
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
                        BLog.i("??????H264??????,16????????????:${FileUtils.byteArray2Hex(byteArray)}")
                    }

//                    BLog.i("presentationTimeUs:${bufferInfo.presentationTimeUs}")

                    if (rtmpConnectState) {
                        RtmpManager.sendVideo(byteArray, bufferInfo.presentationTimeUs / 1000)
                    } else {
                        BLog.e("rtmp???????????????,???????????????????????????")
                    }
                }
                //?????????????????????
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

                BLog.i("SPS??????:${FileUtils.byteArray2Hex(sps)}")
                BLog.i("PPS??????:${FileUtils.byteArray2Hex(pps)}")
            }
        }
    }


    @SuppressLint("SetTextI18n")
    private fun doubleClick() {
        val binding = DialogSelectBinding.inflate(LayoutInflater.from(this))
        alertDialog = AlertDialog.Builder(this, androidx.appcompat.R.style.Theme_AppCompat_Light_Dialog)
            .setView(binding.root)
            .setCancelable(true)
            .create()

        val sb = StringBuilder()
        sb.append(liveRoomUrl.alias + "\n" + liveRoomUrl.host + "\n")
        sb.append("????????????:${rotationDegrees}\n")
        sb.append("???????????????x???:${viewBinding.previewView.width}x${viewBinding.previewView.height}\n")
        sb.append("???????????????x???:${height}x${width}\n")//???????????????????????????,???????????????????????????
        sb.append("??????minBufferSize:${minBufferSize}\n")
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
                    "????????????"
                } else {
                    "????????????"
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
                T.show("???????????????????????????,??????????????????")
                return@setOnClickListener
            }
            debugOutYUV = true
            currentCallbackType = RtmpManager.OnProcessing.callbackType_i420Mirror
        }

        binding.btnSaveNV12Rotate.setOnClickListener {
            val isSuccess = ImageUtil.saveYUV2File(nv12_rotated, File(getExternalFilesDir("nv12_rotated"), "nv12_rotated_${height}x${width}_${TimeUtils.getCurrentTime()}.yuv"))
            T.show(
                if (isSuccess) {
                    "????????????"
                } else {
                    "????????????"
                }
            )
            alertDialog.dismiss()
        }

        binding.btnLive.setOnClickListener {
            if (!rtmpConnectState) {
                T.show("????????????rtmp?????????")
                return@setOnClickListener
            }
            if (!isLiving) {
                val currentTime = TimeUtils.getCurrentTime()
                h264File = File(getExternalFilesDir("h264"), "live_${currentTime}.h264")
                pcmFile = File(getExternalFilesDir("pcm"), "live_${currentTime}.pcm")
                startNanoTime = System.nanoTime()
                isLiving = true
                startAudioRecord()
                T.show("????????????")
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
                T.show("??????rtmp???????????????")
            } else {
                var progressDialog: ProgressDialog? = ProgressDialog(this)
                progressDialog?.setCancelable(false)
                progressDialog?.setMessage("????????????rtmp?????????...")
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
                            T.show("??????rtmp???????????????")
                        } else {
                            T.show("??????rtmp???????????????")
                        }
                        progressDialog?.dismiss()
                        progressDialog = null
                    }
                }
            }
        }

        BLog.i("???:${height},???:${width}")
        alertDialog.show()
    }

    private fun swapCamera() {
        isInitYUV = false
        bindPreview(cameraProviderFuture.get())
    }

    /**
     * ??????????????????UI
     */
    private fun updateBtnLive(binding: DialogSelectBinding) {
        binding.btnLive.text = if (isLiving) {
            "????????????"
        } else {
            "????????????"
        }
    }

    /**
     * ????????????rtmp???????????????UI
     */
    private fun updateBtnConnectRtmp(binding: DialogSelectBinding) {
        binding.btnConnectRtmp.text = if (rtmpConnectState) {
            "??????rtmp?????????"
        } else {
            "??????rtmp?????????"
        }
    }

    private fun stopLive() {
        isLiving = false
        T.show("????????????")
        stopReleaseRecordAudio()
    }

    private fun addBufferTask(bufferTask: BufferTask) {
        queue.put(bufferTask)
    }


    private fun startQueue() {
        LiveTaskManager.getInstance().execute {
            while (!isDestroy) {
                val bufferTask = queue.take()
                //YUV??????NV21
                ImageUtil.yuvToNv12_or_Nv21(bufferTask.y, bufferTask.u, bufferTask.v, nv12, rowStride, 2, width, height, ImageUtil.DST_TYPE_NV12)

                val onProcessing = object : RtmpManager.OnProcessing {
                    override fun callback(@RtmpManager.OnProcessing.CallbackTypeAnnotation callbackType: String, result: ByteArray) {
                        debugOutYUV = false
                        var isSuccess = false
                        if (currentCallbackType == callbackType) {
                            when (callbackType) {
                                RtmpManager.OnProcessing.callbackType_nv12ToI420 -> {
                                    BLog.i("????????????:nv12???i420")
                                    isSuccess = ImageUtil.saveYUV2File(result, File(getExternalFilesDir("i420"), "i420_${width}x${height}_${TimeUtils.getCurrentTime()}.yuv"))
                                }
                                RtmpManager.OnProcessing.callbackType_rotateI420 -> {
                                    BLog.i("????????????:i420??????")
                                    isSuccess = ImageUtil.saveYUV2File(result, File(getExternalFilesDir("i420_rotated"), "i420_rotated_${height}x${width}_${TimeUtils.getCurrentTime()}.yuv"))
                                }
                                RtmpManager.OnProcessing.callbackType_i420Mirror -> {
                                    BLog.i("????????????:i420????????????")
                                    isSuccess = ImageUtil.saveYUV2File(result, File(getExternalFilesDir("i420_mirror"), "i420_mirror_${height}x${width}_${TimeUtils.getCurrentTime()}.yuv"))
                                }
                            }
                            T.show(
                                if (isSuccess) {
                                    "????????????"
                                } else {
                                    "????????????"
                                }
                            )
                            alertDialog.dismiss()
                            currentCallbackType = ""
                        }
                    }

                }
                RtmpManager.nv12Rotate(
                    nv12!!, width, height, nv12_rotated!!, rotationDegrees, isUseFrontCamera, if (debugOutYUV) {
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
                        BLog.i("mediaCodec?????????,?????????")
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