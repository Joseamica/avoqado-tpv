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

import android.util.Log;

import com.pax.commonlib.utils.ConvertUtils;
import com.pax.commonlib.utils.LogUtils;
import com.pax.jemv.clcommon.ByteArray;
import com.pax.jemv.clcommon.EMV_CAPK;
import com.pax.jemv.clcommon.EMV_REVOCLIST;
import com.pax.jemv.clcommon.OutcomeParam;
import com.pax.jemv.clcommon.RetCode;
import com.pax.jemv.mir.api.ClssMIRApi;
import com.paxsz.module.emv.constant.TagsTable;
import com.paxsz.module.emv.process.entity.IssuerRspData;
import com.paxsz.module.emv.process.entity.TransResult;
import com.paxsz.module.emv.process.enums.CvmResultEnum;
import com.paxsz.module.emv.process.enums.TransResultEnum;

public class ClssMirProcess extends ClssKernelProcess {
    private static final String TAG = "ClssMirProcess";
    public static final int MIR_PROTOCOL_01 = 0x01;
    public static final int MIR_PROTOCOL_02 = 0x02;
    private static final int TAG_USER_INTERFACE_REQ = 0xDF8116;
    private boolean needRevock = true;

    @Override
    public TransResult startTransProcess() {
        int ret = ClssMIRApi.Clss_CoreInit_MIR();
        if (ret != RetCode.EMV_OK) {
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                    CvmResultEnum.CVM_NO_CVM);
        }

        ret = ClssMIRApi.Clss_SetFinalSelectData_MIR(finalSelectData, finalSelectDataLen, transactionPath);
        if (ret != RetCode.EMV_OK) {
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                    CvmResultEnum.CVM_NO_CVM);
        }

        ByteArray dataExchangeArray = new ByteArray();
        ret = ClssMIRApi.Clss_DataExchange_MIR(finalSelectData, finalSelectDataLen, dataExchangeArray);
        if (ret == RetCode.EMV_OK && ConvertUtils.bcd2Str(dataExchangeArray.data).contains("5A")) {
            //While DataOut include 5A , application need to process data exchange to update 9F02
            // DF55 etc..  这个功能是用来给固定的卡号进行一个折扣优惠。return ok后，从出参中获取5A，如果存在，
            // 与提前设置卡号比较，如果相同，把交易金额减去一个固定值再进行交易
        }

        //set terminal parameters into kernel which will send to backend later in field55
        setParam();

        if (transactionPath.path == MIR_PROTOCOL_01) {
            ret = processProtocol1();
        } else if (transactionPath.path == MIR_PROTOCOL_02) {
            ret = processProtocol2();
            if (ret == RetCode.CLSS_TRY_AGAIN) {
                return new TransResult(ret, TransResultEnum.RESULT_TRY_AGAIN,
                        CvmResultEnum.CVM_NO_CVM);
            }
            checkTag82Value();
        } else {
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                    CvmResultEnum.CVM_NO_CVM);
        }

        ByteArray outcomeParamSet = new ByteArray(8);
        ByteArray userInterReqData = new ByteArray(22);
        getTlv(TagsTable.LIST, outcomeParamSet);
        getTlv(TAG_USER_INTERFACE_REQ, userInterReqData);

        //check Whether See phone
        if (((outcomeParamSet.data[1] & 0x10) == 0x10 || (outcomeParamSet.data[4] & 0x40) == 0x40)
                && userInterReqData.data[0] == 0x20) {
            //(Start B || UI Request on Restart Present) && Message Identifier==See phone
            if (clssStatusListener != null) {
                //clssStatusListener.onRemoveCard();
                LogUtils.e(TAG, "onRemoveCard");
                boolean ret2 = clssStatusListener.needSeePhone();
                if (!ret2) {
                    return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
                }
            }
            return new TransResult(ret, TransResultEnum.RESULT_CLSS_SEE_PHONE, CvmResultEnum.CVM_NO_CVM);
        }

        if (ret != RetCode.EMV_OK) {
            if (ret == RetCode.CLSS_USE_CONTACT) {
                return new TransResult(ret, TransResultEnum.RESULT_CLSS_TRY_ANOTHER_INTERFACE,
                        CvmResultEnum.CVM_NO_CVM);
            } else if (ret == RetCode.CLSS_DECLINE) {
                if (transParam.ucTransType == 0x20) {
                    //The decision to approve or decline the refund should be made by the Acquirer or merchant regardless of the type of cryptogram generated.
                    if (clssStatusListener != null) {
                        //clssStatusListener.onRemoveCard();
                        LogUtils.e(TAG, "onRemoveCard");
                    }
                    TransResult result = new TransResult(ret, TransResultEnum.RESULT_REQ_ONLINE, CvmResultEnum.CVM_NO_CVM);
                    updateCVMResult(result, outcomeParamSet.data[3]);
                    return result;
                }
            }
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
        }

        if (clssStatusListener != null) {
            //clssStatusListener.onReadCardOk();
            //clssStatusListener.onRemoveCard();
            LogUtils.e(TAG, "onReadCardOk");
            LogUtils.e(TAG, "onRemoveCard");
        }

        //check Outcome Parameter Set (DF8129) Status
        TransResult result = genTransResult(outcomeParamSet.data[0]);
        if (result.getTransResult() == TransResultEnum.RESULT_TRY_AGAIN) {
            return new TransResult(ret, TransResultEnum.RESULT_TRY_AGAIN, CvmResultEnum.CVM_NO_CVM);
        }
        //check CVM
        updateCVMResult(result, outcomeParamSet.data[3]);
        return result;
    }

    private void setParam() {
        /*
        //region Mandatory Parameters(Refer to PAX CLSS L2 API Programming Guide For MIR Library 3.3 part)
        setTlv(TagsTable.CURRENCY_CODE, clssParam.getTransCurrCode());
        setTlv(TagsTable.ACQUIRER_ID, null);
        setTlv(TagsTable.COUNTRY_CODE, clssParam.getCountryCode());
        setTlv(TagsTable.TERMINAL_TYPE, clssParam.getTermTypeBytes());

        setTlv(TagsTable.AMOUNT, clssParam.getAmount());
        setTlv(TagsTable.AMOUNT_OTHER, clssParam.getOtherAmount());
        setTlv(TagsTable.TRANS_TYPE, clssParam.getTransTypeBytes());  //trans type
        setTlv(TagsTable.TRANS_DATE, clssParam.getTransDate());             //trans date
        //endregion

        //region Conditional or Optional Parameters
        setTlv(TagsTable.TRANS_TIME, clssParam.getTransTime());             //trans time
        //trans number
        setTlv(TagsTable.TRNAS_NO, clssParam.getTransNo());

        setTlv(0xDF810C, clssParam.getTlvDF810CTag());
        setTlv(TagsTable.MERCHANT_CATEGORY_CODE, clssParam.getMerchantCategoryCode());
        setTlv(TagsTable.MERCHANT_ID, clssParam.getMerchantId());
        setTlv(TagsTable.TERMINAL_ID, clssParam.getTermId());
        setTlv(TagsTable.MERCHANT_NAME_LOCATION, null);

        //reader param
        setTlv(TagsTable.APP_VER, clssParam.getAppVersion());
        //setTlv(TagsTable.TERMINAL_CAPABILITY, clssParam.getTermCapability());
        setTlv(TagsTable.TRANS_CURRENCY_EXPONENT, clssParam.getTransCurrExpBytes());

        //aid param
        if (clssParam.getTransType() == 0x20) {
            setTlv(0xDF51, clssParam.getRefundFloorLimit());
        } else {
            setTlv(0xDF51, clssParam.getFloorLimitBytes());
        }
        setTlv(0xDF53, clssParam.getTransLimitBytes());
        setTlv(0xDF54, clssParam.getTransLimitBytes());
        setTlv(0xDF52, clssParam.getCvmLimitBytes());

        //TAC param
        if (clssParam.getTransType() == 0x20) { //refund required AAC
            setTlv(TagsTable.TAC_DENIAL, clssParam.getRefundTacDenial());
        } else {
            setTlv(TagsTable.TAC_DENIAL, clssParam.getTacDenial());
        }
        setTlv(TagsTable.TAC_DEFAULT, clssParam.getTacDefault());
        setTlv(TagsTable.TAC_ONLINE, clssParam.getTacOnline());

        //setTlv(0xDF55, new byte[]{(byte) 0xE8, 0x00});
        //Online PIN supported,CD-CVM allowed (for POS-terminals always 1),EMV contact mode supported
        setTlv(0xDF55, clssParam.getTermTPMCapability()); //MIR非接2020.03需求，需要修改为A8 00
        //Transaction Recovery Limit
        setTlv(0xDF56, clssParam.getTransRecoveryLimit());
        //Data Exchange Tag List.Recommended minimum set of tags:‘5A’.
        setTlv(0xDF8134, clssParam.getDataExchangeTagList());
        //endregion

         */
    }

    private void checkTag82Value() {
        ByteArray byteArray2 = new ByteArray();
        int ret = getTlv(0x82, byteArray2);
        if (ret != RetCode.EMV_OK) {
            /* // TODO
            setTlv(0x82, clssParam.getProtocol2Tag82());  //protocol2时tag82是空，但是55域需要上送2字节0x00
             */
        }

    }

    /**
     * Generate Trans Result
     *
     * @param status Outcome Parameter Set Byte 1 (index is 0)
     *               0001: APPROVED
     *               0010: DECLINED
     *               0011: ONLINE REQUEST
     *               0100: END APPLICATION
     *               0101: SELECT NEXT
     *               0110: TRY ANOTHER INTERFACE
     *               0111: TRY AGAIN
     *               1111: N/A
     *               Other values: RFU
     * @return Trans Result
     */
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

    /**
     * Update CVM Result
     *
     * @param result Trans Result
     * @param status Outcome Parameter Set Byte 4 (index is 3)
     *               0000: NO CVM
     *               0001: OBTAIN SIGNATURE
     *               0010: ONLINE PIN
     *               0011: CONFIRMATION CODE VERIFIED
     *               1110: CVM FAILED
     *               1111: N/A
     *               Other values: RFU
     */
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

    private int processProtocol1() {
        int ret = ClssMIRApi.Clss_InitiateApp_MIR();
        if (ret != RetCode.EMV_OK) {
            return ret;
        }
        ret = ClssMIRApi.Clss_ReadData_MIR();
        if (ret != RetCode.EMV_OK) {
            return ret;
        }
        needRevock = true;
        ClssMIRApi.Clss_DelAllCAPK_MIR();
        ClssMIRApi.Clss_DelAllRevocList_MIR();
        addCapkRevList();
        //TODO("ucExceptFileFlg参数由谁决定")Whether the PAN is found in the exception file list or not. 1-Yes 0-No
        /*
        ret = ClssMIRApi.Clss_TransProc_MIR(clssParam.getExceptFileFlag());
        */
        if (ret != RetCode.EMV_OK) {
            return ret;
        }
        return ClssMIRApi.Clss_CardAuth_MIR();
    }

    private int processProtocol2() {
        int ret = ClssMIRApi.Clss_TransInitiate2_MIR();
        if (ret != RetCode.EMV_OK) {
            return ret;
        }

        while (true) {
            ret = ClssMIRApi.Clss_TransProcess2_MIR();
            LogUtils.d(TAG, "Clss_TransProcess2_MIR: " + ret);
            if (ret == RetCode.EMV_OK) {
                break;
            } else if (ret == RetCode.CLSS_TRY_AGAIN) {
                clssStatusListener.onDetect2ndTap();
            } else {
                return ret;
            }
        }

        needRevock = false;
        ClssMIRApi.Clss_DelAllCAPK_MIR();
        addCapkRevList();
        return ClssMIRApi.Clss_TransComplete2_MIR();
    }

    @Override
    public TransResult completeTransProcess(IssuerRspData issuerRspData) {
        return new TransResult(RetCode.EMV_OK, TransResultEnum.RESULT_ONLINE_APPROVED, CvmResultEnum.CVM_NO_CVM);
    }

    @Override
    public int getTlv(int tag, ByteArray value) {
        byte[] bcdTag = ConvertUtils.intToByteArray(tag, ConvertUtils.EEndian.BIG_ENDIAN);
        int ret = ClssMIRApi.Clss_GetTLVDataList_MIR(bcdTag, (byte) bcdTag.length, value.length, value);
        LogUtils.e(TAG, "getTlv: " + bcdTag +","+ value);
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
        Log.d(TAG, "setTlv: " + bcdTag+","+ value);
        return ClssMIRApi.Clss_SetTLVDataList_MIR(buf, buf.length);
    }

    @Override
    protected int addCapkAndRevokeList(EMV_CAPK emvCapk, EMV_REVOCLIST emvRevoclist) {
        int ret = RetCode.EMV_OK;
        if (emvCapk != null) {
            ClssMIRApi.Clss_DelAllCAPK_MIR();
            ret = ClssMIRApi.Clss_AddCAPK_MIR(emvCapk);
            if (ret != RetCode.EMV_OK) {
                LogUtils.e(TAG, "Clss_AddCAPK_MIR ret = " + ret);
                return ret;
            }
        }

        if (needRevock && emvRevoclist != null) {
            ClssMIRApi.Clss_DelAllRevocList_MIR();
            return ClssMIRApi.Clss_AddRevocList_MIR(emvRevoclist);
        }
        return ret;
    }

    @Override
    public String getTrack2() {
        ByteArray track = new ByteArray();
        int ret = getTlv(TagsTable.TRACK2, track);
        if (ret == RetCode.EMV_OK) {
            return getTrack2FromTag57(ConvertUtils.bcd2Str(track.data, track.length));
        }
        return "";
    }

    @Override
    public boolean isNeedSecondTap(IssuerRspData issuerRspData) {
        return false;
    }
}
