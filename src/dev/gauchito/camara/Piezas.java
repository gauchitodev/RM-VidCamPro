package dev.gauchito.camara;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/** Piezas de pantalla dibujadas a mano. */
class Piezas {

    /** Cuadrícula de tercios sobre la vista previa. */
    static class Cuadricula extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Cuadricula(Context c) {
            super(c);
            p.setColor(0x66FFFFFF);
            p.setStrokeWidth(c.getResources().getDisplayMetrics().density);
        }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight();
            for (int i = 1; i < 3; i++) {
                c.drawLine(w * i / 3, 0, w * i / 3, h, p);
                c.drawLine(0, h * i / 3, w, h * i / 3, p);
            }
        }
    }

    /** Aro de enfoque: aparece donde tocaste; amarillo fijo si quedó bloqueado. */
    static class Aro extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float d;
        boolean bloqueado;
        Aro(Context c) {
            super(c);
            d = c.getResources().getDisplayMetrics().density;
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(2 * d);
        }
        @Override protected void onDraw(Canvas c) {
            p.setColor(bloqueado ? 0xFFFFD54F : 0xFFFFFFFF);
            float r = Math.min(getWidth(), getHeight()) / 2f - 2 * d;
            c.drawCircle(getWidth() / 2f, getHeight() / 2f, r, p);
            if (bloqueado) {
                p.setStyle(Paint.Style.FILL);
                c.drawCircle(getWidth() / 2f, getHeight() / 2f, 3 * d, p);
                p.setStyle(Paint.Style.STROKE);
            }
        }
    }
}
