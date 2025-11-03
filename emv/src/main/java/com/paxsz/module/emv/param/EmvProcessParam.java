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

package com.paxsz.module.emv.param;

import com.paxsz.module.emv.xmlparam.entity.clss.AmexParam;
import com.paxsz.module.emv.xmlparam.entity.clss.PayPassAid;
import com.paxsz.module.emv.xmlparam.entity.clss.PayPassParam;
import com.paxsz.module.emv.xmlparam.entity.clss.PayWaveAid;
import com.paxsz.module.emv.xmlparam.entity.clss.PayWaveParam;
import com.paxsz.module.emv.xmlparam.entity.common.CapkParam;
import com.paxsz.module.emv.xmlparam.entity.common.Config;
import com.paxsz.module.emv.xmlparam.entity.contact.EmvAid;

import java.util.ArrayList;

public class EmvProcessParam {

    private ArrayList<EmvAid> emvAidList;
    private EmvTransParam emvTransParam;
    private CapkParam capkParam;
    private Config termConfig;
    //private EFTParam eftParam;
    //private MirParam mirParam;
    //private DpasParam dpasParam;
    //private JcbParam jcbParam;
    //private PbocParam pbocParam;
    //private PureParam pureParam;
    //private RuPayParam ruPayParam;
    private PayWaveParam payWaveParam;
    private PayPassParam payPassParam;
    private AmexParam amexParam;

    private EmvProcessParam(Builder builder) {
        this.emvTransParam = builder.emvTransParam;
        this.capkParam = builder.capkParam;
        this.emvAidList = builder.emvAidList;
        this.payPassParam = builder.payPassParam;
        this.payWaveParam = builder.payWaveParam;
        this.amexParam = builder.amexParam;
        this.termConfig = builder.termConfig;
    }

    public EmvTransParam getEmvTransParam() {
        return emvTransParam;
    }

    public CapkParam getCapkParam() {
        return capkParam;
    }

    public ArrayList<EmvAid> getEmvAidList() {
        return emvAidList;
    }

    public PayPassParam getPayPassParam() {
        return payPassParam;
    }

    public PayWaveParam getPayWaveParam() {
        return payWaveParam;
    }

    public Config getTermConfig() {
        return termConfig;
    }
/*
    public EFTParam getEftParam() {
        return eftParam;
    }

    public MirParam getMirParam() {
        return mirParam;
    }

    public DpasParam getDpasParam() {
        return dpasParam;
    }

    public JcbParam getJcbParam() {
        return jcbParam;
    }

    public PbocParam getPbocParam() {
        return pbocParam;
    }

    public PureParam getPureParam() {
        return pureParam;
    }

    public RuPayParam getRuPayParam() {
        return ruPayParam;
    }

 */
    public AmexParam getAmexParam() {
        return amexParam;
    }

    public final static class Builder {
        private EmvTransParam emvTransParam;
        private CapkParam capkParam;
        private Config termConfig;
        private ArrayList<EmvAid> emvAidList;
        private PayPassParam payPassParam;
        private PayWaveParam payWaveParam;
        private AmexParam amexParam;
        /*
        private EFTParam eftParam;
        private MirParam mirParam;
        private DpasParam dpasParam;
        private JcbParam jcbParam;
        private PbocParam pbocParam;
        private PureParam pureParam;
        private RuPayParam ruPayParam;
         */

        public Builder(EmvTransParam transParam, Config termConfigParam, CapkParam capkParam) {
            this.emvTransParam = transParam;
            this.termConfig = termConfigParam;
            this.capkParam = capkParam;
        }

        public Builder setEmvAidList(ArrayList<EmvAid> emvAidList) {
            this.emvAidList = emvAidList;
            return this;
        }

        public Builder setPayPassParam(PayPassParam payPassParam) {
            this.payPassParam = payPassParam;
            return this;
        }

        public Builder setPayWaveParam(PayWaveParam payWaveParam) {
            this.payWaveParam = payWaveParam;
            return this;
        }

        public Builder setExpressPayParam(AmexParam amexParam) {
            this.amexParam = amexParam;
            return this;
        }

        public EmvProcessParam create() {
            return new EmvProcessParam(this);
        }

    }


}
