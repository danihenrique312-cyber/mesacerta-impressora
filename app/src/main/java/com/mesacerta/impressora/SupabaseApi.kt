package com.mesacerta.impressora

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Fala com o servidor do MesaCerta (rota /api/impressora). O app NÃO acessa mais o
 * banco direto: toda chamada leva o slug + o código secreto do restaurante, e o
 * servidor confere antes de devolver ou alterar qualquer coisa.
 */
class SupabaseApi {

    companion object {
        private const val TAG = "MesaCertaApi"
        private val JSON = "application/json".toMediaType()
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    @Volatile private var slug: String = ""
    @Volatile private var chave: String = ""

    /** Define o restaurante e o código secreto usados em todas as chamadas. */
    fun configurar(slugDigitado: String, chaveDigitada: String) {
        slug = normalizarSlug(slugDigitado)
        chave = chaveDigitada.trim()
    }

    /** Resposta crua do servidor: código HTTP + corpo (ou erro de rede). */
    private class Resposta(val codigo: Int, val corpo: JSONObject?, val semRede: Boolean)

    private fun chamar(acao: String, extras: JSONObject = JSONObject()): Resposta {
        val json = JSONObject(extras.toString()).apply {
            put("acao", acao)
            put("slug", slug)
            put("k", chave)
        }
        val request = Request.Builder()
            .url("${Config.API_BASE}/api/impressora")
            .post(json.toString().toRequestBody(JSON))
            .build()
        return try {
            client.newCall(request).execute().use { resp ->
                val texto = resp.body?.string() ?: ""
                val corpo = try { JSONObject(texto) } catch (e: Exception) { null }
                Resposta(resp.code, corpo, false)
            }
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Sem rede em $acao", e)
            Resposta(-1, null, true)
        } catch (e: Exception) {
            Log.e(TAG, "Erro inesperado em $acao", e)
            Resposta(-2, null, false)
        }
    }

    /** Resultado da checagem de acesso: distingue "código errado" de "deu erro". */
    sealed class ResultadoAcesso {
        object Ok : ResultadoAcesso()
        object CodigoInvalido : ResultadoAcesso()
        data class ErroHttp(val codigo: Int) : ResultadoAcesso()
        object SemRede : ResultadoAcesso()
    }

    /** Fila de impressão: ids de pedidos novos + solicitações manuais pendentes. */
    class Fila(val pedidos: List<String>, val solicitacoes: List<JSONObject>)

    /**
     * Padroniza o slug digitado: minúsculas, sem acento, espaços/underscores viram hífen.
     * Ex: " Mega Burguer " -> "mega-burguer".
     */
    fun normalizarSlug(bruto: String): String {
        val semAcento = java.text.Normalizer.normalize(bruto.trim(), java.text.Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
        return semAcento.lowercase()
            .replace(Regex("[\\s_]+"), "-")
            .replace(Regex("[^a-z0-9-]"), "")
            .replace(Regex("-{2,}"), "-")
            .trim('-')
    }


    /** Pergunta ao servidor o que há pra imprimir (também serve pra validar o código). */
    fun buscarFila(desdeIso: String): Pair<ResultadoAcesso, Fila?> {
        val r = chamar("fila", JSONObject().put("desde", desdeIso))
        if (r.semRede) return ResultadoAcesso.SemRede to null
        if (r.codigo == 401) return ResultadoAcesso.CodigoInvalido to null
        val corpo = r.corpo
        if (r.codigo != 200 || corpo == null) return ResultadoAcesso.ErroHttp(r.codigo) to null

        val ids = mutableListOf<String>()
        val arrP = corpo.optJSONArray("pedidos") ?: JSONArray()
        for (i in 0 until arrP.length()) ids.add(arrP.optString(i))
        val sols = mutableListOf<JSONObject>()
        val arrS = corpo.optJSONArray("solicitacoes") ?: JSONArray()
        for (i in 0 until arrS.length()) arrS.optJSONObject(i)?.let { sols.add(it) }
        return ResultadoAcesso.Ok to Fila(ids, sols)
    }

    fun buscarPedido(idPedido: String): Pedido? {
        val r = chamar("pedido", JSONObject().put("id", idPedido))
        val dados = r.corpo?.optJSONObject("dados") ?: return null
        return parsePedido(dados)
    }

    fun buscarComandaCompleta(comandaId: String): ComandaCompleta? {
        val r = chamar("comanda", JSONObject().put("id", comandaId))
        val dados = r.corpo?.optJSONObject("dados") ?: return null
        return parseComanda(dados)
    }

    /** Marca um pedido como impresso (pra não aparecer mais como "pendente de impressão"). */
    fun marcarPedidoImpresso(pedidoId: String) {
        val r = chamar("marcar_pedido", JSONObject().put("id", pedidoId))
        if (r.codigo != 200) Log.e(TAG, "Falha ao marcar pedido impresso (${r.codigo})")
    }

    /** Marca uma solicitação de impressão manual como processada (impresso ou erro). */
    fun marcarSolicitacaoStatus(solicitacaoId: String, status: String) {
        val r = chamar(
            "marcar_solicitacao",
            JSONObject().put("id", solicitacaoId).put("status", status)
        )
        if (r.codigo != 200) Log.e(TAG, "Falha ao marcar solicitação (${r.codigo})")
    }

    private fun parsePedido(obj: JSONObject): Pedido {
        // Extrai o número da mesa (vem aninhado: comandas -> mesas -> numero)
        val comandas = obj.optJSONObject("comandas")
        val mesa = comandas?.optJSONObject("mesas")
        val mesaNumero = mesa?.optString("numero") ?: "?"
        val restauranteIdDoPedido = textoOuVazio(mesa?.optString("restaurante_id"))
        val restauranteObj = mesa?.optJSONObject("restaurantes")
        val imprimirDuasVias = restauranteObj?.optBoolean("imprimir_duas_vias", true) ?: true
        val nomeCliente = textoOuVazio(comandas?.optString("nome_cliente"))
        val telefoneCliente = textoOuVazio(comandas?.optString("telefone_cliente"))

        return Pedido(
            id = obj.optString("id"),
            mesaNumero = mesaNumero,
            criadoEm = obj.optString("criado_em"),
            nomeCliente = nomeCliente,
            telefoneCliente = telefoneCliente,
            imprimirDuasVias = imprimirDuasVias,
            restauranteId = restauranteIdDoPedido,
            itens = parseItens(obj.optJSONArray("itens_pedido"))
        )
    }

    private fun parseItens(itensArray: JSONArray?): List<ItemPedido> {
        val array = itensArray ?: JSONArray()
        val itens = mutableListOf<ItemPedido>()
        for (i in 0 until array.length()) {
            val it = array.getJSONObject(i)
            val cardapio = it.optJSONObject("itens_cardapio")
            val nome = cardapio?.optString("nome") ?: "Item"
            val preco = cardapio?.optDouble("preco") ?: 0.0
            val qtd = it.optInt("quantidade", 1)

            val variacoes = mutableMapOf<String, String>()
            val varObj = it.optJSONObject("variacoes_escolhidas")
            if (varObj != null) {
                val chaves = varObj.keys()
                while (chaves.hasNext()) {
                    val chave = chaves.next()
                    variacoes[chave] = varObj.optString(chave)
                }
            }

            itens.add(ItemPedido(qtd, nome, preco, variacoes, textoOuVazio(it.optString("observacao"))))
        }
        return itens
    }

    private fun textoOuVazio(valor: String?): String {
        return if (valor == null || valor == "null") "" else valor
    }

    private fun parseComanda(obj: JSONObject): ComandaCompleta {
        val mesa = obj.optJSONObject("mesas")
        val mesaNumero = mesa?.optString("numero") ?: "?"
        val restauranteIdDaComanda = textoOuVazio(mesa?.optString("restaurante_id"))
        val restauranteObj = mesa?.optJSONObject("restaurantes")
        val imprimirDuasVias = restauranteObj?.optBoolean("imprimir_duas_vias", true) ?: true
        val nomeCliente = textoOuVazio(obj.optString("nome_cliente"))
        val telefoneCliente = textoOuVazio(obj.optString("telefone_cliente"))

        val pedidosArray = obj.optJSONArray("pedidos") ?: JSONArray()
        val pedidos = mutableListOf<Pedido>()
        for (i in 0 until pedidosArray.length()) {
            val p = pedidosArray.getJSONObject(i)
            val status = p.optString("status")
            if (status == "cancelado") continue // não entra no resumo impresso

            pedidos.add(
                Pedido(
                    id = p.optString("id"),
                    mesaNumero = mesaNumero,
                    criadoEm = p.optString("criado_em"),
                    nomeCliente = nomeCliente,
                    telefoneCliente = telefoneCliente,
                    imprimirDuasVias = imprimirDuasVias,
                    restauranteId = restauranteIdDaComanda,
                    itens = parseItens(p.optJSONArray("itens_pedido"))
                )
            )
        }
        // Ordena do mais antigo pro mais novo
        pedidos.sortBy { it.criadoEm }

        return ComandaCompleta(
            id = obj.optString("id"),
            mesaNumero = mesaNumero,
            nomeCliente = nomeCliente,
            telefoneCliente = telefoneCliente,
            imprimirDuasVias = imprimirDuasVias,
            pedidos = pedidos
        )
    }
}
