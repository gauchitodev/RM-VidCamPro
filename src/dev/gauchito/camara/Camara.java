package dev.gauchito.camara;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.GradientDrawable;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.media.MediaCodec;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Range;
import android.util.Size;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Cámara propia (solo video): graba con la cámara lógica 4 (gran angular + principal)
 * y cambia de lente con el zoom sin cortar el video.
 *
 * Estabilización como la app de fábrica (NormalVideoStreamController de NubiaCamera):
 * la sesión se arma con un modo de operación de ZTE/Qualcomm que reserva el margen
 * para estabilizar, y adentro se pide CONTROL_VIDEO_STABILIZATION_MODE = 1.
 * En una sesión común, pedir la estabilización tira abajo el servicio de cámara.
 * Con estabilización, cada pedido va también a la superficie del grabador aunque no grabe.
 *
 * La superficie del grabador es persistente y está en la sesión desde el principio:
 * empezar a grabar no rearma nada (no se congela la vista previa).
 */
public class Camara extends Activity {
    static String CAMARA = "4";                  // lógica: físicas 2 (gran angular) y 0 (principal)
    static final float ZOOM_GRAN_ANGULAR = 0.67f; // el HAL entrega la gran angular siempre entera
    static final float UMBRAL_PRINCIPAL = 1.06f;  // desde acá la principal (el HAL cambia en ~1,052)
    // "1" es la principal (1,06): debajo de eso, la gran angular con zoom digital nuestro
    static final float[] LENTES = {0.67f, UMBRAL_PRINCIPAL, 2f, 5f};
    static final String[] NOMBRES = {"0,6", "1", "2", "5"};

    /** Modos de grabación: ancho, alto, fps, bitrate H.264. */
    static final int[][] MODOS = {{3840, 2160, 30, 50_000_000}, {1920, 1080, 60, 30_000_000}, {1920, 1080, 30, 18_000_000}};
    static final String[] NOMBRES_MODO = {"4K 30", "1080 60", "1080 30"};

    /** Estabilización: apagada, normal, súper (modos de operación de NubiaCamera). */
    static final String[] NOMBRES_ESTAB = {"Estab. no", "Estab.", "Estab. +"};
    /** El teléfono para el que están medidos los modos de sesión y el reparto del zoom. */
    static final boolean ES_RM10PRO = "NX789J".equals(android.os.Build.MODEL) || "NX789J".equals(android.os.Build.DEVICE);
    private boolean modosFabrica, avisoModelo;

    static int modoOperacion(int estab, int fps) {
        if (estab == 0) return fps == 60 ? 32772 : 0;
        if (fps == 60) return estab == 2 ? 32801 : 32800;
        return estab == 2 ? 32799 : 32798;
    }

    private final Ajustes pro = new Ajustes();
    private SharedPreferences prefs;

    // pantalla
    private FrameLayout raiz;
    private TextureView vista;
    private Piezas.Cuadricula cuadricula;
    private Piezas.Aro aro;
    private final TextView[] botonesLente = new TextView[LENTES.length];
    private TextView botonModo, botonEstab, botonPro, reloj, aviso;
    private View botonGrabar;
    private GradientDrawable fondoGrabar;
    private ImageView miniatura;
    private LinearLayout panelPro;
    private final TextView[] chips = new TextView[Ajustes.NOMBRES.length];
    private TextView valorPro;
    private SeekBar barraPro;
    private int ajusteElegido = Ajustes.ISO;
    private final List<View> giratorias = new ArrayList<>();

    // cámara
    private Handler fondo;
    private CameraDevice camara;
    private CameraCaptureSession sesion;
    private CaptureRequest.Builder pedido;
    private Surface superficieVista;
    private Motor motor;
    private Surface entradaMotor;
    private Surface superficieGrabador;
    private android.media.ImageReader lectorVideo;
    private int orientacionSensor = 90;
    private Rect arraySensor = new Rect(0, 0, 4000, 3000);
    private Range<Float> rangoZoom = new Range<>(0.67f, 10f);

    private float zoom = UMBRAL_PRINCIPAL;        // arranca en la principal
    private float zoomDestino = UMBRAL_PRINCIPAL;
    private int modo = 0;
    private int estab = 1;
    private int giro = 0;                        // cómo está el teléfono: 0, 90, 180, 270

    // grabación
    private MediaRecorder grabador;
    private Uri video, ultimoVideo;
    private ParcelFileDescriptor archivo;
    private boolean grabando;
    private long inicio;

    // prueba del cambio de lente: --ez medir true graba un zoom lento 0,67→1,3 y anota cada cuadro
    private boolean medir;
    private final StringBuilder medicion = new StringBuilder();

    private void pruebaCambio() {
        // primero la principal (para que enfoque y quede su foco), después la gran angular
        ponerZoom(1.3f, false);
        raiz.postDelayed(() -> ponerZoom(0.67f, false), 2500);
        raiz.postDelayed(() -> {
            empezarGrabacion();
            raiz.postDelayed(() -> {
                final long t0 = SystemClock.uptimeMillis();
                fondo.post(new Runnable() {
                    public void run() {
                        float p = Math.min(1f, (SystemClock.uptimeMillis() - t0) / 6000f);
                        zoom = (float) Math.exp(Math.log(0.67) + (Math.log(1.3) - Math.log(0.67)) * p);
                        zoomAlPedido(zoom);
                        repetir();
                        if (p < 1f) fondo.postDelayed(this, 33);
                        else raiz.postDelayed(() -> {
                            pararGrabacion();
                            fondo.post(() -> {
                                medir = false;
                                try (FileWriter w = new FileWriter(new File(getExternalFilesDir(null), "medicion.txt"))) {
                                    w.write(medicion.toString());
                                } catch (Exception e) { registrar("medición: " + e); }
                            });
                        }, 1000);
                    }
                });
            }, 1500);
        }, 4500);
    }

    // ---------- lo que el motor necesita saber de cada cuadro ----------

    private static final int ANILLO = 32;
    private final long[] anilloTs = new long[ANILLO];
    private final Motor.Cuadro[] anilloInfo = new Motor.Cuadro[ANILLO];
    private int anilloPos;
    private Motor.Cuadro ultimaInfo;
    private float focoPrincipal = -1;
    private boolean soltarFocoProgramado;
    private String ultimoAf = "";
    private boolean focoPrincipalSeguro;

    private void anotarCuadro(CaptureRequest r, TotalCaptureResult t) {
        Long ts = t.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP);
        String fis = t.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
        Float zh = t.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO);
        Object tag = r.getTag();
        float zp = tag instanceof Float ? (Float) tag : (zh == null ? 1f : zh);
        Motor.Cuadro c = new Motor.Cuadro(fis, zp, zh == null ? zp : zh);
        synchronized (anilloTs) {
            anilloTs[anilloPos] = ts == null ? 0 : ts;
            anilloInfo[anilloPos] = c;
            anilloPos = (anilloPos + 1) % ANILLO;
            ultimaInfo = c;
            anilloTs.notifyAll();
        }
        // foco previo: la principal arranca con su último foco en vez de buscarlo
        Integer af = t.get(android.hardware.camera2.CaptureResult.CONTROL_AF_STATE);
        String clave = fis + "/" + af;
        if (!clave.equals(ultimoAf)) { ultimoAf = clave; if (medir) registrar("af " + clave + " foco=" + pro.focoVivo + " zp=" + zp); }
        if ("0".equals(fis)) {
            // el último foco de la principal (enfocado si se pudo; si sigue buscando, igual el último)
            if (pro.focoForzado == null && (af == null || af != 3)) {
                if (af != null && (af == 2 || af == 4)) { focoPrincipal = pro.focoVivo; focoPrincipalSeguro = true; }
                else if (!focoPrincipalSeguro) focoPrincipal = pro.focoVivo;
            }
            if (af != null && af == 1 && focoPrincipalSeguro && pro.focoForzado == null) focoPrincipalSeguro = false;
            if (pro.focoForzado != null && !soltarFocoProgramado) {
                soltarFocoProgramado = true;
                fondo.postDelayed(() -> { pro.focoForzado = null; soltarFocoProgramado = false; aplicarPro(); }, 400);
            }
        } else if ("2".equals(fis)) {
            boolean auto = pro.idx[Ajustes.ENFOQUE] == 0 && !pro.bloqueado;
            if (zp >= 0.95f && auto && focoPrincipal >= 0 && pro.focoForzado == null) {   // 0 = infinito
                pro.focoForzado = focoPrincipal; aplicarPro();
                registrar("foco previo " + focoPrincipal);
            } else if (zp < 0.9f && pro.focoForzado != null && !soltarFocoProgramado) {
                pro.focoForzado = null; aplicarPro();
            }
        }
    }

    /** Para el motor: la info del cuadro con esa marca de tiempo; espera hasta 40 ms a que llegue. */
    private Motor.Cuadro infoCuadro(long ts) {
        long fin = SystemClock.uptimeMillis() + 40;
        synchronized (anilloTs) {
            while (true) {
                Motor.Cuadro cerca = null; long mejor = 4_000_000;   // ±4 ms
                for (int i = 0; i < ANILLO; i++) {
                    long d = Math.abs(anilloTs[i] - ts);
                    if (anilloInfo[i] != null && d < mejor) { mejor = d; cerca = anilloInfo[i]; }
                }
                if (cerca != null) return cerca;
                long falta = fin - SystemClock.uptimeMillis();
                if (falta <= 0) { if (!avisoMarcas) { avisoMarcas = true; registrar("sin resultado para ts=" + ts); } return ultimaInfo; }
                try { anilloTs.wait(falta); } catch (InterruptedException e) { return ultimaInfo; }
            }
        }
    }
    private boolean avisoMarcas;

    /** El zoom pedido se reparte: debajo de 1,06 el HAL queda en la gran angular entera y el motor hace el zoom. */
    private void zoomAlPedido(float z) {
        pedido.set(CaptureRequest.CONTROL_ZOOM_RATIO, z >= UMBRAL_PRINCIPAL ? z : ZOOM_GRAN_ANGULAR);
        pedido.setTag(z);
    }

    private final CameraCaptureSession.CaptureCallback alTerminarCuadro = new CameraCaptureSession.CaptureCallback() {
        @Override public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult t) {
            pro.leerResultado(t);
            anotarCuadro(r, t);
            if (medir) {
                String fis = t.get(android.hardware.camera2.CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
                Float z = t.get(android.hardware.camera2.CaptureResult.CONTROL_ZOOM_RATIO);
                Long ts = t.get(android.hardware.camera2.CaptureResult.SENSOR_TIMESTAMP);
                medicion.append(ts).append(' ').append(z).append(' ').append(fis).append(' ')
                        .append(pro.isoVivo).append(' ').append(pro.expVivo).append(' ').append(pro.focoVivo).append('\n');
            }
        }
    };

    // ---------- ciclo de vida ----------

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("camara", MODE_PRIVATE);
        modo = limitar(prefs.getInt("modo", 0), 0, MODOS.length - 1);
        estab = limitar(prefs.getInt("estab", 1), 0, NOMBRES_ESTAB.length - 1);
        pro.cargar(prefs);
        leerDatosDePrueba(getIntent());
        pro.fps = MODOS[modo][2];

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        motor = new Motor(this::infoCuadro, s -> registrar(s));
        motor.calibracion(leerCalib("calib0"), leerCalib("calib1"));
        armarPantalla();
        HandlerThread h = new HandlerThread("camara");
        h.start();
        fondo = new Handler(h.getLooper());
        new OrientationEventListener(this) {
            public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                int g = ((o + 45) / 90 * 90) % 360;
                if (g != giro) { giro = g; girarBotones(); }
            }
        }.enable();
    }

    static int limitar(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    /**
     * Ganchos de prueba por ADB (modo, estab, medir). Esta pantalla está abierta a otras apps
     * (la abre el lanzador), así que solo valen si existe el archivo "pruebas" en la carpeta
     * propia de la app: desde Android 11 ninguna otra app puede escribir ahí; ADB sí.
     * Sin esa llave se ignoran: si no, cualquier app podría abrirnos con medir=true y
     * hacernos grabar con cámara y micrófono, o mandar valores que nos cierren.
     */
    private void leerDatosDePrueba(Intent in) {
        try {
            if (in == null || in.getExtras() == null) return;
            if (!in.hasExtra("modo") && !in.hasExtra("estab") && !in.hasExtra("medir")) return;
            if (!pruebasHabilitadas()) { registrar("ignoré datos de prueba: falta la llave de pruebas"); return; }
            if (in.hasExtra("modo")) modo = limitar(in.getIntExtra("modo", modo), 0, MODOS.length - 1);
            if (in.hasExtra("estab")) estab = limitar(in.getIntExtra("estab", estab), 0, NOMBRES_ESTAB.length - 1);
            medir = in.getBooleanExtra("medir", false);
            registrar("pruebas por ADB: modo=" + modo + " estab=" + estab + " medir=" + medir);
        } catch (RuntimeException e) {
            // datos armados para romper (p. ej. un Parcelable que no se puede leer): se ignoran
            registrar("datos de prueba ilegibles: " + e.getClass().getSimpleName());
        }
    }

    /** La llave de pruebas: un archivo que solo ADB (o la propia app) puede crear. */
    private boolean pruebasHabilitadas() {
        File d = getExternalFilesDir(null);
        return d != null && new File(d, "pruebas").isFile();
    }

    @Override
    protected void onResume() {
        super.onResume();
        getWindow().getInsetsController().hide(WindowInsets.Type.systemBars());
        getWindow().getInsetsController().setSystemBarsBehavior(
                WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        List<String> faltan = new ArrayList<>();
        for (String p : new String[]{android.Manifest.permission.CAMERA, android.Manifest.permission.RECORD_AUDIO})
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) faltan.add(p);
        if (!faltan.isEmpty()) { requestPermissions(faltan.toArray(new String[0]), 1); return; }
        if (vista.isAvailable()) fondo.post(this::abrir);
        fondo.post(this::limpiarPendientes);
        fondo.post(this::buscarUltimoVideo);
        refrescarChips();
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        for (int x : r) if (x != PackageManager.PERMISSION_GRANTED) { decir("Sin permiso de cámara y micrófono no puedo grabar"); return; }
        if (vista.isAvailable()) fondo.post(this::abrir);
    }

    @Override
    protected void onPause() {
        fondo.post(() -> { if (grabador != null) terminarGrabacion(); cerrar(); });
        grabando = false;
        mostrarParado();
        guardarPrefs();
        super.onPause();
    }

    /** Calibración guardada del cambio de cámara; si está rota o es disparatada, se ignora. */
    private float[] leerCalib(String k) {
        try {
            String v = prefs.getString(k, null);
            if (v == null) return null;
            String[] p = v.split(",");
            if (p.length != 6) return null;
            float[] f = new float[6];
            for (int i = 0; i < 6; i++) f[i] = Float.parseFloat(p[i]);
            return Motor.calibracionValida(f) ? f : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Override
    protected void onDestroy() {
        motor.soltar();
        super.onDestroy();
    }

    private void guardarPrefs() {
        SharedPreferences.Editor e = prefs.edit();
        float[][] c = motor.calibracion();
        for (int k = 0; k < 2; k++) if (c[k] != null) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 6; i++) b.append(i > 0 ? "," : "").append(c[k][i]);
            e.putString("calib" + k, b.toString());
        }
        e.putInt("modo", modo).putInt("estab", estab);
        pro.guardar(e);
        e.apply();
    }

    // ---------- pantalla ----------

    private void armarPantalla() {
        raiz = new FrameLayout(this);
        raiz.setClipChildren(false);
        raiz.setBackgroundColor(Color.BLACK);

        vista = new TextureView(this);
        vista.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { motor.vista(s, w, h); fondo.post(Camara.this::abrir); }
            public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) { motor.vista(s, w, h); }
            public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { motor.vista(null, 0, 0); return true; }
            public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });
        raiz.addView(vista, new FrameLayout.LayoutParams(-1, -1));
        cuadricula = new Piezas.Cuadricula(this);
        cuadricula.setVisibility(pro.idx[Ajustes.CUADRICULA] == 1 ? View.VISIBLE : View.GONE);
        raiz.addView(cuadricula, new FrameLayout.LayoutParams(-1, -1));
        aro = new Piezas.Aro(this);
        aro.setAlpha(0f);
        raiz.addView(aro, new FrameLayout.LayoutParams(dp(72), dp(72)));

        // 16:9 en vertical: ancho completo, alto = ancho * 16 / 9, centrado (la cuadrícula encima)
        raiz.addOnLayoutChangeListener((v, l, t, r, bt, ol, ot, or, ob) -> {
            int w = r - l, h = w * 16 / 9;
            for (View x : new View[]{vista, cuadricula}) {
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) x.getLayoutParams();
                if (lp.height != h) {
                    lp.width = w; lp.height = h; lp.gravity = Gravity.CENTER;
                    x.setLayoutParams(lp);
                }
            }
        });

        // gestos sobre la imagen: pellizco = zoom, toque = enfocar ahí, mantener = bloquear
        ScaleGestureDetector pellizco = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            public boolean onScale(ScaleGestureDetector d) {
                ponerZoom(zoomDestino * d.getScaleFactor(), false);
                return true;
            }
        });
        GestureDetector toques = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            public boolean onDown(MotionEvent e) { return true; }
            public boolean onSingleTapUp(MotionEvent e) { tocarImagen(e.getX(), e.getY(), false); return true; }
            public void onLongPress(MotionEvent e) { if (!pellizco.isInProgress()) tocarImagen(e.getX(), e.getY(), true); }
        });
        vista.setOnTouchListener((v, e) -> {
            pellizco.onTouchEvent(e);
            if (e.getPointerCount() == 1 && !pellizco.isInProgress()) toques.onTouchEvent(e);
            return true;
        });

        // arriba: estabilización, PRO, calidad; el reloj debajo
        LinearLayout arriba = new LinearLayout(this);
        arriba.setGravity(Gravity.CENTER_VERTICAL);
        botonEstab = boton(NOMBRES_ESTAB[estab], v -> cambiarEstab());
        botonPro = boton("PRO", v -> alternarPro());
        botonModo = boton(NOMBRES_MODO[modo], v -> cambiarModo());
        arriba.addView(botonEstab);
        View hueco1 = new View(this), hueco2 = new View(this);
        arriba.addView(hueco1, new LinearLayout.LayoutParams(0, 1, 1));
        arriba.addView(botonPro);
        arriba.addView(hueco2, new LinearLayout.LayoutParams(0, 1, 1));
        arriba.addView(botonModo);
        FrameLayout.LayoutParams la = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        la.topMargin = dp(32); la.leftMargin = la.rightMargin = dp(16);
        arriba.setClipChildren(false);           // los botones girados (teléfono acostado) no se cortan
        raiz.addView(arriba, la);

        reloj = texto("", 18);
        reloj.setVisibility(View.GONE);
        GradientDrawable fr = new GradientDrawable();
        fr.setColor(0xCCD32F2F); fr.setCornerRadius(dp(6));
        reloj.setBackground(fr);
        reloj.setPadding(dp(10), dp(4), dp(10), dp(4));
        FrameLayout.LayoutParams lr = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lr.topMargin = dp(88);
        raiz.addView(reloj, lr);

        aviso = texto("", 14);
        aviso.setPadding(dp(12), dp(6), dp(12), dp(6));
        aviso.setBackground(pildora(0x99000000));
        aviso.setVisibility(View.GONE);
        FrameLayout.LayoutParams lav = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lav.topMargin = dp(132);
        raiz.addView(aviso, lav);

        // abajo: panel PRO (si está abierto), lentes, y la fila de galería + grabar
        LinearLayout abajo = new LinearLayout(this);
        abajo.setOrientation(LinearLayout.VERTICAL);
        abajo.setGravity(Gravity.CENTER_HORIZONTAL);

        armarPanelPro();
        LinearLayout.LayoutParams lpp = new LinearLayout.LayoutParams(-1, -2);
        lpp.leftMargin = lpp.rightMargin = dp(10); lpp.bottomMargin = dp(14);
        abajo.addView(panelPro, lpp);

        LinearLayout lentes = new LinearLayout(this);
        lentes.setPadding(dp(6), dp(6), dp(6), dp(6));
        lentes.setBackground(pildora(0x66000000));
        for (int i = 0; i < LENTES.length; i++) {
            final int n = i;
            TextView t = texto(NOMBRES[i], 15);
            t.setOnClickListener(v -> ponerZoom(LENTES[n], true));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(52), dp(52));
            lp.leftMargin = lp.rightMargin = dp(4);
            lentes.addView(t, lp);
            botonesLente[i] = t;
            giratorias.add(t);
        }
        abajo.addView(lentes, new LinearLayout.LayoutParams(-2, -2));

        LinearLayout fila = new LinearLayout(this);
        fila.setGravity(Gravity.CENTER_VERTICAL);

        miniatura = new ImageView(this);
        miniatura.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable fm = new GradientDrawable();
        fm.setShape(GradientDrawable.OVAL);
        fm.setColor(0x33FFFFFF);
        fm.setStroke(dp(2), 0xCCFFFFFF);
        miniatura.setBackground(fm);
        miniatura.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        miniatura.setClipToOutline(true);
        miniatura.setContentDescription("Galería");
        miniatura.setOnClickListener(v -> abrirGaleria());
        giratorias.add(miniatura);
        FrameLayout cajaMini = new FrameLayout(this);
        cajaMini.addView(miniatura, new FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER));
        fila.addView(cajaMini, new LinearLayout.LayoutParams(0, dp(80), 1));

        FrameLayout aroGrabar = new FrameLayout(this);
        GradientDrawable fa = new GradientDrawable();
        fa.setShape(GradientDrawable.OVAL);
        fa.setStroke(dp(5), Color.WHITE);
        aroGrabar.setBackground(fa);
        botonGrabar = new View(this);
        fondoGrabar = new GradientDrawable();
        fondoGrabar.setColor(0xFFE53935);
        fondoGrabar.setCornerRadius(dp(30));
        botonGrabar.setBackground(fondoGrabar);
        aroGrabar.addView(botonGrabar, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER));
        aroGrabar.setOnClickListener(v -> { if (grabando) pararGrabacion(); else empezarGrabacion(); });
        aroGrabar.setContentDescription("Grabar");
        fila.addView(aroGrabar, new LinearLayout.LayoutParams(dp(80), dp(80)));

        fila.addView(new View(this), new LinearLayout.LayoutParams(0, dp(80), 1));
        LinearLayout.LayoutParams lf = new LinearLayout.LayoutParams(-1, -2);
        lf.topMargin = dp(20);
        abajo.addView(fila, lf);

        FrameLayout.LayoutParams lb = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        lb.bottomMargin = dp(40);
        raiz.addView(abajo, lb);

        setContentView(raiz);
        marcarLente();
        giratorias.add(botonEstab); giratorias.add(botonModo); giratorias.add(botonPro);
    }

    private void armarPanelPro() {
        panelPro = new LinearLayout(this);
        panelPro.setOrientation(LinearLayout.VERTICAL);
        panelPro.setPadding(dp(8), dp(8), dp(8), dp(10));
        GradientDrawable f = new GradientDrawable();
        f.setColor(0xB3000000); f.setCornerRadius(dp(18));
        panelPro.setBackground(f);
        panelPro.setVisibility(prefs.getBoolean("proAbierto", false) ? View.VISIBLE : View.GONE);

        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout filaChips = new LinearLayout(this);
        for (int i = 0; i < chips.length; i++) {
            final int a = i;
            TextView t = texto("", 14);
            t.setPadding(dp(12), dp(8), dp(12), dp(8));
            t.setOnClickListener(v -> elegirAjuste(a));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.rightMargin = dp(6);
            filaChips.addView(t, lp);
            chips[i] = t;
        }
        hs.addView(filaChips);
        panelPro.addView(hs);

        LinearLayout filaBarra = new LinearLayout(this);
        filaBarra.setGravity(Gravity.CENTER_VERTICAL);
        valorPro = texto("", 15);
        valorPro.setTextColor(0xFFFFD54F);
        valorPro.setMinWidth(dp(96));
        filaBarra.addView(valorPro);
        barraPro = new SeekBar(this);
        barraPro.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean usuario) { if (usuario) moverAjuste(p); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        filaBarra.addView(barraPro, new LinearLayout.LayoutParams(0, dp(44), 1));
        TextView auto = texto("Auto", 14);
        auto.setPadding(dp(12), dp(8), dp(12), dp(8));
        auto.setBackground(pildora(0x33FFFFFF));
        auto.setOnClickListener(v -> { barraPro.setProgress(valorAuto(ajusteElegido)); moverAjuste(valorAuto(ajusteElegido)); });
        filaBarra.addView(auto);
        LinearLayout.LayoutParams lb = new LinearLayout.LayoutParams(-1, -2);
        lb.topMargin = dp(6);
        panelPro.addView(filaBarra, lb);
        elegirAjuste(ajusteElegido);
    }

    private static int valorAuto(int a) { return a == Ajustes.EV ? 6 : 0; }

    private TextView texto(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.WHITE);
        t.setGravity(Gravity.CENTER);
        return t;
    }

    private TextView boton(String s, View.OnClickListener l) {
        TextView t = texto(s, 15);
        t.setPadding(dp(14), dp(8), dp(14), dp(8));
        t.setBackground(pildora(0x66000000));
        t.setOnClickListener(l);
        return t;
    }

    private GradientDrawable pildora(int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(40));
        return g;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void girarBotones() {
        float r = giro == 90 ? -90 : giro == 270 ? 90 : 0;
        runOnUiThread(() -> { for (View v : giratorias) v.animate().rotation(r).setDuration(200).start(); });
    }

    private void marcarLente() {
        int cerca = 0;
        for (int i = 0; i < LENTES.length; i++)
            if (Math.abs(LENTES[i] - zoomDestino) < Math.abs(LENTES[cerca] - zoomDestino)) cerca = i;
        for (int i = 0; i < LENTES.length; i++) {
            TextView t = botonesLente[i];
            boolean este = i == cerca;
            String n = este && Math.abs(LENTES[i] - zoomDestino) > 0.07f
                    ? String.format(Locale.ROOT, "%.1f", zoomDestino).replace('.', ',') : NOMBRES[i];
            t.setText(este ? n + "×" : NOMBRES[i]);
            t.setTextColor(este ? 0xFFFFD54F : Color.WHITE);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(este ? 0x99000000 : 0x00000000);
            t.setBackground(g);
        }
    }

    private void decir(String s) {
        runOnUiThread(() -> {
            aviso.setText(s);
            aviso.setVisibility(View.VISIBLE);
            aviso.removeCallbacks(ocultarAviso);
            aviso.postDelayed(ocultarAviso, 2500);
        });
        registrar(s);
    }

    private final Runnable ocultarAviso = () -> aviso.setVisibility(View.GONE);

    /** Registro de diagnóstico en la carpeta propia de la app; al pasar 256 KB se rota (queda uno anterior). */
    private void registrar(String s) {
        try {
            File f = new File(getExternalFilesDir(null), "registro.txt");
            if (f.length() > 256 * 1024) f.renameTo(new File(f.getParentFile(), "registro.anterior.txt"));
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(new SimpleDateFormat("dd/MM HH:mm:ss.SSS", Locale.ROOT).format(new Date()) + " " + s + "\n");
            }
        } catch (Exception ignorado) {}
    }

    // ---------- PRO ----------

    private void alternarPro() {
        boolean abrir = panelPro.getVisibility() != View.VISIBLE;
        panelPro.setVisibility(abrir ? View.VISIBLE : View.GONE);
        botonPro.setTextColor(abrir ? 0xFFFFD54F : Color.WHITE);
        prefs.edit().putBoolean("proAbierto", abrir).apply();
        refrescarChips();
    }

    private void elegirAjuste(int a) {
        ajusteElegido = a;
        barraPro.setMax(pro.opciones(a).length - 1);
        barraPro.setProgress(pro.idx[a]);
        refrescarChips();
    }

    private void moverAjuste(int i) {
        int a = ajusteElegido;
        if (a == Ajustes.OBTURADOR && !pro.obturadorValido(i)) {
            i = 2; while (!pro.obturadorValido(i)) i++;      // a 60 fps no hay 1/30
            barraPro.setProgress(i);
        }
        if (grabando && a == Ajustes.CODEC) { barraPro.setProgress(pro.idx[a]); decir("Pará la grabación para cambiar el códec"); return; }
        if (pro.idx[a] == i) return;
        pro.idx[a] = i;
        if (a == Ajustes.CUADRICULA) cuadricula.setVisibility(i == 1 ? View.VISIBLE : View.GONE);
        if (a == Ajustes.CODEC) fondo.post(this::armarSesion);
        else fondo.post(this::aplicarPro);
        refrescarChips();
        guardarPrefs();
    }

    /** Actualiza el texto de los chips; mientras el panel está abierto, cada medio segundo (valores en vivo). */
    private final Runnable refresco = this::refrescarChips;

    private void refrescarChips() {
        for (int i = 0; i < chips.length; i++) {
            TextView t = chips[i];
            t.setText(pro.etiqueta(i));
            boolean el = i == ajusteElegido;
            t.setTextColor(el ? 0xFF000000 : Color.WHITE);
            t.setBackground(pildora(el ? 0xFFFFD54F : 0x33FFFFFF));
        }
        valorPro.setText(Ajustes.NOMBRES[ajusteElegido] + "\n" + pro.opciones(ajusteElegido)[pro.idx[ajusteElegido]]);
        botonPro.setTextColor(panelPro.getVisibility() == View.VISIBLE ? 0xFFFFD54F : Color.WHITE);
        raiz.removeCallbacks(refresco);
        if (panelPro.getVisibility() == View.VISIBLE) raiz.postDelayed(refresco, 500);
    }

    private void aplicarPro() {
        if (pedido == null) return;
        pro.aplicar(pedido);
        repetir();
    }

    /** Toque en la imagen: enfocar y medir la luz ahí; mantener apretado: además bloquear. */
    private void tocarImagen(float x, float y, boolean bloquear) {
        float nx = x / vista.getWidth(), ny = y / vista.getHeight();
        // la imagen está girada 90° respecto del sensor (vertical): x de pantalla = y del sensor
        int W = arraySensor.width(), H = arraySensor.height();
        int cx = (int) (ny * W), cy = (int) ((1 - nx) * H);
        int m = W / 12;
        int l = Math.max(0, cx - m), t = Math.max(0, cy - m);
        int r = Math.min(W - 1, cx + m), b = Math.min(H - 1, cy + m);
        MeteringRectangle rect = new MeteringRectangle(l, t, r - l, b - t, MeteringRectangle.METERING_WEIGHT_MAX);

        aro.bloqueado = false;
        aro.invalidate();
        aro.setX(vista.getX() + x - aro.getWidth() / 2f);
        aro.setY(vista.getY() + y - aro.getHeight() / 2f);
        aro.animate().cancel();
        aro.setScaleX(1.3f); aro.setScaleY(1.3f); aro.setAlpha(1f);
        aro.animate().scaleX(1f).scaleY(1f).setDuration(200).start();

        fondo.post(() -> {
            pro.bloqueado = false;
            pro.region = new MeteringRectangle[]{rect};
            aplicarPro();
            if (bloquear) {
                // dejar que enfoque y mida ~0,8 s, y después fijar todo
                fondo.postDelayed(() -> {
                    pro.bloqueado = true;
                    aplicarPro();
                    runOnUiThread(() -> { aro.bloqueado = true; aro.invalidate(); refrescarChips(); });
                    decir("Enfoque y exposición bloqueados · tocá para soltar");
                }, 800);
            }
        });
        if (!bloquear) aro.animate().alpha(0f).setStartDelay(1200).setDuration(400).start();
        refrescarChips();
    }

    // ---------- galería ----------

    /**
     * Si la app se cerró en medio de una grabación, el video quedó "pendiente": invisible,
     * sin terminar (no se puede reproducir) y en 4K puede pesar varios GB hasta que Android
     * lo borre a la semana. Se borran los nuestros que hayan quedado así. Solo vemos y
     * podemos borrar los pendientes propios, nunca los de otras apps.
     */
    private void limpiarPendientes() {
        if (grabador != null) return;                    // grabando: el pendiente es el actual
        Uri base = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Bundle q = new Bundle();
        q.putString(ContentResolver.QUERY_ARG_SQL_SELECTION,
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Video.Media.IS_PENDING + " = 1");
        q.putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, new String[]{"Movies/Camara%"});
        q.putInt(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE);
        int n = 0;
        try (Cursor c = getContentResolver().query(base, new String[]{MediaStore.Video.Media._ID}, q, null)) {
            while (c != null && c.moveToNext()) {
                Uri u = ContentUris.withAppendedId(base, c.getLong(0));
                if (u.equals(video)) continue;
                n += getContentResolver().delete(u, null, null);
            }
        } catch (Exception e) { registrar("limpiar pendientes: " + e); }
        if (n > 0) registrar("borré " + n + " video(s) sin terminar de una grabación cortada");
    }

    private void buscarUltimoVideo() {
        Uri base = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        try (Cursor c = getContentResolver().query(base, new String[]{MediaStore.Video.Media._ID},
                MediaStore.Video.Media.RELATIVE_PATH + " LIKE ? AND " + MediaStore.Video.Media.IS_PENDING + " = 0",
                new String[]{"Movies/Camara%"}, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (c != null && c.moveToFirst()) ponerMiniatura(ContentUris.withAppendedId(base, c.getLong(0)));
        } catch (Exception e) { registrar("galería: " + e); }
    }

    private void ponerMiniatura(Uri u) {
        ultimoVideo = u;
        try {
            Bitmap bm = getContentResolver().loadThumbnail(u, new Size(200, 200), null);
            runOnUiThread(() -> miniatura.setImageBitmap(bm));
        } catch (Exception e) { registrar("miniatura: " + e); }
    }

    private void abrirGaleria() {
        if (ultimoVideo == null) { decir("Todavía no hay videos"); return; }
        Intent i = new Intent(Intent.ACTION_VIEW);
        i.setDataAndType(ultimoVideo, "video/mp4");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(i); } catch (Exception e) { decir("No encontré con qué abrir el video"); }
    }

    // ---------- cámara (todo en el hilo "fondo") ----------

    private void abrir() {
        if (camara != null || !vista.isAvailable()) return;
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        CameraManager cm = getSystemService(CameraManager.class);
        try {
            CameraCharacteristics c = cm.getCameraCharacteristics(CAMARA);
            Integer o = c.get(CameraCharacteristics.SENSOR_ORIENTATION);
            if (o != null) orientacionSensor = o;
            Range<Float> z = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            if (z != null) rangoZoom = z;
            Rect a = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            if (a != null) arraySensor = a;
            Float fm = c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE);
            if (fm != null && fm > 0) pro.focoMin = fm;
            modosFabrica = ES_RM10PRO && c.getPhysicalCameraIds().equals(new java.util.HashSet<>(java.util.Arrays.asList("0", "2")));
            if (!modosFabrica && !avisoModelo) {
                avisoModelo = true;
                decir("Hecha para el RedMagic 10 Pro: acá, sin los modos de fábrica");
                registrar("modelo " + android.os.Build.MODEL + ", físicas " + c.getPhysicalCameraIds() + ": sin modos de fábrica");
            }
            cm.openCamera(CAMARA, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice d) { camara = d; armarSesion(); }
                public void onDisconnected(CameraDevice d) { d.close(); camara = null; }
                public void onError(CameraDevice d, int e) { d.close(); camara = null; decir(errorDeCamara(e)); }
            }, fondo);
        } catch (Exception e) { decir("No pude abrir la cámara: " + e.getMessage()); }
    }

    /** Qué quiere decir cada error de la cámara (al volver a la app, se reabre sola). */
    static String errorDeCamara(int e) {
        switch (e) {
            case CameraDevice.StateCallback.ERROR_CAMERA_IN_USE:
            case CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE:
                return "La cámara la está usando otra app";
            case CameraDevice.StateCallback.ERROR_CAMERA_DISABLED:
                return "Android cortó la cámara (pantalla bloqueada o en segundo plano)";
            default:
                return "La cámara falló (" + e + "): salí y volvé a entrar";
        }
    }

    private void cerrar() {
        try { if (sesion != null) sesion.close(); } catch (Exception ignorado) {}
        sesion = null;
        if (camara != null) camara.close();
        camara = null;
        pedido = null;
    }

    /** Arma un grabador; su superficie la llena el motor. */
    private MediaRecorder nuevoGrabador(ParcelFileDescriptor pfd, int giroGrabar) throws Exception {
        int[] m = MODOS[modo];
        boolean hevc = pro.idx[Ajustes.CODEC] == 1;
        MediaRecorder r = new MediaRecorder(this);
        r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        r.setOutputFile(pfd.getFileDescriptor());
        r.setVideoEncoder(hevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
        r.setVideoSize(m[0], m[1]);
        r.setVideoFrameRate(m[2]);
        r.setVideoEncodingBitRate(hevc ? m[3] * 7 / 10 : m[3]);     // H.265 da lo mismo con menos
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
        r.setAudioSamplingRate(48000);
        r.setAudioChannels(2);
        r.setAudioEncodingBitRate(256_000);
        r.setOrientationHint((orientacionSensor + giroGrabar) % 360);   // trasera
        r.prepare();
        return r;
    }

    /**
     * Sesión con una sola salida: la entrada del motor, marcada como video para que el HAL
     * estabilice sobre ella, en el modo de operación de la app de fábrica.
     */
    private void armarSesion() {
        if (camara == null) return;
        try {
            if (sesion != null) { sesion.close(); sesion = null; }
            int[] m = MODOS[modo];
            entradaMotor = motor.entrada(m[0], m[1]);
            OutputConfiguration oc = new OutputConfiguration(entradaMotor);
            List<OutputConfiguration> salidas = new ArrayList<>();
            salidas.add(oc);
            // con estabilización el HAL quiere una salida "de codificador" además de la vista;
            // se la damos con un lector que tira los cuadros, y el motor usa la otra
            if (lectorVideo != null) { lectorVideo.close(); lectorVideo = null; }
            if (estab > 0 && modosFabrica) {
                lectorVideo = android.media.ImageReader.newInstance(m[0], m[1], android.graphics.ImageFormat.PRIVATE, 4,
                        android.hardware.HardwareBuffer.USAGE_VIDEO_ENCODE);
                lectorVideo.setOnImageAvailableListener(r -> { android.media.Image im = r.acquireLatestImage(); if (im != null) im.close(); }, fondo);
                OutputConfiguration ov = new OutputConfiguration(lectorVideo.getSurface());
                ov.setStreamUseCase(android.hardware.camera2.CameraMetadata.SCALER_AVAILABLE_STREAM_USE_CASES_VIDEO_RECORD);
                salidas.add(ov);
            }

            int fps = m[2];
            pro.fps = fps;
            pedido = camara.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            pedido.addTarget(entradaMotor);
            if (lectorVideo != null) pedido.addTarget(lectorVideo.getSurface());
            pedido.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, new Range<>(fps, fps));
            zoomAlPedido(zoom);
            // en el RedMagic, la estabilización en una sesión común tira abajo el servicio de cámara:
            // ahí solo se pide dentro de los modos de fábrica; en otros teléfonos es lo estándar
            boolean eisSeguro = modosFabrica || !ES_RM10PRO;
            pedido.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, estab > 0 && eisSeguro ? 1 : 0);
            pro.aplicar(pedido);

            // los modos de operación de ZTE no están documentados: solo donde se midieron;
            // en cualquier otro teléfono, una sesión común (la estabilización estándar anda ahí)
            int op = modosFabrica ? modoOperacion(estab, fps) : SessionConfiguration.SESSION_REGULAR;
            final String desc = NOMBRES_MODO[modo] + " estab=" + estab + " op=" + op + " códec=" + Ajustes.CODEC_OP[pro.idx[Ajustes.CODEC]];
            registrar("armando sesión " + desc);
            SessionConfiguration sc = new SessionConfiguration(op, salidas, fondo::post,
                    new CameraCaptureSession.StateCallback() {
                        public void onConfigured(CameraCaptureSession s) {
                            sesion = s;
                            repetir();
                            registrar("sesión lista " + desc);
                            if (medir && medicion.length() == 0) { medicion.append("ts zoom fisica iso exp foco\n"); runOnUiThread(Camara.this::pruebaCambio); }
                        }
                        public void onConfigureFailed(CameraCaptureSession s) {
                            decir("La cámara no aceptó " + desc);
                        }
                    });
            sc.setSessionParameters(pedido.build());
            camara.createCaptureSession(sc);
        } catch (Exception e) {
            decir("Sesión: " + e.getMessage());
        }
    }

    private void repetir() {
        try {
            if (sesion != null) sesion.setRepeatingRequest(pedido.build(), alTerminarCuadro, fondo);
        } catch (Exception e) { registrar("repetir: " + e); }
    }

    // ---------- zoom ----------

    private long animId;

    /**
     * Cambia el zoom; con animar=true lo anima (botones), si no, directo (pellizco).
     * Si cruza el cambio de cámara física (0,6 ↔ 1) va más lento y arranca y frena suave,
     * para disimular el salto; el resto va rápido y frena al llegar.
     */
    private void ponerZoom(float z, boolean animar) {
        z = Math.max(rangoZoom.getLower(), Math.min(rangoZoom.getUpper(), z));
        zoomDestino = z;
        marcarLente();
        final long id = ++animId;
        final float desde = zoom, hasta = z;
        final boolean cruza = Math.min(desde, hasta) < UMBRAL_PRINCIPAL && Math.max(desde, hasta) >= UMBRAL_PRINCIPAL;
        final long t0 = SystemClock.uptimeMillis();
        final long dur = !animar ? 0 : cruza ? 350 : 250;
        fondo.post(new Runnable() {
            public void run() {
                if (id != animId || pedido == null) return;
                float p = dur == 0 ? 1f : Math.min(1f, (SystemClock.uptimeMillis() - t0) / (float) dur);
                float e = cruza ? p * p * (3 - 2 * p)                 // arranca y frena suave
                                : 1f - (1f - p) * (1f - p);           // frena al llegar
                // interpolar en escala logarítmica: el salto 1→2 se siente igual que 2→4
                zoom = (float) Math.exp(Math.log(desde) + (Math.log(hasta) - Math.log(desde)) * e);
                zoomAlPedido(zoom);
                repetir();
                if (p < 1f) fondo.postDelayed(this, 16);
            }
        });
    }

    // ---------- modos ----------

    private void cambiarModo() {
        if (grabando) { decir("Pará la grabación para cambiar la calidad"); return; }
        modo = (modo + 1) % MODOS.length;
        botonModo.setText(NOMBRES_MODO[modo]);
        if (pro.idx[Ajustes.OBTURADOR] > 0 && !obturadorEntra()) {
            pro.idx[Ajustes.OBTURADOR] = 0;
            decir("Obturador en Auto: 1/30 no entra a 60 fps");
        }
        guardarPrefs();
        refrescarChips();
        fondo.post(this::armarSesion);
    }

    private boolean obturadorEntra() { return Ajustes.OBT_DEN[pro.idx[Ajustes.OBTURADOR]] >= MODOS[modo][2]; }

    private void cambiarEstab() {
        if (grabando) { decir("Pará la grabación para cambiar la estabilización"); return; }
        estab = (estab + 1) % NOMBRES_ESTAB.length;
        botonEstab.setText(NOMBRES_ESTAB[estab]);
        guardarPrefs();
        fondo.post(this::armarSesion);
    }

    // ---------- grabar ----------

    private void empezarGrabacion() {
        if (camara == null || sesion == null) return;
        grabando = true;
        botonGrabar.animate().scaleX(0.55f).scaleY(0.55f).setDuration(150).start();
        fondoGrabar.setCornerRadius(dp(10));
        for (View v : new View[]{botonModo, botonEstab}) v.setAlpha(0.4f);
        final int giroAlEmpezar = giro;
        fondo.post(() -> {
            try {
                String nombre = "VID_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date()) + ".mp4";
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, nombre);
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Camara");
                v.put(MediaStore.Video.Media.IS_PENDING, 1);
                ContentResolver cr = getContentResolver();
                video = cr.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v);
                archivo = cr.openFileDescriptor(video, "w");
                grabador = nuevoGrabador(archivo, giroAlEmpezar);
                superficieGrabador = grabador.getSurface();
                motor.grabarEn(superficieGrabador);
                grabador.start();
                inicio = SystemClock.elapsedRealtime();
                runOnUiThread(this::mostrarReloj);
                registrar("grabando " + nombre + " giro=" + giroAlEmpezar);
            } catch (Exception e) {
                decir("No arrancó la grabación: " + e.getMessage());
                cancelarGrabacion();
            }
        });
    }

    private void mostrarReloj() {
        reloj.setVisibility(View.VISIBLE);
        reloj.post(new Runnable() {
            public void run() {
                if (!grabando) { reloj.setVisibility(View.GONE); return; }
                long s = (SystemClock.elapsedRealtime() - inicio) / 1000;
                reloj.setText(String.format(Locale.ROOT, "● %02d:%02d", s / 60, s % 60));
                reloj.postDelayed(this, 250);
            }
        });
    }

    private void mostrarParado() {
        runOnUiThread(() -> {
            botonGrabar.animate().scaleX(1f).scaleY(1f).setDuration(150).start();
            fondoGrabar.setCornerRadius(dp(30));
            for (View v : new View[]{botonModo, botonEstab}) v.setAlpha(1f);
        });
    }

    private void pararGrabacion() {
        if (!grabando) return;
        grabando = false;
        mostrarParado();
        fondo.post(this::terminarGrabacion);
    }

    /** En el hilo de fondo: saca el grabador del pedido, lo para y publica el video. */
    private void terminarGrabacion() {
        if (grabador == null) return;
        motor.grabarEn(null);
        boolean bien = true;
        try { grabador.stop(); } catch (RuntimeException e) { bien = false; }  // muy corto: sin cuadros
        liberarGrabador();
        ContentResolver cr = getContentResolver();
        if (bien) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.Video.Media.IS_PENDING, 0);
            cr.update(video, v, null, null);
            ponerMiniatura(video);
            decir("Guardado en Galería");
        } else {
            cr.delete(video, null, null);
            decir("Muy corto, no se guardó");
        }
        video = null;
    }

    private void cancelarGrabacion() {
        grabando = false;
        motor.grabarEn(null);
        liberarGrabador();
        if (video != null) { try { getContentResolver().delete(video, null, null); } catch (Exception ignorado) {} video = null; }
        mostrarParado();
    }

    private void liberarGrabador() {
        if (grabador != null) { try { grabador.reset(); grabador.release(); } catch (Exception ignorado) {} grabador = null; }
        if (archivo != null) { try { archivo.close(); } catch (Exception ignorado) {} archivo = null; }
    }
}
