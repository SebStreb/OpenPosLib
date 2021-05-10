package be.ac.ucl.positioning_library.objects

import android.location.Location
import android.location.LocationManager
import android.os.Parcelable
import android.util.Log
import com.programmerare.crsConstants.constantsByAreaNameNumber.v9_8_9.EpsgNumber
import com.programmerare.crsTransformations.compositeTransformations.CrsTransformationAdapterCompositeFactory.createCrsTransformationMedian
import com.programmerare.crsTransformations.coordinate.latLon
import kotlinx.parcelize.Parcelize
import kotlin.math.pow
import kotlin.math.sqrt


/**
 * Geographical position.
 */
@Suppress("unused", "MemberVisibilityCanBePrivate") // available for library usage
@Parcelize // parcelable so we can send through android bundles
data class Position internal constructor(
        private var lat: Double = 0.0,
        private var lon: Double = 0.0,

        private var alt: Double = 0.0,
        private var height: Double = 0.0,

        private var latAcc: Double = 0.0,
        private var lonAcc: Double = 0.0,
        private var altAcc: Double = 0.0,

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
     * Height of the position above the WGS84 ellipsoid, in meters.
     */
    val ellipsoidHeight get() = height

    /**
     * Difference in height between geoid and ellipsoid WGS84 model at this position, in meters.
     */
    val geoidHeight get() = height - alt

    /**
     * Adjust altitude and height to correct antenna size.
     */
    internal fun adjustAntennaSize(antennaSize: Double) {
        alt -= antennaSize
        height -= antennaSize
    }

    /**
     * Standard deviation of the [latitude] measurement, in meters.
     */
    val latitudeAccuracy get() = latAcc

    /**
     * Standard deviation of the [longitude] measurement, in meters.
     */
    val longitudeAccuracy get() = lonAcc

    /**
     * Standard deviation the measurement (horizontal latitude/longitude radius), in meters.
     */
    val horizontalAccuracy get() = sqrt(latAcc.pow(2) + lonAcc.pow(2))

    /**
     * Standard deviation of the [altitude] (and [ellipsoidHeight]) measurement, in meters.
     */
    val verticalAccuracy get() = altAcc


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
        accuracy = horizontalAccuracy.toFloat()
        verticalAccuracyMeters = verticalAccuracy.toFloat()
        time = timestamp
    }


    /**
     * Get a projection of this [Position] on a different Coordinate Reference System (CRS).
     *
     * @param epsgNumber the EPSG number of the target CRS (see [https://epsg.org] for references, or use one of [EpsgNumber] constants)
     * @return the coordinates in the target CRS, first along the X/Easting/longitude axis and second along the Y/Northing/latitude axis, or null if not transformation could be found to that CRS
     */
    fun projectTo(epsgNumber: Int): Pair<Double, Double>? {
        val before = toCrsCoordinate()
        if (epsgNumber == EpsgNumber.WORLD__WGS_84__4326) return Pair(before.getX(), before.getY())

        val after = createCrsTransformationMedian().transform(before, epsgNumber)
        if (!after.isSuccess || !after.isReliable(4, 0.01)) {
            Log.wtf("Transform", "not working: $epsgNumber")
            return null
        }

        return Pair(after.outputCoordinate.getX(), after.outputCoordinate.getY())
    }

    /**
     * Get a CRS Coordinate from transformation library corresponding to this [Position] in WGS 84.
     *
     * @return the corresponding crs coordinate
     */
    private fun toCrsCoordinate() = latLon(latitude, longitude, EpsgNumber.WORLD__WGS_84__4326)



    companion object {

        /**
         * Create a [Position] object from coordinates in WGS84 datum.
         *
         * @param lat latitude in degrees relative to WGS84 ellipsoid
         * @param lon latitude in degrees relative to WGS84 ellipsoid
         *
         * @param alt altitude in meters above mean sea level (WGS84 geoid)
         * @param height height in meters above WGS84 ellipsoid
         *
         *
         *
         * @param latAcc latitude standard deviation in meters
         * @param lonAcc longitude standard deviation in meters
         * @param altAcc altitude standard deviation in meters
         *
         * @param time UTC timestamp of the position measurement in milliseconds since January 1, 1970
         *
         * @return the corresponding position object
         */
        fun fromWGS84(
            lat: Double, lon: Double,
            alt: Double, height: Double,
            latAcc: Double, lonAcc: Double, altAcc: Double,
            time: Long) =
                Position(lat, lon, alt, height, latAcc, lonAcc, altAcc, time)

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
                Position(lat = lat, lon = lon, alt = alt, height = alt + gHeight, time = time)

    }

}
