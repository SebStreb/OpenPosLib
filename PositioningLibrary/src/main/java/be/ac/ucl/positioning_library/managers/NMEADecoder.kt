package be.ac.ucl.positioning_library.managers

import android.util.Log
import be.ac.ucl.positioning_library.PositioningLibrary
import be.ac.ucl.positioning_library.objects.Position
import java.time.Instant
import java.time.LocalDate
import kotlin.math.sqrt


/**
 * Decode NMEA messages.
 */
internal class NMEADecoder(private var heightOffset: Double, private val positionListener: PositionListener) {

    companion object { private const val TAG = "NMEADecoder" }


    /**
     * Callback for decoded positions.
     */
    fun interface PositionListener {
        /**
         * Callback called when a position has been decoded.
         *
         * @param position the decoded position
         */
        fun onPosition(position: Position)
    }


    /**
     * Supported datums by the library.
     *
     * @property desc string description of the datum used by NMEA DTM messages
     */
    private enum class Datum(val desc: String) {

        /**
         * World Geodetic System 1984: https://epsg.io/6326-datum
         */
        WGS_84("W84"),

    }


    /**
     * Partial decoded [Position], with potentially some information missing.
     *
     * @property timestamp decoded [Position.timestamp], always present
     * @property lat decoded [Position.latitude], present if [hasCoordinates] is true
     * @property lon decoded [Position.longitude], present if [hasCoordinates] is true
     * @property alt decoded [Position.altitude], present if [hasCoordinates] is true
     * @property gHeight decoded [Position.geoidHeight], present if [hasCoordinates] is true
     * @property hasCoordinates true if coordinate information are present in partial position, false otherwise
     * @property latAcc decoded [Position.latitudeAccuracy], present if [hasAccuracies] is true
     * @property lonAcc decoded [Position.longitudeAccuracy], present if [hasAccuracies] is true
     * @property altAcc decoded [Position.verticalAccuracy], present if [hasAccuracies] is true
     * @property hasAccuracies true if accuracy information are present in partial position, false otherwise
     */
    private inner class PartialPosition(
        val timestamp: Long,

        var lat: Double = 0.0,
        var lon: Double = 0.0,
        var alt: Double = 0.0,
        var gHeight: Double = 0.0,
        var hasCoordinates: Boolean = false,

        var latAcc: Double = 0.0,
        var lonAcc: Double = 0.0,
        var altAcc: Double = 0.0,
        var hasAccuracies: Boolean = false,
    ) {

        /**
         * Register coordinates information in partial position.
         *
         * @param lat decoded [Position.latitude]
         * @param lon decoded [Position.longitude]
         * @param alt decoded [Position.altitude]
         * @param gHeight decoded [Position.geoidHeight]
         */
        fun setCoordinates(lat: Double, lon: Double, alt: Double, gHeight: Double) {
            hasCoordinates = true
            this.lat = lat
            this.lon = lon
            this.alt = alt - heightOffset
            this.gHeight = gHeight
        }

        /**
         * Register accuracy information in partial position.
         *
         * @param latAcc decoded [Position.latitudeAccuracy]
         * @param lonAcc decoded [Position.longitudeAccuracy]
         * @param altAcc decoded [Position.verticalAccuracy]
         */
        fun setAccuracies(latAcc: Double, lonAcc: Double, altAcc: Double) {
            hasAccuracies = true
            this.latAcc = latAcc
            this.lonAcc = lonAcc
            this.altAcc = altAcc
        }

        /**
         * True if all information are present in partial position, false otherwise.
         */
        val isComplete get() = hasCoordinates && hasAccuracies

        /**
         * Get the corresponding [Position] when partial position [isComplete]
         *
         * @return the corresponding [Position]
         */
        fun toPosition() = when (currentDatum) {
            Datum.WGS_84 -> Position.fromWGS84(timestamp, lat, lon, alt, alt+gHeight, latAcc, lonAcc, altAcc)
        }
    }


    /**
     * Last decoded GGA message.
     */
    var lastGGA = String()


    // Last incomplete data received for reconstruction of data bigger than buffer
    private var incompleteData = String()

    // Current partial decoded position, null if none
    private var partialPosition: PartialPosition? = null

    // Current datum as announced in DTM messages, default WGS84
    private var currentDatum = Datum.WGS_84


    /**
     * Get list of NMEAs contained in antenna data.
     * This function uses [incompleteData] to reconstructed NMEAs split between calls .
     *
     * @param antennaData the data sent by the antenna
     * @return the list of NMEAs contained
     */
    fun getNMEAMessages(antennaData: String): List<String> {
        val completeData = incompleteData + antennaData

        val nmeas = mutableListOf<String>()
        var read = 0

        for (match in "\\$([^*$]*)\\*([0-9A-F][0-9A-F])".toRegex().findAll(completeData)) {
            nmeas += match.value
            read = match.range.last + 1
        }

        incompleteData = if (read < completeData.length) completeData.drop(read) else ""
        return nmeas
    }


    /**
     * Decode an NMEA message and update partial position.
     *
     * @param data the NMEA to decode with checksum and without line ending
     */
    fun decode(data: String) {
        // separate sentence & checksum
        // some android generated NMEA have no checksum to check
        val sentence: String
        if (data[0] == '$' && data[data.length - 3] == '*') {
            sentence = data.drop(1).dropLast(3) // all between $ and *

            if (!validateChecksum(sentence, data.takeLast(2))) {
                Log.d(TAG, "Wrong checksum: $data")
                return
            }
        } else sentence = if (data[0] == '$') data.drop(1) else data

        // separate fields
        val nmea = sentence.split(",")
        when { // check type of NMEA
            nmea[0].contains("GGA") -> {
                // update last GGA
                lastGGA = data
                parseGGA(nmea)
            }
            nmea[0].contains("GST") -> parseGST(nmea)
            nmea[0].contains("DTM") -> parseDTM(nmea)
            // else -> Log.d(TAG, "Nothing to do for NMEA ${nmea[0]}")
        }

        if (partialPosition?.isComplete == true) positionListener.onPosition(partialPosition!!.toPosition())
    }

    /**
     * Because Android API doesn't give GST messages, set manually accuracy values from location updates.
     *
     * @param hAcc horizontal accuracy, standard deviation in meters
     * @param altAcc altitude standard deviation in meters
     */
    fun setAccuraciesFromAndroidAPI(hAcc: Double, altAcc: Double) {
        // estimate latAcc & lonAcc based on hAcc
        // hAcc = sqrt(latAcc^2 + lonAcc^2)
        // if we take latAcc = lonAcc:
        // latAcc = lonAcc = hAcc / sqrt(2)
        val latLonAcc = hAcc / sqrt(2.0)
        partialPosition?.setAccuracies(latLonAcc, latLonAcc, altAcc)
        if (partialPosition?.isComplete == true) positionListener.onPosition(partialPosition!!.toPosition())
    }


    /**
     * Decode a GGA NMEA message.
     *
     * @param nmea the GGA to decode
     */
    private fun parseGGA(nmea: List<String>) {
        // check if GGA is valid
        if (nmea.size < 13 || !validate(nmea[1]) ||
                !validate(nmea[2]) || nmea[3] !in listOf("N", "S") ||
                !validate(nmea[4]) || nmea[5] !in listOf("E", "W") ||
                !validate(nmea[6]) || !validate(nmea[9]) || !validate(nmea[11])) {
            Log.d(TAG, "invalid GGA")
            return
        }

        // get GGA values
        val timestamp = parseTimestamp(nmea[1])
        val lat = dms2lla(nmea[2], nmea[3])
        val lon = dms2lla(nmea[4], nmea[5])
        val alt = nmea[9].toDouble()
        val gHeight = nmea[11].toDouble()

        // log GGA values
        Log.d(TAG, "timestamp: ${Instant.ofEpochMilli(timestamp)}")
        Log.d(TAG, "lat: ${"%.6f".format(lat)}")
        Log.d(TAG, "lon: ${"%.6f".format(lon)}")
        Log.d(TAG, "alt: ${"%.2f m".format(alt)}")
        Log.d(TAG, "gHeight: ${"%.2f m".format(gHeight)}")

        val type = nmea[6].toInt()
        if (PositioningLibrary.firstFloat && type == 5) {
            PositioningLibrary.firstFloat = false
            Log.wtf(PositioningLibrary.STATS, "First float at timestamp ${System.currentTimeMillis()}")
        }
        if (PositioningLibrary.firstFix && type == 4) {
            PositioningLibrary.firstFix = false
            Log.wtf(PositioningLibrary.STATS, "First fix at timestamp ${System.currentTimeMillis()}")
        }


        // update partial position
        val lastTimestamp = partialPosition?.timestamp ?: 0L
        if (timestamp < lastTimestamp) return // ignore old timestamp
        else if (timestamp > lastTimestamp) partialPosition = PartialPosition(timestamp) // move to new timestamp
        partialPosition!!.setCoordinates(lat, lon, alt, gHeight) // update coordinates
    }

    /**
     * Decode a GST NMEA message.
     *
     * @param nmea the GST to decode
     */
    private fun parseGST(nmea: List<String>) {
        // check if GST is valid
        if (nmea.size != 9 || !validate(nmea[1]) || !validate(nmea[6]) || !validate(nmea[7]) || !validate((nmea[8]))) {
            Log.d(TAG, "invalid GST")
            return
        }

        // get GST values
        val timestamp = parseTimestamp(nmea[1])
        val latAcc = nmea[6].toDouble()
        val lonAcc = nmea[7].toDouble()
        val altAcc = nmea[8].toDouble()

        // log GST values
        Log.d(TAG, "timestamp: ${Instant.ofEpochMilli(timestamp)}")
        Log.d(TAG, "latAcc: ${"%.2f m".format(latAcc)}")
        Log.d(TAG, "lonAcc: ${"%.2f m".format(lonAcc)}")
        Log.d(TAG, "altAcc: ${"%.2f m".format(altAcc)}")

        // update partial position
        val lastTimestamp = partialPosition?.timestamp ?: 0L
        if (timestamp < lastTimestamp) return // ignore old timestamp
        else if (timestamp > lastTimestamp) partialPosition = PartialPosition(timestamp) // move to new timestamp
        partialPosition!!.setAccuracies(latAcc, lonAcc, altAcc) // update accuracies
    }

    /**
     * Decode a DTM NMEA message.
     *
     * @param nmea the DTM to decode
     */
    private fun parseDTM(nmea: List<String>) {
        // check if DTM is valid
        if (nmea.size != 9) {
            Log.d(TAG, "invalid DTM")
            return
        }

        // get DTM values
        val datumDesc = nmea[1]
        var found = false
        for (datum in Datum.values()) {
            if (datum.desc == datumDesc) {
                currentDatum = datum
                found = true
                break
            }
        }

        // log DTM values
        if (!found) Log.wtf(TAG, "Not supported datum: $datumDesc")
        else Log.d(TAG, "Switched to datum: ${currentDatum.desc}")
    }


    /**
     * Check if an NMEA checksum is the expected one.
     *
     * @param sentence the NMEA sentence to check
     * @param checksum the provided NMEA checksum
     * @return true if the checksum is correct, false otherwise
     */
    private fun validateChecksum(sentence: String, checksum: String) = computeChecksum(sentence).equals(checksum, ignoreCase = true)

    /**
     * Compute an NMEA checksum for an NMEA sentence.
     *
     * @param sentence the NMEA sentence
     * @return the NMEA checksum
     */
    private fun computeChecksum(sentence: String) = sentence.fold(0) { acc, char -> acc xor char.code }.toString(16).padStart(2, '0')

    /**
     * Check if an NMEA field represents a [Double].
     *
     * @param value the field to check
     * @return true if the field is a double number, false otherwise
     */
    private fun validate(value: String) = value.toDoubleOrNull() != null


    /**
     * Convert NMEA UTC fix time to timestamp.
     *
     * @param field the NMEA time field
     * @return the timestamp
     */
    private fun parseTimestamp(field: String): Long {
        val dateTimestamp = LocalDate.now().toEpochDay() * 24*60*60*1000
        val hourTimestamp = field.substring(0, 2).toLong() * 60*60*1000
        val minuteTimestamp = field.substring(2, 4).toLong() * 60*1000
        val secondTimestamp = field.substring(4, 6).toLong() * 1000
        val millisecondTimestamp = field.substring(7).toLong()
        return dateTimestamp + hourTimestamp + minuteTimestamp + secondTimestamp + millisecondTimestamp
    }

    /**
     * Convert latitude and longitude from degree/minute/second string form to degrees in decimal form.
     *
     * @param value latitude or longitude to convert
     * @param hemisphere hemisphere of the latitude or longitude, as a single character string N, S, E, W
     * @return the latitude or longitude in decimal form
     */
    private fun dms2lla(value: String, hemisphere: String): Double {
        val isLon = hemisphere in listOf("E", "W")
        val isNeg = hemisphere in listOf("S", "W")
        val degrees = value.substring(0, degSize(isLon)).toDouble()
        val minutes = value.substring(degSize(isLon)).toDouble() / 60f
        return (degrees + minutes) * if (isNeg) -1 else 1
    }

    /**
     * Get the size of a latitude or a longitude field in NMEA, as a number of digits.
     *
     * @param isLon true if the field is a longitude, false if it is a latitude
     * @return the number of digits required in an NMEA field for a latitude or longitude
     */
    private fun degSize(isLon: Boolean) = if (isLon) 3 else 2

}
