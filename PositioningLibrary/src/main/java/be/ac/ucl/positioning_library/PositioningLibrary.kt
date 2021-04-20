package be.ac.ucl.positioning_library

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import be.ac.ucl.positioning_library.objects.AntennaConfig
import be.ac.ucl.positioning_library.objects.CORSConfig
import be.ac.ucl.positioning_library.services.AndroidLocationService
import be.ac.ucl.positioning_library.services.AntennaPositionService
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
     * Set the execution mode to [PositioningMode.CORRECTED].
     * @param corsConfig configuration of the cors server connection
     */
    fun setCorrectedMode(corsConfig: CORSConfig) {
        executionMode = PositioningMode.CORRECTED
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
     * Set the execution mode to [PositioningMode.EXTERNAL_CORRECTED].
     *
     * @param antennaConfig configuration of the external GNSS antenna
     * @param corsConfig configuration of the cors server connection
     */
    fun setExternalCorrectedMode(antennaConfig: AntennaConfig, corsConfig: CORSConfig) {
        executionMode = PositioningMode.EXTERNAL_CORRECTED
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
            PositioningMode.BASIC -> Intent(context, AndroidLocationService::class.java)
            PositioningMode.INTERNAL -> Intent(context, AndroidLocationService::class.java)
                    .putExtra(AndroidLocationService.USE_GNSS, true)
            PositioningMode.CORRECTED -> TODO()
            PositioningMode.EXTERNAL -> Intent(context, AntennaPositionService::class.java)
                    .putExtra(AntennaPositionService.ANTENNA_CONFIG, antennaConfig)
            PositioningMode.EXTERNAL_CORRECTED -> Intent(context, AntennaPositionService::class.java)
                    .putExtra(AntennaPositionService.ANTENNA_CONFIG, antennaConfig)
                    .putExtra(AntennaPositionService.CORS_CONFIG, corsConfig)
        }

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
