package com.mesacerta.impressora

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.net.Uri
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Tela principal do app:
 * - Escolhe a impressora Bluetooth (das já pareadas)
 * - Digita o "slug" do restaurante (ex: boteco-do-ze)
 * - Liga/desliga o serviço de impressão automática
 */
class MainActivity : AppCompatActivity() {

    private lateinit var spinnerImpressora: Spinner
    private lateinit var campoSlug: EditText
    private lateinit var botaoLigar: Button
    private lateinit var textoStatus: TextView
    private lateinit var textoAvisoBateria: TextView

    private var dispositivosPareados = listOf<Pair<String, String>>() // nome to mac
    private val handler = Handler(Looper.getMainLooper())

    private val permissoesLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { carregarImpressoras() }

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        spinnerImpressora = findViewById(R.id.spinnerImpressora)
        campoSlug = findViewById(R.id.campoSlug)
        botaoLigar = findViewById(R.id.botaoLigar)
        textoStatus = findViewById(R.id.textoStatus)
        textoAvisoBateria = findViewById(R.id.textoAvisoBateria)

        val prefs = getSharedPreferences(Config.PREF_NOME, Context.MODE_PRIVATE)
        campoSlug.setText(prefs.getString(Config.PREF_RESTAURANTE_SLUG, ""))

        botaoLigar.setOnClickListener { alternarServico() }

        findViewById<Button>(R.id.botaoBateria).setOnClickListener {
            abrirConfigBateria()
        }

        pedirPermissoes()
        atualizarBotao()
        iniciarAtualizacaoStatus()
    }

    private fun pedirPermissoes() {
        val permissoes = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissoes.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissoes.add(Manifest.permission.BLUETOOTH_SCAN)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissoes.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        val faltando = permissoes.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (faltando.isNotEmpty()) {
            permissoesLauncher.launch(faltando.toTypedArray())
        } else {
            carregarImpressoras()
        }
    }

    @SuppressLint("MissingPermission")
    private fun carregarImpressoras() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            Toast.makeText(this, "Este aparelho não tem Bluetooth", Toast.LENGTH_LONG).show()
            return
        }
        if (!adapter.isEnabled) {
            Toast.makeText(this, "Ligue o Bluetooth e reabra o app", Toast.LENGTH_LONG).show()
            return
        }

        dispositivosPareados = adapter.bondedDevices.map { it.name to it.address }
        val nomes = dispositivosPareados.map { it.first }

        if (nomes.isEmpty()) {
            Toast.makeText(
                this,
                "Nenhuma impressora pareada. Pareie primeiro nas configurações do Bluetooth.",
                Toast.LENGTH_LONG
            ).show()
        }

        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, nomes)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerImpressora.adapter = spinnerAdapter

        // Restaura a impressora salva
        val prefs = getSharedPreferences(Config.PREF_NOME, Context.MODE_PRIVATE)
        val macSalvo = prefs.getString(Config.PREF_IMPRESSORA_MAC, "")
        val idx = dispositivosPareados.indexOfFirst { it.second == macSalvo }
        if (idx >= 0) spinnerImpressora.setSelection(idx)
    }

    private fun alternarServico() {
        if (ServicoImpressao.rodando) {
            pararServico()
        } else {
            ligarServico()
        }
    }

    private fun ligarServico() {
        val slug = campoSlug.text.toString().trim()
        if (slug.isBlank()) {
            Toast.makeText(this, "Digite o nome do restaurante (slug)", Toast.LENGTH_SHORT).show()
            return
        }
        if (dispositivosPareados.isEmpty()) {
            Toast.makeText(this, "Nenhuma impressora selecionada", Toast.LENGTH_SHORT).show()
            return
        }

        val pos = spinnerImpressora.selectedItemPosition
        if (pos < 0 || pos >= dispositivosPareados.size) return
        val (nome, mac) = dispositivosPareados[pos]

        // Salva a configuração
        getSharedPreferences(Config.PREF_NOME, Context.MODE_PRIVATE).edit().apply {
            putString(Config.PREF_IMPRESSORA_MAC, mac)
            putString(Config.PREF_IMPRESSORA_NOME, nome)
            putString(Config.PREF_RESTAURANTE_SLUG, slug)
            putBoolean(Config.PREF_SERVICO_ATIVO, true)
            apply()
        }

        val servico = Intent(this, ServicoImpressao::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(servico)
        } else {
            startService(servico)
        }

        Toast.makeText(this, "Sistema de impressão ligado!", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ atualizarBotao() }, 500)
    }

    private fun pararServico() {
        getSharedPreferences(Config.PREF_NOME, Context.MODE_PRIVATE).edit()
            .putBoolean(Config.PREF_SERVICO_ATIVO, false).apply()
        stopService(Intent(this, ServicoImpressao::class.java))
        Toast.makeText(this, "Sistema de impressão desligado", Toast.LENGTH_SHORT).show()
        handler.postDelayed({ atualizarBotao() }, 500)
    }

    private fun atualizarBotao() {
        if (ServicoImpressao.rodando) {
            botaoLigar.text = "DESLIGAR IMPRESSÃO"
            botaoLigar.setBackgroundColor(0xFFB03030.toInt())
        } else {
            botaoLigar.text = "LIGAR IMPRESSÃO"
            botaoLigar.setBackgroundColor(0xFF1F3B2C.toInt())
        }
    }

    private fun iniciarAtualizacaoStatus() {
        val runnable = object : Runnable {
            override fun run() {
                textoStatus.text = if (ServicoImpressao.rodando) {
                    "● ${ServicoImpressao.ultimoStatus}"
                } else {
                    "○ Desligado"
                }
                atualizarBotao()
                handler.postDelayed(this, 2000)
            }
        }
        handler.post(runnable)
    }

    @SuppressLint("BatteryLife")
    private fun abrirConfigBateria() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            intent.data = Uri.parse("package:$packageName")
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }
}
