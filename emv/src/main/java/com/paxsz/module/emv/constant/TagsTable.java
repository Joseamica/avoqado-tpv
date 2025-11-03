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
 * 2021/06/15                     YeHongbo                    Create
 * ===========================================================================================
 */

package com.paxsz.module.emv.constant;

public class TagsTable {

    /** Tag's Request */
    public static final int CRYPTO = 0x9F27;
    public static final int APP_CRYPTO = 0x9F26;
    public static final int NUM_UNPREDICTABLE = 0x9F37;
    public static final int ATC = 0x9F36;
    public static final int TRANS_TYPE = 0x9C;
    public static final int INTERCHANGE_PROFILE = 0x82;
    public static final int VERIFICATION_CARDHOLDER = 0x9F34;
    public static final int TERMINAL_CAPABILITY = 0x9F33;
    public static final int TRANS_DATE = 0x9A;
    public static final int CURRENCY_CODE = 0x5F2A;
    public static final int AMOUNT_OTHER = 0x9F03;
    public static final int AMOUNT = 0x9F02;
    public static final int PAN_SEQ_NO = 0x5F34;
    public static final int TERMINAL_TYPE = 0x9F35;
    public static final int APPLICATION_DATA = 0x9F10;
    public static final int DEDICATED_FILE = 0x84;
    public static final int COUNTRY_CODE = 0x9F1A;
    public static final int APP_VER = 0x9F09;
    public static final int SERIAL_NUM = 0x9F1E;
    public static final int TVR = 0x95;
    public static final int APP_LABEL = 0x50;
    public static final int CARDHOLDER_NAME = 0x5F20;

    /** Tag's Other */
    public static final int PAN = 0x5A;
    public static final int APP_NAME = 0x9F12;
    public static final int AID = 0x9F06;
    public static final int CDOL2 = 0x8D;
    public static final int TRACK2 = 0x57;


    public static final int CARD_DATA = 0xDF8117;
    public static final int CVM_REQ = 0xDF8118;
    public static final int CVM_NO = 0xDF8119;
    public static final int DEF_UDOL = 0xDF811A;
    public static final int SEC = 0xDF811F;
    public static final int MAG_CVM_REQ = 0xDF811E;
    public static final int MAG_CVM_NO = 0xDF812C;
    public static final int TRANS_TIME = 0x9F21;

    //TAC Online
    public static final int TAC_DEFAULT = 0xDF8120;
    public static final int TAC_DENIAL = 0xDF8121;
    public static final int TAC_ONLINE = 0xDF8122;

    //limit  set for AID
    public static final int FLOOR_LIMIT = 0xDF8123;
    public static final int TRANS_LIMIT = 0xDF8124;
    public static final int TRANS_CVM_LIMIT = 0xDF8125;
    public static final int CVM_LIMIT = 0xDF8126;
    public static final int RUPAY_CVM_LIMIT = 0xDF4D;
    public static final int RUPAY_FLOOR_LIMIT = 0x9F1B;
    public static final int RUPAY_TRANS_LIMIT = 0xDF4C;

    public static final int MAX_TORN = 0xDF811D;

    public static final int KERNEL_CFG = 0xDF811B;

    public static final int CAPK_ID = 0x8F;
    public static final int CAPK_RID = 0x4F;

    public static final int PRO_ID = 0x9F5A;

    public static final int TRACK1 = 0x56;
    public static final int TRACK2_1 = 0x9F6B;

    public static final int TSI = 0x9B;
    public static final int ISSUER_CODE_TABLE_INDEX = 0x9F11;

    public static final int LIST = 0xDF8129;

    public static final int ACCOUNT_TYPE = 0x5F57;
    public static final int ACQUIRER_ID = 0x9F01;
    public static final int MERCHANT_CATEGORY_CODE = 0x9F15;
    public static final int MERCHANT_ID = 0x9F16;
    public static final int MERCHANT_NAME_LOCATION = 0x9F4E;
    public static final int TERMINAL_ID = 0x9F1C;
    public static final int MOB_SUP = 0x9F7E;

    public static final int BALANCE_BEFORE_GAC = 0xDF8104;
    public static final int BALANCE_AFTER_GAC = 0xDF8105;
    public static final int MESS_HOLD_TIME = 0xDF812D;

    public static final int DS_AC_TYPE = 0xDF8108;
    public static final int DS_INPUT_CARD = 0xDF60;
    public static final int DS_INPUT_TERMINAL = 0xDF8109;
    public static final int DS_ODS_INFO = 0xDF62;
    public static final int DS_ODS_READER = 0xDF810A;
    public static final int DS_ODS_TERMINAL = 0xDF63;

    public static final int FST_WRITE = 0xDF8110;
    public static final int READ = 0xDF8112;
    public static final int WIRTE_BEFORE_AC = 0xFF8102;
    public static final int WIRTE_AFTER_AC = 0xFF8103;
    public static final int TIMEOUT = 0xDF8127;

    public static final int ADDITIONAL_CAPABILITY = 0x9F40;
    public static final int DS_OPERATOR_ID = 0x9F5C;

    //Terminal Transaction Qualifiers
    public static final int TTQ = 0x9F66;
    //Card Terminal Qualifiers
    public static final int CTQ= 0x9F6C;

    //added by barret.
    public static final int TRANS_CURRENCY_EXPONENT = 0x5F36;
    public static final int AUTH_CODE = 0x89;
    public static final int AUTH_RESP_CODE = 0x8A;

    public static final int TRNAS_NO = 0x9F41;
    public static final int EXPIRE_DATE = 0x5F24;

    public static final int SERVICE_ID = 0xDF16;

    public static final int INTER_DEV_NUM = 0x9F1E;

    private TagsTable() {

    }
}
