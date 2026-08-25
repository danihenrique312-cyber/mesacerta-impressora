package com.mesacerta.impressora

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * Quando o celular termina de reiniciar, o Android avisa aqui.
 * Se o serviço estava ativo antes, religamos ele sozinho.
 */
class ReiniciarReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "MesaCertaReiniciar"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
            val prefs = context.getSharedPreferences(Config.PREF_NOME, Context.MODE_PRIVATE)
            val estavaAtivo = prefs.getBoolean(Config.PREF_SERVICO_ATIVO, false)

            if (estavaAtivo) {
                Log.i(TAG, "Celular reiniciou — religando o serviço de impressão")
                val servico = Intent(context, ServicoImpressao::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(servico)
                } else {
                    context.startService(servico)
                }
            }
        }
    }
}
