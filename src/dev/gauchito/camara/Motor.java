package dev.gauchito.camara;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Motor de imagen en la placa gráfica. La cámara escribe en una SurfaceTexture nuestra;
 * cada cuadro se dibuja a un FBO apaisado (tamaño del video) con:
 *  - zoom digital de la gran angular (el HAL la entrega siempre entera entre 0,67 y 1,06),
 *  - corrección al cambiar de cámara física: escala, corrimiento y color que se sueltan en
 *    ~0,7 s, más un fundido corto con el último cuadro de la cámara anterior.
 * Del FBO sale a la pantalla (girado a vertical) y, si graba, al codificador (apaisado).
 */
final class Motor implements SurfaceTexture.OnFrameAvailableListener {

    /** Lo que la cámara sabe de cada cuadro (de los resultados), por marca de tiempo. */
    interface Fuente { Cuadro info(long ts); }
    static final class Cuadro {
        final String fisica; final float zoomPedido, zoomHal;
        Cuadro(String f, float zp, float zh) { fisica = f; zoomPedido = zp; zoomHal = zh; }
    }
    interface Aviso { void decir(String s); }

    static final int FADE_MS = 120, SUELTA_MS = 700, SUELTA_COLOR_MS = 1500;
    static final float MEZCLA_INICIAL = 0.6f;   // cuánto del cuadro viejo en el primer cuadro nuevo

    private final HandlerThread hilo = new HandlerThread("motor");
    private final Handler h;
    private final ExecutorService medidor = Executors.newSingleThreadExecutor();
    private final Fuente fuente;
    private final Aviso aviso;

    private EGLDisplay dpy = EGL14.EGL_NO_DISPLAY;
    private EGLContext ctx = EGL14.EGL_NO_CONTEXT;
    private EGLConfig cfg;
    private EGLSurface sNada = EGL14.EGL_NO_SURFACE, sVista = EGL14.EGL_NO_SURFACE, sVideo = EGL14.EGL_NO_SURFACE;
    private int vistaW, vistaH;

    private int texCam;
    private SurfaceTexture stCam;
    private Surface entrada;
    private int anchoV, altoV;

    private final int[] fbo = new int[2], tex = new int[2];
    private int actual = 0;
    private int fboChico, texChico;
    private final ByteBuffer pixChico = ByteBuffer.allocateDirect(Registro.ANCHO * Registro.ALTO * 4).order(ByteOrder.nativeOrder());
    private byte[] ultimoChico;                // el último cuadro mostrado, chico, para medir

    private int pCam, pCopia;
    private FloatBuffer cuad;
    private final float[] stM = new float[16], camM = new float[16];

    // transición entre cámaras físicas
    private String fisicaAnterior;
    private long t0Trans = -1;                 // ms (uptime) del cambio
    private final float[] corr = {1, 0, 0, 1, 1, 1};        // escala, dx, dy, gr, gg, gb al empezar
    private final float[][] calib = new float[2][];          // guardada: [0] subiendo (2→0), [1] bajando
    private boolean hayAnterior;
    volatile boolean rotarVista = true;        // vista vertical
    private long pantallaOffsetNs = Long.MIN_VALUE;

    Motor(Fuente f, Aviso a) {
        fuente = f; aviso = a;
        hilo.start();
        h = new Handler(hilo.getLooper());
        enHilo(this::iniciarEgl);
    }

    /** Corre en el hilo del motor y espera. */
    private void enHilo(Runnable r) {
        if (Thread.currentThread() == hilo) { r.run(); return; }
        CountDownLatch l = new CountDownLatch(1);
        h.post(() -> { try { r.run(); } finally { l.countDown(); } });
        try { l.await(); } catch (InterruptedException ignorado) {}
    }

    // ---------- EGL / GL ----------

    private void iniciarEgl() {
        dpy = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        int[] v = new int[2];
        EGL14.eglInitialize(dpy, v, 0, v, 1);
        int[] atr = {EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT, 0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT | EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE};
        EGLConfig[] c = new EGLConfig[1];
        int[] n = new int[1];
        EGL14.eglChooseConfig(dpy, atr, 0, c, 0, 1, n, 0);
        cfg = c[0];
        ctx = EGL14.eglCreateContext(dpy, cfg, EGL14.EGL_NO_CONTEXT, new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        sNada = EGL14.eglCreatePbufferSurface(dpy, cfg, new int[]{EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE}, 0);
        EGL14.eglMakeCurrent(dpy, sNada, sNada, ctx);

        cuad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        cuad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);

        String vs = "attribute vec2 aPos; varying vec2 vUv;"
                + "void main(){ vUv = aPos * 0.5 + 0.5; gl_Position = vec4(aPos, 0.0, 1.0); }";
        // cámara → FBO: zoom digital + corrección que se suelta + fundido con el cuadro anterior
        String fsCam = "#extension GL_OES_EGL_image_external : require\n"
                + "precision highp float; varying vec2 vUv;"
                + "uniform samplerExternalOES sCam; uniform sampler2D sAnt; uniform mat4 uTex;"
                + "uniform float uDig; uniform float uEsc; uniform vec2 uDesp; uniform vec3 uGan; uniform float uMez;"
                + "void main(){"
                + "  vec2 c = vec2(0.5);"
                + "  vec2 p = c + (vUv + uDesp - c) * uEsc;"          // corrección (en el cuadro ya con zoom)
                + "  vec2 q = c + (p - c) / uDig;"                    // zoom digital: muestrear un recorte
                + "  vec3 n = texture2D(sCam, (uTex * vec4(q, 0.0, 1.0)).xy).rgb * uGan;"
                + "  vec3 a = texture2D(sAnt, vUv).rgb;"
                + "  gl_FragColor = vec4(mix(n, a, uMez), 1.0);"
                + "}";
        // FBO → pantalla o video; uRot = 1 gira 90° horario (apaisado → vertical, como el sensor)
        String fsCopia = "precision mediump float; varying vec2 vUv; uniform sampler2D sTex; uniform float uRot;"
                + "void main(){ vec2 t = uRot > 0.5 ? vec2(1.0 - vUv.y, vUv.x) : vUv;"
                + "  gl_FragColor = texture2D(sTex, t); }";
        pCam = programa(vs, fsCam);
        pCopia = programa(vs, fsCopia);

        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        texCam = t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texCam);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        stCam = new SurfaceTexture(texCam);
        stCam.setOnFrameAvailableListener(this, h);
        entrada = new Surface(stCam);

        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        fboChico = f[0];
        texChico = nuevaTextura(Registro.ANCHO, Registro.ALTO);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboChico);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, texChico, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private static int nuevaTextura(int w, int hh) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, hh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    private static int programa(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        for (int[] s : new int[][]{{GLES20.GL_VERTEX_SHADER}, {GLES20.GL_FRAGMENT_SHADER}}) {
            int sh = GLES20.glCreateShader(s[0]);
            GLES20.glShaderSource(sh, s[0] == GLES20.GL_VERTEX_SHADER ? vs : fs);
            GLES20.glCompileShader(sh);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(sh, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new RuntimeException("shader: " + GLES20.glGetShaderInfoLog(sh));
            GLES20.glAttachShader(p, sh);
        }
        GLES20.glBindAttribLocation(p, 0, "aPos");
        GLES20.glLinkProgram(p);
        return p;
    }

    // ---------- lo que usa la actividad ----------

    /** Superficie donde escribe la cámara, del tamaño del video (apaisado). */
    Surface entrada(int w, int hh) {
        enHilo(() -> {
            if (w != anchoV || hh != altoV) {
                anchoV = w; altoV = hh;
                stCam.setDefaultBufferSize(w, hh);
                for (int i = 0; i < 2; i++) {
                    if (tex[i] != 0) { GLES20.glDeleteTextures(1, new int[]{tex[i]}, 0); GLES20.glDeleteFramebuffers(1, new int[]{fbo[i]}, 0); }
                    tex[i] = nuevaTextura(w, hh);
                    int[] f = new int[1];
                    GLES20.glGenFramebuffers(1, f, 0);
                    fbo[i] = f[0];
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[i]);
                    GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex[i], 0);
                }
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                hayAnterior = false;
            }
        });
        return entrada;
    }

    void vista(SurfaceTexture st, int w, int hh) {
        enHilo(() -> {
            soltarVista();
            if (st == null) return;
            st.setDefaultBufferSize(w, hh);
            vistaW = w; vistaH = hh;
            sVista = EGL14.eglCreateWindowSurface(dpy, cfg, new Surface(st), new int[]{EGL14.EGL_NONE}, 0);
        });
    }

    private void soltarVista() {
        if (sVista != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(dpy, sNada, sNada, ctx);
            EGL14.eglDestroySurface(dpy, sVista);
            sVista = EGL14.EGL_NO_SURFACE;
        }
    }

    void grabarEn(Surface s) {
        enHilo(() -> {
            if (sVideo != EGL14.EGL_NO_SURFACE) { EGL14.eglMakeCurrent(dpy, sNada, sNada, ctx); EGL14.eglDestroySurface(dpy, sVideo); }
            sVideo = s == null ? EGL14.EGL_NO_SURFACE : EGL14.eglCreateWindowSurface(dpy, cfg, s, new int[]{EGL14.EGL_NONE}, 0);
        });
    }

    /**
     * Una corrección razonable entre las dos cámaras: escala ±15 %, corrimiento hasta 15 %
     * del cuadro y ganancias de color de 0,5 a 2. Fuera de eso es una mala medida.
     * (Escrito al revés a propósito: un NaN no pasa ninguna comparación.)
     */
    static boolean calibracionValida(float[] f) {
        if (f == null || f.length != 6) return false;
        if (!(f[0] > 0.85f && f[0] < 1.18f)) return false;
        if (!(Math.abs(f[1]) <= 0.15f && Math.abs(f[2]) <= 0.15f)) return false;
        for (int i = 3; i < 6; i++) if (!(f[i] >= 0.5f && f[i] <= 2f)) return false;
        return true;
    }

    void calibracion(float[] subiendo, float[] bajando) { calib[0] = subiendo; calib[1] = bajando; }
    float[][] calibracion() { return calib; }

    void soltar() {
        enHilo(() -> {
            grabarEn(null);
            soltarVista();
            if (stCam != null) stCam.release();
            EGL14.eglMakeCurrent(dpy, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
            EGL14.eglDestroyContext(dpy, ctx);
            EGL14.eglTerminate(dpy);
        });
        medidor.shutdownNow();
        hilo.quitSafely();
    }

    // ---------- cada cuadro ----------

    @Override
    public void onFrameAvailable(SurfaceTexture st) {
        if (anchoV == 0) return;
        EGL14.eglMakeCurrent(dpy, sNada, sNada, ctx);
        stCam.updateTexImage();
        long ts = stCam.getTimestamp();
        stCam.getTransformMatrix(stM);
        sinGiro(stM, camM);

        Cuadro c = fuente.info(ts);
        String fis = c == null ? fisicaAnterior : c.fisica;
        // zoom digital: la gran angular viene entera (0,67) aunque se pida más
        float dig = 1;
        if (c != null) {
            float halEfectivo = "2".equals(fis) ? Camara.ZOOM_GRAN_ANGULAR : c.zoomHal;
            dig = Math.max(1f, c.zoomPedido / halEfectivo);
        }
        long ahora = SystemClock.uptimeMillis();

        // ¿cambió de cámara física?
        if (fis != null && fisicaAnterior != null && !fis.equals(fisicaAnterior) && hayAnterior) {
            empezarTransicion(fis, dig, ahora);
        }
        fisicaAnterior = fis;

        // parámetros de la transición en este momento
        float mez = 0, w = 0, wc = 0;
        if (t0Trans >= 0) {
            float e = ahora - t0Trans;
            mez = MEZCLA_INICIAL * (1f - suave(e / FADE_MS));
            w = 1f - suave(e / SUELTA_MS);
            wc = 1f - suave(e / SUELTA_COLOR_MS);       // brillo y color: más despacio
            if (e > SUELTA_COLOR_MS) t0Trans = -1;
        }
        int ant = 1 - actual;
        dibujarCamara(fbo[actual], anchoV, altoV, dig,
                1 + (corr[0] - 1) * w, corr[1] * w, corr[2] * w,
                1 + (corr[3] - 1) * wc, 1 + (corr[4] - 1) * wc, 1 + (corr[5] - 1) * wc,
                mez, tex[ant]);

        // copia chica del cuadro mostrado (para medir en el próximo cambio)
        copiar(tex[actual], fboChico, Registro.ANCHO, Registro.ALTO, false);
        GLES20.glReadPixels(0, 0, Registro.ANCHO, Registro.ALTO, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixChico.position(0));
        if (ultimoChico == null) ultimoChico = new byte[pixChico.capacity()];
        pixChico.position(0);
        pixChico.get(ultimoChico);

        // pantalla
        if (sVista != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(dpy, sVista, sVista, ctx);
            copiar(tex[actual], 0, vistaW, vistaH, rotarVista);
            EGL14.eglSwapBuffers(dpy, sVista);
        }
        // video
        if (sVideo != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(dpy, sVideo, sVideo, ctx);
            copiar(tex[actual], 0, anchoV, altoV, false);
            EGLExt.eglPresentationTimeANDROID(dpy, sVideo, aMonotono(ts));
            EGL14.eglSwapBuffers(dpy, sVideo);
        }
        EGL14.eglMakeCurrent(dpy, sNada, sNada, ctx);

        // en la transición el anterior queda congelado: no se pisa hasta que termine el fundido
        if (!(t0Trans >= 0 && ahora - t0Trans < FADE_MS)) actual = 1 - actual;
        hayAnterior = true;
    }

    /**
     * El codificador y el audio van con el reloj monótono; la cámara puede marcar con el de
     * arranque (incluye el tiempo dormido). Si la marca está más cerca de ese, la paso.
     */
    private static long aMonotono(long ts) {
        long arr = SystemClock.elapsedRealtimeNanos(), mono = System.nanoTime();
        return Math.abs(ts - arr) < Math.abs(ts - mono) ? ts - (arr - mono) : ts;
    }

    private static float suave(float x) {
        x = Math.max(0, Math.min(1, x));
        return x * x * (3 - 2 * x);
    }

    /**
     * En el cuadro del cambio: arranca con la corrección guardada de la vez anterior
     * (casi no cambia) y mide la de ahora aparte; cuando llega, la ajusta.
     */
    private void empezarTransicion(String fisNueva, float dig, long ahora) {
        int sentido = "0".equals(fisNueva) ? 0 : 1;
        float[] g = calib[sentido];
        final boolean habiaGuardada = g != null;
        if (g != null) System.arraycopy(g, 0, corr, 0, 6);
        else { corr[0] = 1; corr[1] = corr[2] = 0; corr[3] = corr[4] = corr[5] = 1; }
        t0Trans = ahora;
        // el último cuadro mostrado está en tex[1 − actual]: queda congelado como "anterior"
        // mientras dura el fundido (en ese tiempo no se intercambian los buffers)

        // medir: el viejo (último mostrado) contra el nuevo sin corrección
        final byte[] viejo = ultimoChico.clone();
        dibujarCamara(fboChico, Registro.ANCHO, Registro.ALTO, dig, 1, 0, 0, 1, 1, 1, 0, tex[1 - actual]);
        GLES20.glReadPixels(0, 0, Registro.ANCHO, Registro.ALTO, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixChico.position(0));
        final byte[] nuevo = new byte[pixChico.capacity()];
        pixChico.position(0);
        pixChico.get(nuevo);
        final long t0 = t0Trans;
        medidor.execute(() -> {
            Registro r = new Registro();
            long a = SystemClock.uptimeMillis();
            r.medir(viejo, nuevo);
            long ms = SystemClock.uptimeMillis() - a;
            float[] m = {r.escala, r.dx, r.dy, r.gr, r.gg, r.gb};
            boolean confiable = r.calidad > 0.15f && calibracionValida(m);
            if (aviso != null) aviso.decir(String.format(java.util.Locale.ROOT,
                    "cambio %s: escala %.3f desp %.3f,%.3f color %.2f/%.2f/%.2f calidad %.2f (%d ms)%s",
                    fisNueva, m[0], m[1], m[2], m[3], m[4], m[5], r.calidad, ms, confiable ? "" : " DESCARTADA"));
            if (!confiable) return;
            h.post(() -> {
                calib[sentido] = m;
                // si ya había una guardada, la de ahora queda para la próxima (cambiarla en el medio
                // se ve como un salto); si no había, se aplica igual, en la misma transición
                if (!habiaGuardada && t0Trans == t0) System.arraycopy(m, 0, corr, 0, 6);
            });
        });
    }

    private void dibujarCamara(int destino, int w, int hh, float dig, float esc, float dx, float dy,
                               float gr, float gg, float gb, float mez, int texAnt) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, destino);
        GLES20.glViewport(0, 0, w, hh);
        GLES20.glUseProgram(pCam);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texCam);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(pCam, "sCam"), 0);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texAnt);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(pCam, "sAnt"), 1);
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(pCam, "uTex"), 1, false, camM, 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pCam, "uDig"), dig);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pCam, "uEsc"), esc);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(pCam, "uDesp"), dx, dy);
        GLES20.glUniform3f(GLES20.glGetUniformLocation(pCam, "uGan"), gr, gg, gb);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pCam, "uMez"), mez);
        dibujarCuad();
    }

    private void copiar(int texOrigen, int destino, int w, int hh, boolean rotar) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, destino);
        GLES20.glViewport(0, 0, w, hh);
        GLES20.glUseProgram(pCopia);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texOrigen);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(pCopia, "sTex"), 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pCopia, "uRot"), rotar ? 1 : 0);
        dibujarCuad();
    }

    private void dibujarCuad() {
        GLES20.glEnableVertexAttribArray(0);
        GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 0, cuad);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    /**
     * La matriz de la SurfaceTexture puede traer el giro del sensor; para el FBO apaisado
     * queremos la imagen como la ve el sensor. Probamos los 4 giros alrededor del centro y nos
     * quedamos con el que deja la matriz como una sin giro (x→x, y invertida como siempre).
     */
    private static void sinGiro(float[] m, float[] out) {
        float mejor = -9;
        float[] r = new float[16], t = new float[16];
        for (int k = 0; k < 4; k++) {
            double a = Math.PI / 2 * k;
            float c = (float) Math.round(Math.cos(a)), s = (float) Math.round(Math.sin(a));
            // R: rotar alrededor de (0.5, 0.5): p' = c·(p − ½) − s·… + ½   (columna mayor)
            java.util.Arrays.fill(r, 0);
            r[0] = c; r[1] = s; r[4] = -s; r[5] = c; r[10] = 1; r[15] = 1;
            r[12] = 0.5f - 0.5f * c + 0.5f * s;
            r[13] = 0.5f - 0.5f * s - 0.5f * c;
            android.opengl.Matrix.multiplyMM(t, 0, m, 0, r, 0);
            // sin giro y con la inversión vertical normal de la SurfaceTexture: x→+x, y→−y
            float p = t[0] - t[5] - Math.abs(t[1]) - Math.abs(t[4]);
            if (p > mejor) { mejor = p; System.arraycopy(t, 0, out, 0, 16); }
        }
    }
}
