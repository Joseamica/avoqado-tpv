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
 * 20210607 	        xieYb                  Create
 * ===========================================================================================
 *
 */

package com.paxsz.module.emv.process.contactless;

import com.paxsz.module.emv.process.entity.OnlineResultWrapper;

/**
 * callback during contact process
 */
public interface IContactlessCallback {

    /**
     * finish read card data
     */
    void onReadCardOk();
    /**
     * finish card interaction
     */
    void onRemoveCard();
    /**
     * Return if the last trans returned "Please See Phone" warning
     */
    boolean needSeePhone();

    /**
     * here can  get card info from IEmvContactlessService
     * @return result
     */
    int confirmCard();
    /**
     * When need to enter pin
     * @param isOnlinePin is online pin
     * @param supportPINByPass  supportPINByPass
     * @param leftTimes left retry Times
     * @param pinData pinData
     * @return enter pin result
     */
    int onCardHolderPwd(boolean isOnlinePin, boolean supportPINByPass, int leftTimes, byte[] pinData);

    /**
     * need show enterTip ui
     * @return result
     */
    int showEnterTip();

    /**
     * need show online process
     * @return OnlineResultWrapper
     */
    OnlineResultWrapper startOnlineProcess(int result);

    /**
     * show second detect card
     */
    void onDetect2ndTap();
}
