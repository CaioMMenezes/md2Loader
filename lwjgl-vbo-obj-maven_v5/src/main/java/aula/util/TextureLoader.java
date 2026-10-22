package aula.util;

import java.awt.image.BufferedImage;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

import javax.imageio.ImageIO;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL12;

import static org.lwjgl.opengl.GL11.*;

public class TextureLoader {
    private static final int BYTES_PER_PIXEL = 4; // Um byte para cada canal RGBA.
       
    public static int loadTexture(BufferedImage image){
    	
            if (image == null) throw new IllegalArgumentException("Imagem de textura ausente");

            // 1. Leia os pixels ARGB do BufferedImage produzido por ImageIO ou PcxLoader.
          int[] pixels = new int[image.getWidth() * image.getHeight()];
            image.getRGB(0, 0, image.getWidth(), image.getHeight(), pixels, 0, image.getWidth());

            ByteBuffer buffer = BufferUtils.createByteBuffer(image.getWidth() * image.getHeight() * BYTES_PER_PIXEL);

            // 2. Reordene ARGB para RGBA no buffer direto que será enviado ao OpenGL.
            // As linhas seguem de cima para baixo; os UVs MD2 usam a mesma convenção.
            for(int y = 0; y < image.getHeight(); y++){
                for(int x = 0; x < image.getWidth(); x++){
                    int pixel = pixels[y * image.getWidth() + x];
                    buffer.put((byte) ((pixel >> 16) & 0xFF)); // Vermelho.
                    buffer.put((byte) ((pixel >> 8) & 0xFF));  // Verde.
                    buffer.put((byte) (pixel & 0xFF));         // Azul.
                    buffer.put((byte) ((pixel >> 24) & 0xFF)); // Opacidade.
                }
            }

            // 3. flip() troca o buffer de escrita para leitura, voltando a posição para zero.
            buffer.flip();

            // 4. Crie a textura no contexto atual e selecione-a para configurar e preencher.

          int textureID = glGenTextures();
            glBindTexture(GL_TEXTURE_2D, textureID);

            // 5. Nas bordas, repita o último texel para evitar pegar a borda oposta da skin.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);

            // 6. Interpole texels vizinhos ao ampliar/reduzir. GL_LINEAR dispensa mipmaps.
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);

            // 7. Envie a imagem RGBA inteira à GPU, incluindo dimensões não potências de dois.
            glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, image.getWidth(), image.getHeight(), 0, GL_RGBA, GL_UNSIGNED_BYTE, buffer);

            // 8. O proprietário guardará o ID para desenhar e chamará glDeleteTextures ao fechar.
          return textureID;
       }

       public static BufferedImage loadImage(String loc)
       {
            // Conveniência para os formatos reconhecidos pelo ImageIO. PCX usa PcxLoader.
            try (FileInputStream input = new FileInputStream(loc)) {
               return ImageIO.read(input);
            } catch (IOException e) {
                // Mantém a API antiga: falha de leitura é sinalizada pelo retorno null.
            }
           return null;
       }
}
