package be.ac.ucl.gnsspositioning.objects

import android.hardware.usb.UsbDevice
import android.os.Parcelable
import kotlinx.parcelize.Parcelize


/**
 * Configuration for the use of an external antenna.
 *
 * @property antennaSize size of the antenna, in centimeters
 * @property baudRate baud rate of the serial connection with the antenna
 * @property dataBits number of data bits in the serial connection with the antenna [5-8]
 * @property stopBits number of stop bits in the serial connection with the antenna [1-3]
 * @property parity parity used for the serial connection with the antenna
 * - 0: none
 * - 1: odd
 * - 2: even
 * - 3: mark
 * - 4: space
 * @property flowControl flow control used for the serial connection with the antenna
 * - 0: off
 * - 1: RST-CTS
 * - 2: DSR-DTR
 * - 3: XON-XOFF
 * @property bufferSize size of the buffer for the communication with the antenna
 */
@Parcelize
data class AntennaConfig(
        val antenna: UsbDevice,
        val antennaSize: Int,
        val baudRate: Int = 38400,
        val dataBits: Int = 8,
        val stopBits: Int = 1,
        val parity: Int = 0,
        val flowControl: Int = 0,
        val bufferSize: Int = 4096,
) : Parcelable
