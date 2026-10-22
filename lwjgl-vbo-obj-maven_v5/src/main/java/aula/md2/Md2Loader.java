package aula.md2;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Lê somente dados de CPU: não exige janela nem contexto OpenGL. */
public final class Md2Loader {
    private static final int HEADER_SIZE = 17 * Integer.BYTES;
    private static final int IDP2 = 0x32504449;

    private Md2Loader() { }

    /** Cada canto de triângulo tem um índice de posição e um UV próprio. */
    public record Data(int skinWidth, int skinHeight, int vertexCount, int[] vertexIndices,
                       float[] textureCoordinates, List<Md2Frame> frames, List<String> skins) {
        public int triangleCount() { return vertexIndices.length / 3; }
    }

    public static Data load(Path path) throws IOException {
        // 1. MD2 é binário little-endian; o padrão big-endian do ByteBuffer seria incorreto.
        byte[] bytes = Files.readAllBytes(path);
        if (bytes.length < HEADER_SIZE) throw invalid("cabeçalho incompleto");
        ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (buffer.getInt() != IDP2 || buffer.getInt() != 8) {
            throw invalid("assinatura deve ser IDP2 e versão deve ser 8");
        }

        int width = buffer.getInt(), height = buffer.getInt(), frameSize = buffer.getInt();
        int skinCount = buffer.getInt(), vertexCount = buffer.getInt(), uvCount = buffer.getInt();
        int triangleCount = buffer.getInt(), commandCount = buffer.getInt(), frameCount = buffer.getInt();
        int skinsOffset = buffer.getInt(), uvOffset = buffer.getInt(), trianglesOffset = buffer.getInt();
        int framesOffset = buffer.getInt(), commandsOffset = buffer.getInt(), endOffset = buffer.getInt();

        // 2. Valide antes de alocar vetores ou acessar offsets fornecidos pelo arquivo.
        if (width <= 0 || height <= 0 || vertexCount <= 0 || uvCount <= 0
                || triangleCount <= 0 || frameCount <= 0 || skinCount < 0 || commandCount < 0
                || frameSize < 40L + vertexCount * 4L
                || endOffset < HEADER_SIZE || endOffset > bytes.length) {
            throw invalid("dimensões, contagens ou tamanho de frame inválidos");
        }
        checkRange(skinsOffset, skinCount * 64L, endOffset);
        checkRange(uvOffset, uvCount * 4L, endOffset);
        checkRange(trianglesOffset, triangleCount * 12L, endOffset);
        checkRange(framesOffset, frameCount * (long) frameSize, endOffset);
        checkRange(commandsOffset, commandCount * 4L, endOffset);

        // 3. Nomes de skins são referências; o Main escolhe explicitamente a skin local.
        List<String> skins = new ArrayList<>();
        for (int i = 0; i < skinCount; i++) skins.add(readName(bytes, skinsOffset + i * 64, 64));

        // 4. UVs do MD2 são shorts em pixels. Normalize para as coordenadas da textura.
        float[] uv = new float[Math.multiplyExact(uvCount, 2)];
        buffer.position(uvOffset);
        for (int i = 0; i < uvCount; i++) {
            uv[i * 2] = buffer.getShort() / (float) width;
            uv[i * 2 + 1] = buffer.getShort() / (float) height;
        }

        // 5. Expanda os UVs por canto, preservando costuras onde uma posição tem UVs diferentes.
        int[] indices = new int[Math.multiplyExact(triangleCount, 3)];
        float[] expandedUv = new float[Math.multiplyExact(indices.length, 2)];
        buffer.position(trianglesOffset);
        for (int triangle = 0; triangle < triangleCount; triangle++) {
            int base = triangle * 3;
            for (int corner = 0; corner < 3; corner++) {
                int index = Short.toUnsignedInt(buffer.getShort());
                if (index >= vertexCount) throw invalid("índice de vértice fora da malha");
                indices[base + corner] = index;
            }
            for (int corner = 0; corner < 3; corner++) {
                int index = Short.toUnsignedInt(buffer.getShort());
                if (index >= uvCount) throw invalid("índice de UV fora da tabela");
                expandedUv[(base + corner) * 2] = uv[index * 2];
                expandedUv[(base + corner) * 2 + 1] = uv[index * 2 + 1];
            }
        }

        // 6. Cada frame tem escala/translação próprias e vértices compactados em bytes.
        List<Md2Frame> frames = new ArrayList<>();
        for (int frame = 0; frame < frameCount; frame++) {
            int offset = framesOffset + frame * frameSize;
            buffer.position(offset);
            float[] scale = {buffer.getFloat(), buffer.getFloat(), buffer.getFloat()};
            float[] translation = {buffer.getFloat(), buffer.getFloat(), buffer.getFloat()};
            for (int axis = 0; axis < 3; axis++) {
                if (!Float.isFinite(scale[axis]) || !Float.isFinite(translation[axis])) {
                    throw invalid("escala ou translação não finita");
                }
            }
            String name = readName(bytes, offset + 24, 16);
            buffer.position(offset + 40);
            float[] positions = new float[Math.multiplyExact(vertexCount, 3)];
            for (int vertex = 0; vertex < vertexCount; vertex++) {
                for (int axis = 0; axis < 3; axis++) {
                    // Java usa byte com sinal: converter para 0..255 evita deformar a malha.
                    float value = Byte.toUnsignedInt(buffer.get()) * scale[axis] + translation[axis];
                    if (!Float.isFinite(value)) throw invalid("posição não finita");
                    positions[vertex * 3 + axis] = value;
                }
                // O quarto byte indexa uma tabela de normais. Não há GL_LIGHTING neste projeto.
                buffer.get();
            }
            frames.add(new Md2Frame(name, positions));
        }
        // Os comandos strip/fan podem ser dispensados: usamos a tabela de triângulos acima.
        return new Data(width, height, vertexCount, indices, expandedUv,
                List.copyOf(frames), List.copyOf(skins));
    }

    private static void checkRange(int offset, long size, int end) throws IOException {
        if (size == 0) return;
        if (offset < HEADER_SIZE || size < 0 || offset + size > end) {
            throw invalid("seção ultrapassa os limites do arquivo");
        }
    }

    private static String readName(byte[] bytes, int offset, int length) {
        int end = offset;
        while (end < offset + length && bytes[end] != 0) end++;
        return new String(bytes, offset, end - offset, StandardCharsets.US_ASCII);
    }

    private static IOException invalid(String detail) { return new IOException("MD2 inválido: " + detail); }
}
