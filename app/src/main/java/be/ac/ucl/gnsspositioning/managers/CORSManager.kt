package be.ac.ucl.gnsspositioning.managers

import android.util.Base64
import be.ac.ucl.gnsspositioning.objects.CORSConfig
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
class CORSManager(private val CORSConfig: CORSConfig, private val listener: Listener) {

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
         *
         * @param reason description of the reason why the connection was stopped
         */
        fun onStop(reason: String)

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
                } else throw IOException("CORS server stopped responding")
            } catch (e: IOException) { stop(e.message ?: "Lost connection with CORS server") }
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
            catch (e: ConnectException) { return@launch stop("Couldn't connect to CORS server") }

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
     *
     * @param reason description of the reason why the connection needs to be stopped
     */
    fun stop(reason: String) {
        corsConnection?.close()
        status = Status.STOP
        listener.onStop(reason) // inform that we lost connection
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
