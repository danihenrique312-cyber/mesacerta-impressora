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

        if (pedido.imprimirDuasVias) {
            escreverViaCozinha(out, pedido)
            // Espaço extra entre as vias (sem cortar aqui)
            repeat(LINHAS_ENTRE_VIAS) {
                out.write('\n'.code)
            }
            escreverViaCaixa(out, pedido)
        } else if (pedido.entrega != null) {
            // 1 via só e pedido de casa: esse papel vai junto com o pedido pro
            // motoboy, então sai tudo nele (itens, valores, endereço e troco).
            escreverViaUnicaCasa(out, pedido)
        } else {
            escreverViaCozinha(out, pedido)
        }

        // Corte do papel só no final
        out.write("\n\n\n".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(GS.toByte(), 'V'.code.toByte(), 0x00))

        return out.toByteArray()
    }

    /**
     * Monta o RESUMO/EXTRATO completo de uma comanda: todos os pedidos feitos
     * naquela mesa, do primeiro ao último, com o total geral no final.
     * Usado quando o garçom pede pra "Imprimir comanda" (não é a impressão
     * automática de cada pedido, é o extrato acumulado inteiro).
     */
    fun montarResumoComanda(comanda: ComandaCompleta): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(ESC.toByte(), '@'.code.toByte()))
        out.write(byteArrayOf(ESC.toByte(), '3'.code.toByte(), 45))

        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1))
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x11))
        escreverLinha(out, comanda.entrega?.titulo() ?: "MESA ${comanda.mesaNumero}")
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00))
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
        escreverLinha(out, "COMANDA COMPLETA")
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))

        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0))
        if (comanda.nomeCliente.isNotBlank()) {
            escreverLinha(out, "Cliente: ${comanda.nomeCliente}")
        }
        if (comanda.telefoneCliente.isNotBlank()) {
            escreverLinha(out, "Tel: ${comanda.telefoneCliente}")
        }
        escreverLinha(out, "-".repeat(LARGURA))

        var totalGeral = 0.0
        for (pedido in comanda.pedidos) {
            escreverLinha(out, "Pedido as ${horaDoPedido(pedido.criadoEm)}")
            totalGeral += escreverItensComPreco(out, pedido.itens, comDetalhes = true, destaque = false)
            escreverLinha(out, "")
        }

        escreverLinha(out, "-".repeat(LARGURA))
        val entrega = comanda.entrega
        if (entrega != null && entrega.taxaEntrega > 0 && comanda.pedidos.isNotEmpty()) {
            escreverLinha(out, montarLinhaComPrecoAlinhado("Subtotal", formatarPreco(totalGeral)))
            escreverLinha(out, montarLinhaComPrecoAlinhado("Taxa de entrega", formatarPreco(entrega.taxaEntrega)))
            totalGeral += entrega.taxaEntrega
        }
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
        escreverLinha(out, montarLinhaComPrecoAlinhado("TOTAL GERAL", formatarPreco(totalGeral)))
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))
        if (entrega != null) {
            escreverLinha(out, "-".repeat(LARGURA))
            escreverDadosEntrega(out, entrega, totalGeral)
        }
        escreverLinha(out, "-".repeat(LARGURA))
        escreverLinha(out, horarioAgora())

        out.write("\n\n\n".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(GS.toByte(), 'V'.code.toByte(), 0x00))

        return out.toByteArray()
    }

    private fun horaDoPedido(criadoEmIso: String): String {
        return try {
            val instant = java.time.Instant.parse(criadoEmIso)
            val zonado = instant.atZone(java.time.ZoneId.systemDefault())
            String.format("%02d:%02d", zonado.hour, zonado.minute)
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Via da cozinha: itens e observações, sem preço — é só pra saber o que fazer.
     * Também sai o nome e telefone do cliente, pra não ter dúvida de quem é.
     */
    private fun escreverViaCozinha(out: ByteArrayOutputStream, pedido: Pedido) {
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1))
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x11))
        escreverLinha(out, pedido.entrega?.titulo() ?: "MESA ${pedido.mesaNumero}")
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00))
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
        escreverLinha(out, "VIA COZINHA")
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))

        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0))
        escreverDadosCliente(out, pedido)
        pedido.entrega?.let { entrega ->
            // Na cozinha sai o endereço também: é dali que o pedido sai pra entrega.
            escreverLinha(out, "-".repeat(LARGURA))
            escreverDadosEntrega(out, entrega, totalComTaxa(pedido))
        }
        escreverLinha(out, "-".repeat(LARGURA))

        escreverUnidadesCozinha(out, expandirUnidades(pedido.itens))

        escreverLinha(out, "-".repeat(LARGURA))
        escreverLinha(out, horarioAgora())
    }

    /**
     * Via única do pedido de casa (quando o restaurante imprime só 1 via):
     * itens com adicionais e preços, total, endereço, pagamento e troco.
     */
    private fun escreverViaUnicaCasa(out: ByteArrayOutputStream, pedido: Pedido) {
        val entrega = pedido.entrega ?: return
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1))
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x11))
        escreverLinha(out, entrega.titulo())
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00))
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
        escreverLinha(out, "VIA ÚNICA")
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))

        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0))
        escreverDadosCliente(out, pedido)
        escreverLinha(out, "-".repeat(LARGURA))

        var total = escreverUnidadesComPreco(out, pedido.itens)

        escreverLinha(out, "-".repeat(LARGURA))
        if (entrega.taxaEntrega > 0) {
            escreverLinha(out, montarLinhaComPrecoAlinhado("Subtotal", formatarPreco(total)))
            escreverLinha(out, montarLinhaComPrecoAlinhado("Taxa de entrega", formatarPreco(entrega.taxaEntrega)))
            total += entrega.taxaEntrega
        }
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
        escreverLinha(out, montarLinhaComPrecoAlinhado("TOTAL", formatarPreco(total)))
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))
        escreverLinha(out, "-".repeat(LARGURA))
        escreverDadosEntrega(out, entrega, total)
        escreverLinha(out, "-".repeat(LARGURA))
        escreverLinha(out, horarioAgora())
    }

    /**
     * Itens com preço alinhado na direita; adicionais embaixo do item ("+ Bacon").
     * Devolve a soma de tudo. [destaque] deixa o nome do item em negrito e alto
     * (pra cozinha ler de longe na via única).
     */
    private fun escreverItensComPreco(
        out: ByteArrayOutputStream,
        itens: List<ItemPedido>,
        comDetalhes: Boolean,
        destaque: Boolean
    ): Double {
        var total = 0.0
        for (grupo in agruparAdicionais(itens)) {
            val item = grupo.item
            val subtotal = item.preco * item.quantidade
            total += subtotal
            if (destaque) {
                out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
                out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x01))
            }
            escreverLinha(out, montarLinhaComPrecoAlinhado("${item.quantidade}x ${item.nome}", formatarPreco(subtotal)))
            if (destaque) {
                out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00))
                out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))
            }
            for (adicional in grupo.adicionais) {
                val subtotalAdicional = adicional.item.preco * adicional.item.quantidade
                total += subtotalAdicional
                escreverLinha(
                    out,
                    montarLinhaComPrecoAlinhado("  ${textoAdicional(adicional)}", formatarPreco(subtotalAdicional))
                )
            }
            if (comDetalhes) {
                for ((chave, valor) in item.variacoes) {
                    escreverLinha(out, "  $chave: $valor")
                }
                if (item.observacao.isNotBlank()) {
                    escreverLinha(out, "  obs: ${item.observacao}")
                }
                if (destaque) escreverLinha(out, "")
            }
        }
        return total
    }

    /** Um lanche avulso do pedido, com o tamanho separado do nome e os adicionais dele. */
    private class Unidade(
        val nome: String,
        val tamanho: String?,
        val item: ItemPedido,
        val adicionais: List<AdicionalUnidade>
    )

    private class AdicionalUnidade(val nome: String, val quantidade: Int, val precoUnitario: Double)

    /**
     * Cada lanche sai numa linha própria (pro chapeiro montar um por um).
     * Os adicionais vão logo abaixo de cada lanche, e o tamanho fica por último.
     */
    private fun expandirUnidades(itens: List<ItemPedido>): List<Unidade> {
        val unidades = mutableListOf<Unidade>()
        for (grupo in agruparAdicionais(itens)) {
            val item = grupo.item
            val (base, tamanho) = separarTamanho(item.nome)
            for (indice in 0 until item.quantidade) {
                val adicionais = mutableListOf<AdicionalUnidade>()
                for (adicional in grupo.adicionais) {
                    val total = adicional.item.quantidade
                    // Um adicional por lanche, até acabar; se sobrar mais que os lanches, vai no primeiro.
                    var quantidade = if (indice < total) 1 else 0
                    if (indice == 0 && total > item.quantidade) quantidade += total - item.quantidade
                    if (quantidade > 0) {
                        adicionais.add(AdicionalUnidade(adicional.nome, quantidade, adicional.item.preco))
                    }
                }
                unidades.add(Unidade(base, tamanho, item, adicionais))
            }
        }
        return unidades
    }

    /** "Pastel de carne - Médio" vira ("Pastel de carne", "Médio"). Sem " - ", o tamanho fica nulo. */
    private fun separarTamanho(nome: String): Pair<String, String?> {
        val corte = nome.lastIndexOf(" - ")
        if (corte <= 0) return nome to null
        val base = nome.substring(0, corte).trim()
        val tamanho = nome.substring(corte + 3).trim()
        if (base.isEmpty() || tamanho.isEmpty()) return nome to null
        return base to tamanho
    }

    private fun textoAdicionalUnidade(adicional: AdicionalUnidade): String =
        "+ ${adicional.nome}" + if (adicional.quantidade > 1) " x${adicional.quantidade}" else ""

    /** Via da cozinha: uma linha por lanche, com adicionais em negrito logo abaixo e o tamanho no fim. */
    private fun escreverUnidadesCozinha(out: ByteArrayOutputStream, unidades: List<Unidade>) {
        for (unidade in unidades) {
            out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
            out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x01))
            escreverLinha(out, "1x ${unidade.nome}")
            out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00))
            out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))

            for ((chave, valor) in unidade.item.variacoes) {
                escreverLinha(out, "  $chave: $valor")
            }
            if (unidade.adicionais.isNotEmpty()) {
                out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
                for (adicional in unidade.adicionais) {
                    escreverLinha(out, "  ${textoAdicionalUnidade(adicional)}")
                }
                out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))
            }
            if (unidade.item.observacao.isNotBlank()) {
                escreverLinha(out, "  obs: ${unidade.item.observacao}")
            }
            unidade.tamanho?.let { escreverLinha(out, "  Tamanho: $it") }
            escreverLinha(out, "")
        }
    }

    /**
     * Via única (pedido de casa): cada lanche com o preço dele, os adicionais com preço
     * logo abaixo e o tamanho por último. Devolve a soma de tudo (lanches + adicionais).
     */
    private fun escreverUnidadesComPreco(out: ByteArrayOutputStream, itens: List<ItemPedido>): Double {
        var total = 0.0
        for (unidade in expandirUnidades(itens)) {
            total += unidade.item.preco
            out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
            out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x01))
            escreverLinha(out, montarLinhaComPrecoAlinhado("1x ${unidade.nome}", formatarPreco(unidade.item.preco)))
            out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00))
            out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))

            for ((chave, valor) in unidade.item.variacoes) {
                escreverLinha(out, "  $chave: $valor")
            }
            for (adicional in unidade.adicionais) {
                val subtotal = adicional.precoUnitario * adicional.quantidade
                total += subtotal
                escreverLinha(
                    out,
                    montarLinhaComPrecoAlinhado("  ${textoAdicionalUnidade(adicional)}", formatarPreco(subtotal))
                )
            }
            if (unidade.item.observacao.isNotBlank()) {
                escreverLinha(out, "  obs: ${unidade.item.observacao}")
            }
            unidade.tamanho?.let { escreverLinha(out, "  Tamanho: $it") }
            escreverLinha(out, "")
        }
        return total
    }

    // ---- Adicionais ----------------------------------------------------------
    // No pedido, cada adicional (bacon, catupiri...) é uma linha própria com a
    // observação "Adicional de: <item>". No papel ele sai embaixo do item, como
    // "+ Bacon", pra não parecer outro item ("1x Extra Bacon").

    private const val PREFIXO_ADICIONAL = "Adicional de: "

    private class AdicionalAgrupado(val item: ItemPedido, val nome: String, val detalhe: String)

    private class ItemAgrupado(val item: ItemPedido, val adicionais: List<AdicionalAgrupado>)

    private fun itemPaiDoAdicional(item: ItemPedido): String? {
        if (!item.observacao.startsWith(PREFIXO_ADICIONAL)) return null
        return item.observacao.removePrefix(PREFIXO_ADICIONAL).trim().ifEmpty { null }
    }

    /** "Extra Bacon - Médio" (do "Pastel de carne - Médio") vira "Bacon". */
    private fun nomeCurtoAdicional(nome: String, nomePai: String): String {
        var curto = nome.replace(Regex("^(extra|adicional)(\\s+de)?\\s+", RegexOption.IGNORE_CASE), "").trim()
        val corte = nomePai.lastIndexOf(" - ")
        if (corte >= 0) {
            val sufixo = nomePai.substring(corte)
            if (curto.lowercase().endsWith(sufixo.lowercase())) curto = curto.dropLast(sufixo.length).trim()
        }
        if (curto.isEmpty()) return nome
        return curto.replaceFirstChar { it.uppercase() }
    }

    /**
     * Junta cada adicional embaixo do item dele. Adicional sem o item no mesmo
     * pedido continua saindo como linha normal (com a observação), pra não sumir.
     */
    private fun agruparAdicionais(itens: List<ItemPedido>): List<ItemAgrupado> {
        val principais = itens.filter { itemPaiDoAdicional(it) == null }
        val adicionaisPorPrincipal = principais.map { mutableListOf<AdicionalAgrupado>() }
        val soltos = mutableListOf<ItemPedido>()
        for (adicional in itens) {
            val nomePai = itemPaiDoAdicional(adicional) ?: continue
            val indice = principais.indexOfFirst { it.nome == nomePai }
            if (indice < 0) {
                soltos.add(adicional)
                continue
            }
            val quantidadePai = principais[indice].quantidade
            val detalhe = when {
                adicional.quantidade == quantidadePai -> ""
                adicional.quantidade < quantidadePai -> "em ${adicional.quantidade}"
                else -> "x${adicional.quantidade}"
            }
            adicionaisPorPrincipal[indice].add(
                AdicionalAgrupado(adicional, nomeCurtoAdicional(adicional.nome, nomePai), detalhe)
            )
        }
        return principais.mapIndexed { i, item -> ItemAgrupado(item, adicionaisPorPrincipal[i]) } +
            soltos.map { ItemAgrupado(it, emptyList()) }
    }

    /** "+ Bacon" ou "+ Bacon (em 2)" quando só parte dos itens leva. */
    private fun textoAdicional(adicional: AdicionalAgrupado): String =
        "+ ${adicional.nome}" + if (adicional.detalhe.isNotEmpty()) " (${adicional.detalhe})" else ""

    /**
     * Via do caixa: itens com preço de cada um, mais o total do pedido — pra cobrança.
     * Também sai o nome e telefone do cliente.
     */
    private fun escreverViaCaixa(out: ByteArrayOutputStream, pedido: Pedido) {
        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 1))
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x11))
        escreverLinha(out, pedido.entrega?.titulo() ?: "MESA ${pedido.mesaNumero}")
        out.write(byteArrayOf(GS.toByte(), '!'.code.toByte(), 0x00))
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
        escreverLinha(out, "VIA CAIXA")
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))

        out.write(byteArrayOf(ESC.toByte(), 'a'.code.toByte(), 0))
        escreverDadosCliente(out, pedido)
        escreverLinha(out, "-".repeat(LARGURA))

        var total = escreverItensComPreco(out, pedido.itens, comDetalhes = false, destaque = false)

        escreverLinha(out, "-".repeat(LARGURA))
        val entrega = pedido.entrega
        if (entrega != null && entrega.taxaEntrega > 0) {
            escreverLinha(out, montarLinhaComPrecoAlinhado("Subtotal", formatarPreco(total)))
            escreverLinha(out, montarLinhaComPrecoAlinhado("Taxa de entrega", formatarPreco(entrega.taxaEntrega)))
            total += entrega.taxaEntrega
        }
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
        escreverLinha(out, montarLinhaComPrecoAlinhado("TOTAL", formatarPreco(total)))
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))
        if (entrega != null) {
            escreverLinha(out, "-".repeat(LARGURA))
            escreverDadosEntrega(out, entrega, total)
        }
        escreverLinha(out, "-".repeat(LARGURA))
        escreverLinha(out, horarioAgora())
    }

    /** Total do pedido de casa: itens + taxa de entrega. */
    private fun totalComTaxa(pedido: Pedido): Double =
        pedido.itens.sumOf { it.preco * it.quantidade } + (pedido.entrega?.taxaEntrega ?: 0.0)

    /**
     * Pedido de casa: endereço (ou retirada), forma de pagamento e quanto levar de troco.
     */
    private fun escreverDadosEntrega(out: ByteArrayOutputStream, entrega: Entrega, total: Double) {
        out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
        if (entrega.ehEntrega) {
            escreverLinha(out, "ENTREGAR EM:")
            out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))
            escreverLinha(out, "${entrega.rua}, ${entrega.numeroCasa}")
            if (entrega.bairro.isNotBlank()) escreverLinha(out, "Bairro: ${entrega.bairro}")
            if (entrega.complemento.isNotBlank()) escreverLinha(out, "Compl.: ${entrega.complemento}")
            if (entrega.referencia.isNotBlank()) escreverLinha(out, "Ref.: ${entrega.referencia}")
        } else {
            escreverLinha(out, "RETIRADA NO BALCÃO")
            out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))
        }
        if (entrega.formaPagamento.isNotBlank()) {
            escreverLinha(out, "Pagamento: ${entrega.rotuloPagamento()}")
            if (entrega.formaPagamento == "dinheiro") {
                val troco = entrega.trocoPara
                if (troco != null && troco > 0) {
                    escreverLinha(out, "Troco para ${formatarPreco(troco)}")
                    out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 1))
                    escreverLinha(out, "LEVAR TROCO: ${formatarPreco((troco - total).coerceAtLeast(0.0))}")
                    out.write(byteArrayOf(ESC.toByte(), 'E'.code.toByte(), 0))
                } else {
                    escreverLinha(out, "Sem troco")
                }
            }
        }
        if (entrega.observacao.isNotBlank()) escreverLinha(out, "Obs.: ${entrega.observacao}")
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
