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
 * 20200528  	         JackHuang               Create
 * ===========================================================================================
 */
package com.paxsz.module.emv.process.contactless;

import com.pax.jemv.clcommon.Clss_PreProcInterInfo;
import com.pax.jemv.clcommon.Clss_TransParam;
import com.pax.jemv.clcommon.KernType;
import com.paxsz.module.emv.param.EmvProcessParam;

class ClssKernelProcessFactory {
    private int kernelType;
    private EmvProcessParam emvProcessParam;
    private byte[] finalSelectData;
    private int finalSelectDataLen;
    private Clss_TransParam transParam;
    private Clss_PreProcInterInfo preProcInterInfo;
    private IContactlessCallback clssStatusListener;

    public ClssKernelProcessFactory(int kernelType) {
        this.kernelType = kernelType;
    }

    /**
     * when need to add a new clss kernel, create a object here
     */

    public ClssKernelProcess getKernelProcess() {
        switch (kernelType) {
            case KernType.KERNTYPE_VIS:
                return new ClssPayWaveProcess();
            case KernType.KERNTYPE_MC:
                return new ClssPayPassProcess();
            case KernType.KERNTYPE_AE:
                return new ClssAmericanExpressProcess();
            //TODO incluir los demas kernel
            /*
            case KernType.KERNTYPE_PBOC:
                clssParam = emvProcessParam.getPbocParam();
                return Router.getService(ClssKernelProcess.class, EmvKernelConst.PBOC);
            case KernType.KERNTYPE_EFT:
                clssParam = emvProcessParam.getEftParam();
                return Router.getService(ClssKernelProcess.class, EmvKernelConst.EFT);
            case KernType.KERNTYPE_JCB:
                clssParam = emvProcessParam.getJcbParam();
                return Router.getService(ClssKernelProcess.class, EmvKernelConst.JCB);
            case KernType.KERNTYPE_MIR:
                clssParam = emvProcessParam.getMirParam();
                return Router.getService(ClssKernelProcess.class, EmvKernelConst.MIR);
            case KernType.KERNTYPE_PURE:
                clssParam = emvProcessParam.getPureParam();
                return Router.getService(ClssKernelProcess.class, EmvKernelConst.PURE);
            case KernType.KERNTYPE_RUPAY:
                clssParam = emvProcessParam.getRuPayParam();
                return Router.getService(ClssKernelProcess.class, EmvKernelConst.RUPAY);
            case KernType.KERNTYPE_ZIP:
                clssParam = emvProcessParam.getDpasParam();
                return Router.getService(ClssKernelProcess.class, EmvKernelConst.DPAS);
                 */
            default:
                throw new IllegalArgumentException("Unsupported Kernel " + kernelType);
        }
    }
}
