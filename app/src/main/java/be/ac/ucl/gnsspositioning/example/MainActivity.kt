package be.ac.ucl.gnsspositioning.example

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import be.ac.ucl.gnsspositioning.*
import be.ac.ucl.gnsspositioning.objects.AntennaConfig
import be.ac.ucl.gnsspositioning.objects.CORSConfig
import be.ac.ucl.gnsspositioning.objects.Position
import com.felhr.usbserial.UsbSerialDevice
import java.io.File

/**
 * Example of use of the positioning service. Main steps are:
 * - listening for events when a USB device is plugged in
 * - Check that it is a supported antenna
 * - Ask user permission to use antenna
 * - start positioning service with antenna
 * - listen for events when a new position is measured
 * - react if an error happens
 * - stop positioning service when done
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
        private const val USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"
        private const val USB_PERMISSION = "be.ac.ucl.gnsspositioning.USB_PERMISSION"

        private const val PERM_REQUEST = 419
    }

    // handle usb connection and permission
    private lateinit var usbManager: UsbManager

    // UI elements
    private lateinit var serviceMode: Spinner
    private lateinit var serviceStatus: TextView
    private lateinit var toggleService: Button

    private lateinit var latitude: TextView
    private lateinit var longitude: TextView
    private lateinit var altitude: TextView
    private lateinit var precision: TextView

    private lateinit var executionMode: ExecutionMode

    private val antennaServices = listOf(ExecutionMode.EXTERNAL, ExecutionMode.EXTERNAL_CORRECTED)

    // plugged antenna
    private var antenna: UsbDevice? = null

    private var service = ServiceWrapper()

    // react to events
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                USB_ATTACHED -> { // usb device plugged in
                    usbManager.deviceList.values.firstOrNull(UsbSerialDevice::isSupported)?.let { device ->
                        requestUserPermission(device) // request permission to use if it is an antenna
                    }
                }
                USB_DETACHED -> { // usb device unplugged
                    antenna = null
                    if (service.running && executionMode in antennaServices) stopService(getString(R.string.unplugged))
                }
                USB_PERMISSION -> { // response to permission request
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (!granted) return updateServiceStatus(false, getString(R.string.perm_refused))
                    antenna = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)!!
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        requestPermissionIfNecessary()

        val timestamp = System.currentTimeMillis().toString()
        val logcatFile = File(filesDir, "$timestamp-logcat.log").apply { createNewFile() }
        Runtime.getRuntime().exec("logcat -f ${logcatFile.path}")

        // get usb manager
        usbManager = getSystemService(UsbManager::class.java)

        // get UI elements
        serviceMode = findViewById(R.id.service_mode)
        serviceStatus = findViewById(R.id.service_status)
        toggleService = findViewById(R.id.toggle_service)

        latitude = findViewById(R.id.latitude)
        longitude = findViewById(R.id.longitude)
        altitude = findViewById(R.id.altitude)
        precision = findViewById(R.id.precision)

        // listen for events
        registerReceiver(broadcastReceiver, IntentFilter().apply {
            addAction(USB_ATTACHED)
            addAction(USB_DETACHED)
            addAction(USB_PERMISSION)
        })

        serviceMode.adapter = ArrayAdapter(this, R.layout.spinner, ExecutionMode.values()).apply {
            setDropDownViewResource(R.layout.spinner_dropdown)
        }
        serviceMode.onItemSelectedListener = object :  AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                executionMode = serviceMode.selectedItem as ExecutionMode
                if (service.running) {
                    stopService(getString(R.string.change_exec_mode))
                    startService()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = service.setBasicMode()
        }

        // set up start/stop button
        toggleService.setOnClickListener { if (service.running) stopService(getString(R.string.stopped)) else startService() }

        // request permission if antenna already plugged in
        usbManager.deviceList.values.firstOrNull(UsbSerialDevice::isSupported)?.let { requestUserPermission(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        antenna = null
        unregisterReceiver(broadcastReceiver)
        if (service.running) stopService(getString(R.string.app_closed))
    }

    /**
     * Ask the user the permission tu use the antenna USB device.
     *
     * @param device the antenna USB device
     */
    private fun requestUserPermission(device: UsbDevice) =
        usbManager.requestPermission(device, PendingIntent.getBroadcast(this, 0, Intent(USB_PERMISSION), 0))

    /**
     * Start the positioning service.
     */
    private fun startService() {
        requestPermissionIfNecessary()

        if (executionMode in antennaServices && antenna == null) {
            return updateServiceStatus(false, getString(R.string.not_plugged_in))
        }

        when (executionMode) {
            ExecutionMode.BASIC -> service.setBasicMode()
            ExecutionMode.INTERNAL ->  service.setInternalMode()
            ExecutionMode.CORRECTED -> TODO() // service.setCorrectedMode()
            ExecutionMode.EXTERNAL -> service.setExternalMode(AntennaConfig(antenna!!, 180))
            ExecutionMode.EXTERNAL_CORRECTED -> service.setExternalCorrectedMode(
                    AntennaConfig(antenna!!, 180),
                    CORSConfig(BuildConfig.CORS_ADDRESS, BuildConfig.CORS_PORT,
                        BuildConfig.CORS_MOUNT_POINT, BuildConfig.CORS_USERNAME, BuildConfig.CORS_PASSWORD),
            )
        }

        service.startService(this, object : ServiceListener {
            override fun onPosition(position: Position) = updatePosition(position)
            override fun onError(message: String) = updateServiceStatus(false, message)
        })

        updateServiceStatus(true, getString(R.string.service_started))
    }

    /**
     * Stop the positioning service.
     *
     * @param reason a description of why the positioning service is stopped
     */
    private fun stopService(reason: String) {
        service.stopService(this)
        updateServiceStatus(false, reason)
    }

    /**
     * Update the UI to reflect a new service status.
     *
     * @param status true if the service is now connected, false if disconnected
     * @param message a description of the new status
     */
    private fun updateServiceStatus(status: Boolean, message: String) {
        serviceStatus.text = message
        toggleService.background.setTint(ContextCompat.getColor(this, if (status) R.color.purple_200 else R.color.purple_500))
        toggleService.text = if (status) getString(R.string.stop_service) else getString(R.string.start_service)
    }

    /**
     * Update the UI to show the new measured position.
     *
     * @param position the new measured position
     */
    private fun updatePosition(position: Position) {
        latitude.text = getString(R.string.lat_lon, position.lat)
        longitude.text = getString(R.string.lat_lon, position.lon)
        altitude.text = getString(R.string.alt, position.alt)
        precision.text = getString(R.string.prec, position.prec)
    }

    private fun requestPermissionIfNecessary() {
        // filter requests to only include all that were not already granted
        val toRequest = listOf(Manifest.permission.ACCESS_FINE_LOCATION).filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        // show permission request to the user
        if (toRequest.isNotEmpty()) requestPermissions(toRequest.toTypedArray(), PERM_REQUEST)
    }
}
