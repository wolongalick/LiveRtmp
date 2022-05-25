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

    //I420旋转
    void rotateI420(jbyte *src_i420_data, jint width, jint height, jbyte *dst_i420_data, jint degree);

    //I420转换成NV12
    void i420ToNv12(jbyte *src_i420_data, jint width, jint height, jbyte *src_nv12_data);


};

#endif //LIVERTMP_CXWYUV_H
