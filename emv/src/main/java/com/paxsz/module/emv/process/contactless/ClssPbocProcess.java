/*
 * ===========================================================================================
 *     COPYRIGHT
 *           PAX Computer Technology(Shenzhen) CO., LTD PROPRIETARY INFORMATION
 *    This software is supplied under the terms of a license agreement or nondisclosure
 *    agreement with PAX Computer Technology(Shenzhen) CO., LTD and may not be copied or
 *    disclosed except in accordance with the terms in that agreement.
 *      Copyright (C) 2020-? PAX Computer Technology(Shenzhen) CO., LTD All rights reserved.
 *  Description: // Detail description about the function of this module,
 *              // interfaces with the other modules, and dependencies.
 *  Revision History:
 *  Date                  Author	                 Action
 *  20210111  	          John Cai                   Create
 *  20210615              YeHongbo                   Modify
 * ===========================================================================================
 */

package com.paxsz.module.emv.process.contactless;

import com.pax.commonlib.utils.ConvertUtils;
import com.pax.commonlib.utils.LogUtils;
import com.pax.commonlib.utils.impl.CharsetUtils;
import com.pax.jemv.clcommon.ACType;
import com.pax.jemv.clcommon.ByteArray;
import com.pax.jemv.clcommon.Clss_ReaderParam;
import com.pax.jemv.clcommon.CvmType;
import com.pax.jemv.clcommon.DDAFlag;
import com.pax.jemv.clcommon.EMV_CAPK;
import com.pax.jemv.clcommon.EMV_REVOCLIST;
import com.pax.jemv.clcommon.RetCode;
import com.pax.jemv.clcommon.TransactionPath;
import com.pax.jemv.qpboc.api.ClssPbocApi;
import com.pax.jemv.qpboc.model.Clss_PbocAidParam;
import com.pax.jemv.qpboc.model.Clss_PbocTornConfig;
import com.paxsz.module.emv.process.entity.TransResult;
import com.paxsz.module.emv.process.enums.CvmResultEnum;
import com.paxsz.module.emv.process.enums.TransResultEnum;
import com.paxsz.module.emv.process.entity.IssuerRspData;
import com.paxsz.module.emv.constant.TagsTable;
import com.paxsz.module.emv.utils.EmvParamConvert;

public class ClssPbocProcess extends ClssKernelProcess {

    private static final String TAG = "ClssPbocProcess";
    // AID Type
    private static final byte DEBIT_TYPE = 0;
    private static final byte CREDIT_TYPE = 1;
    private static final byte QCREDIT_TYPE = 2;

    private String track2;
    private boolean isNeedOffline = false;

    @Override
    public TransResult startTransProcess() {
        int ret = init();
        if (ret != RetCode.EMV_OK) {
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                    CvmResultEnum.CVM_NO_CVM);
        }

        ret = ClssPbocApi.Clss_SetTransData_Pboc(transParam, preProcInterInfo);
        if (ret != RetCode.EMV_OK) {
            return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                    CvmResultEnum.CVM_NO_CVM);
        }

        // TODO TornProcess
        ACType acType = new ACType();
        isNeedOffline = false;
        ret = wholeTornProcess(acType);
        if (isNeedOffline) {
            if (ret == RetCode.CLSS_USE_CONTACT) {
                return new TransResult(ret, TransResultEnum.RESULT_CLSS_TRY_ANOTHER_INTERFACE,
                        CvmResultEnum.CVM_NO_CVM);
            } else if (ret == RetCode.CLSS_TERMINATE) {
                return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                        CvmResultEnum.CVM_NO_CVM);
            }
            if (acType.type == ACType.AC_TC) {
                return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_APPROVED, CvmResultEnum.CVM_NO_CVM);
            } else if (acType.type == ACType.AC_AAC) {
                return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
            } else if (acType.type == ACType.AC_ARQC) {
                return new TransResult(ret, TransResultEnum.RESULT_REQ_ONLINE, CvmResultEnum.CVM_NO_CVM);
            }
        }

        ret = ClssPbocApi.Clss_Proctrans_Pboc(transactionPath, acType);
        LogUtils.i(TAG, "clssPbocmaiProcTrans ret = " + ret);
        if (ret != RetCode.EMV_OK) {
            if (ret == RetCode.CLSS_LAST_CMD_ERR) {
                // TODO current tornProcess
                currentTornProcess();
                return new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED,
                        CvmResultEnum.CVM_NO_CVM);
            } else if (ret == RetCode.CLSS_REFER_CONSUMER_DEVICE) {
                return new TransResult(ret, TransResultEnum.RESULT_CLSS_SEE_PHONE,
                        CvmResultEnum.CVM_CONSUMER_DEVICE);
            } else if (ret == RetCode.CLSS_USE_CONTACT) {
                return new TransResult(ret, TransResultEnum.RESULT_CLSS_TRY_ANOTHER_INTERFACE,
                        CvmResultEnum.CVM_NO_CVM);
            }
        }
        LogUtils.i(TAG, "clssPbocTrans TransPath = " + transactionPath.path + ", ACType = " + acType.type);

        if (clssStatusListener != null) {
            //clssStatusListener.onReadCardOk();
            //clssStatusListener.onRemoveCard();
            LogUtils.e(TAG, "onReadCardOk");
            LogUtils.e(TAG, "onRemoveCard");
        }

        TransResult result = new TransResult(ret, TransResultEnum.RESULT_OFFLINE_DENIED, CvmResultEnum.CVM_NO_CVM);
        if (!continueUpdateResult(acType, result)) {
            return result;
        }

        updateResult(result);
        LogUtils.i(TAG, "result ret = " + result.getCvmResult() + "  " + result.getTransResult());
        return result;
    }

    private int init() {
        ByteArray version = new ByteArray(16);
        ClssPbocApi.Clss_ReadVerInfo_Pboc(version);
        LogUtils.i(TAG, "pboc version " + CharsetUtils.getEncodeString(version.data));
        int ret = ClssPbocApi.Clss_CoreInit_Pboc();
        LogUtils.i(TAG, "clssPBOCCoreInit ret = " + ret);
        if (ret != RetCode.EMV_OK) {
            return ret;
        }
        /* //  TODO
        ClssPbocApi.Clss_SetQUICSFlag_Pboc(clssParam.getQuicsFlag());
        Clss_PbocTornConfig pbocTornConfig = new Clss_PbocTornConfig(
                clssParam.getTornMaxLifeTime(),
                clssParam.getTornLogMaxNum(),
                clssParam.getTornSupport(),
                clssParam.getTornRFU());
        ret = ClssPbocApi.Clss_TornSetConfig_Pboc(pbocTornConfig);
         */
        LogUtils.i(TAG, "Pboc_ornSetConfig ret = " + ret);
        if (ret != RetCode.EMV_OK) {
            return ret;
        }

        clearExpiredTornLog();


        ret = ClssPbocApi.Clss_SetReaderParam_Pboc(getClssReaderParam());
        LogUtils.i(TAG, "PbocSetReaderParam ret = " + ret);
        if (ret != RetCode.EMV_OK) {
            return ret;
        }
        /* // TODO
        ret = ClssPbocApi.Clss_SetPbocAidParam_Pboc(new Clss_PbocAidParam(clssParam.getTrueFloorLimit(),
                clssParam.getAucRFU()));
         */
        LogUtils.i(TAG, "PbocSetPbocAidParam ret = " + ret);

        if (ret != RetCode.EMV_OK) {
            return ret;
        }

        ret = ClssPbocApi.Clss_SetFinalSelectData_Pboc(finalSelectData, finalSelectDataLen);
        LogUtils.i(TAG, "PbocSetFinalSelectData ret = " + ret);
        return ret;
    }

    private void clearExpiredTornLog() {
        ByteArray delTornFlag = new ByteArray();
        while (true) {
            int ret = ClssPbocApi.Clss_ClearTornLog_Pboc((byte) 2, delTornFlag);
            if (ret == RetCode.EMV_NO_DATA && delTornFlag.data[0] != 1) {
                return;
            }
        }
    }

    private Clss_ReaderParam getClssReaderParam() {
        Clss_ReaderParam readerParam = new Clss_ReaderParam();
        /* // TODO
        readerParam.ulReferCurrCon = clssParam.getReferCurrCon();
        readerParam.aucMchNameLoc = clssParam.getMerchantNameLocation();
        readerParam.usMchLocLen = clssParam.getMerchantNameLocLen();
        readerParam.aucMerchCatCode = clssParam.getMerchantCategoryCode();
        readerParam.aucMerchantID = clssParam.getMerchantId();
        readerParam.acquierId = clssParam.getAcquirerId();

        readerParam.aucTmID = clssParam.getTermId();
        readerParam.ucTmType = clssParam.getTermType();
        readerParam.aucTmCap = clssParam.getTermCapability();
        readerParam.aucTmCapAd = clssParam.getExTermCapability();
        readerParam.aucTmCntrCode = clssParam.getCountryCode();
        readerParam.aucTmTransCur = clssParam.getTransCurrCode();
        readerParam.ucTmTransCurExp = clssParam.getTransCurrExp();
        readerParam.aucTmRefCurCode = clssParam.getReferCurrCode();
        readerParam.ucTmRefCurExp = clssParam.getReferCurrExp();

         */
        return readerParam;
    }

    private int wholeTornProcess(ACType acType) {
        byte[] tornBuff = new byte[4];
        int ret = ClssPbocApi.Clss_TornProcessing_Pboc((byte) 0, tornBuff);

            LogUtils.i(TAG, "tornBuff: " + ConvertUtils.bcd2Str(tornBuff));

        if (ret != RetCode.EMV_OK) {
            LogUtils.e(TAG, "Clss_TornProcessing_Pboc return " + ret);
            return ret;
        } else if (tornBuff[1] == 0) {
            ByteArray failFlag = new ByteArray(1);
            ret = ClssPbocApi.Clss_GetTornFailFlag_Pboc(failFlag);
            if (ret == RetCode.EMV_OK && failFlag.data[0] == 2) {
                ByteArray clearFlag = new ByteArray(1);
                clearFlag.data[0] = 1;
                while (clearFlag.data[0] == 1) {
                    ClssPbocApi.Clss_ClearTornLog_Pboc((byte) 0, clearFlag);
                }
            }
            return RetCode.EMV_OK;
        } else if (tornBuff[1] == 1) {
            isNeedOffline = true;
            return offlineTornProcess(acType);
        }
        return RetCode.EMV_OK;
    }

    private int offlineTornProcess(ACType acType) {
        int ret = 0;
        //according to EDC
        boolean isNeedSaveTorn = false;
        boolean isNeedDelTorn = false;
        ClssPbocApi.Clss_DelAllRevocList_Pboc();
        ClssPbocApi.Clss_DelAllCAPK_Pboc();
        addCapkRevList();

        DDAFlag flag = new DDAFlag();
        ret = ClssPbocApi.Clss_CardAuth_Pboc(acType, flag);
        if (ret != RetCode.EMV_OK) {
            if (ret == RetCode.CLSS_USE_CONTACT) {
                return ret;
            }
            ByteArray tornFailFlag = new ByteArray(1);
            int ret1 = ClssPbocApi.Clss_GetTornFailFlag_Pboc(tornFailFlag);
            if (ret1 == RetCode.EMV_OK) {
                if (tornFailFlag.data[0] == 2) { //Application should delete the fail torn log
                    isNeedDelTorn = true;
                } else if (tornFailFlag.data[0] == 1) {//There is a fail torn log deleted and the data of the deleted torn log are saved in the
                    isNeedSaveTorn = true;                            // TLV database.
                }
            }
            //LogUtils.d(String.format(Locale.US, "isNeedDelTorn:%b,isNeedSaveTorn:%b",isNeedDelTorn,isNeedSaveTorn));
            LogUtils.e(TAG,String.format("isNeedDelTorn:%b,isNeedSaveTorn:%b",isNeedDelTorn,isNeedSaveTorn));
            return ret;
        } else if (flag.flag == DDAFlag.FAIL) {
            return RetCode.CLSS_TERMINATE;
        }

        if (acType.type == ACType.AC_TC) {
            isNeedDelTorn = true;
        }

        ByteArray clearFlag = new ByteArray(1);
        clearFlag.data[0] = 1;
        while (clearFlag.data[0] == 1) {
//            if (isNeedSaveTorn) {
//                ClssPbocApi.Clss_ClearTornLog_Pboc((byte) 1, clearFlag);
//            }
            if (isNeedDelTorn) {
                ClssPbocApi.Clss_ClearTornLog_Pboc((byte) 0, clearFlag);
            }
        }

        return RetCode.EMV_OK;
    }

    private int currentTornProcess() {
        return 0;
    }

    private boolean continueUpdateResult(ACType acType, TransResult result) {
        if (acType.type == ACType.AC_AAC) {
            result.setTransResult(TransResultEnum.RESULT_OFFLINE_DENIED);
            return false;
        }

        if (transactionPath.path == TransactionPath.CLSS_VISA_QVSDC) {
            if (processQVSDC(acType, result) != RetCode.EMV_OK) {
                return false;
            }
        } else if (transactionPath.path == TransactionPath.CLSS_VISA_VSDC) {
            if (processVSDC(acType, result) != RetCode.EMV_OK) {
                return false;
            }
        } else {
            return false;
        }

        if (acType.type == ACType.AC_TC) {
            result.setTransResult(TransResultEnum.RESULT_OFFLINE_APPROVED);
        } else if (acType.type == ACType.AC_ARQC) {
            result.setTransResult(TransResultEnum.RESULT_REQ_ONLINE);
        }
        return true;
    }

    private int processQVSDC(ACType acType, TransResult result) {
        if ((acType.type == ACType.AC_TC)
                && transParam.ucTransType != 0x20) { //no refund
            //according to EDC
            ClssPbocApi.Clss_DelAllRevocList_Pboc();
            ClssPbocApi.Clss_DelAllCAPK_Pboc();
            addCapkRevList();

            DDAFlag flag = new DDAFlag();
            int ret = ClssPbocApi.Clss_CardAuth_Pboc(acType, flag);
            LogUtils.i(TAG, "clssPbocCardAuth ret = " + ret);
            if (ret != RetCode.EMV_OK) {
                if (ret == RetCode.CLSS_USE_CONTACT) {
                    result.setTransResult(TransResultEnum.RESULT_CLSS_TRY_ANOTHER_INTERFACE);
                }
                return ret;
            } else if (flag.flag == DDAFlag.FAIL) {
                result.setTransResult(TransResultEnum.RESULT_OFFLINE_DENIED);
                return RetCode.CLSS_FAILED;
            }
        }
        return RetCode.EMV_OK;
    }

    private int processVSDC(ACType acType, TransResult result) {
        if ((acType.type == ACType.AC_TC)
                && transParam.ucTransType != 0x20) { //no refund
            //according to EDC
            ClssPbocApi.Clss_DelAllRevocList_Pboc();
            ClssPbocApi.Clss_DelAllCAPK_Pboc();
            addCapkRevList();

            DDAFlag flag = new DDAFlag();
            int ret = ClssPbocApi.Clss_CardAuth_Pboc(acType, flag);
            LogUtils.i(TAG, "clssPbocCardAuth ret = " + ret);
            if (ret != RetCode.EMV_OK) {
                result.setTransResult(TransResultEnum.RESULT_OFFLINE_DENIED);
                return ret;
            }
        }
        return RetCode.EMV_OK;
    }

    private void updateResult(TransResult result) {
        CvmType cvmType = new CvmType();
        int ret = ClssPbocApi.Clss_GetCvmType_Pboc(cvmType);
        LogUtils.i(TAG, "clssPbocGetCvmType CVMType = " + cvmType.type);
        if (ret < 0) {
            if (ret == RetCode.CLSS_PARAM_ERR) {
                result.setTransResult(TransResultEnum.RESULT_OFFLINE_DENIED);
            }
            return;
        }

        result.setCvmResult(EmvParamConvert.convertCVM((byte) CvmType.RD_CVM_CONSUMER_DEVICE));

        /* // TODO
        String clssAid = clssParam.getAidValue();
        LogUtils.i(TAG, "Aid " + clssAid);
         */
        // For QPS Rule
        if (isDebit()) {
            // If it is a Debit card, follow the normal process
            // 如果是 Debit 卡片，则按照正常的流程走
            LogUtils.d(TAG, "This AID is Debit");
            result.setCvmResult(EmvParamConvert.convertCVM(cvmType.type));
        } else if (isCredit()) {
            // If it is a Credit card, the terminal needs to compare whether the transaction
            // amount is less than or equal to the QPS Limit. If it is, ignore the CVM returned
            // by the kernel, there should be no signature and input PIN; if not, follow the
            // normal process
            // 如果是 Credit 卡片，则需要终端比较交易金额是否小于等于 QPS Limit。如果是，则忽略内核返回的 CVM，
            // 不应该有签名和输 PIN；如果不是，则按照正常的流程走
           // TODO if (clssParam.getTrueAmount() > clssParam.getQpsLimit()) {
            if (true) {
                // Follow the normal process
                // 按正常的流程走
                // TODO LogUtils.d(TAG, "This AID is Credit, and the amount is greater than QPS Limit: " + clssParam.getQpsLimit());
                result.setCvmResult(EmvParamConvert.convertCVM(cvmType.type));
            } else {
                // There should not be a signature and PIN input, so go directly to the case of No CVM
                // 不应该有签名和输 PIN，所以直接走 No CVM 的情况
                // TODO LogUtils.d(TAG, "This AID is Credit, and the amount is less than QPS Limit: " + clssParam.getQpsLimit());
                result.setCvmResult(CvmResultEnum.CVM_NO_CVM);
            }
        }
    }

    private boolean isDebit() {
       //TODO return clssParam.getAidType() == DEBIT_TYPE;
        return true;
    }

    private boolean isCredit() {
        //TODO return clssParam.getAidType() == CREDIT_TYPE || clssParam.getAidType() == QCREDIT_TYPE;
        return false;
    }

    @Override
    public TransResult completeTransProcess(IssuerRspData issuerRspData) {
        return new TransResult(RetCode.EMV_OK, TransResultEnum.RESULT_ONLINE_APPROVED, CvmResultEnum.CVM_NO_CVM);
    }

    @Override
    public int getTlv(int tag, ByteArray value) {
        int ret = ClssPbocApi.Clss_GetTLVData_Pboc((short) tag, value);
        LogUtils.d(TAG, "getTlv: "+ tag+","+value);
        return ret;
    }

    @Override
    public int setTlv(int tag, byte[] value) {
        LogUtils.d(TAG, "setTlv: "+ tag+","+value);
        return ClssPbocApi.Clss_SetTLVData_Pboc((short) tag, value, value.length);
    }

    @Override
    protected int addCapkAndRevokeList(EMV_CAPK emvCapk, EMV_REVOCLIST emvRevoclist) {
        int ret = RetCode.EMV_OK;
        if (emvCapk != null) {
            ClssPbocApi.Clss_DelAllCAPK_Pboc();
            ret = ClssPbocApi.Clss_AddCAPK_Pboc(emvCapk);
            if (ret != RetCode.EMV_OK) {
                LogUtils.e(TAG, "Clss_AddCAPK_Pboc ret = " + ret);
                return ret;
            }
        }
        if (emvRevoclist != null) {
            ClssPbocApi.Clss_DelAllRevocList_Pboc();
            return ClssPbocApi.Clss_AddRevocList_Pboc(emvRevoclist);
        }
        return ret;
    }

    @Override
    public String getTrack2() {
        if (track2 == null) {
            ByteArray pbocGetTrack2List = new ByteArray();
            getTlv(TagsTable.TRACK2, pbocGetTrack2List);
            track2 = getTrack2FromTag57(ConvertUtils.bcd2Str(pbocGetTrack2List.data,
                    pbocGetTrack2List.length));
        }
        return track2;
    }

    @Override
    public boolean isNeedSecondTap(IssuerRspData issuerRspData) {
        return false;
    }
}
