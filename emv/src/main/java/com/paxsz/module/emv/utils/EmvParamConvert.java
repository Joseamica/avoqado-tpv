/*
 *  ===========================================================================================
 *  = COPYRIGHT
 *          PAX Computer Technology(Shenzhen) CO., LTD PROPRIETARY INFORMATION
 *     This software is supplied under the terms of a license agreement or nondisclosure
 *     agreement with PAX Computer Technology(Shenzhen) CO., LTD and may not be copied or
 *     disclosed except in accordance with the terms in that agreement.
 *          Copyright (C) 2020 -? PAX Computer Technology(Shenzhen) CO., LTD All rights reserved.
 *  Description: // Detail description about the function of this module,
 *               // interfaces with the other modules, and dependencies.
 *  Revision History:
 *  Date	               Author	                   Action
 *  2020/05/26 	         Qinny Zhou           	      Create
 *  ===========================================================================================
 */
package com.paxsz.module.emv.utils;

import com.pax.commonlib.utils.convert.ConvertHelper;
import com.pax.commonlib.utils.convert.IConvert;
import com.pax.jemv.clcommon.Clss_PreProcInfo;
import com.pax.jemv.clcommon.CvmType;
import com.pax.jemv.clcommon.EMV_APPLIST;
import com.pax.jemv.clcommon.EMV_CAPK;
import com.pax.jemv.clcommon.KernType;
import com.paxsz.module.emv.process.enums.CvmResultEnum;
import com.paxsz.module.emv.xmlparam.entity.clss.AmexAid;
import com.paxsz.module.emv.xmlparam.entity.clss.PayPassAid;
import com.paxsz.module.emv.xmlparam.entity.clss.PayWaveAid;
import com.paxsz.module.emv.xmlparam.entity.clss.PayWaveInterFloorLimit;
import com.paxsz.module.emv.xmlparam.entity.common.Capk;
import com.paxsz.module.emv.xmlparam.entity.contact.EmvAid;

import java.util.ArrayList;


public class EmvParamConvert {
    protected static final String TAG = "EmvParamConvert";

    private EmvParamConvert() {

    }

    //convert emv_param.contact aid to L2 lib Emv Aid
    public static EMV_APPLIST toEMVApp(EmvAid aidParam) {
        EMV_APPLIST appList = new EMV_APPLIST();
        appList.appName = aidParam.getLocalAIDName().getBytes();
        appList.aid = aidParam.getApplicationID();
        appList.aidLen = (byte) aidParam.getApplicationID().length;
        appList.selFlag = aidParam.getPartialAIDSelection();
        appList.priority = aidParam.getPriority();
        appList.floorLimit = aidParam.getFloorLimit();
        appList.floorLimitCheck = (byte) (aidParam.getFloorLimit() > 0 ? 1 : 0);
        appList.threshold = aidParam.getThreshold();
        appList.targetPer = aidParam.getTargetPercentage();
        appList.maxTargetPer = aidParam.getMaxTargetPercentage();
        appList.randTransSel =aidParam.getRandTransSel();
        appList.velocityCheck = aidParam.getVelocityCheck();
        appList.tacDenial = aidParam.getTacDenial();
        appList.tacOnline = aidParam.getTacOnline();
        appList.tacDefault = aidParam.getTacDefault();
        appList.acquierId = aidParam.getAcquirerIdBytes();
        appList.dDOL = aidParam.getTerminalDefaultDDOL();
        appList.tDOL = aidParam.getTerminalDefaultTDOL();
        appList.version = aidParam.getTerminalAIDVersion();
        appList.riskManData = aidParam.getTerminalRiskManagementData();
        return appList;
    }

    //convert capk.capk capk to L2 lib capk
    public static EMV_CAPK toEMVCapk(Capk capk) {
        EMV_CAPK emvCapk = new EMV_CAPK();
        emvCapk.rID = capk.getRid();
        emvCapk.keyID = capk.getKeyId();
        emvCapk.hashInd = capk.getHashArithmeticIndex();
        emvCapk.arithInd = capk.getRsaArithmeticIndex();
        emvCapk.modul = capk.getModule();
        emvCapk.modulLen = (short) capk.getModuleLength();
        emvCapk.exponent = capk.getExponent();
        emvCapk.exponentLen =  capk.getExponentLength();
        emvCapk.expDate = capk.getExpireDate();
        emvCapk.checkSum = capk.getCheckSum();
        return emvCapk;
    }

    //convert paypass_param.clss_mc aid to L2 clss Clss_PreProcInfo
    public static Clss_PreProcInfo PayPassPreProcInfo(PayPassAid payPassAid){
        Clss_PreProcInfo clssPreProcInfo = new Clss_PreProcInfo();
        clssPreProcInfo.aucAID = payPassAid.getApplicationId();
        clssPreProcInfo.ucAidLen = (byte)payPassAid.getApplicationId().length;
        clssPreProcInfo.ucKernType = KernType.KERNTYPE_MC;
        clssPreProcInfo.ucRdClssFLmtFlg = payPassAid.getContactlessFloorLimitSupported();
        clssPreProcInfo.ucRdClssTxnLmtFlg = payPassAid.getContactlessTransactionLimitSupported();
        clssPreProcInfo.ucRdCVMLmtFlg = payPassAid.getContactlessCvmLimitSupported();
        clssPreProcInfo.ucTermFLmtFlg = payPassAid.getContactlessFloorLimitSupported();
        clssPreProcInfo.ulRdClssFLmt = payPassAid.getContactlessFloorLimit();
        clssPreProcInfo.ulRdCVMLmt = payPassAid.getContactlessCvmLimit();
        clssPreProcInfo.ulTermFLmt = payPassAid.getContactlessFloorLimit();
        clssPreProcInfo.ulRdClssTxnLmt = payPassAid.getContactlessTransactionLimitNoOnDevice();
        return clssPreProcInfo;
    }

    //convert paywave_param.clss_wave aid to L2 clss Clss_PreProcInfo
    public static Clss_PreProcInfo PayPassWavePreProcInfo(PayWaveAid payWaveAid, byte transType){
        Clss_PreProcInfo clssPreProcInfo = new Clss_PreProcInfo();
        int i = getPayWaveInterFloorLimitIndexByTransType(transType, payWaveAid.getPayWaveInterFloorLimitList());
        clssPreProcInfo.aucAID = payWaveAid.getApplicationId();
        clssPreProcInfo.ucAidLen = (byte)payWaveAid.getApplicationId().length;
        clssPreProcInfo.ucKernType = KernType.KERNTYPE_VIS;
        clssPreProcInfo.ucRdClssFLmtFlg = payWaveAid.getPayWaveInterFloorLimitList().get(i).getContactlessFloorLimitSupported();// when in entry, befor
        clssPreProcInfo.ucRdClssTxnLmtFlg = payWaveAid.getPayWaveInterFloorLimitList().get(i).getContactlessTransactionLimitSupported();
        clssPreProcInfo.ucRdCVMLmtFlg = payWaveAid.getPayWaveInterFloorLimitList().get(i).getCvmLimitSupported();
        clssPreProcInfo.ucTermFLmtFlg = payWaveAid.getPayWaveInterFloorLimitList().get(i).getContactlessFloorLimitSupported();
        clssPreProcInfo.ulRdClssFLmt = payWaveAid.getPayWaveInterFloorLimitList().get(i).getContactlessFloorLimit();
        clssPreProcInfo.ulRdCVMLmt = payWaveAid.getPayWaveInterFloorLimitList().get(i).getContactlessCvmLimit();
        clssPreProcInfo.ulTermFLmt = payWaveAid.getPayWaveInterFloorLimitList().get(i).getContactlessFloorLimit();
        clssPreProcInfo.ulRdClssTxnLmt = payWaveAid.getPayWaveInterFloorLimitList().get(i).getContactlessTransactionLimit();
        clssPreProcInfo.aucReaderTTQ = payWaveAid.getReaderTtq();
        clssPreProcInfo.ucCrypto17Flg = payWaveAid.getCryptogramVersion17Supported();
        clssPreProcInfo.ucStatusCheckFlg = payWaveAid.getStatusCheckSupported();
        clssPreProcInfo.ucZeroAmtNoAllowed = payWaveAid.getZeroAmountNoAllowed();

        return clssPreProcInfo;
    }

    public static Clss_PreProcInfo expressPayPreProcInfo(AmexAid aid) {
        Clss_PreProcInfo clssPreProcInfo = new Clss_PreProcInfo();
        clssPreProcInfo.aucAID = aid.getAid();
        clssPreProcInfo.ucAidLen = (byte) aid.getAid().length;
        clssPreProcInfo.ucKernType = KernType.KERNTYPE_AE;
        clssPreProcInfo.ucTermFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulTermFLmt = aid.getFloorLimit();
        clssPreProcInfo.ucRdClssFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulRdClssFLmt = aid.getFloorLimit();
        clssPreProcInfo.ulRdClssTxnLmt = aid.getTransLimit();
        clssPreProcInfo.ulRdCVMLmt = aid.getCvmLimit();
        return clssPreProcInfo;
    }
    /*
    public static Clss_PreProcInfo pbocPreProcInfo(PbocAid aid) {
        Clss_PreProcInfo clssPreProcInfo = new Clss_PreProcInfo();
        clssPreProcInfo.aucAID = aid.getAid();
        clssPreProcInfo.ucAidLen = (byte) aid.getAid().length;
        clssPreProcInfo.ucKernType = KernType.KERNTYPE_PBOC;
        clssPreProcInfo.ucTermFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulTermFLmt = aid.getFloorLimit();
        clssPreProcInfo.ucRdClssTxnLmtFlg = aid.getTransLimitFlag();
        clssPreProcInfo.ucRdClssFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulRdClssFLmt = aid.getFloorLimit();
        clssPreProcInfo.ulRdClssTxnLmt = aid.getTransLimit();
        clssPreProcInfo.ulRdCVMLmt = aid.getCvmLimit();
        clssPreProcInfo.ucRdCVMLmtFlg = aid.getCvmLimitFlag();
        clssPreProcInfo.aucReaderTTQ = aid.getTTQ();
        return clssPreProcInfo;
    }

    public static Clss_PreProcInfo dpasPreProcInfo(DpasAid aid) {
        Clss_PreProcInfo clssPreProcInfo = new Clss_PreProcInfo();
        clssPreProcInfo.aucAID = aid.getAid();
        clssPreProcInfo.ucAidLen = (byte) aid.getAid().length;
        clssPreProcInfo.ucKernType = KernType.KERNTYPE_ZIP;
        clssPreProcInfo.ucTermFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulTermFLmt = aid.getFloorLimit();
        clssPreProcInfo.ucRdClssTxnLmtFlg = aid.getTransLimitFlag();
        clssPreProcInfo.ucRdClssFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulRdClssFLmt = aid.getFloorLimit();
        clssPreProcInfo.ulRdClssTxnLmt = aid.getTransLimit();
        clssPreProcInfo.ulRdCVMLmt = aid.getCvmLimit();
        clssPreProcInfo.ucRdCVMLmtFlg = aid.getCvmLimitFlag();
        clssPreProcInfo.aucReaderTTQ = aid.getTtq();    // TTQ must be set
        return clssPreProcInfo;
    }

    public static Clss_PreProcInfo eftPreProcInfo(EFTAid aid) {
        Clss_PreProcInfo clssPreProcInfo = new Clss_PreProcInfo();
        clssPreProcInfo.aucAID = aid.getAid();
        clssPreProcInfo.ucAidLen = (byte) aid.getAid().length;
        clssPreProcInfo.ucKernType = KernType.KERNTYPE_EFT;
        clssPreProcInfo.ucTermFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulTermFLmt = aid.getFloorLimit();
        clssPreProcInfo.ucRdClssTxnLmtFlg = aid.getTransLimitFlag();
        clssPreProcInfo.ucRdClssFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulRdClssFLmt = aid.getFloorLimit();
        clssPreProcInfo.ulRdClssTxnLmt = aid.getTransLimit();
        clssPreProcInfo.ulRdCVMLmt = aid.getCvmLimit();
        clssPreProcInfo.ucRdCVMLmtFlg = aid.getCvmLimitFlag();
        return clssPreProcInfo;
    }

    public static Clss_PreProcInfo jcbPreProcInfo(JcbAid aid) {
        Clss_PreProcInfo clssPreProcInfo = new Clss_PreProcInfo();
        clssPreProcInfo.aucAID = aid.getAid();
        clssPreProcInfo.ucAidLen = (byte) aid.getAid().length;
        clssPreProcInfo.ucKernType = KernType.KERNTYPE_JCB;
        clssPreProcInfo.ucTermFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulTermFLmt = aid.getFloorLimit();
        clssPreProcInfo.ucRdClssTxnLmtFlg = aid.getTransLimitFlag();
        clssPreProcInfo.ucRdClssFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulRdClssFLmt = aid.getFloorLimit();
        clssPreProcInfo.ulRdClssTxnLmt = aid.getTransLimit();
        clssPreProcInfo.ulRdCVMLmt = aid.getCvmLimit();
        clssPreProcInfo.ucRdCVMLmtFlg = aid.getCvmLimitFlag();
        return clssPreProcInfo;
    }

    public static Clss_PreProcInfo mirPreProcInfo(MirAid aid) {
        Clss_PreProcInfo clssPreProcInfo = new Clss_PreProcInfo();
        clssPreProcInfo.aucAID = aid.getAid();
        clssPreProcInfo.ucAidLen = (byte) aid.getAid().length;
        clssPreProcInfo.ucKernType = KernType.KERNTYPE_MIR;
        clssPreProcInfo.ucTermFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulTermFLmt = aid.getFloorLimit();
        clssPreProcInfo.ucRdClssTxnLmtFlg = aid.getTransLimitFlag();
        clssPreProcInfo.ucRdClssFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulRdClssFLmt = aid.getFloorLimit();
        clssPreProcInfo.ulRdClssTxnLmt = aid.getTransLimit();
        clssPreProcInfo.ulRdCVMLmt = aid.getCvmLimit();
        clssPreProcInfo.ucRdCVMLmtFlg = aid.getCvmLimitFlag();
        return clssPreProcInfo;
    }

    public static Clss_PreProcInfo purePreProcInfo(PureAid aid) {
        Clss_PreProcInfo clssPreProcInfo = new Clss_PreProcInfo();
        clssPreProcInfo.aucAID = aid.getAid();
        clssPreProcInfo.ucAidLen = (byte) aid.getAid().length;
        clssPreProcInfo.ucKernType = KernType.KERNTYPE_PURE;
        clssPreProcInfo.ucTermFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulTermFLmt = aid.getFloorLimit();
        clssPreProcInfo.ucRdClssTxnLmtFlg = aid.getTransLimitFlag();
        clssPreProcInfo.ucRdClssFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulRdClssFLmt = aid.getFloorLimit();
        clssPreProcInfo.ulRdClssTxnLmt = aid.getTransLimit();
        clssPreProcInfo.ulRdCVMLmt = aid.getCvmLimit();
        clssPreProcInfo.ucRdCVMLmtFlg = aid.getCvmLimitFlag();
        return clssPreProcInfo;
    }

    public static Clss_PreProcInfo ruPayPreProcInfo(RuPayAid aid) {
        Clss_PreProcInfo clssPreProcInfo = new Clss_PreProcInfo();
        clssPreProcInfo.aucAID = aid.getAid();
        clssPreProcInfo.ucAidLen = (byte) aid.getAid().length;
        clssPreProcInfo.ucKernType = KernType.KERNTYPE_RUPAY;
        clssPreProcInfo.ucTermFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulTermFLmt = aid.getFloorLimit();
        clssPreProcInfo.ucRdClssTxnLmtFlg = aid.getTransLimitFlag();
        clssPreProcInfo.ucRdClssFLmtFlg = aid.getFloorLimitFlag();
        clssPreProcInfo.ulRdClssFLmt = aid.getFloorLimit();
        clssPreProcInfo.ulRdClssTxnLmt = aid.getTransLimit();
        clssPreProcInfo.ulRdCVMLmt = aid.getCvmLimit();
        clssPreProcInfo.ucRdCVMLmtFlg = aid.getCvmLimitFlag();
        clssPreProcInfo.ucCrypto17Flg = aid.getCrypto17Flag();
        clssPreProcInfo.ucZeroAmtNoAllowed = aid.getZeroAmountNoAllowed();
        clssPreProcInfo.ucStatusCheckFlg = aid.getStatusCheckFlag();
        clssPreProcInfo.aucReaderTTQ = aid.getTTQ();
        clssPreProcInfo.aucRFU = new byte[2];
        return clssPreProcInfo;
    }

     */
    public static CvmResultEnum convertCVM(int result) {
        switch (result) {
            case CvmType.RD_CVM_NO:
                return CvmResultEnum.CVM_NO_CVM;
            case CvmType.RD_CVM_ONLINE_PIN:
            case CvmType.RD_CVM_REQ_ONLINE_PIN:
                return CvmResultEnum.CVM_ONLINE_PIN;
            case CvmType.RD_CVM_SIG:
            case CvmType.RD_CVM_REQ_SIG:
                return CvmResultEnum.CVM_SIG;
            case CvmType.RD_CVM_CONSUMER_DEVICE:
                return CvmResultEnum.CVM_CONSUMER_DEVICE;
            case CvmType.RD_CVM_OFFLINE_PIN:
                return CvmResultEnum.CVM_OFFLINE_PIN;
            default:
                return null;
        }
    }


    public static int getPayWaveInterFloorLimitIndexByTransType(byte transType, ArrayList<PayWaveInterFloorLimit> list){
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).getTransactionType() == transType) {
                return i;
            }
        }
        return 0;
    }


}
