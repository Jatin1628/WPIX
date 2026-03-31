package com.surendramaran.yolov9tflite.camera

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.surendramaran.yolov9tflite.R

class DeviceConnectionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_device_connection)

        val bluetoothButton: Button = findViewById(R.id.bluetoothButton)
        val wifiButton: Button = findViewById(R.id.wifiButton)

        bluetoothButton.setOnClickListener {
            handleBluetoothConnection()
        }

        wifiButton.setOnClickListener {
            handleWifiConnection()
        }
    }

    private fun handleBluetoothConnection() {
        val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()

        if (bluetoothAdapter == null) {
            showAlert("Error", "Bluetooth is not available on this device.")
            return
        }

        if (bluetoothAdapter.isEnabled) {
            showAlert("Info", "Bluetooth is already enabled.")
            return
        }

        showConfirmationDialog(
            "Turn on Bluetooth?",
            "Would you like to turn on Bluetooth?",
            onYes = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Check for BLUETOOTH_CONNECT permission on Android 12+
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        bluetoothAdapter.enable()
                        showAlert("Success", "Bluetooth is being turned on.")
                    } else {
                        requestPermissions(
                            arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                            BLUETOOTH_PERMISSION_REQUEST_CODE
                        )
                    }
                } else {
                    bluetoothAdapter.enable()
                    showAlert("Success", "Bluetooth is being turned on.")
                }
            }
        )
    }

    private fun handleWifiConnection() {
        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        if (wifiManager.isWifiEnabled) {
            showAlert("Info", "Wifi is already enabled.")
            return
        }

        showConfirmationDialog(
            "Turn on Wifi?",
            "Would you like to turn on Wifi?",
            onYes = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    // On Android 10 and above, we need to open settings instead
                    val intent = Intent(Intent.ACTION_MAIN)
                    intent.setClassName(
                        "com.android.settings",
                        "com.android.settings.wifi.WifiSettings"
                    )
                    startActivity(intent)
                    showAlert("Info", "Please enable Wifi from settings.")
                } else {
                    wifiManager.isWifiEnabled = true
                    showAlert("Success", "Wifi is being turned on.")
                }
            }
        )
    }

    private fun showConfirmationDialog(
        title: String,
        message: String,
        onYes: () -> Unit
    ) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Yes") { _, _ -> onYes() }
            .setNegativeButton("No", null)
            .show()
    }

    private fun showAlert(title: String, message: String) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == BLUETOOTH_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(
                            this,
                            Manifest.permission.BLUETOOTH_CONNECT
                        ) == PackageManager.PERMISSION_GRANTED
                    ) {
                        bluetoothAdapter?.enable()
                        showAlert("Success", "Bluetooth is being turned on.")
                    }
                }
            } else {
                showAlert("Permission Denied", "Bluetooth permission is required to enable Bluetooth.")
            }
        }
    }

    companion object {
        private const val BLUETOOTH_PERMISSION_REQUEST_CODE = 1
    }
}
