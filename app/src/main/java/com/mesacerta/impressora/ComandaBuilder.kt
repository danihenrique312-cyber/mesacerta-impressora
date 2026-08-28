package com.mesacerta.impressora

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Monta as comandas no formato ESC/POS que a impressora térmica entende.
 * Gera DUAS vias separadas: uma pra cozinha (só os itens, sem preço)
 * e outra pro caixa (com preço de cada item e o total).
 */
object ComandaBuilder {

    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val LARGURA = 32 // caracteres por linha (Bematech fonte A, ESC/POS)

    /**
     * Monta as duas vias prontas pra imprimir, nessa ordem: [cozinha, caixa].
     */
    fun montarDuasVias(pedido: Pedido): List<ByteArray> {
        return listOf(montarViaCozinha(pedido), montarViaCaixa(pedido))
    }

    /**
     * Via da cozinha: só os itens e observações, sem preço — é só pra saber o que fazer.
     */
    private fun montarViaCozinha(pedido: Pedido): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(ESC.toByte(), '@'.code.toByte()))
        out.write(byteArrayOf(ESC.toByte(), '3'.code.toByte(), 45))

        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1))
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x11))
        escreverLinha(out, "MESA ${pedido.mesaNumero}")
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00))
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
        escreverLinha(out, "VIA COZINHA")
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))

        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0))
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
        finalizar(out)
        return out.toByteArray()
    }

    /**
     * Via do caixa: itens com preço de cada um, mais o total do pedido — pra cobrança.
     */
    private fun montarViaCaixa(pedido: Pedido): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(ESC.toByte(), '@'.code.toByte()))
        out.write(byteArrayOf(ESC.toByte(), '3'.code.toByte(), 45))

        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1))
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x11))
        escreverLinha(out, "MESA ${pedido.mesaNumero}")
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00))
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
        escreverLinha(out, "VIA CAIXA")
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))

        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0))
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
        finalizar(out)
        return out.toByteArray()
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
            // Nome muito grande: quebra em duas linhas
            "$esquerda\n" + " ".repeat((LARGURA - direita.length).coerceAtLeast(0)) + direita
        } else {
            esquerda + " ".repeat(espacoDisponivel - esquerda.length) + direita
        }
    }

    private fun finalizar(out: ByteArrayOutputStream) {
        out.write("\n\n\n".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(GS.toByte(), 'V'.code.toByte(), 0x00))
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
