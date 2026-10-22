package aula.util;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

/** Decodificador PCX de 8 bits, um plano e paleta RGB de 256 cores, usado pelas skins MD2. */
public final class PcxLoader {
    private PcxLoader() { }

    public static BufferedImage load(Path path) throws IOException {
        // 1. Verifique o cabeçalho de 128 bytes e a paleta de 768 bytes no final.
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < 128 + 768) throw invalid("arquivo incompleto");
        if ((bytes[0] & 255) != 10 || (bytes[1] & 255) != 5 || (bytes[2] & 255) != 1
                || (bytes[3] & 255) != 8 || (bytes[65] & 255) != 1) {
            throw invalid("esperado PCX versão 5, RLE, 8 bits e um plano");
        }
        ByteBuffer header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        int width = unsignedShort(header, 8) - unsignedShort(header, 4) + 1;
        int height = unsignedShort(header, 10) - unsignedShort(header, 6) + 1;
        int bytesPerLine = unsignedShort(header, 66);
        if (width <= 0 || height <= 0 || bytesPerLine < width) throw invalid("dimensões inválidas");
        int paletteOffset = bytes.length - 768;

        // 2. Converta cada entrada RGB da paleta para ARGB opaco, aceito pelo TextureLoader.
        // A skin fornecida não possui o marcador 0x0C; não o exigimos antes da paleta.
        int[] palette = new int[256];
        for (int i = 0; i < palette.length; i++) {
            int offset = paletteOffset + i * 3;
            palette[i] = 0xFF000000 | ((bytes[offset] & 255) << 16)
                    | ((bytes[offset + 1] & 255) << 8) | (bytes[offset + 2] & 255);
        }

        // 3. Decodifique linha a linha, incluindo o padding indicado por bytesPerLine.
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int position = 128;
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            int x = 0;
            while (x < bytesPerLine) {
                if (position >= paletteOffset) throw invalid("RLE truncado");
                int value = bytes[position++] & 255;
                int count = 1;
                // Os dois bits altos em 1 indicam repetição; os outros seis dão a quantidade.
                if ((value & 0xC0) == 0xC0) {
                    count = value & 0x3F;
                    if (count == 0 || position >= paletteOffset) throw invalid("repetição RLE inválida");
                    value = bytes[position++] & 255;
                }
                if (x + count > bytesPerLine) throw invalid("RLE ultrapassa a linha");
                for (int i = 0; i < count; i++, x++) {
                    if (x < width) row[x] = palette[value];
                }
            }
            // 4. Preserve a ordem superior->inferior, igual à usada pelo TextureLoader atual.
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        // Só são aceitos a paleta imediatamente após o RLE ou seu marcador opcional.
        if (position != paletteOffset
                && !(position == paletteOffset - 1 && (bytes[position] & 255) == 12)) {
            throw invalid("dados inesperados entre imagem e paleta");
        }
        return image;
    }

    private static int unsignedShort(ByteBuffer buffer, int offset) {
        return Short.toUnsignedInt(buffer.getShort(offset));
    }

    private static IOException invalid(String detail) { return new IOException("PCX inválido: " + detail); }
}
