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
import com.pax.jemv.pure.api.ClssPUREApi;
import com.paxsz.module.emv.constant.TagsTable;
import com.paxsz.module.emv.process.entity.IssuerRspData;
import com.paxsz.module.emv.process.entity.TransResult;
import com.paxsz.module.emv.process.enums.CvmResultEnum;
import com.paxsz.module.emv.process.enums.TransResultEnum;

import java.util.Arrays;

public class ClssPureProcess extends ClssKernelProcess {

    private static final String TAG = "ClssPureProcess";
    private ByteArray outcomeParamSet;

    @Override
    public TransResult startTransProcess() {
        int ret = processFlow();
        outcomeParamSet = new ByteArray(8);
        ByteArray userInterReqData = new ByteArray(22);
        getTlv(TagsTable.LIST,outcomeParamSet);
        getTlv(0xDF8116,userInterReqData);
        if (ret != RetCode.EMV_OK) {
            if (ret == RetCode.CLSS_REFER_CONSUMER_DEVICE) {
                if (clssStatusListener != null) {
                    //clssStatusListener.onRemoveCard();
                    LogUtils.e(TAG, "onRemoveCard");
                    boolean ret2 = clssStatusListener.needSeePhone();
                    if (!ret2) {
                        return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                                CvmResultEnum.CVM_NO_CVM);
                    }
                }
                return new TransResult(ret, TransResultEnum.RESULT_CLSS_SEE_PHONE,
                        CvmResultEnum.CVM_NO_CVM);
            } else if (ret == RetCode.CLSS_DECLINE) {
                if (transParam.ucTransType == 0x20) {
                    if (clssStatusListener != null) {
                        //clssStatusListener.onRemoveCard();
                        LogUtils.e(TAG, "onRemoveCard");
                    }
                    TransResult result = new TransResult(RetCode.EMV_OK, TransResultEnum.RESULT_REQ_ONLINE,
                            CvmResultEnum.CVM_NO_CVM);
                    updateCVMResult(result, outcomeParamSet.data[3]);
                    return result;
                }
                return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                        CvmResultEnum.CVM_NO_CVM);
            } else {
                return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                        CvmResultEnum.CVM_NO_CVM);
            }
        }

        if (clssStatusListener != null) {
            //clssStatusListener.onReadCardOk();
            //clssStatusListener.onRemoveCard();
            LogUtils.e(TAG, "onReadCardOk");
            LogUtils.e(TAG, "onRemoveCard");
        }

        TransResult result = genTransResult(outcomeParamSet.data[0]);
        if (result.getTransResult() == TransResultEnum.RESULT_TRY_AGAIN) {
            result.setResultCode(RetCode.CLSS_RESELECT_APP);
            return result;
        }
        updateCVMResult(result, outcomeParamSet.data[3]);
        return result;
    }

    private int processFlow() {
        ClssPUREApi.Clss_CoreInit_PURE();
        int ret = ClssPUREApi.Clss_SetFinalSelectData_PURE(finalSelectData,finalSelectDataLen);
        if (ret != RetCode.EMV_OK){
            return ret;
        }
        setParam();
        ret = ClssPUREApi.Clss_InitiateApp_PURE(preProcInterInfo);
        if (ret != RetCode.EMV_OK){
            return ret;
        }
        ret = ClssPUREApi.Clss_ReadData_PURE();
        if (ret != RetCode.EMV_OK){
            return ret;
        }

        ClssPUREApi.Clss_DelAllCAPK_PURE();
        ClssPUREApi.Clss_DelAllRevocList_PURE();
        addCapkRevList();
        //TODO("check where to create exception file")
        ret = ClssPUREApi.Clss_StartTrans_PURE(checkExceptionFile());
        if (ret != RetCode.EMV_OK){
            return ret;
        }
        ClssPUREApi.Clss_CardAuth_PURE();
        return ret;
    }

    private void setParam() {
        /*
        //region Mandatory Parameters(Refer to PAX CLSS L2 API Programming Guide For PURE Library 8.1 part)
        setTlv(0xFF8130, clssParam.getAddTagObjList());//Additional Tag Object List
        setTlv(0xFF8131, clssParam.getMandatoryTagObjList());//Mandatory Tag Object List(MTOL)
        setTlv(0xFF8132, clssParam.getAuthTransDataTagObjList());//Authentication Transaction Data Tag Object List(ATDTOL)
        setTlv(0xFF8133, clssParam.getClssKernelCapability());//Contactless Kernel Capabilities
        setTlv(0xFF8134, clssParam.getClssPOSImplOption());//Contactless POS Implementation Option
        //Acquirer Identifier
        setTlv(TagsTable.ACQUIRER_ID, clssParam.getAcquirerId());
        setTlv(TagsTable.APP_VER, clssParam.getAppVersion());//Terminal Application Version Number
        setTlv(TagsTable.MERCHANT_CATEGORY_CODE, clssParam.getMerchantCategoryCode());//Merchant Category Code
        //Terminal Capabilities
        setTlv(TagsTable.TERMINAL_CAPABILITY, clssParam.getTermCapability());
        setTlv(TagsTable.TERMINAL_TYPE, clssParam.getTermTypeBytes());//Terminal Type
        setTlv(TagsTable.MERCHANT_NAME_LOCATION, clssParam.getMerchantNameLocation());//Merchant Name and Location

        //set Dynamic Transaction Parameters according Contactless POS Implementation Options(0xFF8134)
        setDTParams();
        //endregion

        //region Conditional or Optional Parameters
        setTlv(0xFF8135, clssParam.getAppAuthTransType());//Transaction Type value for Application Authentication Transaction
        setTlv(0xFF8136, clssParam.getDefaultDDOL());//Default DDOL(Option4 with the mandatory value 9F3704

        setTlv(TagsTable.TAC_DEFAULT, clssParam.getTacDefault());//Terminal Action Code - Default
        if (clssParam.getTransType() == 0x20) {
            //refund rquired AAC from EMV demo
            setTlv(TagsTable.TAC_DENIAL, clssParam.getRefundTacDenial());
        }else {
            setTlv(TagsTable.TAC_DENIAL, clssParam.getTacDenial());//Terminal Action Code - Denial
        }
        setTlv(TagsTable.TAC_ONLINE, clssParam.getTacOnline());//Terminal Action Code - Online
        if (transParam.ulAmntOther > 0) {
            setTlv(TagsTable.AMOUNT_OTHER, clssParam.getOtherAmount());//Amount, Other (Numeric)
        }
        //endregion

        //以下两个，文档里没有要求设置
        setTlv(TagsTable.ADDITIONAL_CAPABILITY, clssParam.getAddCapability());
        setTlv(TagsTable.TIMEOUT, clssParam.getTimeout());

         */
    }

    private void setDTParams() {
        /*  // TODO
        byte impOption = clssParam.getImpOption();
        if ((impOption & 0) == 0x00){
            //Transaction Parameters (no implementation option supported)
            //6F在调用Clss_SetFinalSelectData_PURE时，已设置进内核，可忽略
            //setTlv(0x6F,);//SELECT Response Message Data
            setTlv(TagsTable.AMOUNT, clssParam.getAmount());//Amount, Authorised (Numeric)
            setTlv(TagsTable.CURRENCY_CODE, clssParam.getTransCurrCode());//Transaction Currency Code
            setTlv(TagsTable.COUNTRY_CODE, clssParam.getCountryCode());//Terminal Country Code
            setTlv(TagsTable.TRANS_DATE, clssParam.getTransDate());//Transaction Date
            setTlv(TagsTable.TRANS_TYPE, clssParam.getTransTypeBytes());//Transaction Type
            //setTlv(0x9F37,);//Unpredictable Number,由内核设置
        }else if ((impOption & 0x80) == 0x80 ){
            //Transaction Parameters (implementation option 1 supported)
            //6F在调用Clss_SetFinalSelectData_PURE时，已设置进内核，可忽略
            //setTlv(0x6F,);//SELECT Response Message Data
            setTlv(TagsTable.AMOUNT, clssParam.getAmount());//Amount, Authorised (Numeric)
            setTlv(TagsTable.CURRENCY_CODE, clssParam.getTransCurrCode());//Transaction Currency Code
            setTlv(TagsTable.COUNTRY_CODE, clssParam.getCountryCode());//Terminal Country Code
            setTlv(TagsTable.TRANS_DATE, clssParam.getTransDate());//Transaction Date
            setTlv(TagsTable.TRANS_TYPE, clssParam.getTransTypeBytes());//Transaction Type
            //setTlv(0x9F37,);//Unpredictable Number,由内核设置
            setTlv(0xBF71, clssParam.getMemorySlotReadTemp());//Memory Slot Read Template(List of Data Object to retrieve)
        }else if ((impOption & 0x40) == 0x40){
            //Transaction Parameters (implementation option 2 supported)
            //6F在调用Clss_SetFinalSelectData_PURE时，已设置进内核，可忽略
            //setTlv(0x6F,);//SELECT Response Message Data
            setTlv(TagsTable.TRANS_TYPE, clssParam.getTransTypeBytes());//Transaction Type
            //setTlv(0x9F37,);//Unpredictable Number,由内核设置
            setTlv(0xBF70, clssParam.getMemorySlotUpdateTemp());//Memory Slot Update Template
        }else if ((impOption & 0x10) == 0x10 || (impOption & 0x90) == 0x90 || (impOption & 0x50) == 0x50){
            //Transaction Parameters for Application Authentication(implement option 4 supported || implement option 1 and option 4 supported || implement option 2 and option 4 supported)
            //6F在调用Clss_SetFinalSelectData_PURE时，已设置进内核，可忽略
            //setTlv(0x6F,);//SELECT Response Message Data
            setTlv(TagsTable.TRANS_TYPE, clssParam.getTransTypeBytes());//Transaction Type
            //setTlv(0x9F37,);//Unpredictable Number,由内核设置
        }

         */
    }

    /**
     * Whether the PAN is found in the exception file list or not.
     * @return 1: Yes 0: No
     */
    private byte checkExceptionFile() {
        byte ucExceptFileFlg = 0;
        ByteArray ucPan = new ByteArray(12);
        ByteArray ucPanSeq = new ByteArray(12);
        int panRet = getTlv(0x5A,ucPan);
        int panSeqRet = getTlv(0x5F34,ucPanSeq);
        if (panRet != RetCode.EMV_OK || panSeqRet != RetCode.EMV_OK){
            return ucExceptFileFlg;
        }
        byte[] bPan, bPanSeq;
        bPan = new byte[ucPan.length];
        System.arraycopy(ucPan.data, 0, bPan, 0, ucPan.length);
        bPanSeq = new byte[ucPanSeq.length];
        System.arraycopy(ucPanSeq.data, 0, bPanSeq, 0, ucPanSeq.length);
       /* TODO
        if (Arrays.equals(bPan, clssParam.getCheckExceptionFilePan()) && bPanSeq[0] == 0x00){
            ucExceptFileFlg = 1;
        }

        */
        return ucExceptFileFlg;
    }

    private TransResult genTransResult(byte status) {
        TransResult result = new TransResult(RetCode.EMV_OK,
                TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);

        switch (status & 0xF0) {
            case OutcomeParam.CLSS_OC_APPROVED:
                result.setTransResult(TransResultEnum.RESULT_OFFLINE_APPROVED);
                break;
            case OutcomeParam.CLSS_OC_ONLINE_REQUEST:
                result.setTransResult(TransResultEnum.RESULT_REQ_ONLINE);
                break;
            case OutcomeParam.CLSS_OC_SELECT_NEXT:
            case OutcomeParam.CLSS_OC_TRY_AGAIN:
                result.setTransResult(TransResultEnum.RESULT_TRY_AGAIN);
                break;
            case OutcomeParam.CLSS_OC_TRY_ANOTHER_INTERFACE:
                result.setTransResult(TransResultEnum.RESULT_CLSS_TRY_ANOTHER_INTERFACE);
                result.setResultCode(RetCode.CLSS_USE_CONTACT);
                break;
            case OutcomeParam.CLSS_OC_DECLINED:
            default:
                result.setTransResult(TransResultEnum.RESULT_OFFLINE_DENIED);
                break;
        }
        return result;
    }

    private void updateCVMResult(TransResult result, byte status) {
        switch (status & 0xF0) {
            case OutcomeParam.CLSS_OC_OBTAIN_SIGNATURE:
                result.setCvmResult(CvmResultEnum.CVM_SIG);
                break;
            case OutcomeParam.CLSS_OC_ONLINE_PIN:
                result.setCvmResult(CvmResultEnum.CVM_ONLINE_PIN);
                break;
            case OutcomeParam.CLSS_OC_ONLINE_REQUEST:
                result.setCvmResult(CvmResultEnum.CVM_CONSUMER_DEVICE);
                break;
            case OutcomeParam.CLSS_OC_NO_CVM:
            default:
                result.setCvmResult(CvmResultEnum.CVM_NO_CVM);
        }
    }

    @Override
    public TransResult completeTransProcess(IssuerRspData issuerRspData) {
        byte start = outcomeParamSet.data[1];
        byte[] script = issuerRspData.getScript();
        if (script == null){
            script = new byte[0];
        }
        boolean startBorD = ((start & 0xF0) == 0x10) || ((start & 0xF0) == 0x30);
        boolean needCompleteTrans = ((issuerRspData.getAuthData() != null && issuerRspData.getAuthData().length >0)
                || (script != null && script.length >0)) && startBorD;
        if (!needCompleteTrans){
            return new TransResult(RetCode.EMV_OK, TransResultEnum.RESULT_ONLINE_APPROVED,
                    CvmResultEnum.CVM_NO_CVM);
        }
        ClssPUREApi.Clss_CompleteTrans_PURE(script,script.length);
        //check Outcome Parameter Set (DF8129) Status
        TransResult result = genTransResult(outcomeParamSet.data[0]);
        if (result.getTransResult() == TransResultEnum.RESULT_TRY_AGAIN) {
            result.setResultCode(RetCode.CLSS_RESELECT_APP);
            return result;
        }
        updateCVMResult(result, outcomeParamSet.data[3]);
        return result;
    }

    @Override
    public int getTlv(int tag, ByteArray value) {
        byte[] bcdTag = ConvertUtils.intToByteArray(tag, ConvertUtils.EEndian.BIG_ENDIAN);
        int ret = ClssPUREApi.Clss_GetTLVDataList_PURE(bcdTag,(byte) bcdTag.length,value.length,value);
        LogUtils.d(TAG, "getTlv: "+bcdTag+","+ value);
        return ret;
    }

    @Override
    public int setTlv(int tag, byte[] value) {
        byte[] bcdTag = ConvertUtils.intToByteArray(tag, ConvertUtils.EEndian.BIG_ENDIAN);
        LogUtils.d(TAG, "setTlv: "+bcdTag+","+ value);
        byte[] buf = new byte[bcdTag.length + 1 + (value != null ? value.length : 0)];
        System.arraycopy(bcdTag,0,buf,0,bcdTag.length);
        if (value != null){
            buf[bcdTag.length] = (byte) value.length;
            System.arraycopy(value,0,buf,bcdTag.length+1,value.length);
        }else {
            buf[bcdTag.length] = 0x00;
        }
        return ClssPUREApi.Clss_SetTLVDataList_PURE(buf,buf.length);
    }

    @Override
    protected int addCapkAndRevokeList(EMV_CAPK emvCapk, EMV_REVOCLIST emvRevoclist) {
        int ret = RetCode.EMV_OK;
        if (emvCapk != null) {
            ClssPUREApi.Clss_DelAllCAPK_PURE();
            ret = ClssPUREApi.Clss_AddCAPK_PURE(emvCapk);
            if (ret != RetCode.EMV_OK) {
                LogUtils.e(TAG, "Clss_AddCAPK_JCB ret = " + ret);
                return ret;
            }
        }
        if (emvRevoclist != null) {
            ClssPUREApi.Clss_DelAllRevocList_PURE();
            return ClssPUREApi.Clss_AddRevocList_PURE(emvRevoclist);
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
