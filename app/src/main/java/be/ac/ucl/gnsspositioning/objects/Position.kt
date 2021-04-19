package be.ac.ucl.gnsspositioning.objects

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import kotlin.math.*


/**
 * Geographical position.
 *
 * @property lat latitude of the position, in decimal form
 * @property lon longitude of the position, in decimal form
 * @property alt altitude of the position, in meters above sea level
 * @property prec precision of the position measurement, in centimeters, 0 if non available
 */
@Parcelize // parcelable so we can send through android bundles
data class Position (
        val lat: Double = 0.0,
        val lon: Double = 0.0,
        val alt: Double = 0.0,
        val prec: Double = 0.0,
) : Parcelable {

    // TODO conversion methods

    companion object {

        // Formula from https://www.movable-type.co.uk/scripts/latlong.html

        /**
         * Get the distance between two positions.
         *
         * @param pos1 the start position
         * @param pos2 the end position
         */
        fun distance(pos1: Position, pos2: Position): Double {
            val earthRadius = 6371e3
            val phi1 = pos1.lat * PI / 180
            val phi2 = pos2.lat * PI / 180
            val deltaPhi = (pos2.lat - pos1.lat) * PI / 180
            val deltaLambda = (pos2.lon - pos1.lon) * PI / 180

            val a = sin(deltaPhi / 2) * sin(deltaPhi / 2) +
                    cos(phi1) * cos(phi2) * sin(deltaLambda / 2) * sin(deltaLambda / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            return earthRadius * c
        }

        /**
         * Get the angle formed between north and the direction from a point to another.
         *
         * @param pos1 the start position
         * @param pos2 the end position
         */
        fun bearing(pos1: Position, pos2: Position): Double {
            val y = sin(pos2.lon - pos1.lon) * cos(pos2.lat)
            val x = cos(pos1.lat) * sin(pos2.lat) - sin(pos1.lat) * cos(pos2.lat) * cos(pos2.lon - pos1.lon)
            val theta = atan2(y, x) // the range of values is -π to π
            // when bearing is north, this angle is 0
            // when bearing is east, this angle is π/2
            // when bearing is south, this angle is π
            // when bearing is west, this angle is -π/2

            return (Math.toDegrees(theta) + 360) % 360 // the range of values is 0 to 360
            // when bearing is north, this angle is 0
            // when bearing is east, this angle is 90
            // when bearing is south, this angle is 180
            // when bearing is west, this angle is 270
        }

    }

}
