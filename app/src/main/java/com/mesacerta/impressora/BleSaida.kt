package com.mesacerta.impressora

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Canal de envio via Bluetooth de baixa energia (BLE/GATT).
 * Muitas impressoras térmicas genéricas só aceitam esse modo (é o que o RawBT usa
 * nelas). Funciona como um OutputStream pra se encaixar no resto do código.
 */
@SuppressLint("MissingPermission")
class BleSaida private constructor(
    private val gatt: BluetoothGatt,
    private val caracteristica: BluetoothGattCharacteristic,
    private val estado: Estado,
    private val tamanhoBloco: Int
) : OutputStream() {

    /** Estado compartilhado com os callbacks do Android. */
    class Estado {
        @Volatile var conectado: CountDownLatch = CountDownLatch(1)
        @Volatile var servicos: CountDownLatch = CountDownLatch(1)
        @Volatile var mtu: CountDownLatch = CountDownLatch(1)
        @Volatile var escrita: CountDownLatch = CountDownLatch(1)
        @Volatile var mtuAtual: Int = 23
        @Volatile var statusConexao: Int = -1
        @Volatile var desconectou = false
        @Volatile var statusServicos: Int = -1
    }

    override fun write(b: Int) = write(byteArrayOf(b.toByte()), 0, 1)

    override fun write(b: ByteArray, off: Int, len: Int) {
        var pos = off
        val fim = off + len
        while (pos < fim) {
            if (estado.desconectou) throw java.io.IOException("Impressora desconectou durante o envio (BLE)")
            val n = minOf(tamanhoBloco, fim - pos)
            val pedaco = b.copyOfRange(pos, pos + n)
            estado.escrita = CountDownLatch(1)
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(caracteristica, pedaco, caracteristica.writeType) ==
                    BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                caracteristica.value = pedaco
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(caracteristica)
            }
            if (!ok) throw java.io.IOException("O Android recusou o envio de dados (BLE)")
            estado.escrita.await(1500, TimeUnit.MILLISECONDS)
            Thread.sleep(15)
            pos += n
        }
    }

    override fun close() {
        try { gatt.disconnect() } catch (_: Exception) {}
        try { gatt.close() } catch (_: Exception) {}
    }

    companion object {
        private const val TAG = "MesaCertaBLE"

        // Características de escrita conhecidas de impressoras térmicas (testadas primeiro)
        private val PREFERIDAS = listOf(
            "49535343-8841-43f4-a8d4-ecbe34729bb3",
            "0000ff02-0000-1000-8000-00805f9b34fb",
            "0000ffe1-0000-1000-8000-00805f9b34fb",
            "00002af1-0000-1000-8000-00805f9b34fb",
            "bef8d6c9-9c21-4c9e-b632-bd58c1009f9f"
        ).map { UUID.fromString(it) }

        // Serviços padrão do Android/Bluetooth que nunca são a impressora
        private val IGNORADOS = listOf("1800", "1801", "180a", "180f").map {
            UUID.fromString("0000$it-0000-1000-8000-00805f9b34fb")
        }

        /** Conecta por BLE e devolve o canal pronto, ou lança exceção explicando o motivo. */
        fun abrir(contexto: Context, dispositivo: BluetoothDevice): BleSaida {
            val estado = Estado()
            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    estado.statusConexao = status
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        estado.conectado.countDown()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        estado.desconectou = true
                        estado.conectado.countDown()
                        estado.servicos.countDown()
                        estado.mtu.countDown()
                        estado.escrita.countDown()
                    }
                }
                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    estado.statusServicos = status
                    estado.servicos.countDown()
                }
                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    if (status == BluetoothGatt.GATT_SUCCESS) estado.mtuAtual = mtu
                    estado.mtu.countDown()
                }
                override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
                    estado.escrita.countDown()
                }
            }

            val gatt = if (Build.VERSION.SDK_INT >= 23) {
                dispositivo.connectGatt(contexto, false, callback, BluetoothDevice.TRANSPORT_LE)
            } else {
                dispositivo.connectGatt(contexto, false, callback)
            } ?: throw java.io.IOException("O Android não abriu a conexão BLE")

            try {
                if (!estado.conectado.await(12, TimeUnit.SECONDS) || estado.desconectou) {
                    throw java.io.IOException("não conectou (código ${estado.statusConexao})")
                }
                if (Build.VERSION.SDK_INT >= 21) {
                    gatt.requestMtu(247)
                    estado.mtu.await(2, TimeUnit.SECONDS)
                }
                if (!gatt.discoverServices()) throw java.io.IOException("não consegui listar os serviços")
                if (!estado.servicos.await(10, TimeUnit.SECONDS) || estado.statusServicos != BluetoothGatt.GATT_SUCCESS) {
                    throw java.io.IOException("não listou os serviços (código ${estado.statusServicos})")
                }

                val todas = gatt.services
                    .filter { it.uuid !in IGNORADOS }
                    .flatMap { it.characteristics }
                    .filter {
                        it.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
                    }
                todas.forEach { Log.i(TAG, "Característica de escrita: ${it.uuid}") }

                val escolhida = PREFERIDAS.firstNotNullOfOrNull { u -> todas.firstOrNull { it.uuid == u } }
                    ?: todas.firstOrNull()
                    ?: throw java.io.IOException("a impressora não tem canal de escrita BLE")

                // Prefere "sem resposta" (mais rápido) quando a característica aceita
                escolhida.writeType =
                    if (escolhida.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0)
                        BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

                val bloco = (estado.mtuAtual - 3).coerceIn(20, 180)
                return BleSaida(gatt, escolhida, estado, bloco)
            } catch (e: Exception) {
                try { gatt.disconnect() } catch (_: Exception) {}
                try { gatt.close() } catch (_: Exception) {}
                throw e
            }
        }
    }
}
