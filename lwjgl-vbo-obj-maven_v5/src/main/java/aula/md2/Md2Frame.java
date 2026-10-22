package aula.md2;

/** Uma pose completa da malha; a ordem dos vértices é igual em todos os frames. */
public final class Md2Frame {
    private final String name;
    private final float[] positions;

    public Md2Frame(String name, float[] positions) {
        if (name == null || positions == null || positions.length == 0 || positions.length % 3 != 0) {
            throw new IllegalArgumentException("Frame precisa de nome e posições XYZ completas");
        }
        this.name = name;
        this.positions = positions.clone();
    }

    public String getName() { return name; }
    public int getVertexCount() { return positions.length / 3; }

    /** component: 0 = X, 1 = Y, 2 = Z. Os valores já estão descompactados. */
    public float getCoordinate(int vertex, int component) {
        if (vertex < 0 || vertex >= getVertexCount() || component < 0 || component > 2) {
            throw new IndexOutOfBoundsException("Vértice ou componente inválido");
        }
        return positions[vertex * 3 + component];
    }
}
