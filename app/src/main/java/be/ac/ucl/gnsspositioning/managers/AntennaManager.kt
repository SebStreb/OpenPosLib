package be.ac.ucl.gnsspositioning.managers

import android.content.Context
import android.hardware.usb.UsbManager
import be.ac.ucl.gnsspositioning.objects.AntennaConfig
import be.ac.ucl.gnsspositioning.objects.Position
import com.felhr.usbserial.UsbSerialDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.IOException
import kotlin.concurrent.thread


/**
 * Manager to handle the connection with the antenna.
 *
 * @property listener Callbacks of the manager.
 */
class AntennaManager(private val antennaConfig: AntennaConfig, private val listener: Listener) {


    /**
     * Callbacks of the manager.
     */
    interface Listener {

        /**
         * Callback called when the antenna calculated a new position.
         *
         * @param position the [Position] calculated by the antenna
         */
        fun onPosition(position: Position)

        /**
         * Callback called when the connection with the antenna was stopped.
         *
         * @param reason description of the reason why the connection was stopped
         */
        fun onStop(reason: String)

    }


    /**
     * Status of the connection with the antenna.
     */
    private enum class Status {

        /**
         * The antenna is not connected.
         */
        STOP,

        /**
         * The antenna is connected, but background data exchange threads are stopped.
         */
        CONNECTED,

        /**
         * The antenna is connected and background data exchange threads are started.
         */
        STARTED,

    }


    // Antenna as device connected through serial port from felhr library
    private var antenna: UsbSerialDevice? = null

    // Actual status of the manager
    private var status = Status.STOP

    // Last precision received from antenna, 0.0 is none
    private var lastPrecision = 0.0

    // Object used to decode NMEA messages
    private var nmeaDecoder = NMEADecoder()

    val positionGGA get() = nmeaDecoder.lastGGA

    // Thread to read antenna data
    private val readAntenna = thread(start = false) {
        // stop when status asks to
        while (status == Status.STARTED) {
            try { // intercept connection errors
                // get data from antenna input stream
                val buffer = ByteArray(antennaConfig.bufferSize)
                val len = antenna?.inputStream?.read(buffer, 0, antennaConfig.bufferSize) ?:
                        throw IOException("Antenna not connected")

                // interpret antenna data, or stop if no data received
                if (len != -1) updatePosition(String(buffer, 0, len))
                else throw IOException("Antenna stopped responding")
            } catch (e: IOException) { stop(e.message ?: "Lost connection with antenna") } // stop on error
        }
    }


    /**
     * Start the connection with the antenna.
     * Status is [Status.CONNECTED], data exchange not started yet.
     *
     * @param context the context of the android app, to retrieve android USB system service
     * @return true if connection worked, false otherwise
     */
    fun connectAntenna(context: Context): Boolean {
        // open antenna as device connected through serial port
        val usbManager = context.getSystemService(UsbManager::class.java)
        antenna = UsbSerialDevice.createUsbSerialDevice(antennaConfig.antenna,
                usbManager.openDevice(antennaConfig.antenna))
        if (!antenna!!.syncOpen()) return false

        // set settings of the serial connection
        antenna!!.setBaudRate(antennaConfig.baudRate)
        antenna!!.setDataBits(antennaConfig.dataBits)
        antenna!!.setStopBits(antennaConfig.stopBits)
        antenna!!.setParity(antennaConfig.parity)
        antenna!!.setFlowControl(antennaConfig.flowControl)

        status = Status.CONNECTED
        return true
    }

    /**
     * Start background data exchange threads with the antenna.
     * Manager is in the state [Status.STARTED].
     */
    fun start() {
        if (status == Status.CONNECTED) {
            readAntenna.start()
            status = Status.STARTED
        }
    }

    /**
     * Stop connection with antenna.
     * Manager is in the state [Status.STOP].
     *
     * @param reason description of the reason why the connection needs to be stopped
     */
    fun stop(reason: String) {
        status = Status.STOP
        if (antenna?.isOpen == true) antenna!!.close()
        listener.onStop(reason) // inform that we lost connection
    }


    /**
     * Transmit CORRECTED corrections data to antenna.
     *
     * @param data CORRECTED correction data sent by CORS server
     * @param len number of bytes of the data to send
     */
    fun sendCorrections(data: ByteArray, len: Int) {
        // only send corrections if antenna is started
        if (status == Status.STARTED) GlobalScope.launch(Dispatchers.IO) { // send on IO thread
            // send correction if all is working, ignore errors (?)
            try { antenna?.outputStream?.write(data, 0, len) } catch (_: IOException) {}
        }
    }


    /**
     * Interpret data received from the antenna and potentially update position.
     *
     * @param data the data received by the antenna
     */
    private fun updatePosition(data: String) {
        // retrieve NMEAs from antenna data
        for (nmea in nmeaDecoder.getNMEAMessages(data)) {
            // interpret NMEA
            val solution = nmeaDecoder.getSolution(nmea)
            when (solution.type) {
                NMEADecoder.SolutionType.PRECISION -> lastPrecision = solution.precision!! // update actual precision
                NMEADecoder.SolutionType.COORDINATES -> { // update actual position
                    val (lat, lon, alt) = solution.coordinates!!
                    // inform of new position
                    // correct altitude with the size of the antenna
                    listener.onPosition(Position(lat, lon, alt - (antennaConfig.antennaSize / 100.0), lastPrecision))
                }
                else -> continue
            }
        }
    }

}
