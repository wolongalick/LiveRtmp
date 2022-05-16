#include <jni.h>
#include <string>

#include "librtmp/rtmp.h"
#include "librtmp/log.h"
#include "CxwLog.h"

/**
 * rtmp结构体
 */
struct Live {
    RTMP *rtmp;
    int16_t sps_len;
    char *sps;

    int16_t pps_len;
    char *pps;
};

Live *mLive = nullptr;

void logCallback(int level, const char *fmt, va_list args) {
    char log[2048];
    vsprintf(log, fmt, args);

    if (level == RTMP_LOGCRIT) {
        LOGE("RTMP日志(RTMP_LOGCRIT):%s", log)
    } else if (level == RTMP_LOGERROR) {
        LOGE("RTMP日志(RTMP_LOGERROR):%s", log)
    } else if (level == RTMP_LOGWARNING) {
        LOGW("RTMP日志(RTMP_LOGWARNING):%s", log)
    } else if (level == RTMP_LOGINFO) {
        LOGI("RTMP日志(RTMP_LOGINFO):%s", log)
    } else if (level == RTMP_LOGDEBUG) {
        LOGD("RTMP日志(RTMP_LOGDEBUG):%s", log)
    } else if (level == RTMP_LOGDEBUG2) {
        LOGD("RTMP日志(RTMP_LOGDEBUG2):%s", log)
    } else {
        LOGI("RTMP日志:%s", log);
    }
}

/**
 *
 * buf数组的范例:000000016764000AACB402D01E34A41408081B4284D40000000168EE06F2C0
 * 需要从buf中取出SPS和PPS
 * @param buf   一帧数据
 * @param len   一帧数据的长度
 * @param live_  直播结构体对象
 */
void saveSPS_PPS(signed char *buf, int len, Live *live_) {
    int separatorLength = 4;
    int spsIndex = 0;
    int ppsIndex = 0;
    for (int i = 0; i < len; ++i) {
        if (buf[i] == 0x00 && buf[i + 1] == 0x00 && buf[i + 2] == 0x00 && buf[i + 3] == 0x01) {
            if (buf[i + separatorLength] == 0x67) {
                spsIndex = i + separatorLength;
            } else if (buf[i + separatorLength] == 0x68) {
                ppsIndex = i + separatorLength;
                break;
            }
        }
    }

    if (spsIndex > 0 && ppsIndex > 0) {
        //计算出sps和pps的长度
        live_->sps_len = ppsIndex - spsIndex - separatorLength;
        live_->pps_len = len - ppsIndex;
        //根据sps和pps的长度分别申请这两个数组
        live_->sps = static_cast<char *>(malloc(live_->sps_len));
        live_->pps = static_cast<char *>(malloc(live_->pps_len));
        //根据sps和pps的索引和长度,分别进行内存拷贝,并存放到live结构体中
        memcpy(live_->sps, buf + spsIndex, live_->sps_len);
        memcpy(live_->pps, buf + ppsIndex, live_->pps_len);
    }

}
/**
 * 连接rtmp服务器
 * @param env
 * @param thiz
 * @param url_
 * @return
 */
jboolean connect(JNIEnv *env, jobject thiz, jstring url_) {
    const char *url = env->GetStringUTFChars(url_, JNI_FALSE);
    LOGI("url:%s", url)

    int ret;
    do {
        //为live结构体分配内存
        mLive = (Live *) malloc(sizeof(Live));
        //为live结构体设置初始值
        memset(mLive, 0, sizeof(Live));

        //1.申请内存
        mLive->rtmp = RTMP_Alloc();

        //设置rtmp日志级别为:全部
        RTMP_LogSetLevel(RTMP_LOGALL);
        //设置rtmp日志回调
        RTMP_LogSetCallback(logCallback);

        //2.初始化
        RTMP_Init(mLive->rtmp);
        //设置链接超时时间:10秒
        mLive->rtmp->Link.timeout = 10;

        //3.设置地址
        ret = RTMP_SetupURL(mLive->rtmp, (char *) url);
        if (!ret) {
            //如果失败,则break
            LOGE("设置地址失败")
            break;
        }
        LOGI("设置地址成功,地址:%s", url)

        //4.开始输出模式
        RTMP_EnableWrite(mLive->rtmp);

        //5.连接服务器
        ret = RTMP_Connect(mLive->rtmp, nullptr);
        if (!ret) {
            //如果失败,则break
            LOGE("连接服务器失败")
            break;
        }
        LOGI("连接服务器成功")

        //6.连接流
        ret = RTMP_Connect(mLive->rtmp, nullptr);
        if (!ret) {
            //如果失败,则break
            LOGE("连接流失败")
            break;
        }
        LOGI("连接流成功")


    } while (!ret);

    if (!ret && mLive) {
        RTMP_Close(mLive->rtmp);
        RTMP_Free(mLive->rtmp);
        mLive = nullptr;
    }


    env->ReleaseStringUTFChars(url_, url);
    return ret;
}
/**
 * 创建sps和pps的包
 * @return  sps和pps的包
 */
RTMPPacket *createSpsPpsPacket() {
    RTMPPacket *packet = static_cast<RTMPPacket *>(malloc(sizeof(RTMPPacket)));
    int bodySize = 16 + mLive->sps_len + mLive->pps_len;
    RTMPPacket_Alloc(packet, bodySize);

    packet->m_headerType = RTMP_PACKET_SIZE_LARGE;
    packet->m_packetType = RTMP_PACKET_TYPE_VIDEO;
    packet->m_hasAbsTimestamp = 0;
    packet->m_nChannel = 0x04;  //视频包的通道固定为0x04
    packet->m_nTimeStamp = 0;   //sps和pps的包,时间戳固定传0
    packet->m_nInfoField2 = mLive->rtmp->m_stream_id;
    packet->m_nBodySize = bodySize;
//    packet->m_nBytesRead//暂时用不到,不用传
//    packet->m_chunk//暂时用不到,不用传

    int i = 0;


    packet->m_body[i++] = 0x17;
    packet->m_body[i++] = 0x00;
    packet->m_body[i++] = 0x00;
    packet->m_body[i++] = 0x00;
    packet->m_body[i++] = 0x00;

    /*sps数据*/
    packet->m_body[i++] = 0x01;
    packet->m_body[i++] = mLive->sps[1];
    packet->m_body[i++] = mLive->sps[2];
    packet->m_body[i++] = mLive->sps[3];
    packet->m_body[i++] = 0XFF;

    /*sps*/
    packet->m_body[i++] = 0xE1;
    packet->m_body[i++] = ((mLive->sps_len) >> 8) & 0xFF; //sps长度的高八位
    packet->m_body[i++] = mLive->sps_len & 0xFF;          //sps长度的第八位
    memcpy(&(packet->m_body[i]), mLive->sps, mLive->sps_len);
    i += mLive->sps_len;

    /*pps*/
    packet->m_body[i++] = 0x01;
    packet->m_body[i++] = ((mLive->pps_len) >> 8) & 0xFF; //pps长度的高八位
    packet->m_body[i++] = mLive->pps_len & 0xFF;          //pps长度的第八位
    memcpy(&(packet->m_body[i]), mLive->pps, mLive->pps_len);

    return packet;
}

/**
 * 创建视频包
 * @param isIFrame      是否为I帧
 * @param frame         帧数据
 * @param frameLength   帧长度
 * @param timeMs        时间戳(单位:毫秒)
 * @return              视频包
 */
RTMPPacket *createVideoPacket(bool isIFrame,char *frame, long frameLength, long timeMs) {
    RTMPPacket *packet = static_cast<RTMPPacket *>(malloc(sizeof(RTMPPacket)));
    int bodySize = 9 + mLive->sps_len + mLive->pps_len;
    RTMPPacket_Alloc(packet, bodySize);

    packet->m_headerType = RTMP_PACKET_SIZE_LARGE;
    packet->m_packetType = RTMP_PACKET_TYPE_VIDEO;
    packet->m_hasAbsTimestamp = 0;
    packet->m_nChannel = 0x04;  //视频包的通道固定为0x04
    packet->m_nTimeStamp = timeMs;
    packet->m_nInfoField2 = mLive->rtmp->m_stream_id;
    packet->m_nBodySize = bodySize;
//    packet->m_nBytesRead//暂时用不到,不用传
//    packet->m_chunk//暂时用不到,不用传

    int i = 0;
    if(isIFrame){
        packet->m_body[i++] = 0x17;
    }else{
        packet->m_body[i++] = 0x27;
    }
    packet->m_body[i++] = 0x01;
    packet->m_body[i++] = 0x00;
    packet->m_body[i++] = 0x00;
    packet->m_body[i++] = 0x00;

    /*h264数据长度,总共占用4个字节,所以需要用位运算的方式,分别在4个字节上存储*/
    packet->m_body[i++] = (frameLength >> 24) & 0xff;   //h264数据长度的第一个字节值
    packet->m_body[i++] = (frameLength >> 16) & 0xff;   //h264数据长度的第二个字节值
    packet->m_body[i++] = (frameLength >> 8) & 0xff;    //h264数据长度的第三个字节值
    packet->m_body[i++] = frameLength & 0xff;           //h264数据长度的第四个字节值

    memcpy(&(packet->m_body[i]), frame, frameLength);

    return packet;
}

/**
 * 发送数据包
 * @param packet
 * @return
 */
int sendPacket(RTMPPacket *packet) {
    int ret = RTMP_SendPacket(mLive->rtmp, packet, 1);
    RTMPPacket_Free(packet);
    free(packet);
    return ret;
}

/**
 * 发送视频数据
 * @param env
 * @param thiz
 * @param data
 * @param timeMs
 * @return
 */
jboolean sendVideo(JNIEnv *env, jobject thiz, jbyteArray data, jlong timeMs) {
    //获取视频帧
    jbyte *frame = env->GetByteArrayElements(data, nullptr);
    jsize frameLength = env->GetArrayLength(data);

    if (frame[4] == 0x67 && (mLive->sps == nullptr || mLive->pps == nullptr)) {
        //如果此帧是sps,且live中的sps或pps为空,则需要保存sps和pps(sps和pps一定在同一组字节数组中出现,所以可以一起解析)
        saveSPS_PPS(frame, frameLength, mLive);
    } else {
        if (frame[4] == 0x65) {//当为I帧时,需要先放sps和pps,再发送I帧
            //1.先发送sps和pps
            RTMPPacket *spsPpsPacket = createSpsPpsPacket();
            bool sps_pps_result = sendPacket(spsPpsPacket);
            if(sps_pps_result){
                LOGI("sps和pps发送成功")
            }else{
                LOGE("sps和pps发送失败")
            }
            //2.再发送I帧
            RTMPPacket *IFramePacket = createVideoPacket(true,reinterpret_cast<char *>(frame), frameLength, timeMs);
            int iFrameResult = sendPacket(IFramePacket);
            if(iFrameResult){
                LOGI("I帧发送成功")
            }else{
                LOGE("I帧发送失败")
            }
        } else {
            //此帧可能是P帧或B帧,直接发送即可
            RTMPPacket *P_BFramePacket = createVideoPacket(false,reinterpret_cast<char *>(frame), frameLength, timeMs);
            int pbFrameResult = sendPacket(P_BFramePacket);
            if(pbFrameResult){
                LOGI("P帧或B帧发送成功")
            }else{
                LOGE("P帧或B帧发送失败")
            }
        }
    }

    //释放视频帧
    env->ReleaseByteArrayElements(data, frame, 0);
    return FALSE;
}

static JNINativeMethod methods[] = {
        {"connect",   "(Ljava/lang/String;)Z", reinterpret_cast<void *>(connect)},
        {"sendVideo", "([BJ)Z",                reinterpret_cast<void *>(sendVideo)}
};

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env;
    if (JNI_OK != vm->GetEnv(reinterpret_cast<void **> (&env), JNI_VERSION_1_4)) {
        LOGE("JNI_OnLoad could not get JNI env");
        return JNI_ERR;
    }

    jclass clazz = env->FindClass("com/alick/rtmplib/RtmpManager");  //获取Java NativeLib类

    //注册Native方法
    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof((methods)[0])) < 0) {
        LOGE("注册Natives方法失败");
        return JNI_ERR;
    }

    return JNI_VERSION_1_4;
}