package com.google.android.things.contrib.common;

import java.io.IOException;

/**
 * Interface for any device driver that allows channel-based voltage reads from analog input.
 *
 * Used alongside {@link AnalogDevice} for easy read handling.
 */
public interface ReadableAnalogDevice {
    float getResult(int channel) throws IOException;
    short getRawResult(int channel) throws IOException;
    short readADC() throws IOException;
}
