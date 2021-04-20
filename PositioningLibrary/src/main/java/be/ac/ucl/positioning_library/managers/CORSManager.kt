package be.ac.ucl.positioning_library.managers

import android.util.Base64
import be.ac.ucl.positioning_library.objects.CORSConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.ConnectException
import java.net.Socket
import kotlin.concurrent.thread


/**
 * Manager to handle connection with CORS server.
 *
 * @property listener Callbacks of the manager.
 */
internal class CORSManager(private val CORSConfig: CORSConfig, private val listener: Listener) {

    /**
     * Callbacks of the manager.
     */
    interface Listener {

        /**
         * Callback called when the CORS server has sent corrections data.
         *
         * @param data the correction data sent by the CORS server
         * @param len number of bytes of the data
         */
        fun onCorrections(data: ByteArray, len: Int)

        /**
         * Callback called when the connection with the CORS server was stopped.
         */
        fun onStop()

    }


    /**
     * Status of the connection with CORS server.
     */
    private enum class Status {

        /**
         * The connection with the CORS server is not started.
         */
        STOP,

        /**
         * The connection with the CORS server is started, but not yet established.
         */
        CONNECTING,

        /**
         * The connection with the CORS server is established.
         */
        CONNECTED,

    }


    // Socket of the connection with cors server, null if not started
    private var corsConnection: Socket? = null

    // Actual status of the connection with cors server
    private var status = Status.STOP

    // Thread to read cors data
    private val readCors = thread(start = false) {
        while (!stopped) {
            try {
                val buffer = ByteArray(CORSConfig.bufferSize)
                val len = corsConnection!!.inputStream.read(buffer, 0, CORSConfig.bufferSize)
                if (len != -1) {
                    if (status == Status.CONNECTING && String(buffer, 0, len).contains("ICY 200 OK"))
                        status = Status.CONNECTED
                    else listener.onCorrections(buffer, len)
                } else throw IOException()
            } catch (e: IOException) { stop() } // stop on error
        }
    }

    /**
     * True if the connection with the COR server is stopped, false otherwise.
     */
    val stopped get() = status == Status.STOP


    /**
     * Start the connection with the CORS server.
     * Manager is in the state [Status.CONNECTING].
     */
    fun start() {
        status = Status.CONNECTING

        GlobalScope.launch(Dispatchers.IO) { // run on IO thread
            // open socket
            try { corsConnection = Socket(CORSConfig.address, CORSConfig.port) }
            catch (e: ConnectException) { return@launch stop() }

            // get request using cors settings
            var request = "GET /${CORSConfig.mountPoint} HTTP/1.0\r\nUser-Agent: NTRIP INTERNAL Positioning\r\n"
            request += if (CORSConfig.username != null && CORSConfig.password != null) {
                val authorization = Base64.encodeToString(
                    "${CORSConfig.username} :${CORSConfig.password}".toByteArray(),
                    Base64.DEFAULT
                )
                "Authorization: Basic $authorization\r\n\r\n"
            } else "Accept: */*\r\nConnection: close\r\n\r\n"

            // send request
            corsConnection!!.outputStream.write(request.toByteArray())

            // start to read cors data
            readCors.start()
        }
    }

    /**
     * Stop the connection with the CORS server.
     * Manager is in the state [Status.STOP].
     */
    fun stop() {
        corsConnection?.close()
        status = Status.STOP
        listener.onStop() // inform that we lost connection
    }


    /**
     * Send GGA NMEA message to CORS server to update
     * our actual position and keep alive the connection.
     *
     * @param gga the GGA message to send
     */
    fun sendGGA(gga: String) {
        // only send if we are connected
        // if sent while connecting, CORS will stop connection
        if (status == Status.CONNECTED) GlobalScope.launch(Dispatchers.IO) { // run on IO thread
            // send GGA if all is working, ignore errors (?)
            try { corsConnection?.outputStream?.write(gga.toByteArray()) } catch (_: IOException) {}
        }
    }

}
