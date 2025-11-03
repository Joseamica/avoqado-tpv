package com.paxsz.module.emv.process.enums;

public enum AidsEnum{
    AMEX("A000000025"),
    MASTERCARD("A000000004"),
    VISA("A000000003"),
    JCB("A000000065"),
    DPAS("A000000152"),
    PBOC("A000000333"),
    RUPAY("A000000524"),
    UZCARD("A000000658"),
    PURE("A000000727"),
    EFT("A000000384"),
    CARNET("A000000724"),
    UNKNOWN("");

    private final String aid;

    AidsEnum(String aid) {
        this.aid = aid;
    }

    public String getAid() {
        return aid;
    }

    public static String getParcelAid(String aid) {
        if (aid.length() >= 10) {
            return aid.substring(0, 10);
        } else {
            return aid;
        }
    }

    public static AidsEnum getEnumByAid(String aid) {
        for (AidsEnum value : AidsEnum.values()) {
            if (value.getAid().equals(aid)) {
                return value;
            }
        }
        return AidsEnum.UNKNOWN;
    }

}
