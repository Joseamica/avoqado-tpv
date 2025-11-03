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

package com.paxsz.module.emv.process.contactless;

import com.pax.commonlib.utils.ConvertUtils;
import com.pax.commonlib.utils.LogUtils;
import com.pax.jemv.clcommon.ByteArray;
import com.pax.jemv.clcommon.EMV_CAPK;
import com.pax.jemv.clcommon.EMV_REVOCLIST;
import com.pax.jemv.clcommon.OutcomeParam;
import com.pax.jemv.clcommon.RetCode;
import com.pax.jemv.clcommon.TransactionPath;
import com.pax.jemv.entrypoint.api.ClssEntryApi;
import com.pax.jemv.jcb.api.ClssJCBApi;
import com.paxsz.module.emv.constant.TagsTable;
import com.paxsz.module.emv.process.entity.TransResult;
import com.paxsz.module.emv.process.enums.CvmResultEnum;
import com.paxsz.module.emv.process.enums.TransResultEnum;
import com.paxsz.module.emv.process.entity.IssuerRspData;


public class ClssJcbProcess extends ClssKernelProcess {
    private static final String TAG = "ClssJcbProcess";

    @Override
    public TransResult startTransProcess() {
        int ret = jcbFlow();
        if (ret == RetCode.CLSS_RESELECT_APP) {
            ret = ClssEntryApi.Clss_DelCurCandApp_Entry();
            if (ret != RetCode.EMV_OK) {
                return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                        CvmResultEnum.CVM_NO_CVM);
            }
            ret = RetCode.CLSS_TRY_AGAIN;
            return new TransResult(ret, TransResultEnum.RESULT_TRY_AGAIN, CvmResultEnum.CVM_NO_CVM);
        }

        if (ret != RetCode.EMV_OK) {
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                    CvmResultEnum.CVM_NO_CVM);
        }

        if (clssStatusListener != null) {
            //clssStatusListener.onReadCardOk();
            //clssStatusListener.onRemoveCard();
            LogUtils.e(TAG, "onReadCardOk");
            LogUtils.e(TAG, "onRemoveCard");
        }

        return genTransResult();
    }

    private int jcbFlow() {
        int ret = ClssJCBApi.Clss_CoreInit_JCB();
        LogUtils.i(TAG, "Clss_CoreInit_JCB = " + ret);
        if (ret != RetCode.EMV_OK) {
            return ret;
        }
        ret = ClssJCBApi.Clss_SetFinalSelectData_JCB(finalSelectData, finalSelectDataLen);
        LogUtils.i(TAG, "Clss_SetFinalSelectData_JCB = " + ret);

        setTransParamJcb();

        ret = ClssJCBApi.Clss_InitiateApp_JCB(transactionPath);
        if (ret != RetCode.EMV_OK) {
            LogUtils.e(TAG, "ClssJCBApi.Clss_InitiateApp_JCB(transactionPath) error, ret = " + ret);
            return ret;
        }

        LogUtils.i(TAG, "ClssJCBApi transactionPath = " + transactionPath.path);

        ret = ClssJCBApi.Clss_ReadData_JCB();
        if (ret != RetCode.EMV_OK) {
            LogUtils.e(TAG, "ClssJCBApi.Clss_ReadData_JCB() error, ret = " + ret);
            return ret;
        }
        ret = appTransProc((byte) transactionPath.path);
        LogUtils.i(TAG, "appTransProc ret = " + ret);
        if (ret != RetCode.EMV_OK) {
            LogUtils.e(TAG, "appTransProc(transactionPath) error, ret = " + ret);
            return ret;
        }
        return ret;
    }

    private int appTransProc(byte transPath) {
        int ret;
        byte ucExceptFileFlg = 0;

        if (transPath == TransactionPath.CLSS_JCB_EMV) {// 0x06)
            ClssJCBApi.Clss_DelAllRevocList_JCB();
            ClssJCBApi.Clss_DelAllCAPK_JCB();

            addCapkRevList();

            ret = ClssJCBApi.Clss_TransProc_JCB(ucExceptFileFlg);
            LogUtils.d(TAG, "debug info: " + ClssJCBApi.Clss_GetDebugInfo_JCB());
            if (ret != RetCode.EMV_OK) {
                LogUtils.e(TAG, "EMV Clss_TransProc_JCB error, ret = " + ret);
                return ret;
            }

            ret = ClssJCBApi.Clss_CardAuth_JCB();
            LogUtils.i(TAG, "ClssJCBApi.Clss_CardAuth_JCB ret = " + ret);
        } else {// 0x05)
            ret = ClssJCBApi.Clss_TransProc_JCB(ucExceptFileFlg);
            LogUtils.d(TAG, "debug info: " + ClssJCBApi.Clss_GetDebugInfo_JCB());
            LogUtils.i(TAG, "MAG or LEGACY ClssJCBApi.Clss_TransProc_JCB ret = " + ret);
        }

        ByteArray byteArray = new ByteArray();
        int iRet = ClssJCBApi.Clss_GetTLVDataList_JCB(new byte[]{(byte) 0x95}, (byte) 1, 10, byteArray);
        byte[] a = new byte[byteArray.length];
        System.arraycopy(byteArray.data, 0, a, 0, byteArray.length);
        String tvr = ConvertUtils.bcd2Str(a);
        LogUtils.i("Clss_TLV_JCB iRet 0x95", iRet + "");
        LogUtils.i("Clss_JCB TVR 0x95", tvr + "");
        return ret;
    }

    private void setTransParamJcb() {
        /*
        //9f02,9f03
        setTlv(TagsTable.AMOUNT, clssParam.getAmount());
        setTlv(TagsTable.AMOUNT_OTHER, clssParam.getOtherAmount());
        //9a,9f21,9c
        setTlv(TagsTable.TRANS_DATE, clssParam.getTransDate());
        setTlv(TagsTable.TRANS_TIME, clssParam.getTransTime());
        setTlv(TagsTable.TRANS_TYPE, clssParam.getTransTypeBytes());

        //9f01,9f4e,9f15,9f1a,9f35,5f2a,5f36 9f53 9f52
        setTlv(TagsTable.ACQUIRER_ID, null);
        setTlv(TagsTable.MERCHANT_NAME_LOCATION, null);
        setTlv(TagsTable.MERCHANT_CATEGORY_CODE, clssParam.getMerchantCategoryCode());
        setTlv(TagsTable.COUNTRY_CODE, clssParam.getCountryCode());
        setTlv(TagsTable.TERMINAL_TYPE, clssParam.getTermTypeBytes());
        setTlv(TagsTable.CURRENCY_CODE, clssParam.getTransCurrCode());
        setTlv(TagsTable.TRANS_CURRENCY_EXPONENT, clssParam.getTransCurrExpBytes());
        setTlv(0x9f53, clssParam.getTermInterchange());
        setTlv(0x9f52, clssParam.getTermCompatFlag());

        //Df8120,Df8121,Df8122 ff8130
        setTlv(TagsTable.TAC_DEFAULT, clssParam.getTacDefault());
        setTlv(TagsTable.TAC_DENIAL, clssParam.getTacDenial());
        setTlv(TagsTable.TAC_ONLINE, clssParam.getTacOnline());
        setTlv(0xFF8130, clssParam.getCombinationOption());

        setTlv(TagsTable.FLOOR_LIMIT, clssParam.getFloorLimitBytes());
        setTlv(TagsTable.TRANS_LIMIT, clssParam.getTransLimitBytes());
        setTlv(TagsTable.TRANS_CVM_LIMIT, clssParam.getCvmTransLimit());
        setTlv(TagsTable.CVM_LIMIT, clssParam.getCvmLimitBytes());
         */
    }

    private TransResult genTransResult() {
        byte[] szBuff = new byte[]{(byte) 0xDF, (byte) 0x81, 0x29};//Outcome Parameter
        ByteArray aucOutcomeParamSetJcb = new ByteArray();
        TransResult result = new TransResult(RetCode.EMV_OK, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);

        int ret = ClssJCBApi.Clss_GetTLVDataList_JCB(szBuff, (byte) 3, 24, aucOutcomeParamSetJcb);
        if (ret != RetCode.EMV_OK) {
            result.setTransResult(TransResultEnum.RESULT_OFFLINE_DENIED);
            return result;
        }

        switch (aucOutcomeParamSetJcb.data[0] & 0xF0) {
            case OutcomeParam.CLSS_OC_APPROVED:
                result.setTransResult(TransResultEnum.RESULT_OFFLINE_APPROVED);
                break;
            case OutcomeParam.CLSS_OC_ONLINE_REQUEST:
                result.setTransResult(TransResultEnum.RESULT_REQ_ONLINE);
                break;
            case OutcomeParam.CLSS_OC_TRY_ANOTHER_INTERFACE:
                result.setTransResult(TransResultEnum.RESULT_CLSS_TRY_ANOTHER_INTERFACE);
                result.setResultCode(RetCode.CLSS_USE_CONTACT);
                break;
            case OutcomeParam.CLSS_OC_DECLINED:
            default://CLSS_OC_END_APPLICATION
                result.setTransResult(TransResultEnum.RESULT_OFFLINE_DENIED);
                break;
        }

        switch (aucOutcomeParamSetJcb.data[3] & 0xF0) {
            case OutcomeParam.CLSS_OC_OBTAIN_SIGNATURE:
                result.setCvmResult(CvmResultEnum.CVM_SIG);
                LogUtils.i(TAG, "CVM = signature");
                break;
            case OutcomeParam.CLSS_OC_ONLINE_PIN:
                result.setCvmResult(CvmResultEnum.CVM_ONLINE_PIN);
                LogUtils.i(TAG, "CVM = online pin");
                break;
            case OutcomeParam.CLSS_OC_CONFIRM_CODE_VER:
                // Now the contactless kernel does not support Offline PIN temporarily, so this CVM
                // mode is equivalent to No CVM.
                result.setCvmResult(CvmResultEnum.CVM_OFFLINE_PIN);
                LogUtils.i(TAG, "CVM = CLSS_OC_CONFIRM_CODE_VER");
                break;
            case OutcomeParam.CLSS_OC_NO_CVM:
            default:
                result.setCvmResult(CvmResultEnum.CVM_NO_CVM);
                LogUtils.i(TAG, " default CVM = no cvm");
                break;
        }

        return result;
    }

    @Override
    public TransResult completeTransProcess(IssuerRspData issuerRspData) {
        return new TransResult(RetCode.EMV_OK, TransResultEnum.RESULT_ONLINE_APPROVED, CvmResultEnum.CVM_NO_CVM);
    }

    @Override
    public int getTlv(int tag, ByteArray value) {
        byte[] bcdTag = ConvertUtils.intToByteArray(tag, ConvertUtils.EEndian.BIG_ENDIAN);

        int ret = ClssJCBApi.Clss_GetTLVDataList_JCB(bcdTag, (byte) bcdTag.length, value.length, value);
        LogUtils.e(TAG, "getTlv: " +bcdTag +","+ value);
        return ret;
    }

    @Override
    public int setTlv(int tag, byte[] value) {
        byte[] bcdTag = ConvertUtils.intToByteArray(tag, ConvertUtils.EEndian.BIG_ENDIAN);
        byte[] buf = new byte[bcdTag.length + 1 + (value != null ? value.length : 0)];

        System.arraycopy(bcdTag, 0, buf, 0, bcdTag.length);
        if (value != null) {
            buf[bcdTag.length] = (byte) value.length;
            System.arraycopy(value, 0, buf, bcdTag.length + 1, value.length);
        } else {
            buf[bcdTag.length] = 0x00;
        }
        LogUtils.d(TAG, "setTlv: " +bcdTag+ "," + value);
        return ClssJCBApi.Clss_SetTLVDataList_JCB(buf, buf.length);
    }

    @Override
    protected int addCapkAndRevokeList(EMV_CAPK emvCapk, EMV_REVOCLIST emvRevoclist) {
        int ret = RetCode.EMV_OK;
        if (emvCapk != null) {
            ClssJCBApi.Clss_DelAllCAPK_JCB();
            ret = ClssJCBApi.Clss_AddCAPK_JCB(emvCapk);
            if (ret != RetCode.EMV_OK) {
                LogUtils.e(TAG, "Clss_AddCAPK_JCB ret = " + ret);
                return ret;
            }
        }
        if (emvRevoclist != null) {
            ClssJCBApi.Clss_DelAllRevocList_JCB();
            return ClssJCBApi.Clss_AddRevocList_JCB(emvRevoclist);
        }
        return ret;
    }

    @Override
    public String getTrack2() {
        ByteArray track = new ByteArray();
        int ret;
        if (transactionPath.path == TransactionPath.CLSS_JCB_MAG) {
            ret = getTlv(TagsTable.TRACK2_1, track);
            if (ret != RetCode.EMV_OK) {
                ret = getTlv(TagsTable.TRACK2, track);
            }
        } else {
            ret = getTlv(TagsTable.TRACK2, track);
        }

        if (ret == RetCode.EMV_OK) {
            //AET-173
            return ConvertUtils.bcd2Str(track.data, track.length).split("F")[0];
        }
        return "";
    }

    @Override
    public boolean isNeedSecondTap(IssuerRspData issuerRspData) {
        return false;
    }
}
