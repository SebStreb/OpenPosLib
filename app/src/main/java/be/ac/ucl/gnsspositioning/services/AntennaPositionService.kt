package be.ac.ucl.gnsspositioning.services

import android.app.*
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.util.Log
import be.ac.ucl.gnsspositioning.R
import be.ac.ucl.gnsspositioning.ServiceWrapper
import be.ac.ucl.gnsspositioning.example.MainActivity
import be.ac.ucl.gnsspositioning.managers.AntennaManager
import be.ac.ucl.gnsspositioning.managers.CORSManager
import be.ac.ucl.gnsspositioning.objects.AntennaConfig
import be.ac.ucl.gnsspositioning.objects.CORSConfig
import be.ac.ucl.gnsspositioning.objects.Position


/**
 * Monitor updates of device position in background.
 */
class AntennaPositionService : Service() {

    companion object {
        // Identify service parameter
        const val ANTENNA_CONFIG = "antenna_config"
        const val CORS_CONFIG = "cors_config"

        // Identify service notification
        private const val FOREGROUND_ID = 420
        private const val CHANNEL_ID = "be.ac.ucl.gnsspositioning.POSITION_SERVICE"
    }

    // Communicate with cors server and antenna
    private lateinit var antennaManager: AntennaManager
    private var corsManager: CORSManager? = null

    private var rtkCorrections = false
    private var ggaPeriod: Long = 0

    // Perform delayed operations
    private lateinit var handler: Handler


    // true if service should stop all background activities
    private var stop = false


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // start notification to keep service running in background even if app is not in foreground
        getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_DEFAULT))
        startForeground(FOREGROUND_ID, Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.service_running))
                .setSmallIcon(R.drawable.position)
                .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), 0))
                .build())

        // prepare delayed operations
        handler = Handler(mainLooper)

        // retrieve service parameters
        val antennaConfig = intent!!.getParcelableExtra<AntennaConfig>(ANTENNA_CONFIG)!!
        val corsConfig = intent.getParcelableExtra<CORSConfig>(CORS_CONFIG) // null if no cors

        rtkCorrections = corsConfig != null
        if (rtkCorrections) ggaPeriod = corsConfig!!.ggaPeriod

        // setup communication with cors server and antenna
        antennaManager = AntennaManager(antennaConfig, object : AntennaManager.Listener {
            override fun onPosition(position: Position) {
                // send new device position decoded by antenna to app
                sendBroadcast(Intent(ServiceWrapper.UPDATE).putExtra(ServiceWrapper.POSITION, position))

                if (rtkCorrections && corsManager!!.stopped) {
                    // start connection with cors if not already started
                    corsManager!!.start()
                    handler.post(::periodicalGGA) // start sending position updates to cors
                }
            }
            override fun onStop(reason: String) = error(reason) // stop service in case of antenna error
        })
        if (rtkCorrections) corsManager = CORSManager(corsConfig!!, object : CORSManager.Listener {
            override fun onCorrections(data: ByteArray, len: Int) = antennaManager.sendCorrections(data, len) // transmit cors corrections to antenna
            override fun onStop(reason: String) = error(reason) // stop service in cas of cors error
        })


        // start connection with antenna
        if (!antennaManager.connectAntenna(this)) error(getString(R.string.antenna_connection_error))

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
        antennaManager.stop(getString(R.string.force_stop))
        if (rtkCorrections) corsManager!!.stop(getString(R.string.force_stop))
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
    private fun error(message: String) { if (!stop) sendBroadcast(Intent(ServiceWrapper.ERROR).putExtra(ServiceWrapper.MESSAGE, message)) }

}
