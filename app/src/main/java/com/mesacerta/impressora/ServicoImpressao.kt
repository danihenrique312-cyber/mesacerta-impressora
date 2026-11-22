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
import org.json.JSONObject

/**
 * O coração do app: fica rodando SEMPRE em segundo plano.
 * - Escuta pedidos novos no banco (mesmo com a tela apagada) e imprime sozinho.
 *   Se a impressora falhar, tenta de novo com espera crescente. Se desistir, o pedido
 *   continua "sem impressão" no painel, e dá pra reimprimir de lá.
 * - Escuta solicitações de impressão manual vindas do site (imprimir comanda
 *   completa, ou reimprimir um pedido específico que não saiu)
 * - Manda ao servidor o que aconteceu (imprimiu, falhou, perdeu a conexão...), para o
 *   painel de saúde mostrar o histórico de cada estabelecimento
 * - Mantém uma notificação fixa (exigência do Android para serviços que rodam sempre)
 */
class ServicoImpressao : Service() {

    companion object {
        private const val TAG = "MesaCertaServico"
        private const val MINUTO_MS = 60_000L
        private const val HORA_MS = 60L * MINUTO_MS
        // Eventos guardados sem internet: no máximo essa quantidade (os mais antigos saem primeiro).
        private const val MAX_EVENTOS_PENDENTES = 300
        // Eventos enviados por volta da consulta (para não travar a fila de pedidos).
        private const val EVENTOS_POR_VOLTA = 20

        @Volatile var rodando = false
            private set
        var ultimoStatus = "Iniciando..."
            private set
    }

    /** Evento que ainda não foi confirmado pelo servidor. */
    private class EventoPendente(
        val tipo: String,
        val pedidoId: String?,
        val detalhe: String,
        val ocorreuEm: Long
    )

    private var threadFila: Thread? = null
    // Imprime um de cada vez (Bluetooth não aceita duas impressões ao mesmo tempo).
    private val filaImpressao = java.util.concurrent.Executors.newSingleThreadExecutor()
    // O que já foi tentado, quando tentar de novo e o que foi desistido (ver ControleImpressao).
    private val controle = ControleImpressao()
    // Solicitações manuais já processadas nesta execução (não repete a mesma).
    private val solicitacoesVistas = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    // Eventos que o servidor ainda não confirmou. Quem envia é a thread de consulta.
    private val eventos = java.util.concurrent.ConcurrentLinkedQueue<EventoPendente>()
    private val api = SupabaseApi()
    private var wakeLock: PowerManager.WakeLock? = null
    private var impressora: ImpressoraBluetooth? = null

    // Estado da consulta ao servidor (só a thread de consulta mexe aqui).
    private var ultimoOk = false
    private var falhasSeguidas = 0
    private var semContato = false
    private var ultimoContatoGravado = 0L
    private var primeiraFalhaEm = 0L

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

        impressora = ImpressoraBluetooth(applicationContext, macImpressora)

        val chave = prefs.getString(Config.PREF_CHAVE_EQUIPE, "") ?: ""
        if (chave.isBlank()) {
            atualizarStatus("Falta o código do restaurante — abra o app e preencha")
            return
        }
        api.configurar(slug, chave)

        // De onde olhar os pedidos: desde o último contato com o servidor (com 1 min de folga),
        // no máximo 3 h para trás. Na primeira vez, só os últimos 2 min, para não despejar histórico.
        val agora = System.currentTimeMillis()
        val ultimoContato = prefs.getLong(Config.PREF_ULTIMO_CONTATO_MS, 0L)
        val inicio = if (ultimoContato <= 0L) {
            agora - 2 * MINUTO_MS
        } else {
            maxOf(ultimoContato - MINUTO_MS, agora - 3 * HORA_MS)
        }
        val desde = formatarIsoUtc(inicio)

        threadFila?.interrupt()
        threadFila = Thread {
            registrarEvento("app_iniciou", null, "Serviço iniciado")
            while (rodando) {
                consultarFila(desde)
                enviarEventosPendentes()
                try { Thread.sleep(3000) } catch (e: InterruptedException) { break }
            }
        }.also { it.start() }
    }

    /** Uma volta da consulta: pergunta ao servidor o que há para imprimir e agenda o que for preciso. */
    private fun consultarFila(desde: String) {
        val (acesso, fila) = api.buscarFila(desde, controle.ignorados())
        when (acesso) {
            is SupabaseApi.ResultadoAcesso.Ok -> {
                if (semContato) {
                    val minutos = maxOf(1L, (System.currentTimeMillis() - primeiraFalhaEm) / MINUTO_MS)
                    registrarEvento("conexao_restaurada", null, "Ficou sem contato por $minutos min")
                    semContato = false
                }
                falhasSeguidas = 0
                registrarContato()
                if (!ultimoOk) atualizarStatus("Conectado, aguardando pedidos")
                ultimoOk = true
                fila?.pedidos?.forEach { id -> if (controle.podeAgendar(id)) agendarImpressao(id) }
                fila?.solicitacoes?.forEach { sol ->
                    if (solicitacoesVistas.add("s:" + sol.optString("id"))) processarSolicitacao(sol)
                }
            }
            is SupabaseApi.ResultadoAcesso.CodigoInvalido -> {
                atualizarStatus("Restaurante ou código incorreto. Confira o slug e o código no painel (QR da cozinha).")
                ultimoOk = false
            }
            is SupabaseApi.ResultadoAcesso.ErroHttp ->
                falhaDeContato("Servidor com problema (erro ${acesso.codigo})")
            is SupabaseApi.ResultadoAcesso.SemRede ->
                falhaDeContato("Sem internet")
        }
    }

    /** A consulta ao servidor falhou. Depois de algumas seguidas, avisa o painel (uma vez só). */
    private fun falhaDeContato(motivo: String) {
        falhasSeguidas++
        if (falhasSeguidas == 1) primeiraFalhaEm = System.currentTimeMillis()
        ultimoOk = false
        atualizarStatus("$motivo. Tentando de novo...")
        if (!semContato && falhasSeguidas >= 3) {
            semContato = true
            // A hora do evento é a da primeira falha, não a de agora (o painel mostra quando caiu).
            registrarEvento("conexao_perdida", null, motivo, primeiraFalhaEm)
        }
    }

    /** Guarda a hora do último contato com o servidor (no máximo a cada 30 s). */
    private fun registrarContato() {
        val agora = System.currentTimeMillis()
        if (agora - ultimoContatoGravado < 30_000L) return
        ultimoContatoGravado = agora
        getSharedPreferences(Config.PREF_NOME, Context.MODE_PRIVATE)
            .edit()
            .putLong(Config.PREF_ULTIMO_CONTATO_MS, agora)
            .apply()
    }

    /** Põe um pedido na fila de impressão (roda na thread de impressão, um de cada vez). */
    private fun agendarImpressao(idPedido: String) {
        controle.agendado(idPedido)
        try {
            filaImpressao.execute { processarPedido(idPedido) }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            // Serviço já encerrado: não há o que fazer.
            controle.liberar(idPedido)
        }
    }

    /**
     * Impressão AUTOMÁTICA de um pedido. Se der certo, marca como impresso. Se falhar,
     * `falhouImpressao` decide quando tentar de novo (a consulta agenda de novo nessa hora).
     */
    private fun processarPedido(idPedido: String) {
        var rotulo: String? = null
        try {
            if (controle.jaImpresso(idPedido)) {
                // Já saiu na impressora numa tentativa anterior: só falta o servidor confirmar.
                confirmarImpressao(idPedido, null)
                return
            }
            atualizarStatus("Imprimindo pedido...")
            // Pequena espera pra garantir que os itens já foram salvos no banco
            Thread.sleep(1200)

            val pedido = api.buscarPedido(idPedido)
            if (pedido == null) {
                // Não existe mais (ou não é deste restaurante): não há o que imprimir.
                Log.w(TAG, "Pedido $idPedido não encontrado")
                controle.concluir(idPedido)
                return
            }
            val nomePedido = pedido.rotulo()
            rotulo = nomePedido

            val dados = ComandaBuilder.montarComanda(pedido)
            when (val resultado = impressora?.imprimir(dados)) {
                is ResultadoImpressao.Sucesso -> {
                    Log.i(TAG, "Pedido ${pedido.id} impresso com sucesso")
                    controle.marcarImpresso(idPedido)
                    registrarEvento("imprimiu", idPedido, nomePedido)
                    atualizarStatus("Última impressão: $nomePedido ✓")
                    confirmarImpressao(idPedido, nomePedido)
                }
                is ResultadoImpressao.Erro -> {
                    Log.e(TAG, "Erro ao imprimir: ${resultado.mensagem}")
                    falhouImpressao(idPedido, rotulo, resultado.mensagem)
                }
                null -> falhouImpressao(idPedido, rotulo, "impressora não configurada")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Erro ao processar pedido", e)
            falhouImpressao(idPedido, rotulo, e.message?.take(120) ?: "erro inesperado")
        } finally {
            controle.liberar(idPedido)
        }
    }

    /** Uma tentativa de impressão falhou: registra, agenda a próxima ou desiste. */
    private fun falhouImpressao(idPedido: String, rotulo: String?, motivo: String) {
        val quem = rotulo ?: "Pedido"
        when (val decisao = controle.registrarFalha(idPedido)) {
            is ControleImpressao.DepoisDaFalha.Tentar -> {
                val esperaSeg = decisao.esperaMs / 1000
                registrarEvento(
                    "falhou",
                    idPedido,
                    "$quem: tentativa ${decisao.tentativa} de ${ControleImpressao.MAX_TENTATIVAS} falhou ($motivo). Nova tentativa em $esperaSeg s."
                )
                atualizarStatus("Não consegui imprimir ($motivo). Tentando de novo.")
            }
            is ControleImpressao.DepoisDaFalha.Desistir -> {
                registrarEvento(
                    "desistiu",
                    idPedido,
                    "$quem: desistiu depois de ${decisao.tentativas} tentativas ($motivo). Fica sem impressão até reimprimir pelo painel."
                )
                atualizarStatus("Pedido sem impressão: reimprima pelo painel.")
            }
        }
    }

    /** Pede ao servidor para marcar o pedido como impresso. Se ele não confirmar, repete depois, sem imprimir de novo. */
    private fun confirmarImpressao(idPedido: String, nomePedido: String?) {
        if (api.marcarPedidoImpresso(idPedido)) {
            controle.concluir(idPedido)
        } else if (controle.adiarConfirmacao(idPedido)) {
            registrarEvento(
                "marcacao_falhou",
                idPedido,
                "${nomePedido ?: "Pedido"}: saiu na impressora, mas o servidor não confirmou. Vai tentar de novo."
            )
        }
    }

    /**
     * Impressão MANUAL: dispara quando o garçom clica em "Imprimir comanda"
     * ou "Imprimir pedido" (reimpressão) no site.
     */
    private fun processarSolicitacao(registro: JSONObject) {
        filaImpressao.execute {
            val solicitacaoId = registro.optString("id")
            try {
                val tipo = registro.optString("tipo")
                atualizarStatus("Imprimindo solicitação manual...")

                val dados: ByteArray? = when (tipo) {
                    "pedido" -> {
                        val pedidoId = registro.optString("pedido_id")
                        val pedido = api.buscarPedido(pedidoId)
                        if (pedido != null) {
                            api.marcarPedidoImpresso(pedidoId)
                            ComandaBuilder.montarComanda(pedido)
                        } else null
                    }
                    "comanda" -> {
                        val comandaId = registro.optString("comanda_id")
                        val comanda = api.buscarComandaCompleta(comandaId)
                        if (comanda != null) ComandaBuilder.montarResumoComanda(comanda) else null
                    }
                    else -> null
                }

                if (dados == null) {
                    Log.w(TAG, "Solicitação $solicitacaoId inválida ou não encontrada")
                    api.marcarSolicitacaoStatus(solicitacaoId, "erro")
                    atualizarStatus("Erro: solicitação não encontrada")
                    return@execute
                }

                val resultado = impressora?.imprimir(dados)
                when (resultado) {
                    is ResultadoImpressao.Sucesso -> {
                        Log.i(TAG, "Solicitação $solicitacaoId impressa com sucesso")
                        api.marcarSolicitacaoStatus(solicitacaoId, "impresso")
                        atualizarStatus("Impressão manual concluída ✓")
                    }
                    is ResultadoImpressao.Erro -> {
                        Log.e(TAG, "Erro ao imprimir solicitação: ${resultado.mensagem}")
                        api.marcarSolicitacaoStatus(solicitacaoId, "erro")
                        atualizarStatus("Erro: ${resultado.mensagem}")
                    }
                    null -> {
                        api.marcarSolicitacaoStatus(solicitacaoId, "erro")
                        atualizarStatus("Impressora não configurada")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Erro ao processar solicitação", e)
                api.marcarSolicitacaoStatus(solicitacaoId, "erro")
                atualizarStatus("Erro ao processar solicitação")
            }
        }
    }

    /** Guarda um evento para enviar ao servidor. Sem internet, ele espera e sai depois, com a hora em que aconteceu. */
    private fun registrarEvento(
        tipo: String,
        pedidoId: String?,
        detalhe: String,
        ocorreuEm: Long = System.currentTimeMillis()
    ) {
        eventos.add(EventoPendente(tipo, pedidoId, detalhe.take(300), ocorreuEm))
        while (eventos.size > MAX_EVENTOS_PENDENTES) eventos.poll()
    }

    /** Manda os eventos guardados, na ordem. Para no primeiro que falhar (tenta de novo na próxima volta). */
    private fun enviarEventosPendentes() {
        var enviados = 0
        while (enviados < EVENTOS_POR_VOLTA) {
            val evento = eventos.peek() ?: return
            if (!api.enviarEvento(evento.tipo, evento.pedidoId, evento.detalhe, evento.ocorreuEm)) return
            eventos.poll()
            enviados++
        }
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
        threadFila?.interrupt()
        filaImpressao.shutdown()
        try { wakeLock?.release() } catch (e: Exception) { /* ignora */ }
        Log.i(TAG, "Serviço encerrado")
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
