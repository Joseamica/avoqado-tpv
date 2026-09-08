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
 * 20200518  	         JackHuang               Create
 * ===========================================================================================
 */
package com.paxsz.module.emv.xmlparam.entity.common;
public class Config {
    //tag 9F16, Merchant Identifier
    private byte[] merchantId;

    //tag 9F4E, Merchant Name and Location
    private String merchantNameAndLocation;
    private byte[] merchantNameAndLocationBytes;

    //tag 9F15, Merchant Category Code
    private byte[] merchantCategoryCode;

    //tag 9F1A, Terminal Country Code
    private byte[] terminalCountryCode;

    /*
     * tag 9F1C, Terminal Identification.
     *
     * 🔴 Añadido el 2026-09-08 para que la variante SANDBOX pueda cobrar con tarjeta.
     * El SDK de Blumon `blumon_sdk-1.6.1.2-sandbox` (integrado el 28-ago) llama
     * `Config.setTerminalID(byte[])` desde `TerminalConfigParser.parse`, y esta clase —la
     * versión del módulo :emv que trae el proyecto— no lo tenía: la app moría con
     * `NoSuchMethodError` al configurar el kernel EMV, o sea en CUANTO se pedía tarjeta
     * (medido en la PAX A910S 2841548417, APK 2.8.7-sandbox).
     *
     * Producción NO estaba afectada y no cambia con esto: `blumon_sdk-prod` no invoca este
     * método (comprobado desensamblando las dos versiones de `TerminalConfigParser$Companion`:
     * la de producción llama 8 setters, la sandbox 9). El campo es aditivo — nadie que no lo
     * llame nota diferencia.
     *
     * ⚠️ Parche LOCAL sobre una librería de PAX: la solución de fondo es que Blumon/PAX
     * entreguen el módulo :emv actualizado junto con el SDK. Si llega esa versión, este bloque
     * se retira. Hay que avisarlo antes de mover producción al SDK 1.6.1.2.
     */
    private byte[] terminalID;

    //The Symbol show before amount, such  as: $1234
    private String terminalCurrencySymbol;

    //tag 9F3C, Transaction Reference Currency Code
    private byte[] transReferenceCurrencyCode;

    //tag 9F3D, Transaction Reference Currency Exponent
    private byte transReferenceCurrencyExponent;

    //Transaction Reference Currency Conversion
    private long conversionRatio;

    public Config() {
        merchantId = new byte[0];
        merchantNameAndLocation = "";
        merchantNameAndLocationBytes = new byte[0];
        merchantCategoryCode = new byte[0];
        terminalCountryCode = new byte[0];
        terminalID = new byte[0];
        terminalCurrencySymbol = "";
        transReferenceCurrencyCode = new byte[0];
    }

    public byte[] getMerchantId() {
        return merchantId;
    }

    public void setMerchantId(byte[] merchantId) {
        this.merchantId = merchantId;
    }

    public String getMerchantNameAndLocation() {
        return merchantNameAndLocation;
    }

    public void setMerchantNameAndLocation(String merchantNameAndLocation) {
        this.merchantNameAndLocation = merchantNameAndLocation;
    }

    public byte[] getMerchantNameAndLocationBytes() {
        return merchantNameAndLocationBytes;
    }

    public void setMerchantNameAndLocationBytes(byte[] merchantNameAndLocationBytes) {
        this.merchantNameAndLocationBytes = merchantNameAndLocationBytes;
    }

    public byte[] getMerchantCategoryCode() {
        return merchantCategoryCode;
    }

    public void setMerchantCategoryCode(byte[] merchantCategoryCode) {
        this.merchantCategoryCode = merchantCategoryCode;
    }

    public byte[] getTerminalCountryCode() {
        return terminalCountryCode;
    }

    public void setTerminalCountryCode(byte[] terminalCountryCode) {
        this.terminalCountryCode = terminalCountryCode;
    }

    public byte[] getTerminalID() {
        return terminalID;
    }

    public void setTerminalID(byte[] terminalID) {
        this.terminalID = terminalID;
    }

    public String getTerminalCurrencySymbol() {
        return terminalCurrencySymbol;
    }

    public void setTerminalCurrencySymbol(String terminalCurrencySymbol) {
        this.terminalCurrencySymbol = terminalCurrencySymbol;
    }

    public byte[] getTransReferenceCurrencyCode() {
        return transReferenceCurrencyCode;
    }

    public void setTransReferenceCurrencyCode(byte[] transReferenceCurrencyCode) {
        this.transReferenceCurrencyCode = transReferenceCurrencyCode;
    }

    public byte getTransReferenceCurrencyExponent() {
        return transReferenceCurrencyExponent;
    }

    public void setTransReferenceCurrencyExponent(byte transReferenceCurrencyExponent) {
        this.transReferenceCurrencyExponent = transReferenceCurrencyExponent;
    }

    public long getConversionRatio() {
        return conversionRatio;
    }

    public void setConversionRatio(long conversionRatio) {
        this.conversionRatio = conversionRatio;
    }
}

