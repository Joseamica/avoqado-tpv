/*
 * ===========================================================================================
 * = COPYRIGHT
 *          PAX Computer Technology(Shenzhen) CO., LTD PROPRIETARY INFORMATION
 *   This software is supplied under the terms of a license agreement or nondisclosure
 *   agreement with PAX Computer Technology(Shenzhen) CO., LTD and may not be copied or
 *   disclosed except in accordance with the terms in that agreement.
 *     Copyright (C) 2020-? PAX Computer Technology(Shenzhen) CO., LTD All rights reserved.
 * Description: // Detail description about the function of this module,
 *             // interfaces with the other modules, and dependencies.
 * Revision History:
 * Date                  Author	                 Action
 * 20200525  	         JackHuang               Create
 * ===========================================================================================
 */
package com.paxsz.module.emv.process.contactless;

import com.pax.commonlib.utils.ConvertUtils;
import com.pax.commonlib.utils.LogUtils;
import com.pax.commonlib.utils.convert.ConvertHelper;
import com.pax.commonlib.utils.convert.IConvert;
import com.pax.jemv.clcommon.ByteArray;
import com.pax.jemv.clcommon.Clss_PreProcInterInfo;
import com.pax.jemv.clcommon.Clss_TransParam;
import com.pax.jemv.clcommon.KernType;
import com.pax.jemv.clcommon.RetCode;
import com.pax.jemv.entrypoint.api.ClssEntryApi;
import com.paxsz.module.emv.param.EmvProcessParam;
import com.paxsz.module.emv.process.EmvBase;
import com.paxsz.module.emv.process.entity.IssuerRspData;
import com.paxsz.module.emv.process.entity.TransResult;
import com.paxsz.module.emv.process.enums.CvmResultEnum;
import com.paxsz.module.emv.process.enums.TransResultEnum;

import java.util.Arrays;

/**
 * this is clss process framework, Please do not change this file at will, if need to
 * add a new kernel process,Please add aid in ClssEntryAddAid.java and create a ClssXXXProcess.java
 * extend ClssKernelProcessFactory.java. Then create ClssXXXProcess object in ClssKernelProcessFactory.java
 */
public class ClssProcess extends EmvBase {
    private static final String TAG = "ClssProcess";
    private EmvProcessParam emvProcessParam;
    private Clss_TransParam transParam;
    private KernType kernType;
    private ClssKernelProcess clssKernelProcess = null;
    private ClssEntryAddAid clssEntryAddAid;
    private static ClssProcess instance;
    private long startMs;
    private long finishMs;
    //private IClssStatusListener clssStatusListener;
    private IContactlessCallback clssStatusListener;
    private ClssProcess() {
    }

    public static ClssProcess getInstance() {
        if (instance == null) {
            instance = new ClssProcess();
        }
        return instance;
    }

    public void registerClssStatusListener(IContactlessCallback clssStatusListener) {
        this.clssStatusListener = clssStatusListener;
    }

    /**
     * 1.entry init
     * 2.add aid
     *
     * @param emvParam
     */
    @Override
    public int preTransProcess(EmvProcessParam emvParam) {
        this.emvProcessParam = emvParam;
        clssEntryAddAid = new ClssEntryAddAid(emvProcessParam);
        int ret = ClssEntryApi.Clss_CoreInit_Entry() ;
        if(ret != RetCode.EMV_OK){
            LogUtils.e(TAG, "Clss_CoreInit_Entry ret = " + ret);
            return ret;
        }
        ClssEntryApi.Clss_DelAllAidList_Entry();
        ClssEntryApi.Clss_DelAllPreProcInfo();

        //if want to support other kernerl aid, Pls add in Class ClssEntryAddAid.
        clssEntryAddAid.addApp();
        //This function must be called before Clss_PreTransProc_Entry and only if entry library is used for PayPass application
        ret = ClssEntryApi.Clss_SetMCVersion_Entry((byte) 0x03);
        LogUtils.e(TAG, "Clss_SetMCVersion_Entry ret = " + ret);

        if (ret != RetCode.EMV_OK) {
            LogUtils.e(TAG, "Clss_SetMCVersion_Entry ret = " + ret);
            return ret ;
        }
        return ClssEntryApi.Clss_PreTransProc_Entry(convertToClssTransParam());
    }

    /**
     * Process as below:
     * 1.detected card
     * 2.select application
     * 3.application initialization
     * 4.read application data
     * 5.offline data authentication
     * 6.terminal risk management
     * 7.ardholder authentication
     * 8.terminal behavior analysis
     * 9.First Generate AC
     */
    @Override
    public TransResult startTransProcess(boolean isQps) {

        startMs = System.currentTimeMillis();
        int ret = ClssEntryApi.Clss_AppSlt_Entry(0, 0);
        if(ret != RetCode.EMV_OK){
            LogUtils.e(TAG, "Clss_AppSlt_Entry ret = " + ret);
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
        }

        kernType = new KernType();
        ByteArray daArray = new ByteArray();
        while(true){
            /*
             * If EMV_RSP_ERR || EMV_APP_BLOCK || ICC_BLOCK || CLSS_RESELECT_APP is returned,
             * application program shall call Clss_DelCurCandApp_Entry to delete current application from candidate list,
             * and select the next application by calling Clss_FinalSelect_Entry, if there are other applications in the candidate list*/
            ret = ClssEntryApi.Clss_FinalSelect_Entry(kernType, daArray);
            LogUtils.d(TAG, "Clss_FinalSelect_Entry ret = " + ret + ", Kernel Type = " + kernType.kernType);
            if (ret == RetCode.EMV_RSP_ERR || ret == RetCode.EMV_APP_BLOCK
                    || ret == RetCode.ICC_BLOCK || ret == RetCode.CLSS_RESELECT_APP) {
                ret = ClssEntryApi.Clss_DelCurCandApp_Entry();
                LogUtils.e(TAG, "Clss_DelCurCandApp_Entry ret = " + ret);
                if (ret != RetCode.EMV_OK) {
                    //candidate list is empty, quit.
                    return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
                }
                continue;
            } else if (ret != RetCode.EMV_OK) {
                if (kernType.kernType == KernType.KERNTYPE_RUPAY) {
                    // RUPAY.QCLD.012
                    byte[] status = new byte[3];
                    int result = ClssEntryApi.Clss_GetExtendFunction_Entry(0x03, status, status.length);
                    LogUtils.d(TAG, "Clss_GetExtendFunction_Entry ret = " + result
                            + ", status = " + ConvertUtils.bcd2Str(status));
                    if (result == RetCode.EMV_OK) {
                        byte sw[] = Arrays.copyOfRange(status, 1, 3);
                        if (Arrays.equals(sw, new byte[]{(byte) 0x62, (byte) 0x83})) {
                            ret = RetCode.EMV_APP_BLOCK;
                        }
                    }
                }
                return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
            }

            Clss_PreProcInterInfo clssPreProcInterInfo = new Clss_PreProcInterInfo();
            ret = ClssEntryApi.Clss_GetPreProcInterFlg_Entry(clssPreProcInterInfo);
            if (ret != RetCode.EMV_OK) {
                LogUtils.e(TAG, "Clss_GetPreProcInterFlg_Entry ret = " + ret);
                return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
            }

            ByteArray finalSelectData = new ByteArray();
            ret = ClssEntryApi.Clss_GetFinalSelectData_Entry(finalSelectData);
            if (ret != RetCode.EMV_OK) {
                LogUtils.e(TAG, "Clss_GetFinalSelectData_Entry ret = " + ret);
                return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
            }

            clssKernelProcess = new ClssKernelProcessFactory(kernType.kernType).getKernelProcess()
                            .setEmvProcessParam(emvProcessParam)
                            .setClssTransParam(transParam)
                            .setFinalSelectData(finalSelectData.data, finalSelectData.length)
                            .setPreProcInterInfo(clssPreProcInterInfo)
                            .setClssStatusListener(clssStatusListener)
                            .setStatusListener(statusListener);

            TransResult transResult = clssKernelProcess.startTransProcess();
            if(transResult.getResultCode() == RetCode.CLSS_RESELECT_APP){
                ret = ClssEntryApi.Clss_DelCurCandApp_Entry();
                LogUtils.e(TAG, "Clss_DelCurCandApp_Entry ret = " + ret);
                if (ret != RetCode.EMV_OK) {
                    LogUtils.e(TAG, "Clss_DelCurCandApp_Entry ret = " + ret);
                    return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
                }
                continue;
            }

            finishMs = System.currentTimeMillis();
            LogUtils.d(TAG, "clss trans time-consuming ms = "+ (finishMs - startMs));
            return transResult;
        }
    }

    /**
     * Process as below:
     * 1.Issuer Authentication
     * 2.Script Processing
     * 3.Complete Trans
     */
    @Override
    public TransResult completeTransProcess(IssuerRspData issuerRspData) {
        return clssKernelProcess.completeTransProcess(issuerRspData);
    }

    public int getTlv(int tag, ByteArray value) {
        int result = clssKernelProcess.getTlv(tag, value);
        LogUtils.d(TAG, "clss getTlv result = "+ result);
        //return clssKernelProcess.getTlv(tag, value);
        return result;
    }
    public void setTlv(int tag, byte[] value) {
        clssKernelProcess.setTlv(tag, value);
    }


    private Clss_TransParam convertToClssTransParam(){
        long amount = Long.parseLong(emvProcessParam.getEmvTransParam().getAmount());
        long amoutOther = Long.parseLong(emvProcessParam.getEmvTransParam().getAmountOther());
        long traceNo = Long.parseLong(emvProcessParam.getEmvTransParam().getTransTraceNo());
        byte transType = emvProcessParam.getEmvTransParam().getTransType();
        byte[] transDate = ConvertHelper.getConvert().strToBcd(emvProcessParam.getEmvTransParam().getTransDate(), IConvert.EPaddingPosition.PADDING_LEFT);
        byte[] transTime = ConvertHelper.getConvert().strToBcd(emvProcessParam.getEmvTransParam().getTransTime(), IConvert.EPaddingPosition.PADDING_LEFT);
        transParam = new Clss_TransParam(amount, amoutOther, traceNo, transType,transDate,transTime);
        return transParam;
    }

    public String getTrack2(){
        if (clssKernelProcess != null) {
            return clssKernelProcess.getTrack2();
        }

        return "";
    }

    public KernType getKernType(){
        return kernType;
    }

    public boolean isNeedSecondTap(IssuerRspData issuerRspData){
        return clssKernelProcess.isNeedSecondTap(issuerRspData);
    }
}
