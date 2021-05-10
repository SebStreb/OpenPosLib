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


/**
 * Monitor updates of device position using android APIs in background.
 */
internal class BasicService : Service() {

    companion object {
        // Identify service notification
        private const val FOREGROUND_ID = 421
        private const val CHANNEL_ID = "be.ac.ucl.positioning_library.ANDROID_LOCATION_SERVICE"
    }

    // android location manager
    private lateinit var locationManager: LocationManager

    // perform delayed operations
    private lateinit var handler: Handler

    // object to decode NMEA messages
    private var nmeaDecoder = NMEADecoder { position ->
        sendBroadcast(Intent(PositioningLibrary.UPDATE).putExtra(PositioningLibrary.POSITION, position))
    }

    // listener for new locations from android API
    private val locationListener = LocationListener { location ->
        nmeaDecoder.setAccuraciesFromAndroidAPI(location.accuracy.toDouble(), location.verticalAccuracyMeters.toDouble())
        Log.d("AndroidLocation", "hAcc: ${"%.2f m".format(location.accuracy)} - vAcc: ${"%.2f m".format(location.verticalAccuracyMeters)}")
    }

    // listener for new NMEA messages from android API
    private val nmeaMessageListener = OnNmeaMessageListener { nmea, _ -> nmeaDecoder.decode(nmea.trim()) }


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

        // get android location manager
        locationManager = getSystemService(LocationManager::class.java)

        // prepare delayed operations
        handler = Handler(mainLooper)

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
        locationManager.addNmeaListener(nmeaMessageListener, handler)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        // remove listeners
        locationManager.removeUpdates(locationListener)
        locationManager.removeNmeaListener(nmeaMessageListener)

        // remove notification
        stopForeground(true)
    }

}
