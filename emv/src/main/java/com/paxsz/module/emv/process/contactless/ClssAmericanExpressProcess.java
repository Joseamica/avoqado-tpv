/*
 * ===========================================================================================
 *            COPYRIGHT
 *           PAX Computer Technology(Shenzhen) CO., LTD PROPRIETARY INFORMATION
 *    This software is supplied under the terms of a license agreement or nondisclosure
 *    agreement with PAX Computer Technology(Shenzhen) CO., LTD and may not be copied or
 *    disclosed except in accordance with the terms in that agreement.
 *      Copyright (C) 2020-? PAX Computer Technology(Shenzhen) CO., LTD All rights reserved.
 *  Description: // Detail description about the function of this module,
 *              // interfaces with the other modules, and dependencies.
 *  Revision History:
 *  Date                  Author	                 Action
 *   20201207  	           John Cai                 Create
 * ===========================================================================================
 */

package com.paxsz.module.emv.process.contactless;

import static com.pax.jemv.clcommon.RetCode.EMV_OK;

import android.util.Log;

import com.pax.commonlib.utils.ConvertUtils;
import com.pax.commonlib.utils.LogUtils;
import com.pax.jemv.amex.api.ClssAmexApi;
import com.pax.jemv.amex.model.CLSS_AEAIDPARAM;
import com.pax.jemv.amex.model.Clss_AddReaderParam_AE;
import com.pax.jemv.amex.model.Clss_ReaderParam_AE;
import com.pax.jemv.amex.model.ONLINE_PARAM;
import com.pax.jemv.amex.model.TransactionMode;
import com.pax.jemv.clcommon.ACType;
import com.pax.jemv.clcommon.ByteArray;
import com.pax.jemv.clcommon.Clss_ProgramID_II;
import com.pax.jemv.clcommon.Clss_ReaderParam;
import com.pax.jemv.clcommon.CvmType;
import com.pax.jemv.clcommon.EMV_CAPK;
import com.pax.jemv.clcommon.EMV_REVOCLIST;
import com.pax.jemv.clcommon.OnlineResult;
import com.pax.jemv.clcommon.RetCode;
import com.paxsz.module.emv.process.entity.IssuerRspData;
import com.paxsz.module.emv.process.entity.TransResult;
import com.paxsz.module.emv.process.enums.CvmResultEnum;
import com.paxsz.module.emv.process.enums.TransResultEnum;
import com.paxsz.module.emv.xmlparam.entity.clss.AmexAid;
import com.paxsz.module.emv.xmlparam.entity.clss.AmexParam;
import com.paxsz.module.emv.xmlparam.entity.clss.ExPayDRL;

import java.util.Arrays;
import java.util.List;

public class ClssAmericanExpressProcess extends ClssKernelProcess {
    private static final String TAG = "ClssAEProcess";
    private TransactionMode transMode;
    private AmexParam amexParam = new AmexParam();

    private int init() {
        int ret;
        if (clssStatusListener == null || !clssStatusListener.needSeePhone()) {
            ret = ClssAmexApi.Clss_CoreInit_AE();
            if (ret != EMV_OK) {
                Log.e(TAG, "Clss_CoreInit_AE ret = " + ret);
                return ret;
            }
        }
        setAmexParamFromEmvProcess();
        ret = ClssAmexApi.Clss_SetExtendFunction_AE(amexParam.getExFunction());
        if (ret != EMV_OK) {
            Log.e(TAG, "Clss_SetExtendFunction_AE ret = " + ret);
            return ret;
        }

        return ret;
    }

    private AmexAid getAidParam() {
        for (AmexAid aid : emvProcessParam.getAmexParam().getAidList()) {
            //finalSelectData[0] is aid len
            if (Arrays.equals(Arrays.copyOfRange(finalSelectData, 1, finalSelectData[0] + 1), aid.getAid()) //full match
                    || isSupportPartialAidMatch(aid)) {
                return aid;
            }
        }
        return null;
    }

    private boolean isSupportPartialAidMatch(AmexAid aid) {
        boolean flag = false;
        if ((aid.getPartialAidSelection() == 0) && (aid.getAid().length < finalSelectData[0])
                && (Arrays.equals(Arrays.copyOfRange(finalSelectData, 1, aid.getAid().length + 1), aid.getAid()))) {
            flag = true;
        }
        return flag;
    }

    private int setReaderParam() {
        //Set reader params
        int ret;
        Clss_ReaderParam_AE readerParamAe = new Clss_ReaderParam_AE();
        readerParamAe.aucUNRange = amexParam.getUnpredictableNumberRange();
        readerParamAe.ucTmSupOptTrans = amexParam.getSupportOptTrans();

        Clss_ReaderParam readerParam = new Clss_ReaderParam();

        readerParam.aucTmCap = amexParam.getTermCapability();
        readerParam.aucTmCapAd = amexParam.getExTermCapability();
        readerParam.ucTmType = amexParam.getTermType();
        readerParam.acquierId = amexParam.getAcquirerId();

        //readerParam.aucMchNameLoc = clssParam.getMerchantNameLocation();
        readerParam.aucMchNameLoc = emvProcessParam.getTermConfig().getMerchantNameAndLocationBytes();
        readerParam.usMchLocLen = (short) readerParam.aucMchNameLoc.length;
        readerParam.aucMerchantID = emvProcessParam.getTermConfig().getMerchantId();
        readerParam.aucMerchCatCode = emvProcessParam.getTermConfig().getMerchantCategoryCode();
        readerParam.aucTmCntrCode = emvProcessParam.getTermConfig().getTerminalCountryCode();
        readerParam.aucTmRefCurCode = emvProcessParam.getTermConfig().getTransReferenceCurrencyCode();
        readerParam.ucTmRefCurExp = emvProcessParam.getTermConfig().getTransReferenceCurrencyExponent();
        readerParam.ulReferCurrCon = emvProcessParam.getTermConfig().getConversionRatio();

        readerParam.aucTmID = amexParam.getTermId();
        readerParam.aucTmTransCur = emvProcessParam.getEmvTransParam().getTransCurrencyCode();
        readerParam.ucTmTransCurExp = emvProcessParam.getEmvTransParam().getTransCurrencyExponent();
        readerParamAe.stReaderParam = readerParam;
        ret = ClssAmexApi.Clss_SetReaderParam_AE(readerParamAe);
        LogUtils.e(TAG, "ClssAmexApi.Clss_SetReaderParam_AE = " + ret);
        if (ret != EMV_OK) {
            LogUtils.e(TAG, "Clss_SetReaderParam_AE ret = " + ret);
        }

        Clss_AddReaderParam_AE addReaderParamAe = new Clss_AddReaderParam_AE(amexParam.getTermTransCap(),
                amexParam.getDelayAuthSupport(), amexParam.getAucRFU());
        ret = ClssAmexApi.Clss_SetAddReaderParam_AE(addReaderParamAe);
        if (ret != EMV_OK) {
            LogUtils.e(TAG, "Clss_SetReaderParam_AE ret = " + ret);
        }
        return ret;
    }

    private int setTransData() {
        int ret;

        CLSS_AEAIDPARAM aeaidparam = new CLSS_AEAIDPARAM();
        aeaidparam.dDOL = amexParam.getdDOL();
        aeaidparam.FloorLimit = amexParam.getClssFloorLimit();
        aeaidparam.FloorLimitCheck = amexParam.getClssFloorLimitCheck();
        aeaidparam.TACDefault = amexParam.getTacDefault();
        aeaidparam.TACDenial = amexParam.getTacDenial();
        aeaidparam.TACOnline = amexParam.getTacOnline();
        aeaidparam.ucAETermCap = amexParam.getExPayRdCap();
        aeaidparam.tDOL = amexParam.gettDOL();
        aeaidparam.Version = amexParam.getVersion();
        aeaidparam.AcquierId = amexParam.getAcquirerId();
        ret = ClssAmexApi.Clss_SetAEAidParam_AE(aeaidparam);
        if (ret != EMV_OK) {
            LogUtils.e(TAG, "Clss_SetAEAidParam_AE ret = " + ret);
            return ret;
        }

        ret = ClssAmexApi.Clss_SetFinalSelectData_AE(finalSelectData, finalSelectDataLen);
        if (ret != EMV_OK) {
            LogUtils.e(TAG, "Clss_SetFinalSelectData_AE ret = " + ret);
            return ret;
        }

        ret = ClssAmexApi.Clss_SetTransData_AE(transParam, preProcInterInfo);
        if (ret != EMV_OK) {
            LogUtils.e(TAG, "Clss_SetTransData_AE ret = " + ret);
            return ret;
        }

        return ret;
    }

    private int addDRL() {
        int ret = EMV_OK;
        List<ExPayDRL> drls = emvProcessParam.getAmexParam().getAmexDRLs();
        if (drls == null) {
            return EMV_OK;
        }
        for (ExPayDRL drl : drls) {
            Clss_ProgramID_II programID = new Clss_ProgramID_II();
            programID.aucProgramId = drl.getProgramId();
            programID.aucRdClssFLmt = drl.getRdClssFloorLmt();
            programID.aucRdClssTxnLmt = drl.getRdClssTxnLmt();
            programID.aucRdCVMLmt = drl.getRdCVMLmt();
            programID.aucTermFLmt = drl.getTermFloorLmt();
            programID.ucAmtZeroNoAllowed = drl.getAmtZeroNoAllowed();
            programID.ucDynamicLimitSet = drl.getDynamicLimitSet();
            programID.ucRdClssFLmtFlg = drl.getRdClssFloorLmtFlg();
            programID.ucRdClssTxnLmtFlg = drl.getRdClssTxnLmtFlg();
            programID.ucRdCVMLmtFlg = drl.getRdCVMLmtFlg();
            programID.ucPrgramIdLen = drl.getProgramIdLen();
            programID.ucStatusCheckFlg = drl.getStatusCheckFlg();
            programID.ucTermFLmtFlg = drl.getTermFloorLmtFlg();
            LogUtils.e(TAG, "Clss_AddDRL_AE ret = " + ret);
            ret = ClssAmexApi.Clss_AddDRL_AE(programID);
            if (ret != EMV_OK) {
                LogUtils.e(TAG, "Clss_AddDRL_AE ret = " + ret);
                return ret;
            }
        }
        return ret;
    }

    @Override
    public TransResult startTransProcess() {
        //initCore
        int ret = init();
        if (ret != EMV_OK) {
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
        }

        //read param
        ret = setReaderParam();
        if (ret != EMV_OK) {
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
        }

        //set transaction data
        ret = setTransData();
        if (ret != EMV_OK) {
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
        }

        //GPO process
        transMode = new TransactionMode();
        ret = ClssAmexApi.Clss_Proctrans_AE(transMode);
        if (ret != EMV_OK) {
            Log.e(TAG, "Clss_Proctrans_AE ret = " + ret);
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
        }

        //read record
        ret = ClssAmexApi.Clss_ReadRecord_AE(new ByteArray());
        if (ret != EMV_OK) {
            Log.e(TAG, "Clss_ReadRecord_AE ret = " + ret);
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
        }

        //add capk and revoc list
        ret = addCapkRevList();
        if (ret != EMV_OK) {
            Log.e(TAG, "addCapkRevList ret = " + ret);
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
        }

        ret = ClssAmexApi.Clss_CardAuth_AE();
        if (ret != EMV_OK) {
            LogUtils.e(TAG, "Clss_CardAuth_AE ret = " + ret);
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
        }

        ret = addDRL();
        if (ret != EMV_OK) {
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
        }

        ACType acType = new ACType();
        ret = startTransaction(acType);
        if (ret != EMV_OK) {
            if (ret == RetCode.CLSS_REFER_CONSUMER_DEVICE) {
                return new TransResult(ret, TransResultEnum.RESULT_CLSS_SEE_PHONE, CvmResultEnum.CVM_CONSUMER_DEVICE);
            }
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
        }

        if (clssStatusListener != null) {
            LogUtils.e(TAG, "Execute onReadOK");
            LogUtils.e(TAG, "Execute onRemoveCard");
            //clssStatusListener.onReadCardOk(); // TODO
            //clssStatusListener.onRemoveCard(); // TODO
        }

        return processCVM(acType);
    }


    private int startTransaction(ACType acType) {
        int ret;
        ByteArray adviceFlg = new ByteArray();
        ByteArray onlineFlg = new ByteArray();
        ret = ClssAmexApi.Clss_StartTrans_AE(amexParam.getSupportFullOnline(), adviceFlg, onlineFlg);
        acType.type = ACType.AC_AAC;
        boolean isOnline = onlineFlg.data[0] == 1;
        LogUtils.e(TAG, "StartTrans ret = " + ret);
        int ret1 = ClssAmexApi.Clss_GetDebugInfo_AE();
        LogUtils.e(TAG, "Start trans debug info: " + ret1);
        LogUtils.e(TAG, "advice: " + ConvertUtils.bcd2Str(Arrays.copyOfRange(
                adviceFlg.data, 0, adviceFlg.length)) + ", online: " + onlineFlg.data[0]);

//        if (ret == RetCode.CLSS_REFER_CONSUMER_DEVICE) {
//            //clssStatusListener.onRemoveCard();
//            LogUtils.e(TAG, "return CLSS_REFER_CONSUMER_DEVICE clss express process");
//            return ret;
//        } else
        if (ret == EMV_OK && isOnline) {
            LogUtils.e(TAG, "acType.type = ACType.AC_ARQC;");
            acType.type = ACType.AC_ARQC;
        } else if (ret == EMV_OK) {
            LogUtils.e(TAG, "acType.type = ACType.AC_TC;");
            acType.type = ACType.AC_TC;
            return ret;
        } else {
            //clssStatusListener.onRemoveCard(); // TODO
            LogUtils.e(TAG, "return onRemoveCard()");
            return ret;
        }

        //if transaction needs to go online, check the delayed authorisation flag
        Clss_AddReaderParam_AE param = new Clss_AddReaderParam_AE(new byte[4], (byte) 0, new byte[27]);
        ret = ClssAmexApi.Clss_GetAddReaderParam_AE(param);
        if (ret != EMV_OK) {
            LogUtils.e(TAG, "Clss_GetAddReaderParam_AE ret = " + ret);
            LogUtils.e(TAG, "return");
            return ret;
        }
        if (param.ucDelayAuthFlag == 1) {
            ONLINE_PARAM onlineParam = new ONLINE_PARAM();
            onlineParam.aucRspCode = "00".getBytes();
            ret = ClssAmexApi.Clss_CompleteTrans_AE((byte) OnlineResult.ONLINE_APPROVE,
                    (byte) TransactionMode.AE_DELAYAUTH_PARTIALONLINE, onlineParam, adviceFlg);
            if (ret != EMV_OK) {
                LogUtils.e(TAG, "Delayed Auth failed, Clss_CompleteTrans_AE ret: " + ret);
                return ret;
            }
        }

        return ret;
    }

    private TransResult processCVM(ACType acType) {
        TransResult result = new TransResult(EMV_OK, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);

        /*
        if (checkAmountCtlssExceedsLimitTrx()) {
            LogUtils.e(TAG, "checkAmountCtlssExceedsLimitTrx");
           result.setTransResult(TransResultEnum.RESULT_CLSS_TRY_ANOTHER_INTERFACE);
            result.setResultCode(RetCode.CLSS_USE_CONTACT);
           return result;
        }
         */

        byte cvmType = ClssAmexApi.Clss_GetCvmType_AE();
        LogUtils.e(TAG, "cvmType =" + cvmType);
        LogUtils.e(TAG, "acType =" + acType);
        if (acType.type == ACType.AC_AAC) {
            result.setTransResult(TransResultEnum.RESULT_OFFLINE_DENIED);
        } else if (acType.type == ACType.AC_ARQC) {
            result.setTransResult(TransResultEnum.RESULT_REQ_ONLINE);
        } else if (acType.type == ACType.AC_TC) {
            result.setTransResult(TransResultEnum.RESULT_OFFLINE_APPROVED);
        } else {
            LogUtils.e(TAG, "acType Unkonwn = " + acType.type);
            result.setResultCode(RetCode.CLSS_PARAM_ERR);
            return result;
        }

        if (cvmType < 0) {
            LogUtils.e(TAG, "Clss_GetCvmType_AE ret = " + cvmType);
            if (cvmType == RetCode.CLSS_DECLINE) {
                result.setTransResult(TransResultEnum.RESULT_OFFLINE_DENIED);
                result.setResultCode(RetCode.CLSS_DECLINE);
            }
            return result;
        }
        /*
        if (cvmType == CvmType.RD_CVM_NO) {
            result.setCvmResult(CvmResultEnum.CVM_NO_CVM);
        } else if (cvmType == CvmType.RD_CVM_SIG) {
            result.setCvmResult(CvmResultEnum.CVM_SIG);
        } else if (cvmType == CvmType.RD_CVM_ONLINE_PIN) {
            result.setCvmResult(CvmResultEnum.CVM_ONLINE_PIN);
        } else {
            LogUtils.e(TAG, "cvmType Unkonwn = " + cvmType);
            result.setResultCode(RetCode.CLSS_PARAM_ERR);
            return result;
        }
         */

        switch (cvmType) {
            case CvmType.RD_CVM_NO:
                result.setCvmResult(CvmResultEnum.CVM_NO_CVM);
                LogUtils.e(TAG, "RD_CVM_NO");
                break;
            case CvmType.RD_CVM_SIG:
                result.setCvmResult(CvmResultEnum.CVM_SIG);
                LogUtils.e(TAG, "RD_CVM_SIG");
                break;
            case CvmType.RD_CVM_ONLINE_PIN:
                result.setCvmResult(CvmResultEnum.CVM_ONLINE_PIN);
                LogUtils.e(TAG, "RD_CVM_ONLINE_PIN");
                break;
            case CvmType.RD_CVM_CONSUMER_DEVICE:
                result.setCvmResult(CvmResultEnum.CVM_CONSUMER_DEVICE);
                LogUtils.e(TAG, "RD_CVM_CONSUMER_DEVICE");
                break;
            default:
                LogUtils.e(TAG, "cvmType Unkonwn = " + cvmType);
                result.setResultCode(RetCode.CLSS_PARAM_ERR);
                break;
        }

        return result;
    }

    private void setAmexParamFromEmvProcess() {
        AmexAid amexAid = getAidParam();
        if (amexAid == null) {
            return;
        }
        amexParam.setAppName(amexAid.getAppName());
        amexParam.setAid(amexAid.getAid());
        amexParam.setSelFlag(amexAid.getSelFlag());
        amexParam.setTacDefault(amexAid.getTacDefault());
        amexParam.setTacDenial(amexAid.getTacDenial());
        amexParam.setTacOnline(amexAid.getTacOnline());
        amexParam.setTermCapability(amexAid.getTermCapability());
        amexParam.setTermAddCapability(amexAid.getTermAddCapability());
        amexParam.setUnpredictableNumberRange(amexAid.getUnpredictableNumberRange());
        amexParam.setUnRange(amexAid.getUnRange());
        amexParam.setSupportOptTrans(amexAid.getSupportOptTrans());
        amexParam.setTermTransCap(amexAid.getTermTransCap());
        amexParam.setDelayAuthSupport(amexAid.getDelayAuthSupport());
        amexParam.setdDOL(amexAid.getdDOL());
        amexParam.settDOL(amexAid.gettDOL());
        amexParam.setClssFloorLimit(amexAid.getClssFloorLimit());
        amexParam.setClssFloorLimitCheck(amexAid.getClssFloorLimitCheck());
        amexParam.setExPayRdCap(amexAid.getExPayRdCap());
        amexParam.setExFunction(amexAid.getExFunction());
        amexParam.setAucRFU(amexAid.getAucRFU());
        amexParam.setSupportFullOnline(amexAid.getSupportFullOnline());
        amexParam.setExTermCapability(amexAid.getExTermCapability());
        amexParam.setReferCurrExp(amexAid.getReferCurrExp());
        amexParam.setTermId(amexAid.getTermId());
        amexParam.setCvmLimit(amexAid.getCvmLimit());
        amexParam.setCvmLimitFlag(amexAid.getCvmLimitFlag());
        amexParam.setTransLimit(amexAid.getTransLimit());
        amexParam.setTransLimitFlag(amexAid.getTransLimitFlag());
        amexParam.setFloorLimit(amexAid.getFloorLimit());
        amexParam.setFloorLimitFlag(amexAid.getFloorLimitFlag());
        amexParam.setAcquirerId(amexAid.getAcquirerId());
        amexParam.setVersion(amexAid.getVersion());
        amexParam.setTermType(amexAid.getTermType());

    }

    @Override
    public TransResult completeTransProcess(IssuerRspData issuerRspData) {
        int ret;
        ONLINE_PARAM onlineParam = new ONLINE_PARAM();
        onlineParam.aucRspCode = issuerRspData.getRespCode();
        onlineParam.aucAuthCode = issuerRspData.getAuthCode();
        onlineParam.aucIAuthData = issuerRspData.getAuthData();
        onlineParam.nAuthCodeLen = issuerRspData.getAuthCode() != null ? issuerRspData.getAuthCode().length : 0;
        onlineParam.nIAuthDataLen = issuerRspData.getAuthData() != null ? issuerRspData.getAuthData().length : 0;
        onlineParam.aucScript = issuerRspData.getScript();
        ret = ClssAmexApi.Clss_CompleteTrans_AE(issuerRspData.getOnlineResult(), (byte) transMode.mode,
                onlineParam, new ByteArray());
        if (ret != EMV_OK) {
            return new TransResult(ret, TransResultEnum.RESULT_ONLINE_CARD_DENIED, CvmResultEnum.CVM_NO_CVM);
        }
        return new TransResult(EMV_OK, TransResultEnum.RESULT_ONLINE_APPROVED, CvmResultEnum.CVM_NO_CVM);
    }

    @Override
    public int getTlv(int tag, ByteArray value) {
        int ret = ClssAmexApi.Clss_GetTLVData_AE((short) tag, value);
        Log.e(TAG, "getTlv: " + tag + " " + value);
        return ret;
    }

    @Override
    public int setTlv(int tag, byte[] value) {
        Log.e(TAG, "setTlv: " + tag + value);
        return ClssAmexApi.Clss_SetTLVData_AE((short) tag, value, value.length);
    }

    @Override
    protected int addCapkAndRevokeList(EMV_CAPK emvCapk, EMV_REVOCLIST emvRevoclist) {
        int ret = EMV_OK;
        if (emvCapk != null) {
            ClssAmexApi.Clss_DelAllCAPK_AE();
            ret = ClssAmexApi.Clss_AddCAPK_AE(emvCapk);
            if (ret != EMV_OK) {
                LogUtils.e(TAG, "Clss_AddCAPK_AE ret = " + ret);
                return ret;
            }
        }
        if (emvRevoclist != null) {
            ClssAmexApi.Clss_DelAllRevocList_AE();
            return ClssAmexApi.Clss_AddRevocList_AE(emvRevoclist);
        }
        return ret;
    }

    @Override
    public String getTrack2() {
        ByteArray byteArray = new ByteArray();
        getTlv(0x57, byteArray);
        return getTrack2FromTag57(ConvertUtils.bcd2Str(byteArray.data, byteArray.length));
    }

    @Override
    public boolean isNeedSecondTap(IssuerRspData issuerRspData) {
        return false;
    }

    private boolean checkAmountCtlssExceedsLimitTrx() {
        ByteArray byteArray = new ByteArray();
        getTlv(0x9F02, byteArray);
        long amountKernel = Long.parseLong(ConvertUtils.bcd2Str(byteArray.data, byteArray.length));
        if (amountKernel > amexParam.getCvmLimit())
            return true;
        return false;
    }
}
