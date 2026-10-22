package aula;

import aula.md2.Md2Animation;
import aula.md2.Md2Model;
import aula.obj.ObjModel;
import aula.util.TextureLoader;
import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.opengl.GL;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.Callbacks.glfwFreeCallbacks;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;

/** Demonstra MD2 animado por padrão e mantém a abertura de OBJ pelo primeiro argumento. */
public class Main {
    public static void main(String[] args) throws Exception {
        // 1. Os caminhos relativos partem da raiz do projeto, onde está o pom.xml.
        Path modelPath = Path.of(args.length > 0 ? args[0] : "md2s/tris.md2");
        boolean isMd2 = modelPath.toString().toLowerCase(Locale.ROOT).endsWith(".md2");
        Path skinPath = args.length > 1 ? Path.of(args[1]) : modelPath.resolveSibling("skin.pcx");

        // 2. O callback torna erros nativos visíveis no console antes de qualquer janela existir.
        GLFWErrorCallback errors = GLFWErrorCallback.createPrint(System.err);
        errors.set();
        long window = 0;
        Md2Model md2 = null;
        int[] objTextures = new int[2];
        boolean initialized = false;
        try {
            if (!glfwInit()) throw new IllegalStateException("GLFW não iniciou");
            initialized = true;
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 2);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
            window = glfwCreateWindow(1024, 768, "Aula VBO - MD2", 0, 0);
            if (window == 0) throw new IllegalStateException("Falha ao criar janela");
            glfwMakeContextCurrent(window);
            glfwSwapInterval(1);
            GL.createCapabilities();
            glEnable(GL_DEPTH_TEST);
            glActiveTexture(GL_TEXTURE0);
            glClearColor(.18f, .20f, .24f, 1);

            // 3. O parser lê a CPU e load() envia VBOs/textura à GPU com o contexto já ativo.
            ObjModel obj = null;
            boolean[] rotate = {true};
            if (isMd2) {
                md2 = new Md2Model(modelPath, skinPath);
                configureWeaponAnimations(md2);
                md2.load();
                installAnimationControls(window, md2, rotate);
                System.out.printf("MD2: %s | %d vértices | %d triângulos | %d frames%n",
                        modelPath, md2.getVertexCount(), md2.getTriangleCount(), md2.getFrameCount());
            } else {
                // Preserve o exemplo OBJ e seus materiais; argumentos MD2 usam o fluxo acima.
                obj = new ObjModel(modelPath.toString());
                objTextures[0] = loadObjTexture("src/main/resources/saida_textura_03___Default.png");
                objTextures[1] = loadObjTexture("src/main/resources/saida_textura_glass.png");
                obj.setMaterialTexture("03___Default", objTextures[0]);
                obj.setMaterialTexture("glass", objTextures[1]);
            }

            // 4. Meça o tempo a cada iteração, inclusive quando a janela estiver minimizada.
            double previousTime = glfwGetTime();
            double angle = 0;
            int[] width = new int[1], height = new int[1];
            String previousTitle = "";
            while (!glfwWindowShouldClose(window)) {
                glfwPollEvents();
                double now = glfwGetTime();
                double deltaTime = now - previousTime;
                previousTime = now;
                if (glfwGetKey(window, GLFW_KEY_ESCAPE) == GLFW_PRESS) glfwSetWindowShouldClose(window, true);

                // 5. Atualizar a animação independe do desenho. Pausar congela a pose atual.
                if (md2 != null) md2.update(deltaTime);
                if (rotate[0]) angle = (angle + deltaTime * 25) % 360;
                glfwGetFramebufferSize(window, width, height);
                if (width[0] == 0 || height[0] == 0) continue;

                // 6. Ajuste a projeção à proporção real da janela, mantendo o pipeline fixo.
                glViewport(0, 0, width[0], height[0]);
                glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);
                glMatrixMode(GL_PROJECTION);
                glLoadIdentity();
                double aspect = (double) width[0] / height[0];
                glFrustum(-aspect * .7, aspect * .7, -.7, .7, 1, 100);
                glMatrixMode(GL_MODELVIEW);
                glLoadIdentity();
                glTranslatef(0, 0, -4);
                if (glfwGetKey(window, GLFW_KEY_S) != GLFW_PRESS) glRotatef(20, 1, 0, 0);
                glRotatef((float) angle, 0, 1, 0);
                glPolygonMode(GL_FRONT_AND_BACK,
                        glfwGetKey(window, GLFW_KEY_W) == GLFW_PRESS ? GL_LINE : GL_FILL);

                if (md2 != null) {
                    // 7. Quake usa Z para cima. Converta para Y e centralize todos os frames.
                    glRotatef(-90, 1, 0, 0);
                    double scale = md2.getDisplayScale();
                    glScaled(scale, scale, scale);
                    glTranslatef(-md2.getCenter(0), -md2.getCenter(1), -md2.getCenter(2));
                    md2.draw();
                    String title = "Aula MD2 | " + md2.getAnimation().name()
                            + " | frame " + md2.getCurrentFrame()
                            + (md2.isPaused() ? " | pausado" : md2.isFinished() ? " | concluído" : "");
                    if (!title.equals(previousTitle)) {
                        glfwSetWindowTitle(window, title);
                        previousTitle = title;
                    }
                } else {
                    glColor3f(1, 1, 1);
                    glEnable(GL_TEXTURE_2D);
                    glTranslatef(0, -1, 0);
                    glScaled(.05, .05, .05);
                    obj.draw();
                }
                // 8. Apresente a imagem desenhada no back buffer.
                glfwSwapBuffers(window);
            }
        } finally {
            // 9. Destrua os objetos GPU antes da janela e os callbacks antes do GLFW.
            try {
                if (md2 != null) md2.close();
                for (int texture : objTextures) if (texture != 0) glDeleteTextures(texture);
            } finally {
                if (window != 0) {
                    glfwFreeCallbacks(window);
                    glfwDestroyWindow(window);
                }
                if (initialized) glfwTerminate();
                glfwSetErrorCallback(null);
                errors.free();
            }
        }
    }

    private static void configureWeaponAnimations(Md2Model model) {
        // Regras didáticas para a skin v_chain fornecida; o arquivo não armazena FPS nem loop.
        // Outros MD2 preservam as sequências descobertas e o padrão de 10 FPS com repetição.
        for (Md2Animation animation : model.getAnimations()) {
            switch (animation.name()) {
                case "active", "putway" -> model.configureAnimation(animation.name(), 10, false);
                case "pow", "idle" -> model.configureAnimation(animation.name(), 10, true);
                default -> { }
            }
        }
        for (Md2Animation animation : model.getAnimations()) {
            if (animation.name().equals("idle")) {
                model.playAnimation("idle");
                break;
            }
        }
    }

    private static void installAnimationControls(long window, Md2Model model, boolean[] rotate) {
        List<Md2Animation> animations = model.getAnimations();
        for (int i = 0; i < animations.size(); i++) {
            Md2Animation animation = animations.get(i);
            String key = i < 9 ? Integer.toString(i + 1) : "sem tecla";
            System.out.printf("%s: %s [%d..%d], %.1f FPS, loop=%s%n", key, animation.name(),
                    animation.firstFrame(), animation.lastFrame(), animation.fps(), animation.loop());
        }
        System.out.println("ESPAÇO: pausar | R: reiniciar | A: girar/parar | W: wireframe | S: sem inclinação | ESC: sair");

        // GLFW_PRESS ocorre uma vez por acionamento; GLFW_REPEAT não reinicia a animação.
        glfwSetKeyCallback(window, (handle, key, scancode, action, mods) -> {
            if (action != GLFW_PRESS) return;
            int index = key - GLFW_KEY_1;
            if (index >= 0 && index < Math.min(9, animations.size())) {
                model.playAnimation(animations.get(index).name());
            } else if (key == GLFW_KEY_SPACE) {
                model.setPaused(!model.isPaused());
            } else if (key == GLFW_KEY_R) {
                model.playAnimation(model.getAnimation().name());
            } else if (key == GLFW_KEY_A) {
                rotate[0] = !rotate[0];
            }
        });
    }

    private static int loadObjTexture(String location) throws IOException {
        var image = ImageIO.read(Path.of(location).toFile());
        if (image == null) throw new IOException("Imagem não reconhecida: " + location);
        return TextureLoader.loadTexture(image);
    }
}
