package com.github.airpodsbattery

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.TextView
import androidx.core.app.ActivityCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class MainActivity : AppCompatActivity() {

    val scanSettings = ScanSettings.Builder()
        .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
        .setReportDelay(2)
        .build()

    val scanFilters: List<ScanFilter> get() {
        val manufacturerData = ByteArray(27)
        val manufacturerDataMask = ByteArray(27)
        manufacturerData[0] = 7
        manufacturerData[1] = 25
        manufacturerDataMask[0] = -1
        manufacturerDataMask[1] = -1
        val builder = ScanFilter.Builder()
        builder.setManufacturerData(76, manufacturerData, manufacturerDataMask)
        return listOf(builder.build())
    }

    val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult?) {
            if (result == null) {
                return
            }
            log("Scan result:")
            log("\tcallbackType: $callbackType")
            log("\tresult: $result")
            decodeManufacturerData(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>?) {
            log("Got batch scan results.")
            if (results == null) {
                return
            }
            for (result in results){
                onScanResult(-1, result)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            log("Scan failed:")
            log("\terrorCode: $errorCode")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        textView().movementMethod = ScrollingMovementMethod()
        refreshBtn().setOnClickListener {
            start()
        }
        start()
    }

    override fun onDestroy() {
        super.onDestroy()
        bluetoothScanner().stopScan(scanCallback)
    }

    fun textView(): TextView {
        return findViewById(R.id.text_view)
    }

    fun refreshBtn(): FloatingActionButton {
        return findViewById(R.id.refresh_fab)
    }

    fun start() {
        bluetoothScanner().stopScan(scanCallback)
        textView().text = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        log("Starting...")
        if (!checkBluetoothEnabled()) {
            return
        }
        if (!checkPermissions()) {
            return
        }
        startScan()
    }

    fun log(msg: String) {
        runOnUiThread {
            textView().append("\n")
            textView().append(msg)
        }
    }

    fun bluetoothManager(): BluetoothManager {
        return  applicationContext.getSystemService(BluetoothManager::class.java)
    }

    fun bluetoothAdapter(): BluetoothAdapter {
        return bluetoothManager().adapter
    }

    fun bluetoothScanner(): BluetoothLeScanner {
        return bluetoothAdapter().bluetoothLeScanner
    }

    fun checkBluetoothEnabled(): Boolean {
        return if (bluetoothAdapter().isEnabled) {
            log("Bluetooth enabled.")
            true
        } else {
            log("Bluetooth disabled.")
            false
        }
    }

    fun checkPermissions(): Boolean {
        log("Checking permission...")
        val permissions = listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        var allPermissionsOk = true
        for (permission in permissions) {
            var status: String
            val granted = ActivityCompat.checkSelfPermission(applicationContext, permission) == PackageManager.PERMISSION_GRANTED
            if (granted) {
                status = "OK"
            } else {
                status = "FAIL"
                allPermissionsOk = false
            }
            log("\t${permission.substringAfter("android.permission.")}: $status")
        }
        return allPermissionsOk
    }

    fun startScan() {
        log("Bonded devices:")
        for (device in bluetoothAdapter().bondedDevices) {
            log("\t${device.name} (${device.address})")
        }
        log("Start scanning....")
        bluetoothScanner().startScan(scanFilters, scanSettings, scanCallback)
    }

    fun decodeManufacturerData(scanResult: ScanResult) {
        //The beacon coming from a pair of AirPods contains a manufacturer specific data field n°76 of 27 bytes
        val data = scanResult.scanRecord?.getManufacturerSpecificData(76)
        if (data == null) {
            log("No manufacturer data.")
            return
        }
        if (data.size != 27) {
            log ("Expected 27 bytes of manufacturer data, got $data.size")
            return
        }
        // We convert this data to a hexadecimal string
        // The 12th and 13th characters in the string represent the charge of the left and right
        // pods. Under unknown circumstances, they are right and left instead (see isFlipped).
        // Values between 0 and 10 are battery 0-100%; Value 15 means it's disconnected
        // The 15th character in the string represents the charge of the case. Values between 0
        // and 10 are battery 0-100%; Value 15 means it's disconnected
        log("Manufacturer data (raw): $data")
        val dataHexBuilder = StringBuilder()
        for (b in data) dataHexBuilder.append(String.format("%02X", b))
        val dataHex = dataHexBuilder.toString()
        log("Manufacturer data (hex): $dataHex")
        val isFlipped = ("" + dataHex[10]).toInt(16) and 0x02 == 0
        val leftStatus = ("" + dataHex[if (isFlipped) 12 else 13]).toInt(16)
        val rightStatus = ("" + dataHex[if (isFlipped) 13 else 12]).toInt(16)
        val caseStatus = ("" + dataHex[15]).toInt(16)
        val leftCharge = (if (leftStatus == 10) 100 else if (leftStatus < 10) leftStatus * 10 + 5 else null)?.toByte()
        val rightCharge = (if (rightStatus == 10) 100 else if (rightStatus < 10) leftStatus * 10 + 5 else null)?.toByte()
        val caseCharge = (if (caseStatus == 10) 100 else if (caseStatus < 10) caseStatus * 10 + 5 else null)?.toByte()
        val leftConnected = leftStatus != 15
        val rightConnected = rightStatus != 15
        val caseConnected = caseStatus != 15
        log("Charges:")
        log("\tCase:")
        log("\t\tConnected: $caseConnected")
        log("\t\tCharge: $caseCharge%")
        log("\tLeft:")
        log("\t\tConnected: $leftConnected")
        log("\t\tCharge: $leftCharge%")
        log("\tRight:")
        log("\t\tConnected: $rightConnected")
        log("\t\tCharge: $rightCharge%")
    }
}