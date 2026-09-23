package com.mesacerta.impressora

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.OutputStream
import java.util.UUID

/**
 * Cuida da conexão com a impressora térmica via Bluetooth (SPP - Serial Port Profile).
 * Impressoras diferentes (Bematech, Goldensky, e outras genéricas chinesas) às vezes
 * só aceitam uma forma específica de conectar — por isso tentamos várias, em sequência,
 * até uma dar certo. Se todas falharem, a mensagem final conta o que aconteceu em
 * cada tentativa, pra dar pra diagnosticar de longe.
 */
class ImpressoraBluetooth(private val enderecoMac: String) {

    companion object {
        private const val TAG = "MesaCertaImpressora"
        // UUID padrão do perfil serial (SPP) — usado por quase todas as térmicas
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private var socket: BluetoothSocket? = null
    private var saida: OutputStream? = null

    @SuppressLint("MissingPermission")
    fun imprimir(dados: ByteArray): ResultadoImpressao = imprimirVarias(listOf(dados))

    /**
     * Imprime várias "vias" (comandas separadas) numa única conexão com a impressora —
     * usado pra sair a via da cozinha e a via do caixa uma atrás da outra.
     */
    @SuppressLint("MissingPermission")
    fun imprimirVarias(listaDados: List<ByteArray>): ResultadoImpressao {
        val adapter = BluetoothAdapter.getDefaultAdapter()
            ?: return ResultadoImpressao.Erro("Bluetooth não disponível neste aparelho")

        if (!adapter.isEnabled) {
            return ResultadoImpressao.Erro("Bluetooth está desligado")
        }

        val dispositivo: BluetoothDevice = try {
            adapter.getRemoteDevice(enderecoMac)
        } catch (e: IllegalArgumentException) {
            return ResultadoImpressao.Erro("Endereço da impressora inválido")
        }

        return try {
            conectar(adapter, dispositivo)
            for ((indice, dados) in listaDados.withIndex()) {
                saida?.write(dados)
                saida?.flush()
                // Pausa longa entre as vias, pra impressora terminar de cortar o
                // papel sem perder dados da próxima via (não pausa após a última).
                if (indice < listaDados.size - 1) {
                    Thread.sleep(30000)
                }
            }
            ResultadoImpressao.Sucesso
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao imprimir", e)
            ResultadoImpressao.Erro(e.message ?: "Erro desconhecido ao imprimir")
        } finally {
            fechar()
        }
    }

    /**
     * Tenta conectar de várias formas diferentes, na ordem. Impressoras diferentes
     * respondem melhor a métodos diferentes — por isso não paramos na primeira falha.
     */
    @SuppressLint("MissingPermission")
    private fun conectar(adapter: BluetoothAdapter, dispositivo: BluetoothDevice) {
        // Cancelar a busca por outros aparelhos antes de conectar é essencial —
        // se o Android ainda estiver "escutando" o Bluetooth em busca de outros
        // dispositivos, a conexão pode falhar ou travar sem motivo aparente.
        try {
            adapter.cancelDiscovery()
        } catch (e: Exception) {
            Log.w(TAG, "Não consegui cancelar a busca Bluetooth (seguindo mesmo assim)", e)
        }

        val erros = mutableListOf<String>()

        val tentativas: List<Pair<String, () -> BluetoothSocket>> = listOf(
            "conexão segura padrão" to {
                dispositivo.createRfcommSocketToServiceRecord(SPP_UUID)
            },
            "conexão insegura padrão" to {
                dispositivo.createInsecureRfcommSocketToServiceRecord(SPP_UUID)
            },
            "canal reflexivo 1" to {
                criarSocketPorCanal(dispositivo, 1)
            },
            "canal reflexivo 2" to {
                criarSocketPorCanal(dispositivo, 2)
            },
            "canal reflexivo 3" to {
                criarSocketPorCanal(dispositivo, 3)
            }
        )

        for ((nomeTentativa, criarSocket) in tentativas) {
            try {
                val novoSocket = criarSocket()
                novoSocket.connect()
                // Deu certo — usa esse socket e para de tentar
                socket = novoSocket
                saida = socket?.outputStream
                Log.i(TAG, "Conectou usando: $nomeTentativa")
                return
            } catch (e: Exception) {
                val motivo = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "Tentativa \"$nomeTentativa\" falhou: $motivo", e)
                erros.add("$nomeTentativa: $motivo")
                // Pequena pausa entre tentativas — dá tempo do rádio Bluetooth "descansar"
                try {
                    Thread.sleep(400)
                } catch (ignorado: InterruptedException) { /* ignora */ }
            }
        }

        // Todas as tentativas falharam — a mensagem final mostra TODAS as causas,
        // pra dar pra diagnosticar de longe.
        throw Exception(
            "Não consegui conectar à impressora depois de ${tentativas.size} tentativas. " +
                "Confira se ela está ligada, pareada e com bateria. Detalhes: " +
                erros.joinToString(" | ")
        )
    }

    /**
     * Cria um socket RFCOMM apontando direto pra um canal específico, via reflexão —
     * usado como alternativa quando o método padrão (por UUID) não funciona, o que é
     * comum em impressoras térmicas genéricas/baratas.
     */
    private fun criarSocketPorCanal(dispositivo: BluetoothDevice, canal: Int): BluetoothSocket {
        val metodo = dispositivo.javaClass.getMethod(
            "createRfcommSocket", Int::class.javaPrimitiveType
        )
        return metodo.invoke(dispositivo, canal) as BluetoothSocket
    }

    private fun fechar() {
        try {
            saida?.close()
            socket?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Erro ao fechar conexão", e)
        }
        saida = null
        socket = null
    }
}

sealed class ResultadoImpressao {
    object Sucesso : ResultadoImpressao()
    data class Erro(val mensagem: String) : ResultadoImpressao()
}
