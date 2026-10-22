# Aula: VBO, OBJ e animação MD2 com LWJGL 3 e Maven

## Modelo MD2 animado

Execute os comandos na raiz do projeto, onde está o `pom.xml`.
Por padrão, `aula.Main` abre `md2s/tris.md2`, aplica `md2s/skin.pcx`
e reproduz a sequência `idle`. Também é possível executar `aula.Main` pela IDE,
com a raiz do projeto como diretório de trabalho.

```bash
mvn compile exec:java
```

Para outro MD2, informe o modelo e, opcionalmente, a skin:

```bash
mvn compile exec:java -Dexec.args="md2s/tris.md2 md2s/skin.pcx"
```

Sem o segundo argumento, o programa procura `skin.pcx` na pasta do modelo.
No PowerShell, coloque a opção inteira entre aspas se o terminal separar `-D`:
`mvn compile exec:java '-Dexec.args=md2s/tris.md2 md2s/skin.pcx'`.
O primeiro argumento também pode ser um OBJ, preservando o exemplo anterior.

### Controles MD2

| Tecla | Ação no modelo fornecido |
| --- | --- |
| 1 | `active`, frames 0–4, uma execução |
| 2 | `pow`, frames 5–31, repetição |
| 3 | `idle`, frames 32–61, repetição |
| 4 | `putway`, frames 62–64, uma execução |
| Espaço | Pausar/continuar a animação |
| R | Reiniciar a sequência atual |
| A | Parar/continuar a rotação de apresentação |
| W, segurado | Wireframe |
| S, segurado | Retirar a inclinação da apresentação |
| ESC | Fechar |

Cada acionamento de 1–4 reinicia a sequência selecionada. Sequências sem repetição
mantêm a última pose; não retornam automaticamente para `idle`. Pausa e rotação
são independentes. O título mostra sequência, frame e estado de reprodução.

### Classes e processos

As etapas estão comentadas em português nos próprios métodos:

| Arquivo | Responsabilidade |
| --- | --- |
| `aula/md2/Md2Loader.java` | Cabeçalho, validação, leitura little-endian, triângulos, UVs e frames |
| `aula/md2/Md2Frame.java` | Nome e posições XYZ de uma pose |
| `aula/md2/Md2Animation.java` | Intervalo inclusivo, FPS e repetição |
| `aula/md2/Md2Model.java` | Sequências, tempo, interpolação, VBOs, textura e liberação |
| `aula/util/PcxLoader.java` | Cabeçalho PCX, RLE, padding e paleta |
| `aula/util/TextureLoader.java` | Conversão ARGB para RGBA e upload OpenGL |
| `aula/Main.java` | Janela, contexto, configuração, controles e loop de desenho |

`aula` é o package do projeto, relativo a `src/main/java`. O `Md2Model` estende
`aula.Model.Model`, também utilizado pelo OBJ.

### Etapas da animação

1. O construtor lê modelo e skin na CPU. Triângulos e UVs são compartilhados;
   as posições variam entre frames.
2. O loader descompacta cada posição com
   `posição = byteSemSinal * escalaDoFrame + translaçãoDoFrame`.
3. Frames consecutivos como `idle1`, `idle2` e `idle3` formam `idle`.
   O MD2 não armazena FPS nem repetição. O padrão é 10 FPS e loop;
   `Main.configureWeaponAnimations` define as regras da arma fornecida.
   Use `configureAnimation(nome, fps, loop)` para modificá-las.
4. `load()` exige contexto OpenGL ativo e cria posições dinâmicas, UVs estáticos
   e textura. Repetir essa chamada não duplica os recursos.
5. `update(deltaTime)` recebe segundos e seleciona dois frames vizinhos e uma
   fração. A velocidade independe da taxa de renderização.
6. `draw()` interpola cada posição com `A * (1 - alpha) + B * alpha`, expande os
   cantos para preservar costuras UV e atualiza o VBO com `glBufferSubData`.
   O buffer direto é reutilizado.
7. O desenho usa `glVertexPointer`, `glTexCoordPointer` e `glDrawArrays`, sem
   shaders. Os limites de todos os frames estabilizam o enquadramento.
   A rotação de -90° em X converte o eixo vertical Z do Quake para Y.
8. `close()` apaga VBOs e textura antes da destruição da janela.

O modelo fornecido possui 190 vértices, 328 triângulos, 65 frames e 984 cantos de
desenho. A skin tem 780 × 168 pixels, um plano de 8 bits e paleta de 256 cores.
O leitor PCX aceita a paleta com ou sem `0x0C`: a skin fornecida não possui esse
marcador. A imagem e os UVs preservam a mesma orientação vertical.

Não se ativa `GL_LIGHTING`. Os índices de normais MD2 são consumidos na leitura,
mas não usados no desenho. Iluminação futura exigirá decodificar e interpolar normais.

### Verificações

O script usa o JDK instalado e os JARs da versão LWJGL do `pom.xml`, já presentes
em `%USERPROFILE%/.m2/repository`. Compila com compatibilidade Java 17 e não baixa
dependências. Os testes não usam JUnit; o ponto de entrada é esse script, e não
`mvn test`:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/check-md2.ps1
```

Para verificar também upload, desenho de todas as sequências e liberação da GPU,
usando uma janela OpenGL oculta:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File scripts/check-md2.ps1 -Render
```

`-ExecutionPolicy Bypass` vale apenas para esse processo, sem mudar a política do
sistema. A captura é salva em `target/md2-preview.png`. O script usa natives
Windows x64. São verificados bytes sem sinal, escala/translação, UVs, PCX com
padding e marcador opcional, arquivos inválidos, interpolação, pausa, loop,
término e independência da taxa de desenho. Artefatos ficam em `target/`.

Referências: [estruturas MD2 e PCX](https://github.com/id-Software/Quake-2/blob/master/qcommon/qfiles.h),
[interpolação original](https://github.com/id-Software/Quake-2/blob/master/ref_gl/gl_mesh.c)
e [leitor PCX original](https://github.com/id-Software/Quake-2/blob/master/ref_gl/gl_image.c).

## Notas do exemplo OBJ original

Requisitos: JDK 17+, Maven 3.9+ e driver com suporte a OpenGL 2.1 (compatibility profile).

### Executar o exemplo OBJ

Na raiz do projeto, passe o caminho do OBJ:

```bash
mvn compile exec:java -Dexec.args="caminho/para/modelo.obj"
```

No Windows, também é possível abrir o projeto como **Maven Project** no IntelliJ ou Eclipse e executar `aula.Main`.

No macOS, a janela GLFW pode exigir execução com `-XstartOnFirstThread`. Se necessário, rode pela IDE com essa opção na JVM (o `exec:java` não cria outra JVM para aplicar esse argumento).

### Controles do exemplo OBJ

- Segure `W` para wireframe.
- `ESC` fecha a janela.

### Objetivos didáticos do exemplo OBJ

O exemplo OBJ usa três VBOs (`positions`, `normals`, `uvs`) e renderiza com o pipeline de compatibilidade, sem shaders e sem VAO. Uma evolução possível é migrar para VAO, `glVertexAttribPointer` e GLSL.

O parser OBJ existente permanece em `aula.obj.ObjModel`. O `Main` associa manualmente as texturas PNG aos materiais `03___Default` e `glass`; arquivos `.mtl` não são carregados automaticamente. A implementação MD2 possui seu próprio leitor binário e usa a skin PCX.

Perfis Maven para natives: Windows x64/ARM64, Linux x64/ARM64 e macOS Intel/Apple Silicon.
