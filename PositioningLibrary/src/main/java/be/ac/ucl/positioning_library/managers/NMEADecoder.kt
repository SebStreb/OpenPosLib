package be.ac.ucl.positioning_library.managers

import android.util.Log
import kotlin.math.sqrt


/**
 * Decode NMEA messages.
 */
internal class NMEADecoder {

    /**
     * Type of possible [Solution].
     */
    enum class SolutionType {

        /**
         * No solution decoded.
         */
        INVALID,

        /**
         * Decoded a new precision.
         */
        PRECISION,

        /**
         * Decoded new coordinates.
         */
        COORDINATES,

    }

    /**
     * Solution received when decoding an NMEA.
     *
     * If [type] is [SolutionType.PRECISION], [precision] is not null and contains new precision.
     *
     * If [type] is [SolutionType.COORDINATES], [coordinates] is not null and contains new coordinates.
     *
     * If [type] is [SolutionType.INVALID], [precision] and [coordinates] are null.
     */
    data class Solution(

        /**
         * Type of the solution decoded.
         */
        val type: SolutionType,

        /**
         * Precision decoded, null if no precision was decoded.
         */
        val precision: Double? = null,

        /**
         * Coordinates decoded, null if no coordinates were decoded.
         */
        val coordinates: Triple<Double, Double, Double>? = null,

        )


    /**
     * Last GGA message decoded.
     */
    var lastGGA = String()

    // Last incomplete data received for reconstruction of data bigger than buffer
    private var incompleteData = String()


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
     * Decode an NMEA message.
     *
     * @param data the NMEA to decode
     * @return a [Solution] describing what was decoded
     */
    fun getSolution(data: String): Solution {
        val nmea = data.split(",")
        return when { // check type of NMEA
            nmea[0].contains("GST") -> parseGST(nmea)
            nmea[0].contains("GGA") -> {
                // update last GGA
                lastGGA = data
                parseGGA(nmea)
            }
            else -> {
                Log.wtf("Discarded NMEA", nmea[0]) // TODO check android NMEA
                Solution(SolutionType.INVALID)
            } // no information to get from other NMEA
        }
    }


    /**
     * Decode a GST NMEA message.
     *
     * @param nmea the GST to decode
     * @return a [Solution] with the decoded precision, or an [SolutionType.INVALID] solution if the GST is not valid
     */
    private fun parseGST(nmea: List<String>): Solution {
        // check if GST is valid
        if (nmea.size < 8 || !validate(nmea[6], 0) || !validate(nmea[7], 0)) {
            Log.d("NMEADecoder", "GST: invalid (${nmea.joinToString(",")})")
            return Solution(SolutionType.INVALID)
        }

        // get precision from GST
        val prec = sqrt((nmea[6].toDouble()*nmea[6].toDouble()) + (nmea[7].toDouble()*nmea[7].toDouble())) * 100
        Log.d("NMEADecoder", "GST: ${"%.2f cm".format(prec)}")
        return Solution(SolutionType.PRECISION, precision = prec)
    }

    /**
     * Decodes GGA to get update of coordinates.
     */
    /**
     * Decode a GGA NMEA message.
     *
     * @param nmea the GGA to decode
     * @return a [Solution] with the decoded coordinates, or an [SolutionType.INVALID] solution if the GGA is not valid
     */
    private fun parseGGA(nmea: List<String>): Solution {
        // check if GGA is valid
        if (nmea.size < 10 || !validate(nmea[2], 4) || nmea[3] !in listOf("N", "S") ||
                !validate(nmea[4], 5) || nmea[5] !in listOf("E", "W") && !validate(nmea[9], 0)) {
            Log.d("NMEADecoder", "GGA: invalid (${nmea.joinToString(",")})")
            return Solution(SolutionType.INVALID)
        }

        // get coordinates from GGA
        val lat = dms2lla(nmea[2], nmea[3])
        val lon = dms2lla(nmea[4], nmea[5])
        val alt = nmea[9].toDouble()
        Log.d("NMEADecoder", "GGA: ${"%.6f".format(lat)} - ${"%.6f".format(lon)} - ${"%2f m".format(alt)}")
        return Solution(SolutionType.COORDINATES, coordinates = Triple(lat, lon, alt))
    }

    /**
     * Check if an NMEA field represents a [Double] with an expected size.
     *
     * @param value the field to check
     * @param minSize the expected size of the field, as a number of digits
     * @return true if the field is valid, false otherwise
     */
    private fun validate(value: String, minSize: Int) = value.toDoubleOrNull() != null && value.length > minSize

    /**
     * Convert latitude and longitude from degree/minute/second form to decimal form.
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
