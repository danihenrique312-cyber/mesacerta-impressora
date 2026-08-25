package com.mesacerta.impressora

import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Monta a comanda no formato ESC/POS que a impressora térmica entende.
 * Mesma lógica que usamos no site (RawBT), adaptada para a Bematech (32 col).
 */
object ComandaBuilder {

    private const val ESC = 0x1B
    private const val GS = 0x1D
    private const val LARGURA = 32 // caracteres por linha (Bematech fonte A, ESC/POS)

    fun montar(pedido: Pedido): ByteArray {
        val out = ByteArrayOutputStream()

        // Reset da impressora
        out.write(byteArrayOf(ESC.toByte(), '@'.code.toByte()))

        // Centralizado + tamanho dobrado para a mesa
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1)) // centraliza
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x11)) // altura+largura dobradas
        escreverLinha(out, "MESA ${pedido.mesaNumero}")
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00)) // volta ao normal

        // Alinha à esquerda para os itens
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0))
        escreverLinha(out, "-".repeat(LARGURA))

        for (item in pedido.itens) {
            // Nome do item em negrito
            out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1)) // negrito on
            escreverLinha(out, "${item.quantidade}x ${item.nome}")
            out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0)) // negrito off

            // Variações (ex: ponto da carne, sem cebola)
            for ((chave, valor) in item.variacoes) {
                escreverLinha(out, "  $chave: $valor")
            }
            // Observação
            if (item.observacao.isNotBlank()) {
                escreverLinha(out, "  obs: ${item.observacao}")
            }
        }

        escreverLinha(out, "-".repeat(LARGURA))

        // Horário
        val hora = SimpleDateFormat("HH:mm:ss", Locale("pt", "BR")).format(Date())
        escreverLinha(out, hora)

        // Espaço no fim + corte de papel
        out.write("\n\n\n".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(GS.toByte(), 'V'.code.toByte(), 0x00)) // corta o papel

        return out.toByteArray()
    }

    /**
     * Escreve uma linha, convertendo acentos para ISO-8859-1 (o que a térmica entende).
     * Ex: "café" vira bytes que a impressora imprime certinho.
     */
    private fun escreverLinha(out: ByteArrayOutputStream, texto: String) {
        val limpo = texto.replace("\n", "")
        out.write(limpo.toByteArray(Charsets.ISO_8859_1))
        out.write('\n'.code)
    }
}
