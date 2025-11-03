/*
 * ===========================================================================================
 * = COPYRIGHT
 *          PAX Computer Technology(Shenzhen) CO., LTD PROPRIETARY INFORMATION
 *   This software is supplied under the terms of a license agreement or nondisclosure
 *   agreement with PAX Computer Technology(Shenzhen) CO., LTD and may not be copied or
 *   disclosed except in accordance with the terms in that agreement.
 *     Copyright (C) 2019-? PAX Computer Technology(Shenzhen) CO., LTD All rights reserved.
 * Description: // Detail description about the function of this module,
 *             // interfaces with the other modules, and dependencies.
 * Revision History:
 * Date	                Author	               Action
 * 20210610 	        xieYb                  Create
 * ===========================================================================================
 *
 */
package com.paxsz.module.emv.process.entity;

import com.paxsz.module.emv.process.enums.TransResultEnum;

public class OnlineResultWrapper {
    private int resultCode;
    private IssuerRspData issuerRspData;
    private TransResultEnum transResultEnum;
    public OnlineResultWrapper() {
        // do nothing
    }

    public int getResultCode() {
        return resultCode;
    }

    public void setResultCode(int resultCode) {
        this.resultCode = resultCode;
    }

    public IssuerRspData getIssuerRspData() {
        return issuerRspData;
    }

    public void setIssuerRspData(IssuerRspData issuerRspData) {
        this.issuerRspData = issuerRspData;
    }

    public TransResultEnum getTransResultEnum() {
        return transResultEnum;
    }

    public void setTransResultEnum(TransResultEnum transResultEnum) {
        this.transResultEnum = transResultEnum;
    }
}
