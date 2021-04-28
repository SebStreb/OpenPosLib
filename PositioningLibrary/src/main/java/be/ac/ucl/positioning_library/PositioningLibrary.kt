package be.ac.ucl.positioning_library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.util.Log
import be.ac.ucl.positioning_library.objects.AntennaConfig
import be.ac.ucl.positioning_library.objects.CORSConfig
import be.ac.ucl.positioning_library.services.BasicService
import be.ac.ucl.positioning_library.services.ExternalService
import com.felhr.usbserial.UsbSerialDevice


/**
 * Entry point of the library, configure, start and stop services to get position.
 */
class PositioningLibrary {

    companion object {
        // identify position update
        const val UPDATE = "be.ac.ucl.gnsspositioning.UPDATE"
        const val POSITION = "position"

        // identify service error
        const val ERROR = "be.ac.ucl.gnsspositioning.ERROR"
        const val MESSAGE = "message"

        /**
         * Check that a USB device is supported by the library as an antenna.
         *
         * @param usbDevice the USB device to check
         * @return true if the device is a supported antenna, false otherwise
         */
        fun isSupportedAntenna(usbDevice: UsbDevice) = UsbSerialDevice.isSupported(usbDevice)
    }

    // execution mode of the service
    private var executionMode = PositioningMode.BASIC

    // configurations, depending on the execution mode
    private var antennaConfig: AntennaConfig? = null
    private var corsConfig: CORSConfig? = null

    // service execution variables
    private var listener: PositioningListener? = null
    private var intent: Intent? = null

    /**
     * True if the positioning service running, false otherwise.
     */
    var running = false

    // react to service events
    private val broadcastReceiver = object : BroadcastReceiver() {
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

    /**
     * Set the execution mode to [PositioningMode.BASIC].
     */
    fun setBasicMode() { executionMode = PositioningMode.BASIC
    }

    /**
     * Set the execution mode to [PositioningMode.INTERNAL].
     */
    fun setInternalMode() {
        executionMode = PositioningMode.INTERNAL
    }

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
     * Start the positioning service in the configured execution mode.
     *
     * @param context the context in which the service is running
     * @param positioningListener definition of callbacks for the service
     */
    fun startService(context: Context, positioningListener: PositioningListener) {
        // listen for service events
        context.registerReceiver(broadcastReceiver, IntentFilter().apply {
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
                    .putExtra(ExternalService.ANTENNA_CONFIG, antennaConfig)
            PositioningMode.EXTERNAL_RTK -> Intent(context, ExternalService::class.java)
                    .putExtra(ExternalService.ANTENNA_CONFIG, antennaConfig)
                    .putExtra(ExternalService.CORS_CONFIG, corsConfig)
        }

        Log.d("PositioningLibrary", "Starting in mode $executionMode")
        // start service
        context.startForegroundService(intent)

        running = true
    }

    /**
     * Stop the positioning service.
     *
     * @param context the context in which the service was running.
     */
    fun stopService(context: Context) {
        context.unregisterReceiver(broadcastReceiver)
        context.stopService(intent)
        intent = null
        listener = null
        running = false
    }

}
