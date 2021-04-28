package be.ac.ucl.positioning_library.services

import android.app.*
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import be.ac.ucl.positioning_library.PositioningLibrary
import be.ac.ucl.positioning_library.R
import be.ac.ucl.positioning_library.managers.AntennaManager
import be.ac.ucl.positioning_library.managers.CORSManager
import be.ac.ucl.positioning_library.objects.AntennaConfig
import be.ac.ucl.positioning_library.objects.CORSConfig
import be.ac.ucl.positioning_library.objects.Position


/**
 * Monitor updates of device position using external antenna in the background.
 */
internal class ExternalService : Service() {

    companion object {
        // Identify service parameter
        const val ANTENNA_CONFIG = "antenna_config"
        const val CORS_CONFIG = "cors_config"

        // Identify service notification
        private const val FOREGROUND_ID = 420
        private const val CHANNEL_ID = "be.ac.ucl.positioning_library.ANTENNA_POSITION_SERVICE"
    }

    // Communicate with cors server and antenna
    private lateinit var antennaManager: AntennaManager
    private var corsManager: CORSManager? = null

    // true if should use corrections from CORS
    private var corsCorrections = false

    // time between two send of GGA to CORS
    private var ggaPeriod = 0L

    // Perform delayed operations
    private lateinit var handler: Handler

    // true if service should stop all background activities
    private var stop = false


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // start notification to keep service running in background even if app is not in foreground
        getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.positioning_library_name), NotificationManager.IMPORTANCE_DEFAULT))
        startForeground(FOREGROUND_ID, Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.positioning_library_name))
                .setContentText(getString(R.string.positioning_library_active))
                .setSmallIcon(R.drawable.positioning_library_icon)
                .build())

        // prepare delayed operations
        handler = Handler(mainLooper)

        // retrieve service parameters
        val antennaConfig = intent!!.getParcelableExtra<AntennaConfig>(ANTENNA_CONFIG)!!
        val corsConfig = intent.getParcelableExtra<CORSConfig>(CORS_CONFIG) // null if no cors

        corsCorrections = corsConfig != null
        if (corsCorrections) ggaPeriod = corsConfig!!.ggaPeriod

        // setup communication with cors server and antenna
        antennaManager = AntennaManager(antennaConfig, object : AntennaManager.Listener {
            override fun onPosition(position: Position) {
                // send new device position decoded by antenna to app
                sendBroadcast(Intent(PositioningLibrary.UPDATE).putExtra(PositioningLibrary.POSITION, position))

                if (corsCorrections && corsManager!!.stopped) {
                    // start connection with cors if not already started
                    corsManager!!.start()
                    handler.post(::periodicalGGA) // start sending position updates to cors
                }
            }
            override fun onStop() = error(getString(R.string.positioning_library_antenna_error)) // stop service in case of antenna error
        })
        if (corsCorrections) corsManager = CORSManager(corsConfig!!, object : CORSManager.Listener {
            override fun onCorrections(data: ByteArray, len: Int) = antennaManager.sendCorrections(data, len) // transmit cors corrections to antenna
            override fun onStop() = error(getString(R.string.positioning_library_cors_error)) // stop service in cas of cors error
        })


        // start connection with antenna
        if (!antennaManager.connectAntenna(this)) error(getString(R.string.positioning_library_antenna_start_error))

        start()

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        stopForeground(true)
        stop()
    }


    /**
     * Starts communication with the antenna.
     */
    private fun start() {
        stop = false
        antennaManager.start()
    }

    /**
     * Stops communication with cors server and antenna.
     */
    private fun stop() {
        stop = true
        antennaManager.stop()
        if (corsCorrections) corsManager!!.stop()
    }


    /**
     * Send updates of device position to cors server.
     * Keep sending updates until asked to stop.
     */
    private fun periodicalGGA() {
        // send position update
        corsManager!!.sendGGA(antennaManager.positionGGA)
        // if not asked to stop, send again after some time
        if (!stop) handler.postDelayed(::periodicalGGA, ggaPeriod)
    }


    /**
     * Inform that the service encountered and error and should be stopped.
     *
     * @param message the error message
     */
    private fun error(message: String) {
        if (!stop) sendBroadcast(Intent(PositioningLibrary.ERROR).putExtra(PositioningLibrary.MESSAGE, message))
    }

}
