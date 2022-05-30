//
// Created by Administrator on 2022/5/24.
//

#ifndef LIVERTMP_CXWYUV_H
#define LIVERTMP_CXWYUV_H

#include "libyuv/include/libyuv.h"


class CxwYuv {
public:

    //NV21转换成I420
    void nv21ToI420(jbyte *src_nv21_data, jint width, jint height, jbyte *dst_i420_data);

    //NV12转换成I420
    void nv12ToI420(jbyte *src_nv12_data, jint width, jint height, jbyte *dst_i420_data);

    //NV12转换成I420,并旋转
    void nv12ToI420Rotate(jbyte *src_nv12_data, jint width, jint height, jbyte *dst_i420_data, jint degree);

    //I420旋转
    void rotateI420(jbyte *src_i420_data, jint width, jint height, jbyte *dst_i420_data, jint degree);

    //I420镜像
    void i420Mirror(jbyte *src_i420_data, jint width, jint height, jbyte *dst_i420_data);

    //I420转换成NV12
    void i420ToNv12(jbyte *src_i420_data, jint width, jint height, jbyte *dst_nv12_data);


};

#endif //LIVERTMP_CXWYUV_H
