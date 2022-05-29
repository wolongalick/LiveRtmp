//
// Created by Administrator on 2022/5/24.
//

#include <jni.h>
#include "CxwYuv.h"

void CxwYuv::nv21ToI420(jbyte *src_nv21_data, jint width, jint height, jbyte *dst_i420_data) {
    jint src_y_size = width * height;
    jint src_u_size = (width >> 1) * (height >> 1);

    jbyte *src_nv21_y_data = src_nv21_data;
    jbyte *src_nv21_vu_data = src_nv21_data + src_y_size;

    jbyte *dst_i420_y_data = dst_i420_data;
    jbyte *dst_i420_u_data = dst_i420_data + src_y_size;
    jbyte *dst_i420_v_data = dst_i420_data + src_y_size + src_u_size;

    libyuv::NV21ToI420((const uint8_t *) src_nv21_y_data, width,
                       (const uint8_t *) src_nv21_vu_data, width,
                       (uint8_t *) dst_i420_y_data, width,
                       (uint8_t *) dst_i420_u_data, width >> 1,
                       (uint8_t *) dst_i420_v_data, width >> 1,
                       width, height);
}


void CxwYuv::nv12ToI420(jbyte *src_nv12_data, jint width, jint height, jbyte *dst_i420_data) {
    jint src_y_size = width * height;
    jint src_u_size = (width >> 1) * (height >> 1);

    jbyte *src_nv12_y_data = src_nv12_data;
    jbyte *src_nv12_vu_data = src_nv12_data + src_y_size;

    jbyte *dst_i420_y_data = dst_i420_data;
    jbyte *dst_i420_u_data = dst_i420_data + src_y_size;
    jbyte *dst_i420_v_data = dst_i420_data + src_y_size + src_u_size;

    libyuv::NV12ToI420((const uint8_t *) src_nv12_y_data,
                       width,
                       (const uint8_t *) src_nv12_vu_data,
                       width,
                       (uint8_t *) dst_i420_y_data,
                       width,
                       (uint8_t *) dst_i420_u_data,
                       width >> 1,
                       (uint8_t *) dst_i420_v_data,
                       width >> 1,
                       width,
                       height);
}


void CxwYuv::nv12ToI420Rotate(jbyte *src_nv12_data, jint width, jint height, jbyte *dst_i420_data, jint degree) {
    jint src_y_size = width * height;
    jint src_u_size = (width >> 1) * (height >> 1);

    jbyte *src_nv12_y_data = src_nv12_data;
    jbyte *src_nv12_vu_data = src_nv12_data + src_y_size;

    jbyte *dst_i420_y_data = dst_i420_data;
    jbyte *dst_i420_u_data = dst_i420_data + src_y_size;
    jbyte *dst_i420_v_data = dst_i420_data + src_y_size + src_u_size;

    libyuv::NV12ToI420Rotate((const uint8_t *) (src_nv12_y_data), width,
                             (const uint8_t *) (src_nv12_vu_data), width,
                             (uint8_t *) (dst_i420_y_data), width,
                             (uint8_t *) dst_i420_u_data, width >> 1,
                             (uint8_t *) dst_i420_v_data, width >> 1,
                             width, height,
                             (libyuv::RotationMode) degree
    );

}

void CxwYuv::rotateI420(jbyte *src_i420_data, jint width, jint height, jbyte *dst_i420_data, jint degree) {
    jint src_i420_y_size = width * height;
    jint src_i420_u_size = (width >> 1) * (height >> 1);

    jbyte *src_i420_y_data = src_i420_data;
    jbyte *src_i420_u_data = src_i420_data + src_i420_y_size;
    jbyte *src_i420_v_data = src_i420_data + src_i420_y_size + src_i420_u_size;

    jbyte *dst_i420_y_data = dst_i420_data;
    jbyte *dst_i420_u_data = dst_i420_data + src_i420_y_size;
    jbyte *dst_i420_v_data = dst_i420_data + src_i420_y_size + src_i420_u_size;

    if (degree == libyuv::kRotate90 || degree == libyuv::kRotate270) {
        // 选择 90或 270 的width和height在旋转之后是相反的
        libyuv::I420Rotate((const uint8_t *) src_i420_y_data, width,
                           (const uint8_t *) src_i420_u_data, width >> 1,
                           (const uint8_t *) src_i420_v_data, width >> 1,
                           (uint8_t *) dst_i420_y_data, height,
                           (uint8_t *) dst_i420_u_data, height >> 1,
                           (uint8_t *) dst_i420_v_data, height >> 1,
                           width, height,
                           (libyuv::RotationMode) degree);
    } else {
        libyuv::I420Rotate((const uint8_t *) src_i420_y_data, width,
                           (const uint8_t *) src_i420_u_data, width >> 1,
                           (const uint8_t *) src_i420_v_data, width >> 1,
                           (uint8_t *) dst_i420_y_data, width,
                           (uint8_t *) dst_i420_u_data, width >> 1,
                           (uint8_t *) dst_i420_v_data, width >> 1,
                           width, height,
                           (libyuv::RotationMode) degree);
    }
}


void CxwYuv::i420ToNv12(jbyte *src_i420_data, jint width, jint height, jbyte *dst_nv12_data) {
    jint src_y_size = width * height;
    jint src_u_size = (width >> 1) * (height >> 1);

    jbyte *src_nv12_y_data = dst_nv12_data;
    jbyte *src_nv12_uv_data = dst_nv12_data + src_y_size;

    jbyte *src_i420_y_data = src_i420_data;
    jbyte *src_i420_u_data = src_i420_data + src_y_size;
    jbyte *src_i420_v_data = src_i420_data + src_y_size + src_u_size;

    libyuv::I420ToNV12(
            (const uint8_t *) src_i420_y_data, width,
            (const uint8_t *) src_i420_u_data, width >> 1,
            (const uint8_t *) src_i420_v_data, width >> 1,
            (uint8_t *) src_nv12_y_data, width,
            (uint8_t *) src_nv12_uv_data, width,
            width, height);
}



