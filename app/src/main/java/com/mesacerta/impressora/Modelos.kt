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
    val restauranteId: String = "",
    val itens: List<ItemPedido>,
    // Preenchido só quando o pedido veio de casa (delivery ou retirada pelo link)
    val entrega: Entrega? = null
) {
    /** "Mesa 4", "Entrega #12" ou "Retirada #3" — usado na tela do app. */
    fun rotulo(): String = entrega?.rotulo() ?: "Mesa $mesaNumero"
}

data class ItemPedido(
    val quantidade: Int,
    val nome: String,
    val preco: Double = 0.0,
    val variacoes: Map<String, String> = emptyMap(),
    val observacao: String = ""
)

/**
 * Dados do pedido feito de casa: endereço, taxa de entrega e como o cliente vai pagar.
 */
data class Entrega(
    val tipo: String, // "entrega" ou "retirada"
    val numero: Int?,
    val rua: String = "",
    val numeroCasa: String = "",
    val bairro: String = "",
    val complemento: String = "",
    val referencia: String = "",
    val taxaEntrega: Double = 0.0,
    val formaPagamento: String = "", // "dinheiro", "cartao" ou "pix"
    val trocoPara: Double? = null,
    val observacao: String = ""
) {
    val ehEntrega: Boolean get() = tipo == "entrega"

    fun rotulo(): String = (if (ehEntrega) "Entrega" else "Retirada") + (numero?.let { " #$it" } ?: "")

    fun titulo(): String = rotulo().uppercase()

    fun rotuloPagamento(): String = when (formaPagamento) {
        "dinheiro" -> "Dinheiro"
        "cartao" -> "Cartão (maquininha)"
        "pix" -> "Pix"
        else -> ""
    }
}

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
    val pedidos: List<Pedido>,
    val entrega: Entrega? = null
)
