package be.ac.ucl.gnss_positioning_example

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
import be.ac.ucl.positioning_library.PositioningMode
import be.ac.ucl.positioning_library.PositioningLibrary
import be.ac.ucl.positioning_library.PositioningListener
import be.ac.ucl.positioning_library.objects.AntennaConfig
import be.ac.ucl.positioning_library.objects.CORSConfig
import be.ac.ucl.positioning_library.objects.Position
import java.io.File

/**
 * Example of use of the positioning service with an external antenna and CORS corrections. Main steps are:
 * - listening for events when a USB device is plugged in
 * - Check that it is a supported antenna
 * - Ask user permission to use antenna
 * - configure library with antenna and CORS settings
 * - start positioning library with listener
 * - stop positioning service when done
 */
class MainActivity : AppCompatActivity() {

    companion object {
        // identify USB events
        private const val USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
        private const val USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"
        private const val USB_PERMISSION = "be.ac.ucl.gnss_positioning_example.USB_PERMISSION"

        // identify permission events
        private const val PERM_REQUEST = 419

        // list of modes requiring an external antenna
        private val antennaServices = listOf(PositioningMode.EXTERNAL, PositioningMode.EXTERNAL_CORRECTED)
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

    // positioning library
    private var positioningLibrary = PositioningLibrary()

    // execution mode of the library
    private lateinit var mode: PositioningMode

    // configuration of the CORS server connection (check you cors.properties file)
    private val corsConfig = CORSConfig(
        BuildConfig.CORS_ADDRESS,
        BuildConfig.CORS_PORT,
        BuildConfig.CORS_MOUNT_POINT,
        BuildConfig.CORS_USERNAME,
        BuildConfig.CORS_PASSWORD
    )

    // plugged antenna, null if unplugged
    private var antenna: UsbDevice? = null

    // react to events
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                USB_ATTACHED -> { // usb device plugged in
                    usbManager.deviceList.values.firstOrNull(PositioningLibrary::isSupportedAntenna)?.let { device ->
                        requestUserPermission(device) // request permission to use if it is an antenna
                    }
                }
                USB_DETACHED -> { // usb device unplugged
                    antenna = null
                    if (positioningLibrary.running && mode in antennaServices) stopService(getString(R.string.unplugged))
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

        // register logs
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

        // set up mode selection spinner
        serviceMode.adapter = ArrayAdapter(this, R.layout.spinner, PositioningMode.values()).apply {
            setDropDownViewResource(R.layout.spinner_dropdown)
        }
        serviceMode.onItemSelectedListener = object :  AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                mode = serviceMode.selectedItem as PositioningMode
                if (positioningLibrary.running) {
                    stopService(getString(R.string.change_exec_mode))
                    startService()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = positioningLibrary.setBasicMode()
        }

        // set up start/stop button
        toggleService.setOnClickListener { if (positioningLibrary.running) stopService(getString(R.string.stopped)) else startService() }

        // request permission if antenna already plugged in
        usbManager.deviceList.values.firstOrNull(PositioningLibrary::isSupportedAntenna)?.let { requestUserPermission(it) }
    }

    override fun onDestroy() {
        super.onDestroy()
        antenna = null
        unregisterReceiver(broadcastReceiver)
        if (positioningLibrary.running) stopService(getString(R.string.app_closed))
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
        // request permissions if necessary
        requestPermissionIfNecessary()

        // check if antenna is needed and connected
        if (mode in antennaServices && antenna == null) return updateServiceStatus(false, getString(R.string.not_plugged_in))

        // configure positioning library
        when (mode) {
            PositioningMode.BASIC -> positioningLibrary.setBasicMode()
            PositioningMode.INTERNAL ->  positioningLibrary.setInternalMode()
            PositioningMode.CORRECTED -> positioningLibrary.setCorrectedMode(corsConfig)
            PositioningMode.EXTERNAL -> positioningLibrary.setExternalMode(AntennaConfig(antenna!!, 180))
            PositioningMode.EXTERNAL_CORRECTED -> positioningLibrary.setExternalCorrectedMode(AntennaConfig(antenna!!, 180), corsConfig)
        }

        // start positioning library
        positioningLibrary.startService(this, object : PositioningListener {
            override fun onPosition(position: Position) = updatePosition(position)
            override fun onError(message: String) = updateServiceStatus(false, message)
        })

        // update UI
        updateServiceStatus(true, getString(R.string.service_started))
    }

    /**
     * Stop the positioning library.
     *
     * @param reason a description of why the positioning service is stopped
     */
    private fun stopService(reason: String) {
        positioningLibrary.stopService(this)
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

    /**
     * Request necessary permissions if they are not already granted.
     */
    private fun requestPermissionIfNecessary() {
        // filter requests to only include all that were not already granted
        val toRequest = listOf(Manifest.permission.ACCESS_FINE_LOCATION).filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        // show permission request to the user
        if (toRequest.isNotEmpty()) requestPermissions(toRequest.toTypedArray(), PERM_REQUEST)
    }

}
