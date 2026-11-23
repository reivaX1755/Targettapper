package com.example.targettapper

import java.nio.ByteBuffer
import java.util.Arrays

/** Diana detectada. Coordenadas y radio en píxeles reales de pantalla. */
data class Target(val x: Int, val y: Int, val radius: Int)

/**
 * Detecta dianas rojas y blancas (círculos concéntricos) en un frame RGBA_8888.
 *
 * Algoritmo:
 *  1. Muestrea el frame en una rejilla reducida (~GRID_COLUMNS columnas) y clasifica
 *     cada celda como ROJO, BLANCO u otro.
 *  2. Agrupa celdas rojas conectadas (flood fill).
 *  3. Una diana es un grupo rojo aproximadamente circular, con el centro BLANCO y con
 *     blanco suficiente dentro de su caja. Así se descartan los corazones de vidas
 *     (rojos, pero con centro rojo y sin blanco) y el texto de puntuación (sin rojo).
 *  4. Elimina duplicados (el aro rojo interior de una misma diana).
 */
class TargetDetector {

    private var cls = ByteArray(0)
    private var visited = BooleanArray(0)
    private var stack = IntArray(0)

    fun detect(
        buf: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int
    ): List<Target> {
        val step = maxOf(2, width / GRID_COLUMNS)
        val w = width / step
        val h = height / step
        val n = w * h
        if (cls.size < n) {
            cls = ByteArray(n)
            visited = BooleanArray(n)
            stack = IntArray(n)
        }
        Arrays.fill(visited, 0, n, false)

        // Zona superior ignorada (vidas y puntuación).
        val yStart = (h * TOP_IGNORE_FRACTION).toInt()

        // 1. Clasificación por colores
        for (gy in 0 until h) {
            if (gy < yStart) {
                Arrays.fill(cls, gy * w, gy * w + w, NONE)
                continue
            }
            val rowBase = gy * step * rowStride
            for (gx in 0 until w) {
                val o = rowBase + gx * step * pixelStride
                val r = buf.get(o).toInt() and 0xFF
                val g = buf.get(o + 1).toInt() and 0xFF
                val b = buf.get(o + 2).toInt() and 0xFF
                cls[gy * w + gx] = when {
                    r > 150 && r - g > 90 && r - b > 90 -> RED
                    r > 200 && g > 200 && b > 200 -> WHITE
                    else -> NONE
                }
            }
        }

        // 2 y 3. Componentes rojos y filtros de forma
        val raw = ArrayList<Target>()
        for (sy in yStart until h) {
            for (sx in 0 until w) {
                val startIdx = sy * w + sx
                if (cls[startIdx] != RED || visited[startIdx]) continue

                var sp = 0
                stack[sp++] = startIdx
                visited[startIdx] = true
                var minX = sx
                var maxX = sx
                var minY = sy
                var maxY = sy
                var count = 0

                while (sp > 0) {
                    val cur = stack[--sp]
                    val cx = cur % w
                    val cy = cur / w
                    count++
                    if (cx < minX) minX = cx
                    if (cx > maxX) maxX = cx
                    if (cy < minY) minY = cy
                    if (cy > maxY) maxY = cy

                    if (cx > 0) {
                        val j = cur - 1
                        if (!visited[j] && cls[j] == RED) { visited[j] = true; stack[sp++] = j }
                    }
                    if (cx < w - 1) {
                        val j = cur + 1
                        if (!visited[j] && cls[j] == RED) { visited[j] = true; stack[sp++] = j }
                    }
                    if (cy > 0) {
                        val j = cur - w
                        if (!visited[j] && cls[j] == RED) { visited[j] = true; stack[sp++] = j }
                    }
                    if (cy < h - 1) {
                        val j = cur + w
                        if (!visited[j] && cls[j] == RED) { visited[j] = true; stack[sp++] = j }
                    }
                }

                val bw = maxX - minX + 1
                val bh = maxY - minY + 1
                if (bw < MIN_CELLS || bh < MIN_CELLS) continue

                val ratio = bw.toFloat() / bh
                if (ratio < 0.75f || ratio > 1.33f) continue

                val fill = count.toFloat() / (bw * bh)
                if (fill < 0.20f || fill > 0.85f) continue

                // Centro blanco: celda central + 4 vecinas, al menos 3 blancas.
                val ccx = (minX + maxX) / 2
                val ccy = (minY + maxY) / 2
                var whiteNear = 0
                if (cls[ccy * w + ccx] == WHITE) whiteNear++
                if (cls[ccy * w + ccx - 1] == WHITE) whiteNear++
                if (cls[ccy * w + ccx + 1] == WHITE) whiteNear++
                if (cls[(ccy - 1) * w + ccx] == WHITE) whiteNear++
                if (cls[(ccy + 1) * w + ccx] == WHITE) whiteNear++
                if (whiteNear < 3) continue

                // Blanco suficiente dentro de la caja (anillo blanco).
                var whiteTotal = 0
                for (yy in minY..maxY) {
                    val base = yy * w
                    for (xx in minX..maxX) {
                        if (cls[base + xx] == WHITE) whiteTotal++
                    }
                }
                if (whiteTotal < WHITE_MIN_FRACTION * bw * bh) continue

                raw.add(
                    Target(
                        x = ((minX + maxX + 1) * step) / 2,
                        y = ((minY + maxY + 1) * step) / 2,
                        radius = (maxOf(bw, bh) * step) / 2
                    )
                )
            }
        }

        // 4. Duplicados: nos quedamos con el más grande de cada grupo concéntrico.
        raw.sortByDescending { it.radius }
        val result = ArrayList<Target>()
        for (t in raw) {
            val duplicate = result.any {
                val dx = (it.x - t.x).toFloat()
                val dy = (it.y - t.y).toFloat()
                val lim = it.radius * 0.7f
                dx * dx + dy * dy < lim * lim
            }
            if (!duplicate) result.add(t)
        }
        return result
    }

    companion object {
        private const val NONE: Byte = 0
        private const val RED: Byte = 1
        private const val WHITE: Byte = 2

        /** Columnas de la rejilla de análisis: menos = más rápido, más = más preciso. */
        private const val GRID_COLUMNS = 240

        /** Fracción superior de pantalla ignorada (vidas + puntuación). */
        private const val TOP_IGNORE_FRACTION = 0.10f

        /** Tamaño mínimo de una diana en celdas de la rejilla. */
        private const val MIN_CELLS = 6

        /** Fracción mínima de blanco dentro de la caja de la diana. */
        private const val WHITE_MIN_FRACTION = 0.06f
    }
}
