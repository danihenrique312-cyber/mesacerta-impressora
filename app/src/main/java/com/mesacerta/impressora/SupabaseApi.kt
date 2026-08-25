package com.mesacerta.impressora

import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
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
            "comandas!inner(mesas!inner(numero))," +
            "itens_pedido(quantidade,variacoes_escolhidas,observacao,itens_cardapio(nome))"

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

        // Extrai os itens
        val itensArray = obj.optJSONArray("itens_pedido") ?: JSONArray()
        val itens = mutableListOf<ItemPedido>()
        for (i in 0 until itensArray.length()) {
            val it = itensArray.getJSONObject(i)
            val cardapio = it.optJSONObject("itens_cardapio")
            val nome = cardapio?.optString("nome") ?: "Item"
            val qtd = it.optInt("quantidade", 1)

            // Variações (pode ser null)
            val variacoes = mutableMapOf<String, String>()
            val varObj = it.optJSONObject("variacoes_escolhidas")
            if (varObj != null) {
                val chaves = varObj.keys()
                while (chaves.hasNext()) {
                    val chave = chaves.next()
                    variacoes[chave] = varObj.optString(chave)
                }
            }

            val obs = it.optString("observacao", "")
            itens.add(ItemPedido(qtd, nome, variacoes, if (obs == "null") "" else obs))
        }

        return Pedido(
            id = obj.optString("id"),
            mesaNumero = mesaNumero,
            criadoEm = obj.optString("criado_em"),
            itens = itens
        )
    }
}
