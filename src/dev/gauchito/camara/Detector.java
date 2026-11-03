package dev.gauchito.camara;

import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.io.FileWriter;
import java.util.Arrays;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Etapa 1: qué cámaras ve y puede abrir una app propia, con lo que importa para grabar. */
public class Detector extends Activity {
    private TextView texto;
    private final StringBuilder sb = new StringBuilder();
    private Handler fondo;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        texto = new TextView(this);
        texto.setTextSize(12);
        texto.setPadding(32, 96, 32, 96);
        texto.setTextIsSelectable(true);
        ScrollView sv = new ScrollView(this);
        sv.addView(texto);
        setContentView(sv);
        HandlerThread h = new HandlerThread("detector");
        h.start();
        fondo = new Handler(h.getLooper());
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.CAMERA}, 1);
        } else {
            fondo.post(this::detectar);
        }
    }

    @Override
    public void onRequestPermissionsResult(int c, String[] p, int[] r) {
        fondo.post(this::detectar);
    }

    private void l(String s) {
        sb.append(s).append('\n');
        final String t = sb.toString();
        runOnUiThread(() -> texto.setText(t));
    }

    private void detectar() {
        CameraManager cm = getSystemService(CameraManager.class);
        try {
            String[] ids = cm.getCameraIdList();
            l("Lista pública: " + Arrays.toString(ids));
            try {
                l("Juntas a la vez: " + cm.getConcurrentCameraIds());
            } catch (Exception e) { l("Juntas: " + e); }
        } catch (Exception e) { l("Lista: " + e); }

        for (int i = 0; i < 10; i++) {
            String id = String.valueOf(i);
            l("\n== Cámara " + id + " ==");
            CameraCharacteristics c;
            try {
                c = cm.getCameraCharacteristics(id);
            } catch (Exception e) { l("  no existe para mí: " + e.getClass().getSimpleName()); continue; }
            Integer f = c.get(CameraCharacteristics.LENS_FACING);
            l("  lado: " + (f == null ? "?" : f == 0 ? "frontal" : f == 1 ? "trasera" : "externa"));
            l("  focal: " + Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)));
            Range<Float> z = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            l("  zoom: " + z);
            Set<String> fis = c.getPhysicalCameraIds();
            l("  físicas: " + fis);
            int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            boolean logica = false;
            if (caps != null) for (int k : caps) if (k == CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA) logica = true;
            l("  lógica (combina lentes): " + logica);
            l("  estab. digital: " + Arrays.toString(c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)) + " (0 apag, 1 prend, 2 vista previa)");
            l("  estab. óptica: " + Arrays.toString(c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)));
            l("  enfoque mín: " + c.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) + " dioptrías");
            l("  nivel: " + c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL));
            StreamConfigurationMap m = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            if (m != null) {
                Size[] vs = m.getOutputSizes(MediaRecorder.class);
                StringBuilder v = new StringBuilder();
                if (vs != null) for (Size s : vs) if (s.getWidth() >= 1920) {
                    long d = m.getOutputMinFrameDuration(MediaRecorder.class, s);
                    v.append(s).append('@').append(d > 0 ? Math.round(1e9 / d) : 0).append(' ');
                }
                l("  video ≥1080p: " + v);
            }
            l("  fps: " + Arrays.toString(c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)));
            l("  abrir: " + probarAbrir(cm, id));
        }
        guardar();
    }

    private String probarAbrir(CameraManager cm, String id) {
        final String[] r = {"sin respuesta"};
        CountDownLatch fin = new CountDownLatch(1);
        try {
            cm.openCamera(id, new CameraDevice.StateCallback() {
                public void onOpened(CameraDevice d) { r[0] = "SÍ"; d.close(); fin.countDown(); }
                public void onDisconnected(CameraDevice d) { r[0] = "desconectada"; d.close(); fin.countDown(); }
                public void onError(CameraDevice d, int e) { r[0] = "error " + e; d.close(); fin.countDown(); }
            }, new Handler(getMainLooper()));
            fin.await(4, TimeUnit.SECONDS);
        } catch (Exception e) { r[0] = "NO: " + e.getClass().getSimpleName() + " " + e.getMessage(); }
        return r[0];
    }

    private void guardar() {
        try {
            File out = new File(getExternalFilesDir(null), "camaras.txt");
            try (FileWriter w = new FileWriter(out)) { w.write(sb.toString()); }
            l("\nGuardado en " + out);
        } catch (Exception e) { l("guardar: " + e); }
    }
}
