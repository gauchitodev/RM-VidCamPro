package dev.gauchito.camara;

/**
 * Compara dos cuadros chicos (RGBA, ANCHO×ALTO) y estima cómo pasar del nuevo al viejo:
 * escala alrededor del centro, desplazamiento y ganancia de cada color.
 * Se usa una vez, en el cuadro en que el teléfono cambia de cámara física.
 *
 * Convención: el nuevo, muestreado en  c + (x + d − c)·s  (coordenadas normalizadas 0..1),
 * se parece al viejo en x. Con s = 1, d = 0 no hay corrección.
 */
final class Registro {
    static final int ANCHO = 256, ALTO = 144;      // cuadro chico
    static final int FW = 256, FH = 128;           // ventana de la FFT (franja central)

    float escala = 1, dx = 0, dy = 0;              // dx, dy en fracción del ancho / alto
    float gr = 1, gg = 1, gb = 1;
    float calidad;                                 // pico de correlación (0..1): qué tan seguro

    /** viejo y nuevo: RGBA de ANCHO×ALTO, fila 0 abajo (como glReadPixels). */
    void medir(byte[] viejo, byte[] nuevo) {
        float[] gv = gris(viejo);
        float[] gn = gris(nuevo);
        float[] ventana = hann();
        float[] fv = ventanear(gv, ventana);
        double[][] FV = fft2(fv);

        float mejor = -1, mejorS = 1, mejorDx = 0, mejorDy = 0;
        for (float s = 0.90f; s <= 1.101f; s += 0.01f) {
            float[] gs = escalar(gn, s);
            float[] r = correlacion(FV, fft2(ventanear(gs, ventana)));
            // pico
            int im = 0;
            for (int i = 1; i < r.length; i++) if (r[i] > r[im]) im = i;
            if (r[im] > mejor) {
                mejor = r[im]; mejorS = s;
                int px = im % FW, py = im / FW;
                // subpíxel por parábola
                float sx = px + parabola(r[py * FW + (px + FW - 1) % FW], r[im], r[py * FW + (px + 1) % FW]);
                float sy = py + parabola(r[((py + FH - 1) % FH) * FW + px], r[im], r[((py + 1) % FH) * FW + px]);
                if (sx > FW / 2f) sx -= FW;
                if (sy > FH / 2f) sy -= FH;
                mejorDx = sx; mejorDy = sy;
            }
        }
        calidad = mejor;
        escala = mejorS;
        // correlacion(viejo, nuevoEscalado) da el corrimiento del nuevo respecto del viejo:
        // nuevoS(x) = viejo(x − d)  ⇒  muestrear nuevoS en x + d
        dx = mejorDx / ANCHO;
        dy = mejorDy / ALTO;

        // ganancias de color: centro del viejo contra el nuevo ya alineado
        double[] sv = new double[3], sn = new double[3];
        for (int y = ALTO / 5; y < ALTO * 4 / 5; y++)
            for (int x = ANCHO / 5; x < ANCHO * 4 / 5; x++) {
                float u = (x + 0.5f) / ANCHO, v = (y + 0.5f) / ALTO;
                float nu = 0.5f + (u + dx - 0.5f) * escala, nv = 0.5f + (v + dy - 0.5f) * escala;
                int i = (y * ANCHO + x) * 4;
                int j = muestra(nu, nv);
                if (j < 0) continue;
                for (int k = 0; k < 3; k++) { sv[k] += viejo[i + k] & 0xFF; sn[k] += nuevo[j + k] & 0xFF; }
            }
        gr = gan(sv[0], sn[0]); gg = gan(sv[1], sn[1]); gb = gan(sv[2], sn[2]);
    }

    private static float gan(double v, double n) {
        if (n < 1) return 1;
        return (float) Math.max(0.5, Math.min(2.0, v / n));
    }

    /** Índice RGBA del vecino más cercano a (u, v) normalizado, o −1 si cae afuera. */
    private static int muestra(float u, float v) {
        int x = (int) (u * ANCHO), y = (int) (v * ALTO);
        if (x < 0 || y < 0 || x >= ANCHO || y >= ALTO) return -1;
        return (y * ANCHO + x) * 4;
    }

    private static float parabola(float a, float b, float c) {
        float d = a - 2 * b + c;
        return Math.abs(d) < 1e-9f ? 0 : 0.5f * (a - c) / d;
    }

    /** Luminancia de la franja central FW×FH. */
    private static float[] gris(byte[] rgba) {
        float[] g = new float[ANCHO * ALTO];
        for (int i = 0; i < g.length; i++)
            g[i] = 0.299f * (rgba[i * 4] & 0xFF) + 0.587f * (rgba[i * 4 + 1] & 0xFF) + 0.114f * (rgba[i * 4 + 2] & 0xFF);
        return g;
    }

    /** El nuevo muestreado en c + (x − c)·s (bilineal), en ANCHO×ALTO. */
    private static float[] escalar(float[] g, float s) {
        float[] o = new float[g.length];
        float cx = ANCHO / 2f, cy = ALTO / 2f;
        for (int y = 0; y < ALTO; y++)
            for (int x = 0; x < ANCHO; x++) {
                float sx = cx + (x + 0.5f - cx) * s - 0.5f, sy = cy + (y + 0.5f - cy) * s - 0.5f;
                int x0 = (int) Math.floor(sx), y0 = (int) Math.floor(sy);
                float fx = sx - x0, fy = sy - y0;
                o[y * ANCHO + x] = (1 - fx) * (1 - fy) * px(g, x0, y0) + fx * (1 - fy) * px(g, x0 + 1, y0)
                        + (1 - fx) * fy * px(g, x0, y0 + 1) + fx * fy * px(g, x0 + 1, y0 + 1);
            }
        return o;
    }

    private static float px(float[] g, int x, int y) {
        x = Math.max(0, Math.min(ANCHO - 1, x)); y = Math.max(0, Math.min(ALTO - 1, y));
        return g[y * ANCHO + x];
    }

    private static float[] hann() {
        float[] w = new float[FW * FH];
        for (int y = 0; y < FH; y++)
            for (int x = 0; x < FW; x++)
                w[y * FW + x] = (float) ((0.5 - 0.5 * Math.cos(2 * Math.PI * x / (FW - 1))) * (0.5 - 0.5 * Math.cos(2 * Math.PI * y / (FH - 1))));
        return w;
    }

    /** Franja central FW×FH, sin la media y con ventana. */
    private static float[] ventanear(float[] g, float[] w) {
        float[] o = new float[FW * FH];
        int y0 = (ALTO - FH) / 2;
        double m = 0;
        for (int y = 0; y < FH; y++) for (int x = 0; x < FW; x++) m += g[(y + y0) * ANCHO + x];
        m /= FW * FH;
        for (int y = 0; y < FH; y++) for (int x = 0; x < FW; x++)
            o[y * FW + x] = (float) ((g[(y + y0) * ANCHO + x] - m) * w[y * FW + x]);
        return o;
    }

    /** Correlación de fase: real de IFFT(A·conj(B) / |A·conj(B)|), normalizada. */
    private static float[] correlacion(double[][] A, double[][] B) {
        int n = FW * FH;
        double[] re = new double[n], im = new double[n];
        for (int i = 0; i < n; i++) {
            // B·conj(A): el pico cae en el corrimiento de B respecto de A
            double r = B[0][i] * A[0][i] + B[1][i] * A[1][i];
            double j = B[1][i] * A[0][i] - B[0][i] * A[1][i];
            double m = Math.hypot(r, j) + 1e-9;
            re[i] = r / m; im[i] = j / m;
        }
        fft2(re, im, true);
        float[] o = new float[n];
        for (int i = 0; i < n; i++) o[i] = (float) (re[i] / n);
        return o;
    }

    private static double[][] fft2(float[] f) {
        double[] re = new double[f.length], im = new double[f.length];
        for (int i = 0; i < f.length; i++) re[i] = f[i];
        fft2(re, im, false);
        return new double[][]{re, im};
    }

    private static void fft2(double[] re, double[] im, boolean inversa) {
        double[] r = new double[Math.max(FW, FH)], i = new double[Math.max(FW, FH)];
        for (int y = 0; y < FH; y++) {
            System.arraycopy(re, y * FW, r, 0, FW); System.arraycopy(im, y * FW, i, 0, FW);
            fft(r, i, FW, inversa);
            System.arraycopy(r, 0, re, y * FW, FW); System.arraycopy(i, 0, im, y * FW, FW);
        }
        for (int x = 0; x < FW; x++) {
            for (int y = 0; y < FH; y++) { r[y] = re[y * FW + x]; i[y] = im[y * FW + x]; }
            fft(r, i, FH, inversa);
            for (int y = 0; y < FH; y++) { re[y * FW + x] = r[y]; im[y * FW + x] = i[y]; }
        }
    }

    /** FFT radix-2 en el lugar, n potencia de 2. */
    private static void fft(double[] re, double[] im, int n, boolean inversa) {
        for (int i = 1, j = 0; i < n; i++) {
            int b = n >> 1;
            for (; (j & b) != 0; b >>= 1) j ^= b;
            j ^= b;
            if (i < j) { double t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t; }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = 2 * Math.PI / len * (inversa ? 1 : -1);
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int s = 0; s < n; s += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = s + k, b = s + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci, xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr; im[b] = im[a] - xi;
                    re[a] += xr; im[a] += xi;
                    double t = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = t;
                }
            }
        }
    }
}
