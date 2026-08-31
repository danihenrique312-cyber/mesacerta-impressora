package com.mesacerta.impressora

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Monta a comanda completa no formato ESC/POS: via da cozinha + via do caixa,
 * numa tirinha CONTÍNUA (sem cortar no meio) — o corte só acontece no final,
 * depois das duas vias. Isso evita perder dados por causa do tempo do corte
 * mecânico da impressora.
 */
object ComandaBuilder {

    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val LARGURA = 32 // caracteres por linha (Bematech fonte A, ESC/POS)

    // Linhas em branco extras entre uma via e outra (~2cm a mais de espaço,
    // pra facilitar destacar as vias na mão).
    private const val LINHAS_ENTRE_VIAS = 6

    /**
     * Monta a comanda inteira (as duas vias) pronta pra mandar pra impressora
     * numa única chamada.
     */
    fun montarComanda(pedido: Pedido): ByteArray {
        val out = ByteArrayOutputStream()

        // Reset da impressora — só uma vez, no início de tudo
        out.write(byteArrayOf(ESC.toByte(), '@'.code.toByte()))
        out.write(byteArrayOf(ESC.toByte(), '3'.code.toByte(), 45))

        escreverViaCozinha(out, pedido)

        // Só imprime a via do caixa se o restaurante tiver essa opção ligada
        if (pedido.imprimirDuasVias) {
            // Espaço extra entre as vias (sem cortar aqui)
            repeat(LINHAS_ENTRE_VIAS) {
                out.write('\n'.code)
            }
            escreverViaCaixa(out, pedido)
        }

        // Corte do papel só no final
        out.write("\n\n\n".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(GS.toByte(), 'V'.code.toByte(), 0x00))

        return out.toByteArray()
    }

    /**
     * Via da cozinha: itens e observações, sem preço — é só pra saber o que fazer.
     * Também sai o nome e telefone do cliente, pra não ter dúvida de quem é.
     */
    private fun escreverViaCozinha(out: ByteArrayOutputStream, pedido: Pedido) {
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1))
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x11))
        escreverLinha(out, "MESA ${pedido.mesaNumero}")
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00))
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
        escreverLinha(out, "VIA COZINHA")
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))

        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0))
        escreverDadosCliente(out, pedido)
        escreverLinha(out, "-".repeat(LARGURA))

        for (item in pedido.itens) {
            out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
            out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x01))
            escreverLinha(out, "${item.quantidade}x ${item.nome}")
            out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00))
            out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))

            for ((chave, valor) in item.variacoes) {
                escreverLinha(out, "  $chave: $valor")
            }
            if (item.observacao.isNotBlank()) {
                escreverLinha(out, "  obs: ${item.observacao}")
            }
            escreverLinha(out, "")
        }

        escreverLinha(out, "-".repeat(LARGURA))
        escreverLinha(out, horarioAgora())
    }

    /**
     * Via do caixa: itens com preço de cada um, mais o total do pedido — pra cobrança.
     * Também sai o nome e telefone do cliente.
     */
    private fun escreverViaCaixa(out: ByteArrayOutputStream, pedido: Pedido) {
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1))
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x11))
        escreverLinha(out, "MESA ${pedido.mesaNumero}")
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00))
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
        escreverLinha(out, "VIA CAIXA")
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))

        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0))
        escreverDadosCliente(out, pedido)
        escreverLinha(out, "-".repeat(LARGURA))

        var total = 0.0
        for (item in pedido.itens) {
            val subtotal = item.preco * item.quantidade
            total += subtotal
            val linhaEsquerda = "${item.quantidade}x ${item.nome}"
            val linhaDireita = formatarPreco(subtotal)
            escreverLinha(out, montarLinhaComPrecoAlinhado(linhaEsquerda, linhaDireita))
        }

        escreverLinha(out, "-".repeat(LARGURA))
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
        escreverLinha(out, montarLinhaComPrecoAlinhado("TOTAL", formatarPreco(total)))
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))
        escreverLinha(out, "-".repeat(LARGURA))
        escreverLinha(out, horarioAgora())
    }

    /**
     * Escreve o nome e telefone do cliente, se tiverem sido informados.
     */
    private fun escreverDadosCliente(out: ByteArrayOutputStream, pedido: Pedido) {
        if (pedido.nomeCliente.isNotBlank()) {
            escreverLinha(out, "Cliente: ${pedido.nomeCliente}")
        }
        if (pedido.telefoneCliente.isNotBlank()) {
            escreverLinha(out, "Tel: ${pedido.telefoneCliente}")
        }
    }

    private fun horarioAgora(): String =
        SimpleDateFormat("HH:mm:ss", Locale("pt", "BR")).format(Date())

    private fun formatarPreco(valor: Double): String =
        "R$ " + String.format(Locale("pt", "BR"), "%.2f", valor)

    /**
     * Junta o texto da esquerda com o preço na direita, preenchendo o meio com espaços
     * pra ficar tudo alinhado dentro da largura do papel (ex: "2x Suco    R$ 18,00").
     */
    private fun montarLinhaComPrecoAlinhado(esquerda: String, direita: String): String {
        val espacoDisponivel = LARGURA - direita.length
        return if (esquerda.length >= espacoDisponivel) {
            "$esquerda\n" + " ".repeat((LARGURA - direita.length).coerceAtLeast(0)) + direita
        } else {
            esquerda + " ".repeat(espacoDisponivel - esquerda.length) + direita
        }
    }

    /**
     * Escreve uma linha, convertendo acentos para ISO-8859-1 (o que a térmica entende).
     */
    private fun escreverLinha(out: ByteArrayOutputStream, texto: String) {
        for (linha in texto.split("\n")) {
            out.write(linha.toByteArray(Charsets.ISO_8859_1))
            out.write('\n'.code)
        }
    }
}
