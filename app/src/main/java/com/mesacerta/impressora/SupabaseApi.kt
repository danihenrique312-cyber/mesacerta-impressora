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
 * Busca os detalhes completos de um pedido no banco (via API REST do Supabase).
 * Usa a mesma consulta que o painel da cozinha usa no site.
 */
class SupabaseApi {

    companion object {
        private const val TAG = "MesaCertaApi"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    fun buscarPedido(idPedido: String): Pedido? {
        // Monta a query com os relacionamentos (mesa + itens)
        val select = "id,criado_em," +
            "comandas!inner(nome_cliente,telefone_cliente,mesas!inner(numero,restaurante_id,restaurantes!inner(imprimir_duas_vias)))," +
            "itens_pedido(quantidade,variacoes_escolhidas,observacao,itens_cardapio(nome,preco))"

        val url = "${Config.SUPABASE_URL}/rest/v1/pedidos" +
            "?id=eq.$idPedido" +
            "&select=$select"

        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", Config.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Erro HTTP ${resp.code} ao buscar pedido")
                    return null
                }
                val corpo = resp.body?.string() ?: return null
                val array = JSONArray(corpo)
                if (array.length() == 0) return null
                parsePedido(array.getJSONObject(0))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao buscar pedido", e)
            null
        }
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

    /**
     * Descobre o ID (UUID) do restaurante a partir do slug salvo nas configurações do app.
     */
    fun buscarRestauranteIdPorSlug(slug: String): String? {
        val url = "${Config.SUPABASE_URL}/rest/v1/restaurantes?slug=eq.$slug&select=id"
        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", Config.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                val corpo = resp.body?.string() ?: return null
                val array = JSONArray(corpo)
                if (array.length() == 0) return null
                array.getJSONObject(0).optString("id")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao buscar restaurante pelo slug", e)
            null
        }
    }

    /**
     * Busca a comanda inteira de uma mesa, com todos os pedidos (exceto cancelados)
     * pra imprimir o resumo/extrato completo.
     */
    fun buscarComandaCompleta(comandaId: String): ComandaCompleta? {
        val select = "id,nome_cliente,telefone_cliente," +
            "mesas!inner(numero,restaurante_id,restaurantes!inner(imprimir_duas_vias))," +
            "pedidos(id,criado_em,status,itens_pedido(quantidade,variacoes_escolhidas,observacao,itens_cardapio(nome,preco)))"

        val url = "${Config.SUPABASE_URL}/rest/v1/comandas?id=eq.$comandaId&select=$select"

        val request = Request.Builder()
            .url(url)
            .addHeader("apikey", Config.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
            .build()

        return try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Erro HTTP ${resp.code} ao buscar comanda")
                    return null
                }
                val corpo = resp.body?.string() ?: return null
                val array = JSONArray(corpo)
                if (array.length() == 0) return null
                parseComanda(array.getJSONObject(0))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao buscar comanda", e)
            null
        }
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

    /**
     * Marca um pedido como impresso (pra não aparecer mais como "pendente de impressão").
     */
    fun marcarPedidoImpresso(pedidoId: String) {
        atualizar("pedidos", "id=eq.$pedidoId", """{"impresso": true}""")
    }

    /**
     * Marca uma solicitação de impressão manual como processada (impresso ou erro).
     */
    fun marcarSolicitacaoStatus(solicitacaoId: String, status: String) {
        val agora = java.time.Instant.now().toString()
        atualizar(
            "solicitacoes_impressao",
            "id=eq.$solicitacaoId",
            """{"status": "$status", "processado_em": "$agora"}"""
        )
    }

    private fun atualizar(tabela: String, filtro: String, corpoJson: String) {
        val url = "${Config.SUPABASE_URL}/rest/v1/$tabela?$filtro"
        val corpo = corpoJson.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .patch(corpo)
            .addHeader("apikey", Config.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer ${Config.SUPABASE_ANON_KEY}")
            .addHeader("Prefer", "return=minimal")
            .build()

        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.e(TAG, "Erro HTTP ${resp.code} ao atualizar $tabela")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao atualizar $tabela", e)
        }
    }
}
