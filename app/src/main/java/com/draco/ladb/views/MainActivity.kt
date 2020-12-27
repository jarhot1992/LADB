package com.draco.ladb.views

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import android.widget.ScrollView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.*
import com.draco.ladb.BuildConfig
import com.draco.ladb.R
import com.draco.ladb.models.ProcessInfo
import com.draco.ladb.utils.ADB
import com.draco.ladb.viewmodels.MainActivityViewModel
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textview.MaterialTextView
import kotlinx.coroutines.*
import java.io.File
import java.util.concurrent.CountDownLatch

class MainActivity : AppCompatActivity() {
    private lateinit var viewModel: MainActivityViewModel
    private lateinit var adb: ADB

    /* UI components */
    private lateinit var command: TextInputEditText
    private lateinit var output: MaterialTextView
    private lateinit var outputScrollView: ScrollView
    private lateinit var progress: ProgressBar

    /* Alert dialogs */
    private lateinit var helpDialog: MaterialAlertDialogBuilder
    private lateinit var pairDialog: MaterialAlertDialogBuilder

    /* Latch that gets decremented after user provides pairing port and code */
    private val pairingInfoLatch = CountDownLatch(1)

    /* Coroutines */
    private lateinit var outputThreadJob: Job

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        viewModel = ViewModelProvider(this).get(MainActivityViewModel::class.java)
        adb = ADB(this)

        command = findViewById(R.id.command)
        output = findViewById(R.id.output)
        outputScrollView = findViewById(R.id.output_scrollview)
        progress = findViewById(R.id.progress)

        viewModel.getCommandString().observe(this, Observer {
            command.setText(it)
        })

        viewModel.getOutputString().observe(this, Observer {
            output.text = it
        })

        helpDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_message)
            .setPositiveButton(R.string.dismiss, null)
            .setNegativeButton(R.string.reset) { _, _ ->
                progress.visibility = View.VISIBLE
                command.isEnabled = false

                lifecycleScope.launch(Dispatchers.IO) {
                    adb.reset()
                    with (getPreferences(MODE_PRIVATE).edit()) {
                        putBoolean("paired", false)
                        apply()
                    }
                    adb.debug("Exiting in three seconds")

                    Thread.sleep(3000)
                    finishAffinity()
                }
            }

        pairDialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.pair_title)
            .setMessage(R.string.pair_message)
            .setCancelable(false)
            .setView(R.layout.dialog_pair)

        command.setOnKeyListener { _, keyCode, event ->
            viewModel.setCommandString(command.text.toString())
            if (keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN) {
                val text = viewModel.getCommandString().value ?: return@setOnKeyListener true
                viewModel.setCommandString("")

                lifecycleScope.launch(Dispatchers.IO) {
                    adb.sendToAdbShellProcess(text)
                }

                return@setOnKeyListener true
            }

            return@setOnKeyListener false
        }

        /* Prepare client */
        initializeClient {
            /* If we started from a shell script, launch it after client init */
            if (intent.type == "text/plain" || intent.type == "text/x-sh")
                executeFromScript()
        }

        with (getPreferences(Context.MODE_PRIVATE)) {
            if (getBoolean("firstLaunch", true)) {
                helpDialog.show()

                with (edit()) {
                    putBoolean("firstLaunch", false)
                    apply()
                }
            }
        }
    }

    private fun executeFromScript() {
        val script = when (intent.type) {
            "text/x-sh" -> {
                val uri = Uri.parse(intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM).toString())
                contentResolver.openInputStream(uri)?.bufferedReader().use {
                    it?.readText()
                }
            }
            "text/plain" -> intent.getStringExtra(Intent.EXTRA_TEXT)
            else -> null
        } ?: return

        /* Store script locally */
        val scriptPath = "${getExternalFilesDir(null)}/script.sh"
        val internalScript = File(scriptPath).apply {
            bufferedWriter().use {
                it.write(script)
            }
            deleteOnExit()
        }

        Snackbar.make(output, getString(R.string.snackbar_file_opened), Snackbar.LENGTH_SHORT)
            .setAction(getString(R.string.dismiss)) {}
            .show()

        /* Execute the script here */
        adb.sendToAdbShellProcess("sh ${internalScript.absolutePath}")
    }

    private fun readEndOfFile(file: File): String {
        val out = ByteArray(ProcessInfo.MAX_OUTPUT_BUFFER_SIZE)
        file.inputStream().use {
            val size = it.channel.size()

            if (size <= out.size)
                return String(it.readBytes())

            val newPos = (it.channel.size() - out.size)
            it.channel.position(newPos)
            it.read(out)
        }

        return String(out)
    }

    private fun startOutputFeed() {
        outputThreadJob = lifecycleScope.launch(Dispatchers.IO) {
            while (isActive) {
                val out = readEndOfFile(adb.outputBufferFile)
                val currentText = viewModel.getOutputString().value
                if (out != currentText) {
                    runOnUiThread {
                        viewModel.setOutputString(out)
                        outputScrollView.post {
                            outputScrollView.fullScroll(ScrollView.FOCUS_DOWN)
                        }
                    }
                }
                Thread.sleep(ProcessInfo.OUTPUT_BUFFER_DELAY_MS)
            }
        }
    }

    private fun initializeClient(callback: Runnable? = null) {
        progress.visibility = View.VISIBLE
        command.isEnabled = false

        lifecycleScope.launch(Dispatchers.IO) {
            /* Begin forwarding output buffer text to output view */
            startOutputFeed()

            /* If we have not been paried yet, do so now */
            if (!getPreferences(MODE_PRIVATE).getBoolean("paired", false)) {
                /* SDK 30+ need to pair to the device using a new method */
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    adb.debug("Requesting pairing information")
                    runOnUiThread {
                        handlePairing()
                    }

                    /* Wait for backend pairing to finish */
                    pairingInfoLatch.await()
                }
            }

            adb.initializeClient()

            with (getPreferences(MODE_PRIVATE).edit()) {
                putBoolean("paired", true)
                apply()
            }

            runOnUiThread {
                command.isEnabled = true
                progress.visibility = View.INVISIBLE
            }

            callback?.run()

            adb.shellProcess.waitFor()
            adb.debug("Shell has died")

            runOnUiThread {
                command.isEnabled = false
            }
        }
    }

    private fun handlePairing() {
        pairDialog
            .create()
            .apply {
                setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.okay)) { _, _ ->
                    val port = findViewById<TextInputEditText>(R.id.port)!!.text.toString()
                    val code = findViewById<TextInputEditText>(R.id.code)!!.text.toString()

                    lifecycleScope.launch(Dispatchers.IO) {
                        adb.debug("Requesting additional pairing information")
                        adb.pair(port, code)
                    }
                }
            }
            .show()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.help -> {
                helpDialog.show()
                true
            }
            R.id.share -> {
                try {
                    val uri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".provider", adb.outputBufferFile)
                    val intent = Intent(Intent.ACTION_SEND)
                    with (intent) {
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        putExtra(Intent.EXTRA_STREAM, uri)
                        type = "file/*"
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Snackbar.make(output, getString(R.string.snackbar_intent_failed), Snackbar.LENGTH_SHORT)
                        .setAction(getString(R.string.dismiss)) {}
                        .show()
                }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menuInflater.inflate(R.menu.main, menu)
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        outputThreadJob.cancel()
    }
}