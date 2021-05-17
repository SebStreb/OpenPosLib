package be.ac.ucl.positioning_library

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import be.ac.ucl.positioning_library.objects.AntennaConfig
import be.ac.ucl.positioning_library.objects.CORSConfig
import be.ac.ucl.positioning_library.services.BasicService
import be.ac.ucl.positioning_library.services.ExternalService
import com.felhr.usbserial.UsbSerialDevice


/**
 * Entry point of the library, configure, start and stop services to get position.
 */
class PositioningLibrary(context: Context) {

    companion object {
        // identify position update
        const val UPDATE = "be.ac.ucl.gnsspositioning.UPDATE"
        const val POSITION = "position"

        // identify service error
        const val ERROR = "be.ac.ucl.gnsspositioning.ERROR"
        const val MESSAGE = "message"

        // identify USB events
        private const val USB_ATTACHED = "android.hardware.usb.action.USB_DEVICE_ATTACHED"
        private const val USB_DETACHED = "android.hardware.usb.action.USB_DEVICE_DETACHED"
        private const val USB_PERMISSION = "be.ac.ucl.gnss_positioning_example.USB_PERMISSION"

        // list of modes requiring an external antenna
        private val antennaServices = listOf(PositioningMode.EXTERNAL, PositioningMode.EXTERNAL_RTK)

        // internal values for stats
        internal var firstPosition = false
        internal var firstClose = false
        internal var firstPrecise = false
        internal var firstCorrection = false
        internal var firstFloat = false
        internal var firstFix = false
        const val STATS = "PositioningLibrary-Stats"
    }

    // execution mode of the service
    private var executionMode = PositioningMode.BASIC

    // configurations, depending on the execution mode
    private var antennaConfig: AntennaConfig? = null
    private var corsConfig: CORSConfig? = null

    // service execution variables
    private var listener: PositioningListener? = null
    private var intent: Intent? = null

    // handle usb connection and permission
    private var usbManager = context.getSystemService(UsbManager::class.java)
    // plugged antenna, null if unplugged
    private var usbDevice: UsbDevice? = null


    /**
     * True if the positioning service running, false otherwise.
     */
    var running = false


    // react to service events
    private val serviceBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                // new position measured
                UPDATE -> listener!!.onPosition(intent.getParcelableExtra(POSITION)!!)
                // error in service
                ERROR -> {
                    listener!!.onError(intent.getStringExtra(MESSAGE)!!)
                    stopService(context)
                }
            }
        }
    }

    // react to usb events
    private val usbBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                USB_ATTACHED -> { // usb device plugged in
                    usbManager.deviceList.values.firstOrNull(UsbSerialDevice::isSupported)?.let { device ->
                        requestUserPermission(context, device) // request permission to use if it is an antenna
                    }
                }
                USB_DETACHED -> { // usb device unplugged
                    usbDevice = null
                    if (running && executionMode in antennaServices) {
                        listener?.onError(context.getString(R.string.positioning_library_unplugged))
                        stopService(context)
                    }
                }
                USB_PERMISSION -> { // response to permission request
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (granted) usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)!!
                }
            }
        }
    }



    /**
     * Set the execution mode to [PositioningMode.BASIC].
     */
    fun setBasicMode() { executionMode = PositioningMode.BASIC }

    /**
     * Set the execution mode to [PositioningMode.INTERNAL].
     */
    fun setInternalMode() { executionMode = PositioningMode.INTERNAL }

    /**
     * Set the execution mode to [PositioningMode.INTERNAL_RTK].
     * @param corsConfig configuration of the cors server connection
     */
    fun setCorrectedMode(corsConfig: CORSConfig) {
        executionMode = PositioningMode.INTERNAL_RTK
        this.corsConfig = corsConfig
    }

    /**
     * Set the execution mode to [PositioningMode.EXTERNAL].
     *
     * @param antennaConfig configuration of the external GNSS antenna
     */
    fun setExternalMode(antennaConfig: AntennaConfig) {
        executionMode = PositioningMode.EXTERNAL
        this.antennaConfig = antennaConfig
    }

    /**
     * Set the execution mode to [PositioningMode.EXTERNAL_RTK].
     *
     * @param antennaConfig configuration of the external GNSS antenna
     * @param corsConfig configuration of the cors server connection
     */
    fun setExternalCorrectedMode(antennaConfig: AntennaConfig, corsConfig: CORSConfig) {
        executionMode = PositioningMode.EXTERNAL_RTK
        this.antennaConfig = antennaConfig
        this.corsConfig = corsConfig
    }


    /**
     * Start listening for USB events to detect an antenna plugged in through USB.
     *
     * @param context the context of the application
     */
    fun startUSBAntennaListener(context: Context) {
        // listen for events
        context.registerReceiver(usbBroadcastReceiver, IntentFilter().apply {
            addAction(USB_ATTACHED)
            addAction(USB_DETACHED)
            addAction(USB_PERMISSION)
        })

        // request permission if antenna already plugged in
        usbManager.deviceList.values.firstOrNull(UsbSerialDevice::isSupported)?.let { requestUserPermission(context, it) }
    }

    /**
     * Stop listening for USB events and discard any plugged antenna.
     *
     * @param context the context of the application
     */
    fun stopUSBAntennaListener(context: Context) {
        context.unregisterReceiver(usbBroadcastReceiver)
        usbDevice = null
    }

    /**
     * Ask the user the permission tu use the antenna USB device.
     *
     * @param device the antenna USB device
     */
    private fun requestUserPermission(context: Context, device: UsbDevice) =
            usbManager.requestPermission(device, PendingIntent.getBroadcast(context, 0, Intent(USB_PERMISSION), 0))


    /**
     * Start the positioning service in the configured execution mode.
     *
     * @param context the context of the application
     * @param positioningListener definition of callbacks for the service
     */
    fun startService(context: Context, positioningListener: PositioningListener) {
        // listen for service events
        context.registerReceiver(serviceBroadcastReceiver, IntentFilter().apply {
            addAction(UPDATE)
            addAction(ERROR)
        })

        // save listener and put configuration objects
        listener = positioningListener
        intent = when (executionMode) {
            PositioningMode.BASIC -> Intent(context, BasicService::class.java)
            PositioningMode.INTERNAL -> TODO("RTKLIB")
            PositioningMode.INTERNAL_RTK -> TODO("RTKLIB+CORS")
            PositioningMode.EXTERNAL -> Intent(context, ExternalService::class.java)
                    .putExtra(ExternalService.ANTENNA, usbDevice) // may be null if not plugged in, checked in service
                    .putExtra(ExternalService.ANTENNA_CONFIG, antennaConfig)
            PositioningMode.EXTERNAL_RTK -> Intent(context, ExternalService::class.java)
                    .putExtra(ExternalService.ANTENNA, usbDevice)
                    .putExtra(ExternalService.ANTENNA_CONFIG, antennaConfig)
                    .putExtra(ExternalService.CORS_CONFIG, corsConfig)
        }

        // start service
        context.startForegroundService(intent)

        running = true

        firstPosition = true
        firstClose = true
        firstPrecise = true
        firstCorrection = true
        firstFloat = true
        firstFix = true

        Log.wtf(STATS, "Starting on mode $executionMode at timestamp ${System.currentTimeMillis()}")
    }

    /**
     * Stop the positioning service.
     *
     * @param context the context of the application
     */
    fun stopService(context: Context) {
        context.unregisterReceiver(serviceBroadcastReceiver)
        context.stopService(intent)
        intent = null
        listener = null
        running = false
    }

}
