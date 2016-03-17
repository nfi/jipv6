/*
 * Copyright (c) 2016, Yanzi Networks AB.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   1. Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *   2. Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *   3. Neither the name of the copyright holders nor the
 *      names of its contributors may be used to endorse or promote products
 *      derived from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
 * SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
 * LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF
 * USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT
 * OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF
 * SUCH DAMAGE.
 *
 * This file is part of jipv6.
 *
 */

package se.sics.jipv6.yal;

import java.util.Arrays;
import java.util.zip.CRC32;

/**
 *
 */
public class Encap {

    private static final boolean DEBUG = false;

    private static final int MAX_VERSION = 2;

    private static final int LENOPT_OPTION_CRC       = 1;
    private static final int LENOPT_OPTION_SEQNO_CRC = 2;

    private int version;
    private int errorCode;
    private PayloadType payloadType;
    private int payloadTypeCode;
    private FingerPrintMode fingerPrintMode;
    private IVMode initVectorMode;
    private byte[] payloadData;
    private int optLen = 0;
    private int optLenOption = 0;
    private boolean crcEnabled = false;

    private Encap() {
        //
    }

    public static Encap createSerial(byte[] serial) {
        Encap encap = new Encap();
        encap.payloadType = PayloadType.SERIAL;
        encap.payloadTypeCode = PayloadType.SERIAL.getType();
        encap.fingerPrintMode = FingerPrintMode.LENOPT;
        encap.optLenOption = LENOPT_OPTION_CRC;
        encap.crcEnabled = true;
        encap.initVectorMode = IVMode.NONE;
        encap.payloadData = serial;
        return encap;
    }

    /* Only handles serial with CRC at the moment */
    public byte[] generateBytes() {
        if (this.fingerPrintMode != FingerPrintMode.LENOPT) {
            throw new IllegalStateException("Only FP LENOPT supported: " + this.fingerPrintMode);
        }
        if (this.initVectorMode != IVMode.NONE) {
            throw new IllegalStateException("Init vector not yet supported: " + this.initVectorMode);
        }
        int encapSize = 4 + this.fingerPrintMode.getSize() + this.initVectorMode.getSize();
        if(this.crcEnabled) {
            encapSize += 4;
        }
        byte[] data = new byte[payloadData.length + encapSize];
        data[0] = (byte) (version << 4);
        data[1] = (byte) payloadTypeCode;
        data[2] = (byte) errorCode;
        data[3] = (byte) (fingerPrintMode.getMode() << 4 | initVectorMode.getMode());
        data[4] = (byte) 0;
        data[5] = (byte) this.optLenOption;
        data[6] = (byte) (payloadData.length / 256);
        data[7] = (byte) (payloadData.length & 255);
        System.arraycopy(payloadData, 0, data, 8, payloadData.length);
        int pos = 8 + payloadData.length;
        if (this.crcEnabled) {
            CRC32 crc = new CRC32();
            crc.update(data, 0, pos);

            data[pos] = (byte) (crc.getValue() >> 0L);
            data[pos + 1] = (byte) (crc.getValue() >> 8);
            data[pos + 2] = (byte) (crc.getValue() >> 16);
            data[pos + 3] = (byte) (crc.getValue() >> 24);
        }
        return data;
    }

    public static Encap parseEncap(byte[] data) throws ParseException {
        if (data == null || data.length < 4) {
            throw new ParseException("too short data: " + (data == null ? 0 : data.length)
                    + " bytes", Error.SHORT);
        }
        Encap encap = new Encap();
        encap.version = (data[0] >> 4) & 0x0f;
        int offset = 4;
        int padding = data[0] & 0xf;
        if (encap.version > MAX_VERSION) {
            throw new ParseException("Unsupported Encap version: " + encap.version, Error.BAD_VERSION);
        }
        encap.errorCode = data[2] & 0xff;
        encap.payloadTypeCode = data[1] & 0xff;
        encap.payloadType = PayloadType.getByType(encap.payloadTypeCode);

        int fingerPrintModeCode = (data[3] >> 4) & 0xf;
        encap.fingerPrintMode = FingerPrintMode.getByMode(fingerPrintModeCode);
        if (encap.fingerPrintMode == null) {
            throw new ParseException("Unsupported fingerprint mode: " + fingerPrintModeCode,
                    Error.BAD_FINGERPRINT_MODE);
        }
        offset += encap.fingerPrintMode.getSize();

        if(encap.fingerPrintMode == FingerPrintMode.LENOPT) {
            /* check if CRC is there - bit 1 of 16 (4 / 5) is CRC*/
            /* no support for SEQNO at the moment */
            encap.optLenOption = data[5] & 0xff;
            if ((encap.optLenOption == LENOPT_OPTION_CRC) || (encap.optLenOption == LENOPT_OPTION_SEQNO_CRC)) {
                encap.crcEnabled = true;
                padding += 4;
            }
            encap.optLen = ((data[6] & 0xff) << 8) + (data[7] & 0xff);
        }

        int initVectorModeCode = data[3] & 0xf;
        encap.initVectorMode = IVMode.getByMode(initVectorModeCode);
        if (encap.initVectorMode == null) {
            throw new ParseException("Unsupported init vector mode: " + initVectorModeCode,
                    Error.BAD_INITVECTOR_MODE);
        }
        offset += encap.initVectorMode.getSize();

        if (offset + encap.optLen + padding > data.length) {
            throw new ParseException("too short data", Error.SHORT);
        }

        if (DEBUG) {
            System.out.println("Total Len: " + data.length);
            System.out.println("payloadLen: " + encap.optLen + " =?= "
                    + (data.length - offset - (encap.crcEnabled ? 4 : 0)));
        }

        if (encap.crcEnabled) {
            CRC32 crc = new CRC32();
            crc.update(data, 0, data.length - 4);
            long crcV = ((data[data.length - 1] & 0xffL) << 24) + ((data[data.length - 2] & 0xff) << 16) +
                    ((data[data.length - 3] & 0xff) << 8) + (data[data.length - 4] & 0xff);
            if (crc.getValue() != crcV) {
                System.out.printf("CRC failed: %08x == %08x\n", crc.getValue(), crcV);
            }
        }
        encap.payloadData = Arrays.copyOfRange(data, offset, data.length - padding);

        return encap;
    }

    public int getVersion() {
        return this.version;
    }

    public boolean hasError() {
        return this.errorCode != 0;
    }

    public int getErrorCode() {
        return this.errorCode;
    }

    public Error getError() {
        return Error.getByType(this.errorCode);
    }

    public String getErrorAsString() {
        Error e = getError();
        return e != null ? e.name() : ("(" + this.errorCode + ")");
    }

    public boolean isEncrypted() {
        return this.fingerPrintMode != null && this.fingerPrintMode.isEncrypted();
    }

    public int getPayloadTypeCode() {
        return this.payloadTypeCode;
    }

    public PayloadType getPayloadType() {
        return payloadType;
    }

    public String getPayloadTypeAsString() {
        PayloadType type = getPayloadType();
        return type != null ? type.name() : ("(" + this.payloadTypeCode + ")");
    }

    public FingerPrintMode getFingerPrintMode() {
        return this.fingerPrintMode;
    }

    public IVMode getInitVectorMode() {
        return this.initVectorMode;
    }

    public byte[] getPayloadData() {
        return this.payloadData;
    }

    public enum PayloadType {
        TLV(1),
        PORTAL_SELECTION_PROTO(3),
        ECHO_REQUEST(4),
        ECHO_REPLY(5),
        STOP_TRANSMITTER(6),
        START_TRANSMITTER(7),
        SERIAL(8),
        DEBUG(9),
        SERIAL_WITH_SEQNO(10),
        RECEIVE_REPORT(11),
        SLEEP_REPORT(12);

        private final int type;

        PayloadType(int type) {
            this.type = type;
        }

        public int getType() {
            return type;
        }

        public static PayloadType getByType(int type) {
            for (PayloadType p : values()) {
                if (p.type == type) {
                    return p;
                }
            }
            return null;
        }

        public static PayloadType getByName(String name) {
            for (PayloadType p : values()) {
                if (p.name().equals(name)) {
                    return p;
                }
            }
            return null;
        }

    };

    public enum Error {
        OK(0),
        SHORT(-1),
        BAD_VERSION(-2),
        BAD_PAYLOAD_TYPE(-3),
        REQUEST_WITH_ERROR(-4),
        BAD_FINGERPRINT_MODE(-5),
        BAD_INITVECTOR_MODE(-6),
        UNKNOWN_KEY(-7),
        BAD_SEQUENCE(-8),
        BAD_CHECKSUM(-9);

        private final int error;

        Error(int error) {
            this.error = error;
        }

        public int getError() {
            return error;
        }

        public static Error getByType(int error) {
            for (Error e : values()) {
                if (e.error == error) {
                    return e;
                }
            }
            return null;
        }

        public static Error getByName(String name) {
            for (Error e : values()) {
                if (e.name().equals(name)) {
                    return e;
                }
            }
            return null;
        }

    }

    public enum FingerPrintMode {
        NONE(0, 0, false), // 0 bytes
        DEVID(1, 8, false), // 8 bytes
        FP(2, 4, true), // 4 bytes
        LENOPT(3, 4, false), // 4 bytes
        DID_AND_FP(4, 16, false); // 16 bytes

        private final int mode;
        private final int size;
        private final boolean isEncrypted;

        FingerPrintMode(int mode, int size, boolean isEncrypted) {
            this.mode = mode;
            this.size = size;
            this.isEncrypted = isEncrypted;
        }

        public int getMode() {
            return this.mode;
        }

        public int getSize() {
            return this.size;
        }

        public boolean isEncrypted() {
            return this.isEncrypted;
        }

        public static FingerPrintMode getByMode(int mode) {
            FingerPrintMode[] values = values();
            // Quick lookup when possible
            if (mode >= 0 && mode < values.length && values[mode].mode == mode) {
                return values[mode];
            }
            for (FingerPrintMode p : values) {
                if (p.mode == mode) {
                    return p;
                }
            }
            return null;
        }

        public static FingerPrintMode getByName(String name) {
            for (FingerPrintMode p : values()) {
                if (p.name().equals(name)) {
                    return p;
                }
            }
            return null;
        }
    }

    public enum IVMode {
        NONE(0, 0),
        MODE128BIT(1, 16);

        private final int mode;
        private final int size;

        IVMode(int mode, int size) {
            this.mode = mode;
            this.size = size;
        }

        public int getMode() {
            return mode;
        }

        public int getSize() {
            return size;
        }

        public static IVMode getByMode(int mode) {
            IVMode[] values = values();
            // Quick lookup when possible
            if (mode >= 0 && mode < values.length && values[mode].mode == mode) {
                return values[mode];
            }
            for (IVMode p : values) {
                if (p.mode == mode) {
                    return p;
                }
            }
            return null;
        }

        public static IVMode getByName(String name) {
            for (IVMode p : values()) {
                if (p.name().equals(name)) {
                    return p;
                }
            }
            return null;
        }

    }
}
