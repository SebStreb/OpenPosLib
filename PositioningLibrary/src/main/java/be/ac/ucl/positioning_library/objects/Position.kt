package be.ac.ucl.positioning_library.objects

import android.location.Location
import android.location.LocationManager
import android.os.Parcelable
import kotlinx.parcelize.Parcelize


/**
 * Geographical position.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate") // available for library usage
@Parcelize // parcelable so we can send through android bundles
data class Position internal constructor(
        private var lat: Double = 0.0,
        private var lon: Double = 0.0,
        private var alt: Double = 0.0,
        private var diff: Double = 0.0,
        private var hAcc: Float = 0f,
        private var vAcc: Float = 0f,
        private var time: Long = 0L,
) : Parcelable { // public val backed up by private var so we can use internal setters


    /**
     * Latitude of the position, in degrees relative to the WGS84 ellipsoid.
     */
    val latitude get() = lat

    /**
     * Longitude of the position, in degrees relative to the WGS84 ellipsoid.
     */
    val longitude get() = lon


    /**
     * Altitude of the position (orthometric height), in meters above the mean sea level (WGS84 geoid).
     * Warning: Android [Location.getAltitude] uses ellipsoid height instead of orthometric height.
     */
    val altitude get() = alt
    /**
     * Removes [antennaSize] from [altitude] to correct actual value.
     */
    internal fun adjustAntennaSize(antennaSize: Double) { alt -= antennaSize }

    /**
     * Difference in height between geoid and ellipsoid  WGS84 model at this position, in meters.
     * For ZED-FP9 antenna, the geoid model is EGM96. TODO check?
     */
    val geoidHeight get() = diff

    /**
     * Height of the position above the WGS84 ellipsoid, in meters.
     */
    val ellipsoidHeight get() = altitude + geoidHeight


    /**
     * Horizontal accuracy of the position measurement (horizontal latitude/longitude radius standard deviation), in meters.
     */
    val horizontalAccuracy get() = hAcc
    /**
     * Sets [horizontalAccuracy] when it is available.
     */
    internal fun setHAcc(acc: Float) { hAcc = acc }

    /**
     * Vertical accuracy of the position measurement (altitude standard deviation), in meters.
     */
    val verticalAccuracy get() = vAcc
    /**
     * Sets [verticalAccuracy] when it is available.
     */
    internal fun setVAcc(acc: Float) { vAcc = acc }


    /**
     * UTC time at which the position has been measured, in milliseconds since January 1, 1970.
     */
    val timestamp get() = time


    /**
     * Get an android [Location] corresponding to this [Position].
     *
     * @return the corresponding location
     */
    fun toLocation(): Location = Location(LocationManager.GPS_PROVIDER).apply {
        latitude = this@Position.latitude
        longitude = this@Position.longitude
        altitude = ellipsoidHeight
        accuracy = horizontalAccuracy
        verticalAccuracyMeters = verticalAccuracy
        time = timestamp
    }


    // TODO conversion methods


    companion object {

        /**
         * Create a [Position] object from an Android [Location] object.
         *
         * @param location the android location object
         * @return the corresponding position object
         */
        fun fromLocation(location: Location) = Position(location.latitude, location.longitude,
                location.altitude, 0.0, // TODO separate altitude & geoid height from ellipsoid height
                location.accuracy, location.verticalAccuracyMeters, location.time)

        /**
         * Create a [Position] object from coordinates in WGS84 datum.
         *
         * @param lat latitude in degrees relative to WGS84 ellipsoid
         * @param lon latitude in degrees relative to WGS84 ellipsoid
         * @param alt altitude in meters above mean sea level (WGS84 geoid)
         * @param diff difference in height between geoid and ellipsoid  WGS84 model at this position
         * @param hAcc horizontal accuracy in meters
         * @param vAcc vertical accuracy in meters
         * @param time UTC timestamp of the position measurement in milliseconds since January 1, 1970
         * @return the corresponding position object
         */
        fun fromWGS84(lat: Double, lon: Double, alt: Double, diff: Double, hAcc: Float, vAcc: Float, time: Long) =
                Position( lat, lon, alt, diff, hAcc, vAcc, time)

        /**
         * Create a [Position] object from coordinates retrieved in an NMEA GGA message, in WGS84 datum.
         * There is no information about accuracies in GGA message, they should be added afterwards.
         *
         * @param time UTC timestamp of the position measurement in milliseconds since January 1, 1970
         * @param lat latitude in degrees relative to WGS84 ellipsoid
         * @param lon longitude in degrees relative to WGS84 ellipsoid
         * @param alt altitude in meters above mean sea level (WGS84 geoid)
         * @param gHeight difference in meters of height between geoid and ellipsoid  WGS84 model at this position
         * @return the corresponding position object
         */
        internal fun fromGGA(time: Long, lat: Double, lon: Double, alt: Double, gHeight: Double) =
                Position(lat = lat, lon = lon, alt = alt, diff = gHeight, time = time)

    }

}
