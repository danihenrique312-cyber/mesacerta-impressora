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
 * A maioria das térmicas ESC/POS, incluindo Bematech, usa esse perfil padrão.
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

        // Tenta conectar (com uma segunda tentativa via método reflexivo se a primeira falhar)
        return try {
            conectar(dispositivo)
            for (dados in listaDados) {
                saida?.write(dados)
                saida?.flush()
                // Pequena pausa pra impressora processar/cortar antes da próxima via
                Thread.sleep(1800)
            }
            ResultadoImpressao.Sucesso
        } catch (e: Exception) {
            Log.e(TAG, "Falha ao imprimir", e)
            ResultadoImpressao.Erro(e.message ?: "Erro desconhecido ao imprimir")
        } finally {
            fechar()
        }
    }

    @SuppressLint("MissingPermission")
    private fun conectar(dispositivo: BluetoothDevice) {
        try {
            socket = dispositivo.createRfcommSocketToServiceRecord(SPP_UUID)
            socket?.connect()
        } catch (e: Exception) {
            // Método alternativo: algumas térmicas baratas precisam desse "truque"
            Log.w(TAG, "Conexão padrão falhou, tentando método alternativo", e)
            try {
                val metodo = dispositivo.javaClass.getMethod(
                    "createRfcommSocket", Int::class.javaPrimitiveType
                )
                socket = metodo.invoke(dispositivo, 1) as BluetoothSocket
                socket?.connect()
            } catch (e2: Exception) {
                throw Exception("Não consegui conectar à impressora. Confira se ela está ligada e pareada.")
            }
        }
        saida = socket?.outputStream
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
