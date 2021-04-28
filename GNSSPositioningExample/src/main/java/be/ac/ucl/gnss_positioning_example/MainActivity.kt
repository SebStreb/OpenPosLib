package be.ac.ucl.gnss_positioning_example

import android.Manifest
import android.content.pm.PackageManager
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
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter


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
        // identify permission events
        private const val PERM_REQUEST = 419
    }


    // UI elements
    private lateinit var serviceMode: Spinner
    private lateinit var serviceStatus: TextView
    private lateinit var toggleService: Button

    private lateinit var latitude: TextView
    private lateinit var longitude: TextView
    private lateinit var hAcc: TextView

    private lateinit var altitude: TextView
    private lateinit var height: TextView
    private lateinit var vAcc: TextView

    private lateinit var time: TextView


    // positioning library
    private lateinit var positioningLibrary: PositioningLibrary

    // execution mode of the library
    private lateinit var mode: PositioningMode

    private val modeNames = mapOf(
            "Android API" to PositioningMode.BASIC,
            "Internal antenna" to PositioningMode.INTERNAL,
            "Internal antenna with RTK" to PositioningMode.INTERNAL_RTK,
            "External antenna" to PositioningMode.EXTERNAL,
            "External antenna with RTK" to PositioningMode.EXTERNAL_RTK,
    )

    // configuration of the CORS server connection (check you cors.properties file)
    private val corsConfig = CORSConfig(
            BuildConfig.CORS_ADDRESS,
            BuildConfig.CORS_PORT,
            BuildConfig.CORS_MOUNT_POINT,
            BuildConfig.CORS_USERNAME,
            BuildConfig.CORS_PASSWORD,
    )


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // register logs
        val timestamp = System.currentTimeMillis().toString()
        val logcatFile = File(filesDir, "$timestamp-logcat.log").apply { createNewFile() }
        Runtime.getRuntime().exec("logcat -f ${logcatFile.path}")

        // get UI elements
        serviceMode = findViewById(R.id.service_mode)
        serviceStatus = findViewById(R.id.service_status)
        toggleService = findViewById(R.id.toggle_service)
        latitude = findViewById(R.id.latitude)
        longitude = findViewById(R.id.longitude)
        hAcc = findViewById(R.id.h_acc)
        altitude = findViewById(R.id.altitude)
        height = findViewById(R.id.height)
        vAcc = findViewById(R.id.v_acc)
        time = findViewById(R.id.time)

        // set up positioning library
        positioningLibrary = PositioningLibrary(this)

        // set up mode selection spinner
        serviceMode.adapter = ArrayAdapter(this, R.layout.spinner, modeNames.keys.toList()).apply {
            setDropDownViewResource(R.layout.spinner_dropdown)
        }
        serviceMode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                mode = modeNames[serviceMode.selectedItem as String]!!
                if (positioningLibrary.running) {
                    stopService(getString(R.string.change_exec_mode))
                    startService()
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) { mode = PositioningMode.BASIC }
        }

        // set up start/stop button
        toggleService.setOnClickListener { if (positioningLibrary.running) stopService(getString(R.string.stopped)) else startService() }

        // start listening for USB device
        positioningLibrary.startUSBAntennaListener(this)
    }

    override fun onDestroy() {
        super.onDestroy()
        positioningLibrary.stopUSBAntennaListener(this)
        if (positioningLibrary.running) stopService(getString(R.string.app_closed))
    }



    /**
     * Start the positioning service.
     */
    private fun startService() {
        // request permissions if necessary
        requestPermissionIfNecessary()

        // configure positioning library
        when (mode) {
            PositioningMode.BASIC -> positioningLibrary.setBasicMode()
            PositioningMode.INTERNAL -> positioningLibrary.setInternalMode()
            PositioningMode.INTERNAL_RTK -> positioningLibrary.setCorrectedMode(corsConfig)
            PositioningMode.EXTERNAL -> positioningLibrary.setExternalMode(AntennaConfig(1.8))
            PositioningMode.EXTERNAL_RTK -> positioningLibrary.setExternalCorrectedMode(AntennaConfig(1.8), corsConfig)
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
        latitude.text = getString(R.string.lat_lon, position.latitude)
        longitude.text = getString(R.string.lat_lon, position.longitude)
        hAcc.text = getString(R.string.meters, position.horizontalAccuracy)

        altitude.text = getString(R.string.meters, position.altitude)
        height.text = getString(R.string.meters, position.ellipsoidHeight)
        vAcc.text = getString(R.string.meters, position.verticalAccuracy)

        time.text = Instant.ofEpochMilli(position.timestamp).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss O"))
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
