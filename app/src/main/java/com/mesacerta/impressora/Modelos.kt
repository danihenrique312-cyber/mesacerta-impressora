package com.mesacerta.impressora

/**
 * Representa um pedido que chega da cozinha.
 */
data class Pedido(
    val id: String,
    val mesaNumero: String,
    val criadoEm: String,
    val nomeCliente: String = "",
    val telefoneCliente: String = "",
    val imprimirDuasVias: Boolean = true,
    val itens: List<ItemPedido>
)

data class ItemPedido(
    val quantidade: Int,
    val nome: String,
    val preco: Double = 0.0,
    val variacoes: Map<String, String> = emptyMap(),
    val observacao: String = ""
)

/**
 * Representa a comanda inteira de uma mesa, com todos os pedidos feitos nela
 * (usado pra imprimir o resumo completo, tipo "extrato" da comanda).
 */
data class ComandaCompleta(
    val id: String,
    val mesaNumero: String,
    val nomeCliente: String = "",
    val telefoneCliente: String = "",
    val imprimirDuasVias: Boolean = true,
    val pedidos: List<Pedido>
)
