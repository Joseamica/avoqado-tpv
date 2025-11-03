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
 * 2021/06/22                     YeHongbo                    Create
 * ===========================================================================================
 */

package com.paxsz.module.emv.xmlparam.entity.clss;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class AmexParam{

    private final List<ExPayDRL> amexDRLs = new ArrayList<>();
    private byte[] appName;
    private byte[] aid;
    private byte selFlag;
    private byte[] tacDefault;
    private byte[] tacDenial;
    private byte[] tacOnline;
    private byte[] termCapability;
    private byte[] termAddCapability;
    private byte[] unpredictableNumberRange;
    private byte[] unRange = new byte[0];
    private byte supportOptTrans;
    private byte[] termTransCap;
    private byte delayAuthSupport;
    private byte[] dDOL;
    private byte[] tDOL;
    private long clssFloorLimit;
    private byte clssFloorLimitCheck;
    private byte exPayRdCap;
    private byte[] exFunction;
    private byte[] aucRFU;
    private byte supportFullOnline;
    private byte[] exTermCapability;
    private byte referCurrExp;

    private byte[] termId;

    private long cvmLimit;
    /**
     * Reader CVM required limit check or not
     * <ul>
     *     <li>0: Deactivated</li>
     *     <li>1: Active and exist</li>
     *     <li>2: Active but not exist</li>
     * </ul>
     */
    private byte cvmLimitFlag;
    /**
     * Reader contactless transaction limit (No On-device CVM)
     */
    private long transLimit;
    /**
     * Reader contactless transaction limit (No On-device CVM) check or not
     * <ul>
     *     <li>0: Deactivated</li>
     *     <li>1: Active and exist</li>
     *     <li>2: Active but not exist</li>
     * </ul>
     */
    private byte transLimitFlag;
    /**
     * Reader contactless floor limit
     */
    private long floorLimit;
    /**
     * Reader contactless floor limit check or not
     * <ul>
     *     <li>0: Deactivated</li>
     *     <li>1: Active and exist</li>
     *     <li>2: Active but not exist</li>
     * </ul>
     */
    private byte floorLimitFlag;

    /**
     * Acquirer Identifier
     */
    private byte[] acquirerId;
    /**
     * AID Version
     */
    private byte[] version;

    /**
     * Terminal Type
     * <p>
     *     Operational Control Provided By:
     *     <ul>
     *         <li>1? - Financial Institution</li>
     *         <li>2? - Merchant</li>
     *         <li>3? - Cardholder</li>
     *     </ul>
     * </p>
     * <p>
     *     Environment:
     *     <ul>
     *         <li>?1 - Attended, Online only</li>
     *         <li>?2 - Attended, Offline with online capability</li>
     *         <li>?3 - Attended, Offline only</li>
     *         <li>?4 - Unattended, Online only</li>
     *         <li>?5 - Unattended, Offline with online capability</li>
     *         <li>?6 - Unattended, Offline only</li>
     *     </ul>
     * </p>
     * <p>For example, '22' means operational control provided by 'Merchant', environment is
     *     attended, offline with online capability.</p>
     * <p>31, 32, 33 do not exist.</p>
     */
    private byte termType;

    protected List<AmexAid> aidList;


    public AmexParam() {
        // You can set default values for some fields here
        exFunction = new byte[]{1};
        unpredictableNumberRange = new byte[]{0x00, 0x60};
        supportOptTrans = 1;    // true: 1, false: 0
        aucRFU = new byte[27];
        supportFullOnline = 0;  // support: 1, do not support: 0
    }

    public final void setAidList(List<AmexAid> aidList) {
        this.aidList = aidList;
    }
    public final List<AmexAid> getAidList() {return this.aidList;}
    public byte[] getAppName() {
        return this.appName;
    }

    public void setAppName(byte[] appName) {
        this.appName = appName;
    }

   public byte[] getAid() {return this.aid;}

    public void setAid(byte[] aid) {
        this.aid = aid;
    }
    public byte getSelFlag() {
        return selFlag;
    }

    /**
     * Set whether the AID supports partial name selection or not.
     *
     * @param selFlag <ul>
     *     <li>1: Does Not Support</li>
     *     <li>0: Supports</li>
     * </ul>
     */
    public void setSelFlag(byte selFlag) {
        this.selFlag = selFlag;
    }


    public long getCvmLimit() {
        return cvmLimit;
    }

    /**
     * Set reader CVM required limit.
     *
     * @param cvmLimit CVM required limit value
     * @see #cvmLimit
     */
    public void setCvmLimit(long cvmLimit) {
        this.cvmLimit = cvmLimit;
    }

    /**
     * <p>Get reader contactless transaction limit (No On-device CVM).</p>
     * <p>
     *     This method should only be called when the <code>long</code> value of
     *     <code>transLimit</code> needs to be read. Please <b>DO NOT</b> get this value in the
     *     contactless kernel process and then perform type conversion, such as converting from
     *     <code>long</code> type to <code>byte</code> array. Doing so will increase the time
     *     consumption of the contactless kernel process.
     * </p>
     * <p>
     *      If you need to read other types of the value in the contactless kernel, the correct
     *      approach is to declare a corresponding type of field in the Aid class and parameter
     *      class of the contactless kernel, convert the data in the preprocessing stage of the
     *      contactless process and set it to Aid class and parameter class.
     * </p>
     *
     * @return transaction limit value
     * @see #transLimit
     */
    public long getTransLimit() {
        return transLimit;
    }

    /**
     * Set reader contactless transaction limit (No On-device CVM).
     *
     * @param transLimit transaction limit value
     * @see #transLimit
     */
    public void setTransLimit(long transLimit) {
        this.transLimit = transLimit;
    }

    /**
     * <p>Get reader contactless floor limit.</p>
     * <p>
     *     This method should only be called when the <code>long</code> value of
     *     <code>floorLimit</code> needs to be read. Please <b>DO NOT</b> get this value in the
     *     contactless kernel process and then perform type conversion, such as converting from
     *     <code>long</code> type to <code>byte</code> array. Doing so will increase the time
     *     consumption of the contactless kernel process.
     * </p>
     * <p>
     *      If you need to read other types of the value in the contactless kernel, the correct
     *      approach is to declare a corresponding type of field in the Aid class and parameter
     *      class of the contactless kernel, convert the data in the preprocessing stage of the
     *      contactless process and set it to Aid class and parameter class.
     * </p>
     *
     * @return floor limit value
     * @see #floorLimit
     */
    public long getFloorLimit() {
        return floorLimit;
    }

    /**
     * Set reader contactless floor limit.
     *
     * @param floorLimit floor limit value
     * @see #floorLimit
     */
    public void setFloorLimit(long floorLimit) {
        this.floorLimit = floorLimit;
    }

    /**
     * Get reader contactless floor limit check or not.
     *
     * @return Reader contactless floor limit check or not
     * @see #floorLimitFlag
     */
    public byte getFloorLimitFlag() {
        return floorLimitFlag;
    }

    /**
     * Set reader contactless floor limit check or not.
     *
     * @param floorLimitFlag Reader contactless floor limit check or not
     * @see #floorLimitFlag
     */
    public void setFloorLimitFlag(byte floorLimitFlag) {
        this.floorLimitFlag = floorLimitFlag;
    }

    /**
     * Get reader contactless transaction limit (No On-device CVM) check or not.
     *
     * @return Reader contactless transaction limit (No On-device CVM) check or not
     * @see #transLimitFlag
     */
    public byte getTransLimitFlag() {
        return transLimitFlag;
    }

    /**
     * Set reader contactless transaction limit (No On-device CVM) check or not.
     *
     * @param transLimitFlag Reader contactless transaction limit (No On-device CVM) check or not
     * @see #transLimitFlag
     */
    public void setTransLimitFlag(byte transLimitFlag) {
        this.transLimitFlag = transLimitFlag;
    }

    /**
     * Get reader CVM required limit check or not.
     *
     * @return Reader CVM required limit check or not
     * @see #cvmLimitFlag
     */
    public byte getCvmLimitFlag() {
        return cvmLimitFlag;
    }

    /**
     * Set reader CVM required limit check or not.
     *
     * @param cvmLimitFlag Reader CVM required limit check or not
     * @see #cvmLimitFlag
     */
    public void setCvmLimitFlag(byte cvmLimitFlag) {
        this.cvmLimitFlag = cvmLimitFlag;
    }

    /**
     * Get acquirer identifier.
     *
     * @return Acquirer ID
     */
    public byte[] getAcquirerId() {
        return acquirerId;
    }

    /**
     * Set acquirer identifier.
     *
     * @param acquirerId Acquirer ID
     */
    public void setAcquirerId(byte[] acquirerId) {
        this.acquirerId = acquirerId;
    }

    /**
     * Get application version bytes.
     *
     * @return Application version bytes
     */
    public byte[] getVersion() {
        return version;
    }

    /**
     * Set application version bytes.
     *
     * @param version Application version bytes
     */
    public void setVersion(byte[] version) {
        this.version = version;
    }

    /**
     * Get terminal type.
     *
     * @return Terminal type
     * @see #termType
     */
    public byte getTermType() {
        return termType;
    }

    /**
     * Set terminal type.
     *
     * @param termType Terminal type
     * @see #termType
     */
    public void setTermType(byte termType) {
        this.termType = termType;
    }

    public byte[] getExTermCapability() {
        return exTermCapability;
    }

    // Setter para exTermCapability
    public void setExTermCapability(byte[] exTermCapability) {
        this.exTermCapability = exTermCapability;
    }

    // Getter para referCurrExp
    public byte getReferCurrExp() {
        return referCurrExp;
    }

    // Setter para referCurrExp
    public void setReferCurrExp(byte referCurrExp) {
        this.referCurrExp = referCurrExp;
    }

    public byte[] getTermId() {
        return termId;
    }

    // Setter para termId
    public void setTermId(byte[] termId) {
        this.termId = termId;
    }

    public void setUnpredictableNumberRange(byte[] unpredictableNumberRange) {
        this.unpredictableNumberRange = unpredictableNumberRange;
    }

    public byte[] getUnpredictableNumberRange() {
        return this.unpredictableNumberRange;
    }

    public byte[] getTacDenial() {
        return tacDenial;
    }

    /**
     * Set Terminal Action Code – Denial.
     *
     * @param tacDenial Terminal Action Code – Denial
     */
    public void setTacDenial(byte[] tacDenial) {
        this.tacDenial = tacDenial;
    }

    /**
     * Get Terminal Action Code – Online.
     *
     * @return Terminal Action Code – Online
     */
    public byte[] getTacOnline() {
        return tacOnline;
    }

    /**
     * Set Terminal Action Code – Online.
     *
     * @param tacOnline Terminal Action Code – Online
     */
    public void setTacOnline(byte[] tacOnline) {
        this.tacOnline = tacOnline;
    }

    /**
     * Get Terminal Action Code – Default.
     *
     * @return Terminal Action Code – Default
     */
    public byte[] getTacDefault() {
        return tacDefault;
    }

    /**
     * Set Terminal Action Code – Default.
     *
     * @param tacDefault Terminal Action Code – Default
     */
    public void setTacDefault(byte[] tacDefault) {
        this.tacDefault = tacDefault;
    }

    /**
     * Get terminal capabilities bytes.
     *
     * @return Terminal capabilities bytes
     */
    public byte[] getTermCapability() {
        return termCapability;
    }

    /**
     * Set terminal capabilities bytes.
     *
     * @param termCapability Terminal capabilities bytes
     */
    public void setTermCapability(byte[] termCapability) {
        this.termCapability = termCapability;
    }

    /**
     * Get support the optimization mode transaction flag.
     *
     * @return Support the optimization mode transaction flag
     */
    public byte getSupportOptTrans() {
        return supportOptTrans;
    }

    /**
     * Set support the optimization mode transaction flag.
     *
     * @param supportOptTrans Support the optimization mode transaction flag
     */
    public void setSupportOptTrans(byte supportOptTrans) {
        this.supportOptTrans = supportOptTrans;
    }

    /**
     * Get unpredictable number range.
     *
     * @return Unpredictable number range

     */
    public byte[] getUnRange() {
        return unRange;
    }

    /**
     * Set unpredictable number range.
     *
     * @param unRange Unpredictable number range
     * @see #unRange
     */
    public void setUnRange(byte[] unRange) {
        this.unRange = unRange;
    }

    /**
     * Get terminal transaction capabilities.
     *
     * @return Terminal transaction capabilities
     * @see #termTransCap
     */
    public byte[] getTermTransCap() {
        return termTransCap;
    }

    /**
     * Set terminal transaction capabilities.
     *
     * @param termTransCap Terminal transaction capabilities
     * @see #termTransCap
     */
    public void setTermTransCap(byte[] termTransCap) {
        this.termTransCap = termTransCap;
    }

    /**
     * Get delayed authorization support flag.
     *
     * @return Delay authorization support flag
     * @see #delayAuthSupport
     */
    public byte getDelayAuthSupport() {
        return delayAuthSupport;
    }

    /**
     * Set delayed authorization support flag.
     *
     * @param delayAuthSupport Delay authorization support flag
     * @see #delayAuthSupport
     */
    public void setDelayAuthSupport(byte delayAuthSupport) {
        this.delayAuthSupport = delayAuthSupport;
    }

    /**
     * Get contactless reader capabilities.
     *
     * @return Contactless reader capabilities
     * @see #exPayRdCap
     */
    public byte getExPayRdCap() {
        return exPayRdCap;
    }

    /**
     * Set contactless reader capabilities.
     *
     * @param exPayRdCap Contactless reader capabilities
     * @see #exPayRdCap
     */
    public void setExPayRdCap(byte exPayRdCap) {
        this.exPayRdCap = exPayRdCap;
    }

    /**
     * Get discretionary data object list.
     *
     * @return Discretionary data object list
     * @see #dDOL
     */
    public byte[] getdDOL() {
        return dDOL;
    }

    /**
     * Set discretionary data object list.
     *
     * @param dDOL Discretionary data object list
     * @see #dDOL
     */
    public void setdDOL(byte[] dDOL) {
        this.dDOL = dDOL;
    }

    /**
     * Get transaction certificate data object list.
     *
     * @return Transaction certificate data object list
     * @see #tDOL
     */
    public byte[] gettDOL() {
        return tDOL;
    }

    /**
     * Set transaction certificate data object list.
     *
     * @param tDOL Transaction certificate data object list
     * @see #tDOL
     */
    public void settDOL(byte[] tDOL) {
        this.tDOL = tDOL;
    }

    /**
     * Get additional terminal capability bytes.
     *
     * @return Additional terminal capability bytes
     */
    public byte[] getTermAddCapability() {
        return this.termAddCapability;
    }

    /**
     * Set additional terminal capability bytes.
     *
     * @param termAddCapability Additional terminal capability bytes
     */
    public void setTermAddCapability(byte[] termAddCapability) {
        this.termAddCapability = termAddCapability;
    }

    /**
     * Get extend function parameter.
     *
     * <p>
     * This parameter will be used as a parameter of the {@code Clss_SetExtendFunction_AE()} method
     * </p>
     *
     * @return Extend function parameter
     * @see #exFunction
     */
    public byte[] getExFunction() {
        return exFunction;
    }

    /**
     * Set extend function parameter.
     *
     * <p>
     * This parameter will be used as a parameter of the {@code Clss_SetExtendFunction_AE()} method
     * </p>
     *
     * @param exFunction Extend function parameter
     * @see #exFunction
     */
    public void setExFunction(byte[] exFunction) {
        this.exFunction = exFunction;
    }

    /**
     * Get the third parameter of the {@code Clss_AddReaderParam_AE} class constructor.
     *
     * @return Third parameter of the {@code Clss_AddReaderParam_AE} class constructor
     */
    public byte[] getAucRFU() {
        return aucRFU;
    }

    /**
     * Set the third parameter of the {@code Clss_AddReaderParam_AE} class constructor.
     *
     * @param aucRFU Third parameter of the {@code Clss_AddReaderParam_AE} class constructor
     */
    public void setAucRFU(byte[] aucRFU) {
        this.aucRFU = aucRFU;
    }

    /**
     * Get support full online flag.
     *
     * @return Support full online flag
     * @see #supportFullOnline
     */
    public byte getSupportFullOnline() {
        return supportFullOnline;
    }

    /**
     * Set support full online flag.
     *
     * @param supportFullOnline Support full online flag
     * @see #supportFullOnline
     */
    public void setSupportFullOnline(byte supportFullOnline) {
        this.supportFullOnline = supportFullOnline;
    }

    public long getClssFloorLimit() {
        return clssFloorLimit;
    }

    public void setClssFloorLimit(long clssFloorLimit) {
        this.clssFloorLimit = clssFloorLimit;
    }

    public byte getClssFloorLimitCheck() {
        return clssFloorLimitCheck;
    }

    public void setClssFloorLimitCheck(byte clssFloorLimitCheck) {
        this.clssFloorLimitCheck = clssFloorLimitCheck;
    }

    /**
     * Get AMEX DRL list.
     *
     * @return DRL list
     */
    public List<ExPayDRL> getAmexDRLs() {
        return amexDRLs;
    }

    /**
     * Add AMEX DRL.
     *
     * @param drl DRL
     */
    public void addDrl(ExPayDRL drl) {
        if (contains(drl) == null) {
            amexDRLs.add(drl);
        }
    }

    /**
     * Determine whether there is a same DRL.
     *
     * @param drl Need to determine whether this DRL has been added
     * @return If the DRL already exists, return the DRL; if it does not exist, return {@code null}
     */
    public ExPayDRL contains(ExPayDRL drl) {
        if (amexDRLs.contains(drl)) {
            return drl;
        } else {
            for (ExPayDRL drl1 : amexDRLs) {
                if (Arrays.equals(drl.getProgramId(), drl1.getProgramId())) {
                    return drl1;
                }
            }
        }
        return null;
    }
}