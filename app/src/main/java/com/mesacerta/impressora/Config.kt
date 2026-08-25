package com.mesacerta.impressora

/**
 * Configurações do app.
 * Usa APENAS a chave pública (publishable/anon) do Supabase — segura para apps.
 * A chave secreta (service_role) NUNCA deve aparecer aqui.
 */
object Config {
    // URL do projeto Supabase (mesma do sistema)
    const val SUPABASE_URL = "https://hmfeoaegthnvmwbqxnlq.supabase.co"

    // Chave PÚBLICA — feita para ficar exposta, protegida pelas regras RLS do banco
    const val SUPABASE_ANON_KEY = "sb_publishable_Is-JXRzn92wqFRFH_FbYYA_ymd96_ZM"

    // Nomes das chaves salvas no celular (preferências locais)
    const val PREF_NOME = "mesacerta_impressora"
    const val PREF_IMPRESSORA_MAC = "impressora_mac"
    const val PREF_IMPRESSORA_NOME = "impressora_nome"
    const val PREF_RESTAURANTE_SLUG = "restaurante_slug"
    const val PREF_SERVICO_ATIVO = "servico_ativo"

    // Canal de notificação do serviço em primeiro plano
    const val CANAL_ID = "mesacerta_impressao"
    const val NOTIF_ID = 1001
}
