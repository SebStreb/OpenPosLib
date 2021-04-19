package be.ac.ucl.gnsspositioning.services

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
import be.ac.ucl.gnsspositioning.R
import be.ac.ucl.gnsspositioning.ServiceWrapper
import be.ac.ucl.gnsspositioning.example.MainActivity
import be.ac.ucl.gnsspositioning.managers.NMEADecoder
import be.ac.ucl.gnsspositioning.objects.Position

class AndroidLocationService : Service() {

    companion object {
        const val USE_GNSS = "use_gnss"

        // Identify service notification
        private const val FOREGROUND_ID = 421
        private const val CHANNEL_ID = "be.ac.ucl.gnsspositioning.ANDROID_LOCATION_SERVICE"
    }

    private lateinit var locationManager: LocationManager

    private var useGNSS = false

    private lateinit var handler: Handler
    private lateinit var nmeaDecoder: NMEADecoder

    private var lastPrecision = 0.0

    private val locationListener = LocationListener { location ->
        Log.wtf("Actual location", Position(location.latitude, location.longitude, location.altitude, location.accuracy * 100.0).toString())
        /*
        sendBroadcast(Intent(ServiceWrapper.UPDATE).putExtra(ServiceWrapper.POSITION,
                Position(location.latitude, location.longitude, location.altitude, location.accuracy * 100.0)
        ))

         */
    }

    private val nmeaMessageListener = OnNmeaMessageListener { nmea, _ ->
        val solution = nmeaDecoder.getSolution(nmea)
        when (solution.type) {
            NMEADecoder.SolutionType.PRECISION -> lastPrecision = solution.precision!!
            NMEADecoder.SolutionType.COORDINATES -> {
                val (lat, lon, alt) = solution.coordinates!!
                sendBroadcast(Intent(ServiceWrapper.UPDATE)
                        .putExtra(ServiceWrapper.POSITION, Position(lat, lon, alt, lastPrecision)))
            }
            else -> return@OnNmeaMessageListener
        }
    }

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

        locationManager = getSystemService(LocationManager::class.java)

        handler = Handler(mainLooper)
        nmeaDecoder = NMEADecoder()

        useGNSS = intent!!.getBooleanExtra(USE_GNSS, false)

        // should check permission before launching service so should not happen
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            sendBroadcast(Intent(ServiceWrapper.ERROR).putExtra(ServiceWrapper.MESSAGE, getString(R.string.perm_not_granted)))
            return START_STICKY
        }

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            sendBroadcast(Intent(ServiceWrapper.ERROR).putExtra(ServiceWrapper.MESSAGE, getString(R.string.gps_disabled)))
            return START_STICKY
        }

        locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0, 0f, locationListener)
        if (useGNSS) locationManager.addNmeaListener(nmeaMessageListener, handler)

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()

        locationManager.removeUpdates(locationListener)
        if (useGNSS) locationManager.removeNmeaListener(nmeaMessageListener)

        stopForeground(true)
    }

}
