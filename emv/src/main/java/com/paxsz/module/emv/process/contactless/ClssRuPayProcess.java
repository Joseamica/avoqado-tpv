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
import com.pax.jemv.clcommon.ACType;
import com.pax.jemv.clcommon.ByteArray;
import com.pax.jemv.clcommon.EMV_CAPK;
import com.pax.jemv.clcommon.EMV_REVOCLIST;
import com.pax.jemv.clcommon.OnlineResult;
import com.pax.jemv.clcommon.OutcomeParam;
import com.pax.jemv.clcommon.RetCode;
import com.pax.jemv.entrypoint.api.ClssEntryApi;
import com.pax.jemv.rupay.api.ClssRuPayApi;
import com.paxsz.module.emv.constant.TagsTable;
import com.paxsz.module.emv.process.entity.IssuerRspData;
import com.paxsz.module.emv.process.entity.TransResult;
import com.paxsz.module.emv.process.enums.CvmResultEnum;
import com.paxsz.module.emv.process.enums.TransResultEnum;

import java.util.Arrays;

public class ClssRuPayProcess extends ClssKernelProcess {

    private static final String TAG = "ClssRuPayProcess";

    @Override
    public TransResult startTransProcess() {
        int ret = ClssRuPayApi.Clss_CoreInit_RuPay();
        if (ret != RetCode.EMV_OK) {
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                    CvmResultEnum.CVM_NO_CVM);
        }

        ret = selectApp();
        if (ret != RetCode.EMV_OK) {
            if (ret == RetCode.CLSS_RESELECT_APP) {
                return new TransResult(ret, TransResultEnum.RESULT_TRY_AGAIN,
                        CvmResultEnum.CVM_NO_CVM);
            } else {
                ret = RetCode.CLSS_FAILED;
                return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                        CvmResultEnum.CVM_NO_CVM);
            }
        }

        //set basic parameters.
        clssBaseParameterSet();

        ret = processRuPay();
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

    /**
     * set application's data of final select.
     * if the return code is CLSS_RESELECT_APP, application should delete current application from condidate list.
     * and then go back to select the next application.
     *
     * @return 0-success others-fail
     */
    private int selectApp() {
        int ret;
        ret = ClssRuPayApi.Clss_SetFinalSelectData_RuPay(finalSelectData, finalSelectDataLen);

        LogUtils.d("RUPAYTEST", "ret = " + ret + ", Clss_SetFinalSelectData_RuPay, finalSelectData = "
                + ConvertUtils.bcd2Str(finalSelectData));

        if (ret == RetCode.CLSS_RESELECT_APP) {
            ret = ClssEntryApi.Clss_DelCurCandApp_Entry();
            if (ret != RetCode.EMV_OK) {
                LogUtils.e(TAG, "Clss_DelCurCandApp_Entry, ret = " + ret);
                return ret;
            }
            return RetCode.CLSS_RESELECT_APP;
        }

        LogUtils.i(TAG, "SetFinalSelectData_RuPay, ret = " + ret);
        return ret;
    }

    /**
     * set basic parameters of RuPay by Barret 20181128
     */
    private void clssBaseParameterSet() {
        /*  // TODO
        //set terminal parameters
        setTlv(TagsTable.APP_VER, clssParam.getAppVersion());
        setTlv(TagsTable.TAC_DEFAULT, clssParam.getTacDefault());
        setTlv(TagsTable.TAC_DENIAL, clssParam.getTacDenial());
        setTlv(TagsTable.TAC_ONLINE, clssParam.getTacOnline());

        setTlv(TagsTable.RUPAY_FLOOR_LIMIT, clssParam.getFloorLimitBytes());
        setTlv(TagsTable.RUPAY_TRANS_LIMIT, clssParam.getTransLimitBytes());
        setTlv(TagsTable.RUPAY_CVM_LIMIT, clssParam.getCvmLimitBytes());

        //additional capability
        setTlv(TagsTable.ADDITIONAL_CAPABILITY, clssParam.getAddCapability());
        setTlv(TagsTable.TERMINAL_CAPABILITY, clssParam.getTermCapability());
        setTlv(TagsTable.ACQUIRER_ID, clssParam.getAcquirerId());
        setTlv(TagsTable.MERCHANT_CATEGORY_CODE, clssParam.getMerchantCategoryCode());
        setTlv(TagsTable.MERCHANT_NAME_LOCATION, clssParam.getMerchantNameLocation());
        //country code = 0356, india
        setTlv(TagsTable.COUNTRY_CODE, clssParam.getCountryCode());
        setTlv(TagsTable.TERMINAL_TYPE, clssParam.getTermTypeBytes());
        //currency code = 0356, india
        setTlv(TagsTable.CURRENCY_CODE, clssParam.getTransCurrCode());
        setTlv(TagsTable.TRANS_CURRENCY_EXPONENT, clssParam.getTransCurrExpBytes());
        setTlv(TagsTable.TERMINAL_ID, clssParam.getTermId());

        //set transaction parameters
        setTlv(TagsTable.AMOUNT, clssParam.getAmount());
        if (transParam.ulAmntOther > 0) {
            setTlv(TagsTable.AMOUNT_OTHER, clssParam.getOtherAmount());
        }

        setTlv(TagsTable.TRANS_TYPE, clssParam.getTransTypeBytes());
        setTlv(TagsTable.TRANS_DATE, clssParam.getTransDate());
        setTlv(TagsTable.TRANS_TIME, clssParam.getTransTime());

        setTlv(TagsTable.SERVICE_ID, clssParam.getServiceIdBytes());

        // Merchant Forced Transaction online - TVR Setting
        if (transParam.ucTransType == 0x29) {
            ByteArray tvr = new ByteArray(5);
            getTlv(TagsTable.TVR, tvr);
            if (enableDebugLog) {
                LogUtils.d(TAG, "get tvr: " + ConvertUtils.bcd2Str(tvr.data));
            }
            tvr.data[3] = (byte) (tvr.data[3] | 0x08);
            if (enableDebugLog) {
                LogUtils.d(TAG, "set tvr: " + ConvertUtils.bcd2Str(tvr.data));
            }
            setTlv(TagsTable.TVR, tvr.data);
        }

         */
    }

    /**
     * transaction process flow of rupay.
     * if the return code is CLSS_RESELECT_APP, application should delete current application from condidate list.
     * and then go back to select the next application.
     *
     * @return 0-success others-fail
     */
    private int processRuPay() {
        int ret;
        byte exceptFileFlg;

        //init the select application of rupay.
        ret = ClssRuPayApi.Clss_InitiateApp_RuPay();
        if (ret != RetCode.EMV_OK) {
            LogUtils.e(TAG, "Clss_InitiateApp_RuPay, ret = " + ret);
            if (ret == RetCode.CLSS_RESELECT_APP) {
                ret = ClssEntryApi.Clss_DelCurCandApp_Entry();
                if (ret != RetCode.EMV_OK) {
                    LogUtils.e(TAG, "Clss_DelCurCandApp_Entry, ret = " + ret);
                    return ret;
                }
                return RetCode.CLSS_RESELECT_APP;
            }
            LogUtils.e(TAG, "Clss_InitiateApp_RuPay, ret = " + ret);
            return ret;
        }

        //read application's data
        ret = ClssRuPayApi.Clss_ReadData_RuPay();
        if (ret != RetCode.EMV_OK) {
            LogUtils.e(TAG, "Clss_ReadData_RuPay, ret = " + ret);
            return ret;
        }

        //delet all revocable list
        ClssRuPayApi.Clss_DelAllRevocList_RuPay();
        ClssRuPayApi.Clss_DelAllCAPK_RuPay();
        addCapkRevList();

        //offline data authentication
        ret = ClssRuPayApi.Clss_CardAuth_RuPay();
        if (ret != RetCode.EMV_OK) {
            LogUtils.e(TAG, "Clss_CardAuth_RuPay, ret = " + ret);
            return ret;
        }

        //exceptFileFlg is a input parameter.
        exceptFileFlg = 0;
        ret = ClssRuPayApi.Clss_TransProc_RuPay(exceptFileFlg);
        if (ret != RetCode.EMV_OK) {
            LogUtils.e(TAG, "Clss_TransProc_RuPay, ret = " + ret);
            return ret;
        }

        //start RuPay's transaction. it will do below actions
        //1.first terminal action analysis. 2.transaction recovery. 3.first card action analysis.
        ret = ClssRuPayApi.Clss_StartTrans_RuPay();
        if (ret != RetCode.EMV_OK) {
            LogUtils.e(TAG, "Clss_StartTrans_RuPay, ret = " + ret);
            LogUtils.e(TAG, "Debug info: " + ClssRuPayApi.Clss_GetDebugInfo_RuPay());
            if (ret == RetCode.CLSS_RESELECT_APP) {
                ret = ClssEntryApi.Clss_DelCurCandApp_Entry();
                if (ret != RetCode.EMV_OK) {
                    LogUtils.e(TAG, "Clss_DelCurCandApp_Entry, ret = " + ret);
                    return ret;
                }
                return RetCode.CLSS_RESELECT_APP;
            }
            LogUtils.e(TAG, "Clss_StartTrans_RuPay, ret = " + ret);
            return ret;
        }

        return RetCode.EMV_OK;
    }

    /**
     * generate transaction result through get tag(0xDF8129)
     *
     * @return TransResult
     */
    private TransResult genTransResult() {
        ByteArray aucOutcomeParamSet = new ByteArray();
        TransResult result = new TransResult(RetCode.EMV_OK, TransResultEnum.RESULT_OFFLINE_DENIED,
                CvmResultEnum.CVM_NO_CVM);

        int ret = getTlv(TagsTable.LIST, aucOutcomeParamSet);
        if (ret != RetCode.EMV_OK) {
            result.setResultCode(ret);
            return result;
        }

        switch (aucOutcomeParamSet.data[0] & 0xF0) {
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

        switch (aucOutcomeParamSet.data[3] & 0xF0) {
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
        ByteArray scriptRstOut = new ByteArray();
        ACType acTypeOut = new ACType();

        byte onlineResult = issuerRspData.getOnlineResult();

        if (!Arrays.equals(issuerRspData.getRespCode(), "89".getBytes())) {
            onlineResult = OnlineResult.ONLINE_ABORT;
        }

        int ret = ClssRuPayApi.Clss_CompleteTrans_RuPay(onlineResult,
                issuerRspData.getScript(), issuerRspData.getScript().length,
                scriptRstOut, acTypeOut);
        if (ret != RetCode.EMV_OK) {
            LogUtils.e(TAG, "Clss_CompleteTrans_RuPay ret: " + ret);
            LogUtils.e(TAG, "Debug info: " + ClssRuPayApi.Clss_GetDebugInfo_RuPay());
            return new TransResult(ret, TransResultEnum.RESULT_ONLINE_CARD_DENIED,
                    CvmResultEnum.CVM_NO_CVM);
        }

        LogUtils.d(TAG, "script out: " + ConvertUtils.bcd2Str(scriptRstOut.data));
        LogUtils.d(TAG, "ac type out: " + acTypeOut.type);

        return new TransResult(RetCode.EMV_OK, TransResultEnum.RESULT_ONLINE_APPROVED, CvmResultEnum.CVM_NO_CVM);
    }

    @Override
    public int getTlv(int tag, ByteArray value) {
        byte[] bcdTag = ConvertUtils.intToByteArray(tag, ConvertUtils.EEndian.BIG_ENDIAN);

        int ret = ClssRuPayApi.Clss_GetTLVDataList_RuPay(bcdTag, (byte) bcdTag.length, value.length, value);
        LogUtils.d(TAG, "getTlv: " + bcdTag + "," + value);
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

        LogUtils.d(TAG, "setTlv: " + bcdTag + "," + value);

        return ClssRuPayApi.Clss_SetTLVDataList_RuPay(buf, buf.length);
    }

    @Override
    protected int addCapkAndRevokeList(EMV_CAPK emvCapk, EMV_REVOCLIST emvRevoclist) {
        int ret = RetCode.EMV_OK;
        if (emvCapk != null) {
            ClssRuPayApi.Clss_DelAllCAPK_RuPay();
            ret = ClssRuPayApi.Clss_AddCAPK_RuPay(emvCapk);
            if (ret != RetCode.EMV_OK) {
                LogUtils.e(TAG, "Clss_AddCAPK_RuPay ret = " + ret);
                return ret;
            }
        } else {
            LogUtils.e(TAG, "capk is null");
        }
        if (emvRevoclist != null) {
            ClssRuPayApi.Clss_DelAllRevocList_RuPay();
            ret = ClssRuPayApi.Clss_AddRevocList_RuPay(emvRevoclist);
            if (ret != RetCode.EMV_OK) {
                LogUtils.e(TAG, "Clss_AddRevocList_RuPay ret = " + ret);
                return ret;
            }
        } else {
            LogUtils.e(TAG, "revokeList is null");
        }
        return ret;
    }

    @Override
    public String getTrack2() {
        ByteArray track = new ByteArray();
        int ret;

        ret = getTlv(TagsTable.TRACK2, track);
        if (ret == RetCode.EMV_OK) {
            //AET-173
            return getTrack2FromTag57(ConvertUtils.bcd2Str(track.data, track.length));
        }
        return "";
    }

    @Override
    public boolean isNeedSecondTap(IssuerRspData issuerRspData) {
        // Check card status update
        if (issuerRspData != null && issuerRspData.getAuthData() != null
                && issuerRspData.getAuthData().length > 0) {
            // Card Status Update
            byte[] csu = Arrays.copyOfRange(issuerRspData.getAuthData(), 4, 8);
            // Issuer Proprietary Data
            byte[] ipd = Arrays.copyOfRange(issuerRspData.getAuthData(), 8, 16);

            LogUtils.i(TAG, "CSU: " + ConvertUtils.bcd2Str(csu));
            LogUtils.i(TAG, "IPD: " + ConvertUtils.bcd2Str(ipd));

            // Update Script Counters || Issuer Approves Online Transaction
            return (csu[2] & 0x40) != 0 || (csu[2] & 0x80) != 0;
        }
        return false;
    }
}
