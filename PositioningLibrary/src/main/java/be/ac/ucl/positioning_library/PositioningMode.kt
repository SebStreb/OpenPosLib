package be.ac.ucl.positioning_library

/**
 * Different execution modes of the library.
 */
enum class PositioningMode {

    /**
     * Use the basic android location API.
     */
    BASIC, // uses basic service

    /**
     * Use the raw GNSS capabilities of the device.
     */
    INTERNAL, // uses internal service

    /**
     * Use the raw GNSS capabilities of the device, corrected with RTK from a CORS server.
     */
    INTERNAL_RTK, // uses internal service & cors manager

    /**
     * Use an external GNSS antenna.
     */
    EXTERNAL, // uses external service (antenna manager)

    /**
     * Use an external GNSS antenna, corrected with RTK from a CORS server.
     */
    EXTERNAL_RTK, // uses external service (antenna manager & cors manager)

}
