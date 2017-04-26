/*
 * Copyright 2016 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.things.contrib.driver.adcv2x;

import com.google.android.things.pio.I2cDevice;
import com.google.android.things.pio.PeripheralManagerService;

import java.io.IOException;

/**
 * Based on CPP Impl:
 * https://github.com/sparkfun/SparkFun_ADC_Block_for_Edison_CPP_Library/blob/master/SparkFunADS1015.cpp
 *
 * with lots and lots of bs byte->short->unsigned errors because JAVA.
 */

public class Adcv2x implements AutoCloseable {

    private static final String TAG = Adcv2x.class.getSimpleName();

    public static final String DEFAULT_BUS = "I2C1";

    /**
     * Out of the box this address is soldered.
     */
    public static final int I2C_ADDRESS_48 = 0x48;
    public static final int I2C_ADDRESS_49 = 0x49;
    public static final int I2C_ADDRESS_4A = 0x4A;
    public static final int I2C_ADDRESS_4B = 0x4B;

    private String mBus;
    private int mAddress;

    private I2cDevice mDevice;

    private float _scaler = 1.0f;

    public static final short _6_144V = 0x00;
    public static final short _4_096V = 0x01;
    public static final short _2_048V = 0x02;
    public static final short _1_024V = 0x03;
    public static final short _0_512V = 0x04;
    public static final short _0_256V = 0x05;

    private final short CONVERSION = 0x00;
    private final short CONFIG = 0x01;

    private final int START_READ = 0x8000;
    private final int CHANNEL_MASK = 0x3000; // There are four channels, and single ended reads
                                            //  are specified by a two-bit address at bits 13:12
    private final int SINGLE_ENDED = 0x4000;   // Set for single-ended
    private final int CFG_REG_CHL_MASK = 0xf000; // Used to clear the high nibble of the cfg regbefore we start our read reques
    private final int BUSY_MASK = 0x8000; // When the highest bit in the cfg reg is set, the conversion is done.
    private final int CHANNEL_SHIFT = 12;

    private final short RANGE_SHIFT = 9;
    private final int RANGE_MASK = 0x0E00; // bits to clear for gain parameter

    /**
     * Create a new instance of a Sparkfun ADC V20 board. Read more about it at
     * https://learn.sparkfun.com/tutorials/sparkfun-blocks-for-intel-edison---adc-v20
     *
     * @param bus I2C Bus Address. Typically the default "I2C1", stored as {@link #DEFAULT_BUS},
     *            but could be something else. Find what's connected with
     *            {@link PeripheralManagerService#getI2cBusList()}.
     *
     * @param address The closed address jumper on the back of the board. Useful for connecting
     *                multiple boards. Use the static ints enclosed in this class.
     *
     *                {@link #I2C_ADDRESS_48} (default when purchased), {@link #I2C_ADDRESS_49},
     *                {@link #I2C_ADDRESS_4A}, or {@link #I2C_ADDRESS_4B},
     *
     * @throws IOException Connection error
     */
    public Adcv2x(String bus, int address) throws IOException {
        PeripheralManagerService pioService = new PeripheralManagerService();
        I2cDevice device = pioService.openI2cDevice(bus, address);

        mBus = bus;
        mAddress = address;

        try {
            connect(device);
        } catch (IOException | RuntimeException e) {
            try {
                close();
            } catch (IOException | RuntimeException ignored) {
            }
            throw e;
        }
    }

    public Adcv2x() throws IOException {
        this(DEFAULT_BUS, I2C_ADDRESS_48);
    }

    /**
     * Create a new AdcV2x sensor driver connected to the given I2c device.
     * @param device I2C device of the sensor.
     * @throws IOException Connection to device failed.
     */
    /*package*/  Adcv2x(I2cDevice device) throws IOException {
        connect(device);
    }

    private void connect(I2cDevice device) throws IOException {
        if (mDevice != null) {
            throw new IllegalStateException("device already connected");
        }
        mDevice = device;

        // set the range here to 4 volts even tho we know we're only feeding it ~3.3
        // the dial turned all the way up will read ~3.2
        setRange(_4_096V);
    }

    /**
     * Sets the appropriate voltage range to read from. Defaults to {@link #_4_096V} to
     * handle 3.3v inputs properly.
     *
     * @param range One of the static shorts above.
     * @throws IOException Cannot access or set the device register
     */
    public void setRange(short range) throws IOException {
        short cfgRegVal = getConfigRegister();
        cfgRegVal &= ~RANGE_MASK;
        cfgRegVal |= (range << RANGE_SHIFT) & RANGE_MASK;
        setConfigRegister(cfgRegVal);

        switch (range) {
            case _6_144V:
                _scaler = 3.0f; // each count represents 3.0 mV
                break;
            case _4_096V:
                _scaler = 2.0f; // each count represents 2.0 mV
                break;
            case _2_048V:
                _scaler = 1.0f; // each count represents 1.0 mV
                break;
            case _1_024V:
                _scaler = 0.5f; // each count represents 0.5mV
                break;
            case _0_512V:
                _scaler = 0.25f; // each count represents 0.25mV
                break;
            case _0_256V:
                _scaler = 0.125f; // each count represents 0.125mV
                break;
            default:
                _scaler = 1.0f;  // here be dragons
                break;
        }
    }

    /**
     * Returns the current reading on a channel, scaled by the current scaler and
     * presented as a floating point number.
     *
     * @param channel the numbered channel you want data from.
     */
    public float getResult(int channel) throws IOException {
        short rawVal = getRawResult(channel);
        float val = (float)rawVal * _scaler/1000;

        return val;
    }

    public short getRawResult(int channel) throws IOException {
        short cfgRegVal = getConfigRegister();

        cfgRegVal &= ~CHANNEL_MASK; // clear existing channel settings
        cfgRegVal |= SINGLE_ENDED;  // set the SE bit for a s-e read
        cfgRegVal |= (channel<<CHANNEL_SHIFT) & CHANNEL_MASK; // put the channel bits in
        cfgRegVal |= START_READ;    // set the start read bit

        cfgRegVal = (short) (cfgRegVal & 0xFFFF);

        setConfigRegister(cfgRegVal);

        return readADC();
    }

    public short readADC() throws IOException {
        short cfgRegVal = getConfigRegister();
        cfgRegVal |= START_READ; // set the start read bit
        setConfigRegister(cfgRegVal);

        byte[] result = new byte[2];
        short fullValue = 0;
        int busyDelay = 0;

        while ((getConfigRegister() & BUSY_MASK) == 0)
        {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if(busyDelay++ > 1000) return (short)0xffff;
        }

        mDevice.readRegBuffer(CONVERSION, result, 2);

        fullValue = (short)((result[0]<<8) + result[1]);
        return (short)(fullValue>>>4);
    }

    private void setConfigRegister(short configValue) throws IOException {
        byte[] data = new byte[2];
        data[0] = (byte)((configValue>>>8) & 0xff);
        data[1] = (byte)(configValue & 0xff);

        mDevice.writeRegBuffer(CONFIG, data, 2);
    }

    private short getConfigRegister() throws IOException {
        byte[] buff = new byte[2];
        mDevice.readRegBuffer(CONFIG, buff, 2);

        // create short from buffer array
        short regBuff = (short)(((buff[0] & 0xFF) << 8) | (buff[1] & 0xFF ));

        // shift according to how they handle it in cpp lib - not sure why?
        regBuff = (short)((((regBuff & 0xffff)>>>8) | (regBuff & 0xffff <<8)));

        return regBuff;
    }

    @Override
    public void close() throws IOException {
        if (mDevice != null) {
            try {
                mDevice.close();
            } finally {
                mDevice = null;
            }
        }
    }

    @Override
    public String toString() {
        return "AdcV2x::" + mBus + "::" + Integer.toHexString(mAddress);
    }
}
