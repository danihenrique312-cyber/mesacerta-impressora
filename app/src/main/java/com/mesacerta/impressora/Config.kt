package com.mesacerta.impressora

/**
 * Configurações do app.
 * Nenhuma chave do banco fica no app: o acesso é pelo código secreto do restaurante.
 */
object Config {
    // Endereço do servidor MesaCerta. O app só fala com o servidor (rota /api/impressora),
    // sempre com o slug + o código secreto do restaurante — nunca direto com o banco.
    const val API_BASE = "https://sistema-cardapio-theta.vercel.app"

    // Nomes das chaves salvas no celular (preferências locais)
    const val PREF_NOME = "mesacerta_impressora"
    const val PREF_IMPRESSORA_MAC = "impressora_mac"
    const val PREF_IMPRESSORA_NOME = "impressora_nome"
    const val PREF_RESTAURANTE_SLUG = "restaurante_slug"
    const val PREF_CHAVE_EQUIPE = "chave_equipe"
    const val PREF_SERVICO_ATIVO = "servico_ativo"

    // Canal de notificação do serviço em primeiro plano
    const val CANAL_ID = "mesacerta_impressao"
    const val NOTIF_ID = 1001
}
