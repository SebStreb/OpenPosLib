package be.ac.ucl.positioning_library.managers

import android.util.Log
import be.ac.ucl.positioning_library.objects.Position
import java.time.Instant
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.sqrt


/**
 * Decode NMEA messages.
 */
internal class NMEADecoder {

    companion object { private const val TAG = "NMEADecoder" }

    /**
     * Type of possible [Solution].
     */
    enum class SolutionType {

        /**
         * No solution decoded.
         */
        INVALID,

        /**
         * Decoded new accuracies.
         */
        ACCURACIES,

        /**
         * Decoded new position.
         */
        POSITION,

    }

    /**
     * Solution received when decoding an NMEA.
     *
     * @property type type of the solution decoded
     * @property accuracies decoded accuracies, not null if [type] is [SolutionType.ACCURACIES]
     * @property position decoded position, not null if [type] is [SolutionType.POSITION]
     */
    data class Solution(val type: SolutionType, val accuracies: Pair<Float, Float>? = null, val position: Position? = null)


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
     * @param data the NMEA to decode with checksum and without line ending
     * @return a [Solution] describing what was decoded
     */
    fun getSolution(data: String): Solution {
        val sentence = data.drop(1).dropLast(3) // all between $ and *
        val checksum = data.takeLast(2) // checksum of sentence

        // FIXME PGLOR have no checksum ?!
        if (!validateChecksum(sentence, checksum)) {
            Log.d(TAG, "Wrong checksum: $data, expected ${computeChecksum(sentence)}")
            return Solution(SolutionType.INVALID)
        }

        val nmea = sentence.split(",") // remove checksum and get fields
        return when { // check type of NMEA
            nmea[0].contains("GST") -> parseGST(nmea)
            nmea[0].contains("GGA") -> {
                // update last GGA
                lastGGA = data
                parseGGA(nmea)
            }
            nmea[0].contains("DTM") || nmea[0].contains("GNS") -> {
                Log.wtf(TAG, "found some ${nmea[0]}")
                Solution(SolutionType.INVALID)
            }
            else -> Solution(SolutionType.INVALID) // no information to get from other NMEA
        }
    }


    /**
     * Decode a GST NMEA message.
     *
     * @param nmea the GST to decode
     * @return a [Solution] with the decoded accuracy, or an [SolutionType.INVALID] solution if the GST is not valid
     */
    private fun parseGST(nmea: List<String>): Solution {
        Log.d(TAG, nmea.joinToString(","))

        // check if GST is valid
        if (nmea.size != 9 || !validate(nmea[6]) || !validate(nmea[7]) || !validate((nmea[8]))) {
            Log.d(TAG, "invalid GST")
            return Solution(SolutionType.INVALID)
        }

        // get accuracies from GST
        val hAcc = sqrt(nmea[6].toDouble().pow(2) + nmea[7].toDouble().pow(2)).toFloat()
        val vAcc = nmea[8].toFloat()

        Log.d(TAG, "hAcc: ${"%.2f m".format(hAcc)}")
        Log.d(TAG, "vAcc: ${"%.2f m".format(vAcc)}")

        return Solution(SolutionType.ACCURACIES, accuracies = Pair(hAcc, vAcc))
    }

    /**
     * Decode a GGA NMEA message.
     *
     * @param nmea the GGA to decode
     * @return a [Solution] with the decoded coordinates, or an [SolutionType.INVALID] solution if the GGA is not valid
     */
    private fun parseGGA(nmea: List<String>): Solution {
        Log.d(TAG, nmea.joinToString(","))

        // check if GGA is valid
        if (nmea.size < 13 || !validate(nmea[1]) ||
                !validate(nmea[2]) || nmea[3] !in listOf("N", "S") ||
                !validate(nmea[4]) || nmea[5] !in listOf("E", "W") ||
                !validate(nmea[9]) || !validate(nmea[11])) {
            Log.d(TAG, "invalid GGA")
            return Solution(SolutionType.INVALID)
        }

        val dateTimestamp = LocalDate.now().toEpochDay() * 24*60*60*1000
        val hourTimestamp = nmea[1].substring(0, 2).toLong() * 60*60*1000
        val minuteTimestamp = nmea[1].substring(2, 4).toLong() * 60*1000
        val secondTimestamp = nmea[1].substring(4, 6).toLong() * 1000
        val millisecondTimestamp = nmea[1].substring(7).toLong()

        // get coordinates from GGA
        val timestamp = dateTimestamp + hourTimestamp + minuteTimestamp + secondTimestamp + millisecondTimestamp
        val lat = dms2lla(nmea[2], nmea[3])
        val lon = dms2lla(nmea[4], nmea[5])
        val alt = nmea[9].toDouble()
        val offset = nmea[11].toDouble()

        Log.d(TAG, "timestamp: ${Instant.ofEpochMilli(timestamp)}")
        Log.d(TAG, "lat: ${"%.6f".format(lat)}")
        Log.d(TAG, "lon: ${"%.6f".format(lon)}")
        Log.d(TAG, "alt: ${"%.2f m".format(alt)}")
        Log.d(TAG, "gHeight: ${"%.2f m".format(offset)}")

        return Solution(SolutionType.POSITION, position = Position.fromGGA(timestamp, lat, lon, alt, offset))
    }


    private fun validateChecksum(sentence: String, checksum: String) = computeChecksum(sentence).equals(checksum, ignoreCase = true)

    /**
     * Check if an NMEA field represents a [Double].
     *
     * @param value the field to check
     * @return true if the field is a double number, false otherwise
     */
    private fun validate(value: String) = value.toDoubleOrNull() != null


    private fun computeChecksum(sentence: String) = sentence.fold(0) { acc, char -> acc xor char.toInt() }
            .toString(16).padStart(2, '0')

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
