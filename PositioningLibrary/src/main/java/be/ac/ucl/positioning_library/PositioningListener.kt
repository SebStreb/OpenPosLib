package be.ac.ucl.positioning_library

import be.ac.ucl.positioning_library.objects.Position


/**
 * Definition of callbacks for the use of the positioning library.
 */
interface PositioningListener {

    /**
     * Callback called when the positioning library measured a new position.
     *
     * @param position the position measured
     */
    fun onPosition(position: Position)

    /**
     * Callback called when the positioning library encountered an error and stopped itself.
     *
     * @param message a message describing the error
     */
    fun onError(message: String)

}
