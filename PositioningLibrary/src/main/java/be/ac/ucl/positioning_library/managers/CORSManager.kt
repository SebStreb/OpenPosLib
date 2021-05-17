package be.ac.ucl.positioning_library.managers

import android.util.Base64
import be.ac.ucl.positioning_library.objects.CORSConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.Socket
import kotlin.concurrent.thread


/**
 * Manager to handle connection with CORS server.
 *
 * @property corsConfig configuration of the connection with the CORS server
 * @property listener callbacks of the manager
 */
internal class CORSManager(private val corsConfig: CORSConfig, private val listener: Listener) {

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
                val buffer = ByteArray(corsConfig.bufferSize)
                val len = corsConnection!!.inputStream.read(buffer, 0, corsConfig.bufferSize)
                if (len != -1) {
                    if (status == Status.CONNECTING && String(buffer, 0, len).contains("ICY 200 OK")) {
                        status = Status.CONNECTED
                    } else listener.onCorrections(buffer, len)
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
    @Suppress("BlockingMethodInNonBlockingContext") // false positive with context IO
    fun start() {
        status = Status.CONNECTING

        // get request using cors settings
        val authorization = if (corsConfig.username == null || corsConfig.password == null) null else
            Base64.encodeToString("${corsConfig.username} :${corsConfig.password}".toByteArray(), Base64.DEFAULT)
        val request = "GET /${corsConfig.mountPoint} HTTP/1.0\r\nUser-Agent: NTRIP PositioningLibrary\r\n${
            if (authorization != null) "Authorization: Basic $authorization\r\n\r\n" 
            else "Accept: */*\r\nConnection: close\r\n\r\n"
        }"

        CoroutineScope(Dispatchers.IO).launch { // run on IO thread
            try { // open socket & send request
                corsConnection = Socket(corsConfig.address, corsConfig.port)
                corsConnection!!.soTimeout = 60*1000 // 1 min before timeout
                corsConnection!!.outputStream.write(request.toByteArray())
            } catch (e: IOException) { return@launch stop() }

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
    @Suppress("BlockingMethodInNonBlockingContext") // false positive with context IO
    fun sendGGA(gga: String) {
        // only send if we are connected
        // if sent while connecting, CORS will stop connection
        if (status == Status.CONNECTED) CoroutineScope(Dispatchers.IO).launch { // run on IO thread
            // send GGA if all is working, ignore errors (?)
            try { corsConnection?.outputStream?.write(gga.toByteArray()) } catch (_: IOException) {}
        }
    }

}
