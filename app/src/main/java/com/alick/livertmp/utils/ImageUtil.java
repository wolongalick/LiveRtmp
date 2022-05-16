package com.alick.livertmp.utils;

import com.alick.utilslibrary.BLog;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;

public class ImageUtil {
    //    nv21ToNV12
    public static void yuvToNv21(byte[] y, byte[] u, byte[] v, byte[] nv21, int stride, int height) {
        System.arraycopy(y, 0, nv21, 0, y.length);
        // 注意，若length值为 y.length * 3 / 2 会有数组越界的风险，需使用真实数据长度计算
        int length = y.length + u.length / 2 + v.length / 2;
        int uIndex = 0, vIndex = 0;
        for (int i = stride * height; i < length; i += 2) {
            nv21[i] = v[vIndex];
            nv21[i + 1] = u[uIndex];
            vIndex += 2;
            uIndex += 2;
        }
    }
//滴滴 小问题
//

    public static void nv21_rotate_to_90(byte[] nv21_data, byte[] nv21_rotated, int width, int height) {
        int y_size       = width * height;
        int buffser_size = y_size * 3 / 2;

        // Rotate the Y luma
        int i        = 0;
        int startPos = (height - 1) * width;
        for (int x = 0; x < width; x++) {
            int offset = startPos;
            for (int y = height - 1; y >= 0; y--) {
                nv21_rotated[i] = nv21_data[offset + x];
                i++;
                offset -= width;
            }
        }
        // Rotate the U and V color components
        i = buffser_size - 1;
        for (int x = width - 1; x > 0; x = x - 2) {
            int offset = y_size;
            for (int y = 0; y < height / 2; y++) {
                nv21_rotated[i] = nv21_data[offset + x];
                i--;
                nv21_rotated[i] = nv21_data[offset + (x - 1)];
                i--;
                offset += width;
            }
        }
    }

    //3/2    2   1
    public static void nv21toNV12(byte[] nv21, byte[] nv12) {
        int size = nv21.length;
        int len = size * 2 / 3;
        System.arraycopy(nv21, 0, nv12, 0, len);

        int i = len;
        while (i < size - 1) {
            nv12[i] = nv21[i + 1];
            nv12[i + 1] = nv21[i];
            i += 2;
        }
    }

    /**
     * NV21图像转RGB或BGR
     *
     * @param input  NV21格式图像数据
     * @param width  图像宽度
     * @param height 图像高度
     * @param output 输出图像缓冲区
     * @param isRGB  为{@code true}转为RGB图像,否则转为BGR图像
     */
    public static void NV21toRGBorBGR(byte[] input, int width, int height, byte[] output, boolean isRGB) {
        int nvOff            = width * height;
        int i, j, yIndex     = 0;
        int y, u, v;
        int r, g, b, nvIndex = 0;
        for (i = 0; i < height; i++) {
            for (j = 0; j < width; j++, ++yIndex) {
                nvIndex = (i / 2) * width + j - j % 2;
                y = input[yIndex] & 0xff;
                u = input[nvOff + nvIndex] & 0xff;
                v = input[nvOff + nvIndex + 1] & 0xff;

                // yuv to rgb
                r = y + ((351 * (v - 128)) >> 8);  //r
                g = y - ((179 * (v - 128) + 86 * (u - 128)) >> 8); //g
                b = y + ((443 * (u - 128)) >> 8); //b

                r = ((r > 255) ? 255 : Math.max(r, 0));
                g = ((g > 255) ? 255 : Math.max(g, 0));
                b = ((b > 255) ? 255 : Math.max(b, 0));
                if (isRGB) {
                    output[yIndex * 3 + 0] = (byte) b;
                    output[yIndex * 3 + 1] = (byte) g;
                    output[yIndex * 3 + 2] = (byte) r;
                } else {
                    output[yIndex * 3 + 0] = (byte) r;
                    output[yIndex * 3 + 1] = (byte) g;
                    output[yIndex * 3 + 2] = (byte) b;
                }
            }
        }
    }

    public static boolean saveYUV2File(byte[] bytes, File file) {
        OutputStream out = null;
        try {
            out = new BufferedOutputStream(new FileOutputStream(file));
            out.write(bytes, 0, bytes.length);
            out.flush();
            BLog.Companion.i("保存文件成功,路径:" + file.getAbsolutePath(), "cxw");
            return true;
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
        return false;
    }
}
