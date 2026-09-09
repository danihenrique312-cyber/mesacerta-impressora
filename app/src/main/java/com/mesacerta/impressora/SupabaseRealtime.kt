package com.mesacerta.impressora

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Mantém uma conexão em tempo real com o Supabase (mesmo mecanismo do site).
 * Escuta DUAS coisas:
 * 1. Pedidos novos (impressão automática, como já era)
 * 2. Solicitações de impressão manual (quando o garçom pede "Imprimir comanda"
 *    ou "Imprimir pedido" no site) — filtrado só pelo restaurante deste app.
 */
class SupabaseRealtime(
    private val restauranteId: String,
    private val onPedidoNovo: (String) -> Unit,
    private val onSolicitacaoNova: (JSONObject) -> Unit,
    private val onStatus: (String) -> Unit
) {
    companion object {
        private const val TAG = "MesaCertaRealtime"
        private const val HEARTBEAT_MS = 25_000L
    }

    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private var webSocket: WebSocket? = null
    private var ref = 0
    private var heartbeatThread: Thread? = null
    private var ativo = false

    fun conectar() {
        ativo = true
        val urlWs = Config.SUPABASE_URL.replace("https://", "wss://") +
            "/realtime/v1/websocket?apikey=${Config.SUPABASE_ANON_KEY}&vsn=1.0.0"

        val request = Request.Builder().url(urlWs).build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket conectado")
                onStatus("Conectado, aguardando pedidos")
                inscreverPedidos()
                inscreverSolicitacoes()
                iniciarHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                tratarMensagem(text)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "WebSocket falhou", t)
                onStatus("Reconectando...")
                reconectarEmBreve()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "WebSocket fechado: $reason")
                if (ativo) reconectarEmBreve()
            }
        })
    }

    private fun inscreverPedidos() {
        // Inscreve nas inserções da tabela "pedidos" (impressão automática)
        ref++
        val join = JSONObject().apply {
            put("topic", "realtime:public:pedidos")
            put("event", "phx_join")
            put("payload", JSONObject().apply {
                put("config", JSONObject().apply {
                    put("postgres_changes", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("event", "INSERT")
                            put("schema", "public")
                            put("table", "pedidos")
                        })
                    })
                })
            })
            put("ref", ref.toString())
        }
        webSocket?.send(join.toString())
    }

    private fun inscreverSolicitacoes() {
        // Inscreve nas solicitações de impressão manual, só as DESSE restaurante
        ref++
        val join = JSONObject().apply {
            put("topic", "realtime:public:solicitacoes_impressao")
            put("event", "phx_join")
            put("payload", JSONObject().apply {
                put("config", JSONObject().apply {
                    put("postgres_changes", org.json.JSONArray().apply {
                        put(JSONObject().apply {
                            put("event", "INSERT")
                            put("schema", "public")
                            put("table", "solicitacoes_impressao")
                            put("filter", "restaurante_id=eq.$restauranteId")
                        })
                    })
                })
            })
            put("ref", ref.toString())
        }
        webSocket?.send(join.toString())
    }

    private fun tratarMensagem(text: String) {
        try {
            val msg = JSONObject(text)
            val evento = msg.optString("event")
            if (evento != "postgres_changes") return

            val topico = msg.optString("topic")
            val payload = msg.optJSONObject("payload") ?: return
            val data = payload.optJSONObject("data") ?: return
            val tipo = data.optString("type")
            if (tipo != "INSERT") return

            val registro = data.optJSONObject("record") ?: return

            if (topico.contains("solicitacoes_impressao")) {
                Log.i(TAG, "Solicitação de impressão manual detectada")
                onSolicitacaoNova(registro)
            } else if (topico.contains("pedidos")) {
                val idPedido = registro.optString("id")
                if (idPedido.isNotBlank()) {
                    Log.i(TAG, "Pedido novo detectado: $idPedido")
                    onPedidoNovo(idPedido)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao tratar mensagem", e)
        }
    }

    private fun iniciarHeartbeat() {
        heartbeatThread = Thread {
            while (ativo) {
                try {
                    Thread.sleep(HEARTBEAT_MS)
                    ref++
                    val hb = JSONObject().apply {
                        put("topic", "phoenix")
                        put("event", "heartbeat")
                        put("payload", JSONObject())
                        put("ref", ref.toString())
                    }
                    webSocket?.send(hb.toString())
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Erro no heartbeat", e)
                }
            }
        }.also { it.start() }
    }

    private fun reconectarEmBreve() {
        if (!ativo) return
        Thread {
            try {
                Thread.sleep(3000)
                if (ativo) conectar()
            } catch (e: Exception) { /* ignora */ }
        }.start()
    }

    fun desconectar() {
        ativo = false
        heartbeatThread?.interrupt()
        webSocket?.close(1000, "encerrado")
        webSocket = null
    }
}
