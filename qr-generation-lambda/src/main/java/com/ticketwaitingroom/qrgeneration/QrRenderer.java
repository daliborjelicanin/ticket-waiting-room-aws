package com.ticketwaitingroom.qrgeneration;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * Encodes a string as a PNG QR code.
 *
 * <p>Pure and deterministic: the same input produces byte-identical output, with no clock,
 * no randomness and no metadata in the PNG. That is what lets the Lambda be idempotent
 * without any coordination — a duplicate SQS delivery overwrites the object in S3 with
 * exactly what was already there, so there is nothing to guard and nothing to detect.
 */
final class QrRenderer {

    private static final int SIZE_PX = 512;

    /**
     * Quiet zone in modules. The spec says four; one is enough when the image is displayed
     * on white, and it leaves more of the 512px budget for the modules themselves.
     */
    private static final int MARGIN_MODULES = 1;

    private static final int WHITE = 0xFFFFFF;
    private static final int BLACK = 0x000000;

    byte[] renderPng(String payload) {
        BitMatrix matrix;
        try {
            matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, SIZE_PX, SIZE_PX, Map.of(
                    // Q tolerates ~25% damage. A ticket QR is scanned off a phone screen at
                    // a gate — glare, fingerprints and a cracked display are the normal
                    // case, not the exception, and the extra modules cost nothing here.
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.Q,
                    EncodeHintType.MARGIN, MARGIN_MODULES));
        } catch (WriterException e) {
            throw new IllegalArgumentException("Cannot encode QR payload: " + payload, e);
        }

        // TYPE_BYTE_BINARY, not TYPE_INT_RGB: a QR code is one bit per pixel by definition,
        // and the 1-bit PNG is a fraction of the size for identical output.
        BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(),
                BufferedImage.TYPE_BYTE_BINARY);
        for (int x = 0; x < matrix.getWidth(); x++) {
            for (int y = 0; y < matrix.getHeight(); y++) {
                image.setRGB(x, y, matrix.get(x, y) ? BLACK : WHITE);
            }
        }

        ByteArrayOutputStream png = new ByteArrayOutputStream();
        try {
            if (!ImageIO.write(image, "PNG", png)) {
                throw new IllegalStateException("No PNG writer available in this JRE");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return png.toByteArray();
    }
}
