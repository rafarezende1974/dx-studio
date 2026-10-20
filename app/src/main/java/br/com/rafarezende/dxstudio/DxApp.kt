package br.com.rafarezende.dxstudio

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro do ultimo crash: a Application instala um handler global que
 * salva a stack trace num arquivo interno; na abertura seguinte, a
 * MainActivity mostra o relatorio na tela (compartilhar / apagar e abrir).
 * Diagnostico da v1: o app fechava sozinho ao abrir em alguns aparelhos.
 */
object CrashLog {
    private const val NAME = "last_crash.txt"

    fun write(ctx: Context, t: Throwable) {
        try {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            val header = "DX Studio - relatorio de crash\n" +
                "Quando: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + "\n" +
                "Aparelho: " + Build.MANUFACTURER + " " + Build.MODEL +
                " (Android " + Build.VERSION.RELEASE + ", SDK " + Build.VERSION.SDK_INT + ")\n\n"
            File(ctx.filesDir, NAME).writeText(header + sw.toString())
        } catch (e: Exception) {
            // Se nem o relatorio salvar, nao ha o que fazer aqui.
        }
    }

    fun read(ctx: Context): String? {
        val f = File(ctx.filesDir, NAME)
        if (!f.exists()) return null
        return try {
            f.readText()
        } catch (e: Exception) {
            null
        }
    }

    fun clear(ctx: Context) {
        try {
            File(ctx.filesDir, NAME).delete()
        } catch (e: Exception) {
            // arquivo ja removido
        }
    }
}

class DxApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            CrashLog.write(this, error)
            previous?.uncaughtException(thread, error)
        }
    }
}
