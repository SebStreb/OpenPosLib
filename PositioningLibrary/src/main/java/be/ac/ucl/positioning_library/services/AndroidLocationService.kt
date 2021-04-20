package be.ac.ucl.positioning_library.services

import android.Manifest
import android.app.*
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationListener
import android.location.LocationManager
import android.location.OnNmeaMessageListener
import android.os.Handler
import android.os.IBinder
import android.util.Log
import androidx.core.app.ActivityCompat
import be.ac.ucl.positioning_library.PositioningLibrary
import be.ac.ucl.positioning_library.R
import be.ac.ucl.positioning_library.managers.NMEADecoder
import be.ac.ucl.positioning_library.objects.Position


/**
 * Monitor updates of device position using android APIs in background.
 */
internal class AndroidLocationService : Service() {

    companion object {
        // identify service parameters
        const val USE_GNSS = "use_gnss"

        // Identify service notification
        private const val FOREGROUND_ID = 421
        private const val CHANNEL_ID = "be.ac.ucl.positioning_library.ANDROID_LOCATION_SERVICE"
    }

    // android location manager
    private lateinit var locationManager: LocationManager

    // perform delayed operations
    private lateinit var handler: Handler

    // object to decode NMEA messages
    private var nmeaDecoder = NMEADecoder()

    // true if using values decoded from NMEA messages, false otherwise
    private var useGNSS = false

    // last precision decoded with NMEA messages
    private var lastPrecision = 0.0

    // listener for new locations from android API
    private val locationListener = LocationListener { location ->
        if (!useGNSS) sendBroadcast(Intent(PositioningLibrary.UPDATE).putExtra(PositioningLibrary.POSITION,
            Position(location.latitude, location.longitude, location.altitude, location.accuracy * 100.0)
        )) else Log.wtf("Android location", Position(location.latitude, location.longitude, location.altitude, location.accuracy * 100.0).toString())
    } // TODO check discrepancy

    // listener for new NMEA messages from android API
    private val nmeaMessageListener = OnNmeaMessageListener { nmea, _ ->
        val solution = nmeaDecoder.getSolution(nmea)
        when (solution.type) {
            NMEADecoder.SolutionType.PRECISION -> lastPrecision = solution.precision!!
            NMEADecoder.SolutionType.COORDINATES -> {
                val (lat, lon, alt) = solution.coordinates!!
                Log.wtf("NMEA location", Position(lat, lon, alt, lastPrecision).toString())
                sendBroadcast(Intent(PositioningLibrary.UPDATE)
                        .putExtra(PositioningLibrary.POSITION, Position(lat, lon, alt, lastPrecision)))
            }
            else -> return@OnNmeaMessageListener
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        // start notification to keep service running in background even if app is not in foreground
        getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.positioning_library_name), NotificationManager.IMPORTANCE_DEFAULT))
        startForeground(
            FOREGROUND_ID, Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.positioning_library_name))
                .setContentText(getString(R.string.positioning_library_active))
                .setSmallIcon(R.drawable.positioning_library_icon)
                .build())

        // get android location manager
        locationManager = getSystemService(LocationManager::class.java)

        // prepare delayed operations
        handler = Handler(mainLooper)

        // get service argument
        useGNSS = intent!!.getBooleanExtra(USE_GNSS, false)

        // check permission granted
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            sendBroadcast(Intent(PositioningLibrary.ERROR).putExtra(PositioningLibrary.MESSAGE, getString(R.string.positioning_library_permission_error)))
            return START_STICKY
        }

        // check gps enabled
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            sendBroadcast(Intent(PositioningLibrary.ERROR).putExtra(PositioningLibrary.MESSAGE, getString(R.string.positioning_library_location_disabled)))
            return START_STICKY
        }

        // start listeners
        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, locationListener)
        if (useGNSS) locationManager.addNmeaListener(nmeaMessageListener, handler)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        // remove listeners
        locationManager.removeUpdates(locationListener)
        if (useGNSS) locationManager.removeNmeaListener(nmeaMessageListener)

        // remove notification
        stopForeground(true)
    }

}
