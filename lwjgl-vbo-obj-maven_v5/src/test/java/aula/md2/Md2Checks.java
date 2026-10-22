package aula.md2;

import aula.util.PcxLoader;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

/** Verificações executáveis sem JUnit; use scripts/check-md2.ps1 a partir da raiz. */
public final class Md2Checks {
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of("target/md2-fixtures");
        Files.createDirectories(fixtures);
        checkBinaryReaders(fixtures);
        checkProvidedModel();
        if (Arrays.asList(args).contains("--render")) checkOpenGl();
        System.out.println("OK: leitura MD2/PCX, arquivos inválidos, interpolação, pausa, loop e término.");
    }

    private static void checkBinaryReaders(Path fixtures) throws Exception {
        // Fixture com valores conhecidos: inclui byte 255, escala e translação não triviais.
        ByteBuffer md2 = ByteBuffer.allocate(144).order(ByteOrder.LITTLE_ENDIAN);
        int[] header = {0x32504449, 8, 2, 2, 52, 0, 3, 3, 1, 0, 1, 68, 68, 80, 92, 144, 144};
        for (int value : header) md2.putInt(value);
        md2.putShort((short) 0).putShort((short) 0);
        md2.putShort((short) 2).putShort((short) 0);
        md2.putShort((short) 0).putShort((short) 2);
        for (int i = 0; i < 3; i++) md2.putShort((short) i);
        for (int i = 0; i < 3; i++) md2.putShort((short) i);
        md2.putFloat(2).putFloat(3).putFloat(4);
        md2.putFloat(-1).putFloat(10).putFloat(-5);
        md2.put(new byte[16]);
        md2.put(new byte[]{0, 1, 2, 0, (byte) 255, 2, 3, 0, 4, 5, 6, 0});
        Path sample = fixtures.resolve("known.md2");
        Files.write(sample, md2.array());
        Md2Loader.Data parsed = Md2Loader.load(sample);
        near(parsed.frames().get(0).getCoordinate(1, 0), 509, "byte sem sinal");
        near(parsed.frames().get(0).getCoordinate(0, 1), 13, "escala e translação Y");
        near(parsed.frames().get(0).getCoordinate(0, 2), 3, "escala e translação Z");
        near(parsed.textureCoordinates()[2], 1, "normalização U");
        near(parsed.textureCoordinates()[5], 1, "normalização V");

        // Corrupções devem gerar IOException antes de acessar memória fora do arquivo.
        byte[] corrupt = md2.array().clone();
        ByteBuffer.wrap(corrupt).order(ByteOrder.LITTLE_ENDIAN).putInt(56, 99999);
        Path invalid = fixtures.resolve("bad-offset.md2");
        Files.write(invalid, corrupt);
        expectIo(() -> Md2Loader.load(invalid));
        corrupt = md2.array().clone();
        corrupt[80] = 99;
        Path badIndex = fixtures.resolve("bad-index.md2");
        Files.write(badIndex, corrupt);
        expectIo(() -> Md2Loader.load(badIndex));
        Path truncated = fixtures.resolve("truncated.md2");
        Files.write(truncated, new byte[10]);
        expectIo(() -> Md2Loader.load(truncated));

        // PCX de duas linhas com padding; teste com e sem o marcador opcional da paleta.
        for (boolean marker : new boolean[]{false, true}) {
            byte[] pcx = new byte[128 + 8 + (marker ? 1 : 0) + 768];
            pcx[0] = 10; pcx[1] = 5; pcx[2] = 1; pcx[3] = 8; pcx[65] = 1;
            ByteBuffer.wrap(pcx).order(ByteOrder.LITTLE_ENDIAN)
                    .putShort(8, (short) 1).putShort(10, (short) 1).putShort(66, (short) 4);
            byte[] rle = {(byte) 0xC2, 5, 0, 0, 6, 7, 0, 0};
            System.arraycopy(rle, 0, pcx, 128, rle.length);
            if (marker) pcx[136] = 12;
            for (int i = 0; i < 256; i++) {
                int base = pcx.length - 768 + i * 3;
                pcx[base] = (byte) i; pcx[base + 1] = (byte) (i * 2); pcx[base + 2] = (byte) (i * 3);
            }
            Path imagePath = fixtures.resolve("padding-" + marker + ".pcx");
            Files.write(imagePath, pcx);
            BufferedImage image = PcxLoader.load(imagePath);
            require(image.getRGB(0, 0) == 0xFF050A0F, "paleta RGB e alpha");
            require(image.getRGB(1, 1) == 0xFF070E15, "padding e ordem das linhas");
            pcx[128] = (byte) 0xC5;
            Files.write(imagePath, pcx);
            expectIo(() -> PcxLoader.load(imagePath));
        }
    }

    private static void checkProvidedModel() throws Exception {
        Md2Loader.Data data = Md2Loader.load(Path.of("md2s/tris.md2"));
        require(data.frames().size() == 65 && data.vertexCount() == 190 && data.triangleCount() == 328,
                "contagens do modelo fornecido");
        BufferedImage image = PcxLoader.load(Path.of("md2s/skin.pcx"));
        require(image.getWidth() == 780 && image.getHeight() == 168, "dimensões da skin fornecida");
        try (Md2Model model = new Md2Model(Path.of("md2s/tris.md2"), Path.of("md2s/skin.pcx"))) {
            require(model.getAnimations().stream().map(Md2Animation::name).toList()
                    .equals(Arrays.asList("active", "pow", "idle", "putway")), "sequências descobertas");
            model.playAnimation("pow");
            model.update(.05);
            require(model.getCurrentFrame() == 5 && model.getNextFrame() == 6, "frames vizinhos");
            near(model.getInterpolation(), .5, "meio do intervalo a 10 FPS");
            for (int vertex = 0; vertex < data.vertexCount(); vertex++) {
                for (int axis = 0; axis < 3; axis++) {
                    double expected = (data.frames().get(5).getCoordinate(vertex, axis)
                            + (double) data.frames().get(6).getCoordinate(vertex, axis)) / 2;
                    near(model.getInterpolatedCoordinate(vertex, axis), expected, "posição no meio das poses");
                }
            }
            model.setPaused(true);
            model.update(100);
            near(model.getInterpolation(), .5, "pausa preserva a pose");
            model.playAnimation("idle");
            model.update(2.95);
            require(model.getCurrentFrame() == 61 && model.getNextFrame() == 32, "costura do loop");
            model.update(.10);
            require(model.getCurrentFrame() == 32, "retorno ao primeiro frame");
            model.configureAnimation("putway", 10, false);
            model.playAnimation("putway");
            model.update(100);
            require(model.isFinished() && model.getCurrentFrame() == 64 && model.getNextFrame() == 64,
                    "animação sem loop mantém última pose");
            model.playAnimation("idle");
            for (int i = 0; i < 60; i++) model.update(1.0 / 60);
            double at60Fps = model.getCurrentFrame() + model.getInterpolation();
            model.playAnimation("idle");
            for (int i = 0; i < 20; i++) model.update(1.0 / 20);
            near(model.getCurrentFrame() + model.getInterpolation(), at60Fps, "independência do FPS de desenho");
        }
    }

    private static void checkOpenGl() throws Exception {
        // Janela oculta: verifica o upload real, todos os frames e a liberação de VBOs/textura.
        if (!glfwInit()) throw new AssertionError("GLFW não iniciou no teste gráfico");
        long window = 0;
        try {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
            window = glfwCreateWindow(640, 480, "MD2 check", 0, 0);
            require(window != 0, "contexto OpenGL de teste");
            glfwMakeContextCurrent(window);
            GL.createCapabilities();
            glViewport(0, 0, 640, 480);
            glEnable(GL_DEPTH_TEST);
            glMatrixMode(GL_PROJECTION);
            glLoadIdentity();
            glFrustum(-.933, .933, -.7, .7, 1, 100);
            glMatrixMode(GL_MODELVIEW);
            glLoadIdentity();
            glTranslatef(0, 0, -4);
            glRotatef(20, 1, 0, 0);
            glRotatef(35, 0, 1, 0);
            glRotatef(-90, 1, 0, 0);
            try (Md2Model model = new Md2Model(Path.of("md2s/tris.md2"), Path.of("md2s/skin.pcx"))) {
                double scale = model.getDisplayScale();
                glScaled(scale, scale, scale);
                glTranslatef(-model.getCenter(0), -model.getCenter(1), -model.getCenter(2));
                model.load();
                for (Md2Animation animation : model.getAnimations()) {
                    model.playAnimation(animation.name());
                    for (int i = 0; i < animation.frameCount() * 2; i++) {
                        model.update(.05);
                        glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                        model.draw();
                        require(glGetError() == GL_NO_ERROR, "OpenGL: " + animation.name());
                    }
                }
                // Capture uma pose interpolada e confira que a malha ocupou pixels do framebuffer.
                model.playAnimation("idle");
                model.update(.05);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                model.draw();
                ByteBuffer pixels = BufferUtils.createByteBuffer(640 * 480 * 4);
                glReadPixels(0, 0, 640, 480, GL_RGBA, GL_UNSIGNED_BYTE, pixels);
                BufferedImage capture = new BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB);
                int colored = 0;
                for (int y = 0; y < 480; y++) {
                    for (int x = 0; x < 640; x++) {
                        int base = (y * 640 + x) * 4;
                        int rgb = ((pixels.get(base) & 255) << 16)
                                | ((pixels.get(base + 1) & 255) << 8) | (pixels.get(base + 2) & 255);
                        if (rgb != 0) colored++;
                        capture.setRGB(x, 479 - y, 0xFF000000 | rgb);
                    }
                }
                require(colored > 1000, "malha visível no framebuffer");
                ImageIO.write(capture, "PNG", Path.of("target/md2-preview.png").toFile());
                System.out.println("OK: OpenGL " + glGetString(GL_VERSION) + "; captura em target/md2-preview.png");
            }
            require(glGetError() == GL_NO_ERROR, "liberação dos recursos OpenGL");
        } finally {
            if (window != 0) glfwDestroyWindow(window);
            glfwTerminate();
        }
    }

    private static void require(boolean condition, String detail) {
        if (!condition) throw new AssertionError(detail);
    }

    private static void near(double actual, double expected, String detail) {
        require(Math.abs(actual - expected) < 0.00002, detail + ": " + actual + " != " + expected);
    }

    private static void expectIo(IoAction action) throws Exception {
        try {
            action.run();
            throw new AssertionError("Arquivo inválido foi aceito");
        } catch (IOException expected) { /* rejeição esperada */ }
    }

    @FunctionalInterface
    private interface IoAction { void run() throws Exception; }
}
