package be.ac.ucl.positioning_library.objects

import android.os.Parcelable
import kotlinx.parcelize.Parcelize


/**
 * Configuration of a CORS connection for RTK corrections.
 *
 * @property address URL or IP of the CORS server
 * @property port port of the CORS server
 * @property mountPoint mount point to use on the CORS server
 * @property username username for the connection with the CORS server, null if no authentication needed
 * @property password password for the connection with the CORS server, null if no authentication needed
 * @property ggaPeriod time between two updates of the position to the CORS server as keep-alive, in milliseconds
 * @property bufferSize size of the buffer for the communication with the CORS server
 */
@Parcelize
data class CORSConfig(
        val address: String,
        val port: Int,
        val mountPoint: String,
        val username: String? = null,
        val password: String? = null,
        val ggaPeriod: Long = 30000L,
        val bufferSize: Int = 4096,
) : Parcelable
