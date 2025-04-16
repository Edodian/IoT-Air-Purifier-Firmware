package com.example.IoTPurifierBLEControlApp

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.util.*

class MainActivity : AppCompatActivity() {

    private val tag = "RecirculatorControl"
    private val serviceUUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    private val rxUUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    private val txUUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")

    private var bluetoothGatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private lateinit var bluetoothAdapter: BluetoothAdapter
    private var scanner: BluetoothLeScanner? = null

    private lateinit var txtStatus: TextView
    private lateinit var ValueTemp: TextView
    private lateinit var ValueHum: TextView
    private lateinit var ValueCo2: TextView
    private lateinit var ValueO3: TextView
    private lateinit var ValuePm1: TextView
    private lateinit var ValuePm25: TextView
    private lateinit var ValuePm10: TextView
    private lateinit var ConnectionState: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            Log.d(tag, "All permissions granted")
        } else {
            Log.e(tag, "Missing required permissions")
        }
    }

    @SuppressLint("MissingInflatedId")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        scanner = bluetoothAdapter.bluetoothLeScanner

        txtStatus = findViewById(R.id.RecState)
        ValueTemp = findViewById(R.id.ValueTemp)
        ValueHum = findViewById(R.id.ValueHum)
        ValueCo2 = findViewById(R.id.ValueCo2)
        ValueO3 = findViewById(R.id.ValueO3)
        ValuePm1 = findViewById(R.id.ValuePm1)
        ValuePm25 = findViewById(R.id.ValuePm25)
        ValuePm10 = findViewById(R.id.ValuePm10)
        ConnectionState = findViewById(R.id.connectionState)
        ConnectionState.setTextColor(Color.RED)
        val btnOn = findViewById<Button>(R.id.btnOn)
        val btnOff = findViewById<Button>(R.id.btnOff)
        findViewById<Button>(R.id.btnScan).setOnClickListener { startScan() }

        findViewById<Button>(R.id.schedule_button).setOnClickListener {
           // sendData("SHOW")
            findViewById<View>(R.id.main_content_group).visibility = View.GONE
            findViewById<View>(R.id.schedule_fragment_container).visibility = View.VISIBLE
            supportFragmentManager.beginTransaction()
                .replace(R.id.schedule_fragment_container, ScheduleFragment())
                .addToBackStack(null)
                .commit()
        }
        findViewById<Button>(R.id.btnOn).setOnClickListener {
            sendData("MANUALTOGGLE|ON")
        }
        findViewById<Button>(R.id.btnOff).setOnClickListener {
            sendData("MANUALTOGGLE|OFF")
        }
        findViewById<View>(R.id.main_content_group).visibility = View.VISIBLE
        requestPermissions()
       // startScan()
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }

        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun startScan() {
        if (!hasScanPermission()) return

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        scanner?.stopScan(scanCallback)

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_SCAN
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        scanner?.startScan(null, settings, scanCallback)
        Log.d(tag, "Started BLE scan")
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device

            if (!hasConnectPermission()){return}

            val deviceName = device.name ?: "Hidden"
            Log.d(tag, "Found BLE device: $deviceName | ${device.address}")

            if (deviceName.contains("FblsRcrByPV")) {
                Log.d(tag, "Target recirculator found, connecting...")

                if (ActivityCompat.checkSelfPermission(this@MainActivity, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                    return
                }
                scanner?.stopScan(this)
                connectToDevice(device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(tag, "BLE scan failed with code $errorCode")
        }
    }

    private fun connectToDevice(device: BluetoothDevice) {
        if (!hasConnectPermission()) return
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        bluetoothGatt = device.connectGatt(this, false, gattCallback)
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                Log.d(tag, "Connected to GATT, requesting MTU...")
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                gatt.requestMtu(517)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.w(tag, "Disconnected from GATT")
                startScan()
                runOnUiThread {
                    ConnectionState.text = getString(R.string.disconnected)
                    ConnectionState.setTextColor(Color.RED)
                }
                if (ActivityCompat.checkSelfPermission(
                        this@MainActivity,
                        Manifest.permission.BLUETOOTH_CONNECT
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    return
                }
                bluetoothGatt?.close()
            }
        }
        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.d(tag, "MTU changed to $mtu, discovering services...")
            if (ActivityCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            gatt.discoverServices()
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            val service = gatt.getService(serviceUUID) ?: return
            rxCharacteristic = service.getCharacteristic(rxUUID)
            val txCharacteristic = service.getCharacteristic(txUUID) ?: return

            enableNotifications(gatt, txCharacteristic)
           // connected = true
            runOnUiThread {
                ConnectionState.text = getString(R.string.connected)
                ConnectionState.setTextColor(Color.GREEN)
            }
        }



        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid != txUUID) return

            val message = characteristic.getStringValue(0)
            runOnUiThread {
                ConnectionState.text = getString(R.string.connected)

                when {
                    message.startsWith("Schedules:") -> {
                        val scheduleFragment = supportFragmentManager.findFragmentById(R.id.schedule_fragment_container)
                        if (scheduleFragment is ScheduleFragment) {
                            scheduleFragment.updateScheduleFromEsp32(message)
                        }
                    }
                    message.startsWith("RecStatus:") -> {
                        val recStatus = when {
                            message.contains("HIGH") -> "ON"
                            message.contains("LOW") -> "OFF"
                            else -> "PAUSED"
                        }
                        txtStatus.text = recStatus
                    }
                    message.startsWith("SensorInfo:") -> {
                        val lines = message.split("\n").filter { it.isNotBlank() }
                        for (line in lines) {
                            when {
                                line.startsWith("PMS7003:") -> {
                                    Regex("PM1: (\\d+) \\| PM25: (\\d+) \\| PM10: (\\d+)")
                                        .find(line)?.destructured?.let { (pm1, pm25, pm10) ->
                                            ValuePm1.text = getString(R.string.unit_pm, pm1)
                                            val pm1Value = pm1.toIntOrNull() ?: 0
                                            ValuePm1.setTextColor(when {
                                                pm1Value < 15 -> Color.GREEN
                                                pm1Value in 15..30 -> Color.YELLOW
                                                else -> Color.RED
                                            })

                                            ValuePm25.text = getString(R.string.unit_pm, pm25)
                                            val pm25Value = pm25.toIntOrNull() ?: 0
                                            ValuePm25.setTextColor(when {
                                                pm25Value < 12 -> Color.GREEN
                                                pm25Value in 12..35 -> Color.YELLOW
                                                else -> Color.RED
                                            })

                                            ValuePm10.text = getString(R.string.unit_pm, pm10)
                                            val pm10Value = pm10.toIntOrNull() ?: 0
                                            ValuePm10.setTextColor(when {
                                                pm10Value < 54 -> Color.GREEN
                                                pm10Value in 54..154 -> Color.YELLOW
                                                else -> Color.RED
                                            })
                                        }
                                }
                                line.startsWith("CO2:") -> {
                                    Regex("CO2:(\\d+) ppm").find(line)?.destructured?.let { (co2) ->
                                        ValueCo2.text = getString(R.string.unit_co2, co2)
                                        val co2Value = co2.toIntOrNull() ?: 0
                                        ValueCo2.setTextColor(when {
                                            co2Value < 1000 -> Color.GREEN
                                            co2Value in 1000..2000 -> Color.YELLOW
                                            else -> Color.RED
                                        })
                                    }
                                }
                                line.startsWith("Temp:") -> {
                                    Regex("Temp:([\\d.]+) C").find(line)?.destructured?.let { (temp) ->
                                        ValueTemp.text = getString(R.string.unit_temp, temp)
                                        val tempValue = temp.toFloatOrNull() ?: 0f
                                        ValueTemp.setTextColor(when {
                                            tempValue in 20f..25f -> Color.GREEN
                                            (tempValue in 15f..20f) || (tempValue in 25f..30f) -> Color.YELLOW
                                            else -> Color.RED
                                        })
                                    }
                                }
                                line.startsWith("Hum:") -> {
                                    Regex("Hum:([\\d.]+) %").find(line)?.destructured?.let { (hum) ->
                                        ValueHum.text = getString(R.string.unit_hum, hum)
                                        val humValue = hum.toFloatOrNull() ?: 0f
                                        ValueHum.setTextColor(when {
                                            humValue in 30f..50f -> Color.GREEN
                                            else -> Color.YELLOW
                                        })
                                    }
                                }
                                line.startsWith("O3:") -> {
                                    Regex("O3:(\\d+) ppb").find(line)?.destructured?.let { (o3) ->
                                        val o3Int = o3.toIntOrNull() ?: 0
                                        ValueO3.text = if (o3Int > 99999)
                                            getString(R.string.unit_error)
                                        else
                                            getString(R.string.unit_o3, o3)
                                        ValueO3.setTextColor(when {
                                            o3Int < 100 -> Color.GREEN
                                            o3Int in 100..200 -> Color.YELLOW
                                            else -> Color.RED
                                        })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }


    }

    private fun enableNotifications(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!hasConnectPermission()) return

        val success = gatt.setCharacteristicNotification(characteristic, true)
        if (!success) {
            Log.e(tag, "Failed to enable characteristic notifications")
            return
        }

        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"))
        if (descriptor == null) {
            Log.e(tag, "CCCD descriptor not found")
            return
        }

        descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        gatt.writeDescriptor(descriptor)
    }

    fun sendData(message: String) {
        val rxChar = rxCharacteristic ?: return
        if (!hasConnectPermission()) return

        val value = message.toByteArray()
        rxChar.value = value
        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        rxChar.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        bluetoothGatt?.writeCharacteristic(rxChar)

        Log.d(tag, "Sent: $message")
    }

    private fun hasConnectPermission() = ActivityCompat.checkSelfPermission(
        this, Manifest.permission.BLUETOOTH_CONNECT
    ) == PackageManager.PERMISSION_GRANTED

    private fun hasScanPermission() = ActivityCompat.checkSelfPermission(
        this, Manifest.permission.BLUETOOTH_SCAN
    ) == PackageManager.PERMISSION_GRANTED

    override fun onDestroy() {

        if (hasConnectPermission()) {
            try {
                bluetoothGatt?.close()
            } catch (e: SecurityException) {
                Log.e(tag, "SecurityException when closing GATT: ${e.message}")
            }
        }
        bluetoothGatt = null
        startScan()
        runOnUiThread {
            ConnectionState.text = getString(R.string.disconnected)
            ConnectionState.setTextColor(Color.RED)
        }
        super.onDestroy()

    }
}
