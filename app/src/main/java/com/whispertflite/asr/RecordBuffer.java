package com.whispertflite.asr;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RecordBuffer {
    // Static variable to store the byte array
    private static byte[] outputBuffer;

    // Synchronized method to set the byte array
    public static synchronized void setOutputBuffer(byte[] buffer) {
        outputBuffer = buffer;
    }

    // Synchronized method to get the byte array
    public static synchronized byte[] getOutputBuffer() {
        return outputBuffer;
    }

    public static float[] getSamples() {
        byte[] buffer = getOutputBuffer();
        if (buffer == null || buffer.length < 2) {
            return new float[0];
        }
        return samplesFromPcm16Le(buffer);
    }

    /**
     * Same normalization as {@link #getSamples()} for raw 16-bit little-endian mono PCM.
     */
    public static float[] samplesFromPcm16Le(byte[] buffer) {
        int numSamples = buffer.length / 2;
        ByteBuffer byteBuffer = ByteBuffer.wrap(buffer);
        byteBuffer.order(ByteOrder.nativeOrder());

        float[] samples = new float[numSamples];
        float maxAbsValue = 0.0f;

        for (int i = 0; i < numSamples; i++) {
            samples[i] = (float) (byteBuffer.getShort() / 32768.0);
            if (Math.abs(samples[i]) > maxAbsValue) {
                maxAbsValue = Math.abs(samples[i]);
            }
        }

        if (maxAbsValue > 0.0f) {
            for (int i = 0; i < numSamples; i++) {
                samples[i] /= maxAbsValue;
            }
        }

        return samples;
    }
}
