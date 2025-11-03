package com.pax.commonlib.utils;

import java.text.NumberFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ConvertUtils {
    public static final String TIME_PATTERN_TRANS = "yyyyMMddHHmmss";
    public static final String TIME_PATTERN_DISPLAY = "yyyy/MM/dd HH:mm:ss";

    /**
     * Parses the string argument as a signed decimal {@code long}.safely
     * @param longStr longStr
     * @param safeValue safeValue
     * @return a long value
     */
    public static long parseLongSafe(String longStr, long safeValue) {
        if (longStr == null) {
            return safeValue;
        }
        try {
            return Long.parseLong(longStr);
        } catch (NumberFormatException e) {
            return safeValue;
        }
    }

    public static int parseIntSafe(String longStr, int safeValue) {
        if (longStr == null) {
            return safeValue;
        }
        try {
            return Integer.parseInt(longStr);
        } catch (NumberFormatException e) {
            return safeValue;
        }
    }

    /**
     * match specific Enum by name
     * @param cls enum type
     * @param name enum type name,for example:ETransType.REFUND.name()
     * @param <T> enum type
     * @return specific type enum
     */

    public static <T extends Enum<T>> T enumValue(Class<T> cls,String name){
        if (cls == null || name == null) {
            return null;
        }
        try {
            return Enum.valueOf(cls, name);
        }catch (Exception e){
            LogUtils.e(cls.getCanonicalName(),e.getLocalizedMessage());
        }
        return null;
    }

    /**
     * padding num with specific digit
     * @param num origin num
     * @param digit padding digit
     * @return padded num with digit
     */
    public static String getPaddedNumber(long num, int digit) {
        NumberFormat nf = NumberFormat.getInstance(Locale.US);
        nf.setGroupingUsed(false);
        nf.setMaximumIntegerDigits(digit);
        nf.setMinimumIntegerDigits(digit);
        return nf.format(num);
    }

    /**
     * convert old time pattern to new time pattern
     * @param formattedTime formattedTime
     * @param oldPattern oldPattern
     * @param newPattern newPattern
     * @return newPattern time string
     */
    public static String convert(String formattedTime, final String oldPattern, final String newPattern) {
        SimpleDateFormat sdf = new SimpleDateFormat(oldPattern, Locale.US);
        Date date;
        try {
            date = sdf.parse(formattedTime);
        } catch (ParseException e) {
            return formattedTime;
        }
        sdf = new SimpleDateFormat(newPattern, Locale.US);
        return sdf.format(date);
    }

    /**
     * convert current time with specific pattern
     * @param pattern pattern
     * @return newPattern time string
     */
    public static String convertCurrentTime( String pattern) {
        SimpleDateFormat dateFormat = new SimpleDateFormat(pattern, Locale.US);
        return dateFormat.format(new Date());
    }

    private static final char[] ARRAY_OF_CHAR = {'0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'};

    /**
     * convert bcd to string
     * @param b bcd bytes
     * @return string
     * @deprecated Use {@code bcd2Str} instead
     */
    @Deprecated

    public static String bcdToStr( byte[] b) {
        String result = "";
        if (b == null) {
            //LogUtils.e(new IllegalArgumentException("bcdToStr input arg is null"));
            return result;
        }

        StringBuilder localStringBuilder = new StringBuilder(b.length * 2);
        for (byte i : b) {
            localStringBuilder.append(ARRAY_OF_CHAR[((i & 0xF0) >>> 4)]);
            localStringBuilder.append(ARRAY_OF_CHAR[(i & 0xF)]);
        }

        return localStringBuilder.toString();
    }


    public static String bcd2Str( byte[] b, int length) {
        if (b == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(length * 2);
        for (int i = 0; i < length; ++i) {
            sb.append(ARRAY_OF_CHAR[((b[i] & 0xF0) >>> 4)]);
            sb.append(ARRAY_OF_CHAR[(b[i] & 0xF)]);
        }

        return sb.toString();
    }


    public static String bcd2Str( byte[] b) {
        if (b == null) {
            return "";
        }
        return bcd2Str(b,b.length);
    }


    public static byte[] strToBcdPaddingLeft(String str) {
        return strToBcd(str, EPaddingPosition.PADDING_LEFT);
    }



    public static byte[] strToBcdPaddingRight(String str) {
        return strToBcd(str, EPaddingPosition.PADDING_RIGHT);
    }
    /**
     * convert string to bcd bytes
     * @param str string
     * @param paddingPosition padding position
     * @return bcd bytes
     */

    public static byte[] strToBcd(String str, EPaddingPosition paddingPosition) {
        if ((str == null) || (paddingPosition == null)) {
            //LogUtils.e(new IllegalArgumentException("bcdToStr input arg is null"));
            return new byte[0];
        }
        String s = str;
        int len = s.length();
        if (len % 2 != 0) {
            if (paddingPosition == EPaddingPosition.PADDING_RIGHT)
                s = s + "0";
            else {
                s = "0" + s;
            }
            len = s.length();
        }
        if (len >= 2) {
            len /= 2;
        }
        byte[] bcd = new byte[len];
        byte[] strBytes = s.getBytes();

        for (int p = 0; p < strBytes.length / 2; p++) {
            bcd[p] = (byte) ((strByte2Int(strBytes[(2 * p)]) << 4) + strByte2Int(strBytes[(2 * p + 1)]));
        }

        return bcd;
    }

    public static int strByte2Int(byte b) {
        int j;
        if ((b >= 'a') && (b <= 'z')) {
            j = b - 'a' + 0x0A;
        } else {
            if ((b >= 'A') && (b <= 'Z'))
                j = b - 'A' + 0x0A;
            else
                j = b - '0';
        }
        return j;
    }

    public static byte[] intToByteArray(int paramInt, EEndian paramEEndian) throws IllegalArgumentException {
        if (paramEEndian == null) {
            throw new IllegalArgumentException("intToByteArray input arg is null");
        }

        byte[] arrayOfByte = new byte[4];
        int offset = 0;

        if (paramEEndian == EEndian.BIG_ENDIAN) {
            arrayOfByte[0] = (byte) (paramInt >>> 24 & 0xFF);
            arrayOfByte[1] = (byte) (paramInt >>> 16 & 0xFF);
            arrayOfByte[2] = (byte) (paramInt >>> 8 & 0xFF);
            arrayOfByte[3] = (byte) (paramInt & 0xFF);
        } else {
            arrayOfByte[0] = (byte) (paramInt & 0xFF);
            arrayOfByte[1] = (byte) (paramInt >>> 8 & 0xFF);
            arrayOfByte[2] = (byte) (paramInt >>> 16 & 0xFF);
            arrayOfByte[3] = (byte) (paramInt >>> 24 & 0xFF);
        }

        for (int j = 0; j < arrayOfByte.length; ++j) {
            if (arrayOfByte[j] != 0) {
                return Arrays.copyOfRange(arrayOfByte, j, arrayOfByte.length);
            }
        }

        return arrayOfByte;
    }

    public static List<String> getMaskIndex(String pattern){
        String regex = "(\\d+)";
        List<String> nums = new LinkedList<>();
        Pattern r = Pattern.compile(regex);
        Matcher m = r.matcher(pattern);
        while (m.find()){
            nums.add(m.group());
        }
        return nums;
    }

    public enum EPaddingPosition {
        PADDING_LEFT,
        PADDING_RIGHT,
    }

    public enum EEndian {
        LITTLE_ENDIAN,
        BIG_ENDIAN,
    }
}
