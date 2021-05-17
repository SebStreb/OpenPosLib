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
@Suppress("unused") // available for library usage
@Parcelize // parcelable so we can send through android bundles
data class Position internal constructor(

    /**
     * UTC time at which the position has been measured, in milliseconds since January 1, 1970.
     */
    val timestamp: Long = 0L,


    /**
     * Latitude of the position, in degrees relative to the WGS84 ellipsoid.
     */
    val latitude: Double = 0.0,

    /**
     * Longitude of the position, in degrees relative to the WGS84 ellipsoid.
     */
    val longitude: Double = 0.0,

    /**
     * Altitude of the position (orthometric height), in meters above the mean sea level (WGS84 geoid).
     * Warning: Android [Location.getAltitude] uses ellipsoid height instead of orthometric height, see [ellipsoidHeight].
     */
    var altitude: Double = 0.0,


    /**
     * Height of the position above the WGS84 ellipsoid, in meters.
     */
    var ellipsoidHeight: Double = 0.0,

    /**
     * Standard deviation of the [latitude] measurement, in meters.
     */
    val latitudeAccuracy: Double = 0.0,

    /**
     * Standard deviation of the [longitude] measurement, in meters.
     */
    val longitudeAccuracy: Double = 0.0,


    /**
     * Standard deviation of the [altitude] (and [ellipsoidHeight]) measurement, in meters.
     */
    val verticalAccuracy: Double = 0.0,

) : Parcelable {


    /**
     * Difference in height between geoid and ellipsoid WGS84 model at this position, in meters.
     */
    val geoidHeight get() = ellipsoidHeight - altitude

    /**
     * Standard deviation the measurement (horizontal latitude/longitude radius), in meters.
     */
    val horizontalAccuracy get() = sqrt(latitudeAccuracy.pow(2) + longitudeAccuracy.pow(2))


    /**
     * Get an android [Location] corresponding to this [Position].
     *
     * @return the corresponding [Location]
     */
    fun toLocation() = Location(LocationManager.GPS_PROVIDER).apply {
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
         * Create a [Position] object from geographical coordinates in WGS84 datum.
         *
         * @param lat latitude in degrees relative to WGS84 ellipsoid
         * @param lon latitude in degrees relative to WGS84 ellipsoid
         *
         * @param alt altitude in meters above mean sea level (WGS84 geoid)
         * @param height height in meters above WGS84 ellipsoid
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
            time: Long,
            lat: Double, lon: Double,
            alt: Double, height: Double,
            latAcc: Double, lonAcc: Double, altAcc: Double,
        ) = Position(time, lat, lon, alt, height, latAcc, lonAcc, altAcc)

    }

}
