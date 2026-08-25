package com.mesacerta.impressora

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * O coração do app: fica rodando SEMPRE em segundo plano.
 * - Escuta pedidos novos no banco (mesmo com a tela apagada)
 * - Quando chega um, busca os detalhes e manda pra impressora sozinho
 * - Mantém uma notificação fixa (exigência do Android para serviços que rodam sempre)
 */
class ServicoImpressao : Service() {

    companion object {
        private const val TAG = "MesaCertaServico"
        @Volatile var rodando = false
            private set
        var ultimoStatus = "Iniciando..."
            private set
    }

    private var realtime: SupabaseRealtime? = null
    private val api = SupabaseApi()
    private var wakeLock: PowerManager.WakeLock? = null
    private var impressora: ImpressoraBluetooth? = null

    override fun onCreate() {
        super.onCreate()
        criarCanalNotificacao()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        rodando = true
        iniciarPrimeiroPlano("Ativo — aguardando pedidos")
        adquirirWakeLock()
        iniciarEscuta()
        // START_STICKY: se o Android matar o serviço, ele tenta recriar sozinho
        return START_STICKY
    }

    private fun iniciarEscuta() {
        val prefs = getSharedPreferences(Config.PREF_NOME, Context.MODE_PRIVATE)
        val slug = prefs.getString(Config.PREF_RESTAURANTE_SLUG, "") ?: ""
        val macImpressora = prefs.getString(Config.PREF_IMPRESSORA_MAC, "") ?: ""

        if (slug.isBlank() || macImpressora.isBlank()) {
            atualizarStatus("Configuração incompleta — abra o app")
            return
        }

        impressora = ImpressoraBluetooth(macImpressora)

        realtime = SupabaseRealtime(
            restauranteSlug = slug,
            onPedidoNovo = { idPedido -> processarPedido(idPedido) },
            onStatus = { status -> atualizarStatus(status) }
        )
        realtime?.conectar()
    }

    private fun processarPedido(idPedido: String) {
        Thread {
            try {
                atualizarStatus("Imprimindo pedido...")
                // Pequena espera pra garantir que os itens já foram salvos no banco
                Thread.sleep(1200)

                val pedido = api.buscarPedido(idPedido)
                if (pedido == null) {
                    Log.w(TAG, "Pedido $idPedido não encontrado")
                    atualizarStatus("Ativo — aguardando pedidos")
                    return@Thread
                }

                val dados = ComandaBuilder.montar(pedido)
                val resultado = impressora?.imprimir(dados)

                when (resultado) {
                    is ResultadoImpressao.Sucesso -> {
                        Log.i(TAG, "Pedido ${pedido.id} impresso com sucesso")
                        atualizarStatus("Última impressão: Mesa ${pedido.mesaNumero} ✓")
                    }
                    is ResultadoImpressao.Erro -> {
                        Log.e(TAG, "Erro ao imprimir: ${resultado.mensagem}")
                        atualizarStatus("Erro: ${resultado.mensagem}")
                    }
                    null -> atualizarStatus("Impressora não configurada")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar pedido", e)
                atualizarStatus("Erro ao processar pedido")
            }
        }.start()
    }

    private fun atualizarStatus(status: String) {
        ultimoStatus = status
        val notif = construirNotificacao(status)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(Config.NOTIF_ID, notif)
    }

    private fun adquirirWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "MesaCerta::ImpressaoWakeLock"
        ).apply { acquire() }
    }

    private fun criarCanalNotificacao() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val canal = NotificationChannel(
                Config.CANAL_ID,
                "Impressão de pedidos",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantém o sistema de impressão ativo"
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(canal)
        }
    }

    private fun iniciarPrimeiroPlano(status: String) {
        val notif = construirNotificacao(status)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                Config.NOTIF_ID,
                notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(Config.NOTIF_ID, notif)
        }
    }

    private fun construirNotificacao(status: String): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pending = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, Config.CANAL_ID)
            .setContentTitle("MesaCerta Impressora")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setOngoing(true)
            .setContentIntent(pending)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        rodando = false
        realtime?.desconectar()
        try { wakeLock?.release() } catch (e: Exception) { /* ignora */ }
        Log.i(TAG, "Serviço encerrado")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
