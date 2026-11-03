package dev.gauchito.camara;

import android.content.SharedPreferences;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.TonemapCurve;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Ajustes del modo PRO. Cada ajuste es una lista de opciones con un índice; el índice 0
 * es "Auto" donde tiene sentido. aplicar() los vuelca en el pedido de la cámara.
 */
class Ajustes {
    static final int EV = 0, ISO = 1, OBTURADOR = 2, BALANCE = 3, ENFOQUE = 4, PERFIL = 5, CODEC = 6, CUADRICULA = 7;
    static final String[] NOMBRES = {"EV", "ISO", "Obturador", "Balance", "Enfoque", "Perfil", "Códec", "Cuadrícula"};

    static final String[] EV_OP = {"−2", "−1,7", "−1,3", "−1", "−0,7", "−0,3", "0", "+0,3", "+0,7", "+1", "+1,3", "+1,7", "+2"};
    static final int[] ISO_VAL = {0, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400};
    static final int[] OBT_DEN = {0, 30, 50, 60, 100, 120, 180, 250, 500, 1000, 2000, 4000, 8000};   // 1/x s
    static final String[] BAL_OP = {"Auto", "Bloqueado", "Cálido", "Fluorescente", "Día", "Nublado", "Sombra"};
    static final int[] BAL_AWB = {1, 1, 2, 3, 5, 6, 8};
    static final String[] PERFIL_OP = {"Normal", "Plano", "Vivo"};
    static final String[] CODEC_OP = {"H.264", "H.265"};
    static final String[] SI_NO = {"No", "Sí"};
    static final int PASOS_FOCO = 24;

    final int[] idx = new int[NOMBRES.length];
    int fps = 30;
    float focoMin = 9.09f;                       // dioptrías del punto más cercano

    // lo que está usando la cámara ahora (de los resultados), para mostrar y para pasar a manual
    volatile int isoVivo = 100;
    volatile long expVivo = 16_666_666;
    volatile float focoVivo = 0;

    // tocar para enfocar / bloquear
    MeteringRectangle[] region;
    boolean bloqueado;
    // al pasar de la gran angular a la principal: el último foco que usó la principal,
    // para que no lo busque desde cero (null = nada forzado)
    volatile Float focoForzado;

    Ajustes() { idx[EV] = 6; }

    void cargar(SharedPreferences p) {
        for (int i = 0; i < idx.length; i++) idx[i] = p.getInt("pro" + i, idx[i]);
        idx[BALANCE] = idx[BALANCE] == 1 ? 0 : idx[BALANCE];   // "bloqueado" no se guarda
        for (int i = 0; i < idx.length; i++) idx[i] = Math.max(0, Math.min(opciones(i).length - 1, idx[i]));
    }

    void guardar(SharedPreferences.Editor e) {
        for (int i = 0; i < idx.length; i++) e.putInt("pro" + i, idx[i]);
    }

    String[] opciones(int a) {
        switch (a) {
            case EV: return EV_OP;
            case ISO: { String[] s = new String[ISO_VAL.length]; s[0] = "Auto"; for (int i = 1; i < s.length; i++) s[i] = "" + ISO_VAL[i]; return s; }
            case OBTURADOR: { String[] s = new String[OBT_DEN.length]; s[0] = "Auto"; for (int i = 1; i < s.length; i++) s[i] = "1/" + OBT_DEN[i]; return s; }
            case BALANCE: return BAL_OP;
            case ENFOQUE: {
                String[] s = new String[PASOS_FOCO + 1]; s[0] = "Auto";
                for (int i = 1; i <= PASOS_FOCO; i++) s[i] = distancia(dioptrias(i));
                return s;
            }
            case PERFIL: return PERFIL_OP;
            case CODEC: return CODEC_OP;
            default: return SI_NO;
        }
    }

    /** Paso del enfoque manual → dioptrías: 1 = infinito, el último = lo más cerca; parejo a la vista. */
    float dioptrias(int paso) {
        float t = (paso - 1) / (float) (PASOS_FOCO - 1);
        return focoMin * t * t;
    }

    static String distancia(float d) {
        if (d < 0.05f) return "∞";
        float m = 1f / d;
        if (m >= 10) return Math.round(m) + " m";
        if (m >= 1) return String.format(Locale.ROOT, "%.1f m", m).replace('.', ',');
        return Math.round(m * 100) + " cm";
    }

    /** Texto del chip: el valor elegido, o en Auto lo que está usando la cámara. */
    String etiqueta(int a) {
        String n = NOMBRES[a];
        switch (a) {
            case ISO: return idx[a] == 0 ? "ISO " + isoVivo + " A" : "ISO " + ISO_VAL[idx[a]];
            case OBTURADOR: return idx[a] == 0 ? "1/" + Math.max(1, Math.round(1e9 / Math.max(1, expVivo))) + " A" : "1/" + OBT_DEN[idx[a]];
            case ENFOQUE: return idx[a] == 0 ? (bloqueado ? "AF bloq." : "AF " + distancia(focoVivo)) : "MF " + opciones(a)[idx[a]];
            case EV: return "EV " + EV_OP[idx[a]];
            case CUADRICULA: return idx[a] == 1 ? "Cuadrícula" : "Sin cuadrícula";
            default: return opciones(a)[idx[a]];
        }
    }

    boolean exposicionManual() { return idx[ISO] > 0 || idx[OBTURADOR] > 0; }

    /** Cuánto dura un cuadro: el obturador no puede pasarse de eso. */
    long duracionCuadro() { return 1_000_000_000L / fps; }

    /** Opciones de obturador válidas para estos fps (1/30 no entra a 60 fps). */
    boolean obturadorValido(int i) { return i == 0 || OBT_DEN[i] >= fps; }

    void leerResultado(CaptureResult r) {
        Integer s = r.get(CaptureResult.SENSOR_SENSITIVITY);
        Long e = r.get(CaptureResult.SENSOR_EXPOSURE_TIME);
        Float f = r.get(CaptureResult.LENS_FOCUS_DISTANCE);
        if (s != null) isoVivo = s;
        if (e != null) expVivo = e;
        if (f != null) focoVivo = f;
    }

    void aplicar(CaptureRequest.Builder p) {
        // exposición
        if (exposicionManual()) {
            int iso = idx[ISO] > 0 ? ISO_VAL[idx[ISO]] : Math.max(100, isoVivo);
            long exp = idx[OBTURADOR] > 0 ? 1_000_000_000L / OBT_DEN[idx[OBTURADOR]] : expVivo;
            exp = Math.min(exp, duracionCuadro());
            p.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF);
            p.set(CaptureRequest.SENSOR_SENSITIVITY, iso);
            p.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp);
            p.set(CaptureRequest.SENSOR_FRAME_DURATION, duracionCuadro());
        } else {
            p.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            p.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, (idx[EV] - 6) * 2);   // pasos de 1/6
            p.set(CaptureRequest.CONTROL_AE_LOCK, bloqueado);
            p.set(CaptureRequest.CONTROL_AE_REGIONS, region);
        }

        // balance de blancos
        p.set(CaptureRequest.CONTROL_AWB_MODE, BAL_AWB[idx[BALANCE]]);
        p.set(CaptureRequest.CONTROL_AWB_LOCK, idx[BALANCE] == 1 || bloqueado);

        // enfoque
        if (idx[ENFOQUE] > 0) {
            p.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            p.set(CaptureRequest.LENS_FOCUS_DISTANCE, dioptrias(idx[ENFOQUE]));
        } else if (focoForzado != null && !bloqueado) {
            p.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            p.set(CaptureRequest.LENS_FOCUS_DISTANCE, focoForzado);
        } else if (bloqueado) {
            p.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF);
            p.set(CaptureRequest.LENS_FOCUS_DISTANCE, focoVivo);
        } else {
            p.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
            p.set(CaptureRequest.CONTROL_AF_REGIONS, region);
        }

        // perfil de color
        if (idx[PERFIL] == 0) {
            p.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_HIGH_QUALITY);
        } else {
            float[] c = idx[PERFIL] == 1 ? curvaPlana() : curvaViva();
            p.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE);
            p.set(CaptureRequest.TONEMAP_CURVE, new TonemapCurve(c, c, c));
        }
    }

    /**
     * Plano: sube las sombras y baja las luces (parecido a un perfil log suave),
     * para tener margen al corregir color después. Sobre gamma sRGB.
     */
    static float[] curvaPlana() {
        return curva(x -> {
            double y = Math.pow(x, 1 / 2.2);                         // a gamma sRGB aproximada
            y = Math.log1p(9 * y) / Math.log(10);                    // comprimir
            return 0.06 + 0.86 * y;                                  // negros y blancos lavados
        });
    }

    /** Vivo: curva en S suave sobre gamma sRGB, más contraste sin quemar. */
    static float[] curvaViva() {
        return curva(x -> {
            double g = Math.pow(x, 1 / 2.2);
            double s = g * g * (3 - 2 * g);                          // S (smoothstep)
            return g + 0.35 * (s - g);                               // mezcla: contraste moderado
        });
    }

    interface F { double f(double x); }

    static float[] curva(F f) {
        int n = 64;
        List<Float> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double x = i / (double) (n - 1);
            double y = Math.max(0, Math.min(1, f.f(x)));
            l.add((float) x); l.add((float) y);
        }
        float[] c = new float[l.size()];
        for (int i = 0; i < c.length; i++) c[i] = l.get(i);
        return c;
    }
}
