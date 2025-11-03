/*
 * ===========================================================================================
 * = COPYRIGHT
 *          PAX Computer Technology(Shenzhen) CO., LTD PROPRIETARY INFORMATION
 *   This software is supplied under the terms of a license agreement or nondisclosure
 *   agreement with PAX Computer Technology(Shenzhen) CO., LTD and may not be copied or
 *   disclosed except in accordance with the terms in that agreement.
 *     Copyright (C) 2021-? PAX Computer Technology(Shenzhen) CO., LTD All rights reserved.
 * Description: // Detail description about the function of this module,
 *             // interfaces with the other modules, and dependencies.
 * Revision History:
 * Date                           Author                      Action
 * 2021/11/23                     YeHongbo                    Create
 * ===========================================================================================
 */

package com.pax.commonlib.utils.impl;

import com.pax.commonlib.utils.LogUtils;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * Get bytes charset
 */
public class CharsetUtils {
    private static final String TAG = "CharsetUtils";
    private static final int DEF_COMPARE_LEN = 16;

    public static final String UTF8 = "UTF-8";
    public static final String GBK = "GBK";

    private CharsetUtils() {
        // do nothing
    }

    /**
     * Get charset name.
     *
     * Now only support UTF-8 and GBK.
     * @param bytes Byte array
     * @return Charset name
     */
    public static String getCharsetName(byte[] bytes) {
        if (isUTF8(bytes)) {
            LogUtils.d(TAG, "Charset: " + UTF8);
            return UTF8;
        } else if (isGBK(bytes)) {
            LogUtils.d(TAG, "Charset: " + GBK);
            return GBK;
        }
        LogUtils.d(TAG, "Unknown charset");
        return UTF8;
    }

    /**
     * Get encode string.
     *
     * Now only support UTF-8 and GBK.
     * @param bytes Byte array
     * @return Encoded string
     */
    public static String getEncodeString(byte[] bytes) {
        try {
            return new String(bytes, getCharsetName(bytes));
        } catch (Exception e) {
            LogUtils.e(TAG, "", e);
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static boolean isUTF8(byte[] bytes) {
        return Arrays.equals(bytes,
                new String(bytes, StandardCharsets.UTF_8).getBytes(StandardCharsets.UTF_8));
    }

    public static boolean isGBK(byte[] bytes) {
        try {
            int len = Math.min(DEF_COMPARE_LEN, bytes.length);
            byte[] temp = Arrays.copyOf(bytes, len);
            return Arrays.equals(temp, new String(temp, GBK).getBytes(GBK));
        } catch (Exception e) {
            LogUtils.e(TAG,e.getMessage(),e);
            return false;
        }
    }
}
