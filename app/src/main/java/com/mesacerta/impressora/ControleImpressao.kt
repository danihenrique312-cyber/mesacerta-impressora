package com.mesacerta.impressora

/**
 * Regras da impressão automática: quando tentar de novo, quando desistir e o que já
 * foi resolvido. Não usa nada do Android, então dá pra testar isolado.
 *
 * A consulta (que decide o que imprimir) e a impressão (que roda em outra thread)
 * usam este objeto ao mesmo tempo, por isso todos os métodos são sincronizados.
 */
class ControleImpressao(private val relogio: () -> Long = { System.currentTimeMillis() }) {

    companion object {
        /** Tentativas de impressão antes de desistir. Depois disso o pedido fica "sem impressão" no painel. */
        const val MAX_TENTATIVAS = 6

        /** Espera (em ms) depois da 1ª, 2ª, 3ª... falha. Depois da última tentativa, desiste. */
        private val ESPERAS_MS = longArrayOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L)

        /** Quantos pedidos desistidos mandar de volta ao servidor (ele aceita até 100). */
        const val MAX_IGNORADOS = 100

        // Limite de memória para as listas que só crescem (pedidos já resolvidos ou desistidos).
        private const val MAX_LEMBRADOS = 1000
    }

    /** O que fazer depois de uma falha: tentar de novo, ou desistir. */
    sealed class DepoisDaFalha {
        /** Tenta de novo. Esta foi a `tentativa`-ésima falha; a próxima sai daqui a `esperaMs`. */
        class Tentar(val tentativa: Int, val esperaMs: Long) : DepoisDaFalha()

        /** Desiste: já gastou todas as tentativas. */
        class Desistir(val tentativas: Int) : DepoisDaFalha()
    }

    private class Estado {
        var falhas = 0
        var proximaEm = 0L
        var naFila = false
        var impresso = false
        var avisouSemConfirmar = false
    }

    private val estados = HashMap<String, Estado>()
    private val desistidos = LinkedHashSet<String>()
    private val concluidos = LinkedHashSet<String>()

    /** Pode agendar agora a impressão deste pedido? */
    @Synchronized
    fun podeAgendar(id: String): Boolean {
        if (id in desistidos || id in concluidos) return false
        val estado = estados[id] ?: return true
        return !estado.naFila && relogio() >= estado.proximaEm
    }

    /** O pedido entrou na fila de impressão (para não entrar duas vezes ao mesmo tempo). */
    @Synchronized
    fun agendado(id: String) {
        val estado = estados.getOrPut(id) { Estado() }
        estado.naFila = true
    }

    /** A rodada de impressão deste pedido terminou (com ou sem sucesso). */
    @Synchronized
    fun liberar(id: String) {
        estados[id]?.naFila = false
    }

    /** O pedido já saiu na impressora; falta só o servidor confirmar. */
    @Synchronized
    fun jaImpresso(id: String): Boolean = estados[id]?.impresso == true

    /** Acabou de sair na impressora. */
    @Synchronized
    fun marcarImpresso(id: String) {
        val estado = estados.getOrPut(id) { Estado() }
        estado.impresso = true
    }

    /**
     * O servidor confirmou que saiu. Não precisa mais de nada. Fica lembrado para nunca
     * ser impresso de novo, mesmo que a fila mostre o pedido por mais um instante.
     */
    @Synchronized
    fun concluir(id: String) {
        estados.remove(id)
        concluidos.add(id)
        while (concluidos.size > MAX_LEMBRADOS) concluidos.remove(concluidos.first())
    }

    /**
     * Não deu para confirmar no servidor. O pedido já saiu, então só repete a confirmação
     * daqui a pouco, sem imprimir de novo. Devolve true só na primeira vez (para avisar uma vez).
     */
    @Synchronized
    fun adiarConfirmacao(id: String, esperaMs: Long = 30_000L): Boolean {
        val estado = estados.getOrPut(id) { Estado() }
        estado.impresso = true
        estado.proximaEm = relogio() + esperaMs
        val primeiraVez = !estado.avisouSemConfirmar
        estado.avisouSemConfirmar = true
        return primeiraVez
    }

    /** Uma tentativa falhou. Diz se vale tentar de novo (e quando) ou se desiste. */
    @Synchronized
    fun registrarFalha(id: String): DepoisDaFalha {
        val estado = estados.getOrPut(id) { Estado() }
        estado.falhas++
        if (estado.falhas >= MAX_TENTATIVAS) {
            estados.remove(id)
            desistidos.add(id)
            while (desistidos.size > MAX_LEMBRADOS) desistidos.remove(desistidos.first())
            return DepoisDaFalha.Desistir(estado.falhas)
        }
        val espera = ESPERAS_MS[minOf(estado.falhas - 1, ESPERAS_MS.size - 1)]
        estado.proximaEm = relogio() + espera
        return DepoisDaFalha.Tentar(estado.falhas, espera)
    }

    /** Pedidos desistidos (o servidor deixa de mandar esses, para não ocuparem a fila). */
    @Synchronized
    fun ignorados(): List<String> = desistidos.toList().takeLast(MAX_IGNORADOS)
}

/**
 * Data e hora em UTC, no formato que o servidor entende. Não usa java.time,
 * para funcionar também em aparelhos com Android mais antigo.
 */
fun formatarIsoUtc(ms: Long): String {
    val formato = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US)
    formato.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return formato.format(java.util.Date(ms))
}
