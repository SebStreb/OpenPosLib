package be.ac.ucl.positioning_library

/**
 * Different execution modes of the library.
 */
enum class PositioningMode {

    /**
     * Use the basic android location API.
     */
    BASIC, // uses location manager

    /**
     * Use the raw GNSS capabilities of the device.
     */
    INTERNAL, // uses gnss manager

    /**
     * Use the raw GNSS capabilities of the device, corrected with RTK from a CORS server.
     */
    CORRECTED, // uses gnss manager & cors manager

    /**
     * Use an external GNSS antenna.
     */
    EXTERNAL, // uses antenna manager

    /**
     * Use an external GNSS antenna, corrected with RTK from a CORS server.
     */
    EXTERNAL_CORRECTED, // uses antenna manager & cors manager

}
