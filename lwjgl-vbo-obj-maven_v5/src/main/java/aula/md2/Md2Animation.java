package aula.md2;

/** Intervalo inclusivo de frames e regras de reprodução definidas pela aplicação. */
public record Md2Animation(String name, int firstFrame, int lastFrame, double fps, boolean loop) {
    public Md2Animation {
        if (name == null || name.isBlank() || firstFrame < 0 || lastFrame < firstFrame
                || !Double.isFinite(fps) || fps <= 0) {
            throw new IllegalArgumentException("Nome, intervalo ou FPS de animação inválido");
        }
    }

    public int frameCount() { return lastFrame - firstFrame + 1; }
}
