package aula.md2;

import aula.Model.Model;
import aula.util.PcxLoader;
import aula.util.TextureLoader;
import org.lwjgl.BufferUtils;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.FloatBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;

/** Modelo animado no pipeline de compatibilidade OpenGL 2.1, sem shaders. */
public final class Md2Model extends Model implements AutoCloseable {
    private final Md2Loader.Data data;
    private final BufferedImage skin;
    private final FloatBuffer positionBuffer;
    private final float[] interpolatedPositions;
    private final Map<String, Md2Animation> animations = new LinkedHashMap<>();
    private final float[] minimum = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY};
    private final float[] maximum = {Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
    private Md2Animation animation;
    private double framePosition;
    private boolean paused;
    private boolean dirty = true;
    private boolean closed;
    private int positionVbo;
    private int uvVbo;
    private int textureId;

    public Md2Model(Path modelPath, Path skinPath) throws IOException {
        // 1. Leia os arquivos na CPU. O construtor não faz chamadas OpenGL.
        data = Md2Loader.load(modelPath);
        skin = PcxLoader.load(skinPath);
        if (skin.getWidth() != data.skinWidth() || skin.getHeight() != data.skinHeight()) {
            throw new IOException("Dimensões da skin não correspondem ao cabeçalho MD2");
        }
        positionBuffer = BufferUtils.createFloatBuffer(Math.multiplyExact(data.vertexIndices().length, 3));
        interpolatedPositions = new float[Math.multiplyExact(data.vertexCount(), 3)];

        // 2. Agrupe frames consecutivos pelo nome sem os dígitos finais: idle1 -> idle.
        // Isso descobre intervalos; FPS e repetição continuam sendo decisões da aplicação.
        int first = 0;
        while (first < data.frames().size()) {
            String baseName = animationName(data.frames().get(first).getName());
            int last = first;
            while (last + 1 < data.frames().size()
                    && animationName(data.frames().get(last + 1).getName()).equals(baseName)) last++;
            String name = baseName;
            int suffix = 2;
            while (animations.containsKey(name)) name = baseName + "_" + suffix++;
            animations.put(name, new Md2Animation(name, first, last, 10, true));
            first = last + 1;
        }
        playAnimation(animations.keySet().iterator().next());

        // 3. Use os limites de TODOS os frames para evitar mudanças de escala durante a animação.
        for (Md2Frame frame : data.frames()) {
            for (int vertex = 0; vertex < data.vertexCount(); vertex++) {
                for (int axis = 0; axis < 3; axis++) {
                    float value = frame.getCoordinate(vertex, axis);
                    minimum[axis] = Math.min(minimum[axis], value);
                    maximum[axis] = Math.max(maximum[axis], value);
                }
            }
        }
    }

    private static String animationName(String frameName) {
        String name = frameName.replaceFirst("[0-9]+$", "");
        return name.isBlank() ? "animation" : name;
    }

    public List<Md2Animation> getAnimations() { return List.copyOf(animations.values()); }
    public Md2Animation getAnimation() { return animation; }
    public int getVertexCount() { return data.vertexCount(); }
    public int getTriangleCount() { return data.triangleCount(); }
    public int getFrameCount() { return data.frames().size(); }
    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }
    public boolean isFinished() { return !animation.loop() && framePosition >= animation.frameCount() - 1; }

    /** Define FPS/repetição sem supor que o MD2 contenha essas informações. */
    public void configureAnimation(String name, double fps, boolean loop) {
        Md2Animation previous = animations.get(name);
        if (previous == null) throw new IllegalArgumentException("Animação inexistente: " + name);
        Md2Animation configured = new Md2Animation(name, previous.firstFrame(), previous.lastFrame(), fps, loop);
        animations.put(name, configured);
        if (animation.name().equals(name)) {
            animation = configured;
            framePosition = 0;
            dirty = true;
        }
    }

    /** Inicia ou reinicia a sequência; animações sem loop param na última pose. */
    public void playAnimation(String name) {
        Md2Animation selected = animations.get(name);
        if (selected == null) throw new IllegalArgumentException("Animação inexistente: " + name);
        animation = selected;
        framePosition = 0;
        paused = false;
        dirty = true;
    }

    public void update(double deltaTime) {
        // 1. O tempo está em segundos; a velocidade não depende do número de desenhos por segundo.
        if (!Double.isFinite(deltaTime) || deltaTime < 0) {
            throw new IllegalArgumentException("deltaTime deve ser finito e não negativo");
        }
        if (paused || deltaTime == 0 || isFinished()) return;

        // 2. A parte inteira seleciona o frame e a fração será o fator de interpolação.
        if (animation.loop()) {
            double duration = animation.frameCount() / animation.fps();
            framePosition = (framePosition + (deltaTime % duration) * animation.fps()) % animation.frameCount();
        } else {
            framePosition = Math.min(animation.frameCount() - 1, framePosition + deltaTime * animation.fps());
        }
        dirty = true;
    }

    public int getCurrentFrame() { return animation.firstFrame() + (int) Math.floor(framePosition); }

    public int getNextFrame() {
        int next = getCurrentFrame() + 1;
        if (next <= animation.lastFrame()) return next;
        return animation.loop() ? animation.firstFrame() : animation.lastFrame();
    }

    public float getInterpolation() { return (float) (framePosition - Math.floor(framePosition)); }

    /** Consulta a pose na CPU, útil também para colisões ou testes sem uma janela. */
    public float getInterpolatedCoordinate(int vertex, int axis) {
        float a = data.frames().get(getCurrentFrame()).getCoordinate(vertex, axis);
        float b = data.frames().get(getNextFrame()).getCoordinate(vertex, axis);
        return (float) ((1.0 - getInterpolation()) * a + getInterpolation() * (double) b);
    }

    @Override
    public void load() {
        if (closed) throw new IllegalStateException("Modelo já liberado");
        if (positionVbo != 0) return;
        try {
            // 1. Reserve o VBO dinâmico uma única vez; a pose muda, mas seu tamanho não.
            positionVbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, positionVbo);
            glBufferData(GL_ARRAY_BUFFER, (long) positionBuffer.capacity() * Float.BYTES, GL_DYNAMIC_DRAW);

            // 2. UVs são constantes em todos os frames, portanto ficam em um VBO estático.
            FloatBuffer uvBuffer = BufferUtils.createFloatBuffer(data.textureCoordinates().length);
            uvBuffer.put(data.textureCoordinates()).flip();
            uvVbo = glGenBuffers();
            glBindBuffer(GL_ARRAY_BUFFER, uvVbo);
            glBufferData(GL_ARRAY_BUFFER, uvBuffer, GL_STATIC_DRAW);

            // 3. O leitor PCX produziu um BufferedImage; reutilize o upload RGBA já existente.
            textureId = TextureLoader.loadTexture(skin);
            dirty = true;
        } catch (RuntimeException | Error failure) {
            close();
            throw failure;
        } finally {
            glBindBuffer(GL_ARRAY_BUFFER, 0);
        }
    }

    private void uploadPose() {
        // 1. Interpole os vértices únicos antes de expandir os cantos dos triângulos.
        // O buffer direto é reutilizado; não alocamos outro buffer a cada atualização.
        positionBuffer.clear();
        for (int vertex = 0; vertex < data.vertexCount(); vertex++) {
            for (int axis = 0; axis < 3; axis++) {
                interpolatedPositions[vertex * 3 + axis] = getInterpolatedCoordinate(vertex, axis);
            }
        }
        for (int index : data.vertexIndices()) {
            positionBuffer.put(interpolatedPositions, index * 3, 3);
        }
        positionBuffer.flip();

        // 2. Substitua somente o conteúdo: mantenha o mesmo identificador e armazenamento VBO.
        glBindBuffer(GL_ARRAY_BUFFER, positionVbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, positionBuffer);
        dirty = false;
    }

    @Override
    public void draw() {
        if (closed || positionVbo == 0) throw new IllegalStateException("Chame load() com contexto OpenGL ativo");
        if (dirty) uploadPose();

        // 1. A cor branca preserva as cores da skin no pipeline fixo, sem iluminação.
        glColor3f(1, 1, 1);
        glEnable(GL_TEXTURE_2D);
        glBindTexture(GL_TEXTURE_2D, textureId);

        // 2. Cada ponteiro associa um atributo ao VBO que está vinculado naquele momento.
        glEnableClientState(GL_VERTEX_ARRAY);
        glBindBuffer(GL_ARRAY_BUFFER, positionVbo);
        glVertexPointer(3, GL_FLOAT, 0, 0L);
        glEnableClientState(GL_TEXTURE_COORD_ARRAY);
        glBindBuffer(GL_ARRAY_BUFFER, uvVbo);
        glTexCoordPointer(2, GL_FLOAT, 0, 0L);

        // 3. Os cantos foram expandidos; podemos desenhar sem um buffer adicional de índices.
        glDrawArrays(GL_TRIANGLES, 0, data.vertexIndices().length);
        glDisableClientState(GL_TEXTURE_COORD_ARRAY);
        glDisableClientState(GL_VERTEX_ARRAY);
        glBindBuffer(GL_ARRAY_BUFFER, 0);
        glBindTexture(GL_TEXTURE_2D, 0);
    }

    public float getCenter(int axis) { return minimum[axis] / 2 + maximum[axis] / 2; }

    public double getDisplayScale() {
        double extent = 0;
        for (int axis = 0; axis < 3; axis++) extent = Math.max(extent, (double) maximum[axis] - minimum[axis]);
        return extent > 0 ? 2.2 / extent : 1;
    }

    @Override
    public void close() {
        // Libere recursos enquanto o contexto ainda existe. Repetir close() é seguro.
        if (positionVbo != 0) glDeleteBuffers(positionVbo);
        if (uvVbo != 0) glDeleteBuffers(uvVbo);
        if (textureId != 0) glDeleteTextures(textureId);
        positionVbo = uvVbo = textureId = 0;
        closed = true;
    }
}
