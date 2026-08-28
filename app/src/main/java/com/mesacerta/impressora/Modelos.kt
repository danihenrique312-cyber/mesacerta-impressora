package com.mesacerta.impressora

/**
 * Representa um pedido que chega da cozinha.
 */
data class Pedido(
    val id: String,
    val mesaNumero: String,
    val criadoEm: String,
    val itens: List<ItemPedido>
)

data class ItemPedido(
    val quantidade: Int,
    val nome: String,
    val preco: Double = 0.0,
    val variacoes: Map<String, String> = emptyMap(),
    val observacao: String = ""
)
