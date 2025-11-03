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
 *   20201207  	           John Cai                 Create
 * ===========================================================================================
 */

package com.paxsz.module.emv.xmlparam.entity.clss;

public class ExPayDRL {
    private byte[] programId = new byte[0];
    private byte programIdLen;

    //Dynamic Limit Set Id. 0xFF - Default DRL Set. Only used in AMEX transaction.
    private byte dynamicLimitSet;

    //Reader Contactless Transaction Limit
    private byte[] rdClssTxnLmt;

    //Reader CVM Limit
    private byte[] rdCVMLmt;

    //Indicates the contactless floor limit.
    private byte[] rdClssFloorLmt;

    //Terminal Floor Limit
    private byte[] termFloorLmt;

    //Indicates the contactless floor limit is present or not
    private byte rdClssFloorLmtFlg;

    //Reader Contactless Transaction Limit is present or not
    private byte rdClssTxnLmtFlg;

    //Reader CVM Limit is present or not
    private byte rdCVMLmtFlg;

    //Terminal Floor Limit is present or not
    private byte termFloorLmtFlg;

    //Support Status Check or not
    private byte statusCheckFlg;

    //Amount 0 allow
    private byte amtZeroNoAllowed;

    public byte[] getProgramId() {
        return programId;
    }

    public void setProgramId(byte[] programId) {
        this.programId = programId;
    }

    public byte getProgramIdLen() {
        return programIdLen;
    }

    public void setProgramIdLen(byte programIdLen) {
        this.programIdLen = programIdLen;
    }

    public byte getDynamicLimitSet() {
        return dynamicLimitSet;
    }

    public void setDynamicLimitSet(byte dynamicLimitSet) {
        this.dynamicLimitSet = dynamicLimitSet;
    }

    public byte[] getRdClssTxnLmt() {
        return rdClssTxnLmt;
    }

    public void setRdClssTxnLmt(byte[] rdClssTxnLmt) {
        this.rdClssTxnLmt = rdClssTxnLmt;
    }

    public byte[] getRdCVMLmt() {
        return rdCVMLmt;
    }

    public void setRdCVMLmt(byte[] rdCVMLmt) {
        this.rdCVMLmt = rdCVMLmt;
    }

    public byte[] getRdClssFloorLmt() {
        return rdClssFloorLmt;
    }

    public void setRdClssFloorLmt(byte[] rdClssFloorLmt) {
        this.rdClssFloorLmt = rdClssFloorLmt;
    }

    public byte[] getTermFloorLmt() {
        return termFloorLmt;
    }

    public void setTermFloorLmt(byte[] termFloorLmt) {
        this.termFloorLmt = termFloorLmt;
    }

    public byte getRdClssFloorLmtFlg() {
        return rdClssFloorLmtFlg;
    }

    public void setRdClssFloorLmtFlg(byte rdClssFloorLmtFlg) {
        this.rdClssFloorLmtFlg = rdClssFloorLmtFlg;
    }

    public byte getRdClssTxnLmtFlg() {
        return rdClssTxnLmtFlg;
    }

    public void setRdClssTxnLmtFlg(byte rdClssTxnLmtFlg) {
        this.rdClssTxnLmtFlg = rdClssTxnLmtFlg;
    }

    public byte getRdCVMLmtFlg() {
        return rdCVMLmtFlg;
    }

    public void setRdCVMLmtFlg(byte rdCVMLmtFlg) {
        this.rdCVMLmtFlg = rdCVMLmtFlg;
    }

    public byte getTermFloorLmtFlg() {
        return termFloorLmtFlg;
    }

    public void setTermFloorLmtFlg(byte termFloorLmtFlg) {
        this.termFloorLmtFlg = termFloorLmtFlg;
    }

    public byte getStatusCheckFlg() {
        return statusCheckFlg;
    }

    public void setStatusCheckFlg(byte statusCheckFlg) {
        this.statusCheckFlg = statusCheckFlg;
    }

    public byte getAmtZeroNoAllowed() {
        return amtZeroNoAllowed;
    }

    public void setAmtZeroNoAllowed(byte amtZeroNoAllowed) {
        this.amtZeroNoAllowed = amtZeroNoAllowed;
    }

}
