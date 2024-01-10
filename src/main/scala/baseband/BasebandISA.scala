package baseband

import chisel3._
import chisel3.util._

object BasebandISA {
  // primaryInst values and corresponding instructions

  /* Configure command:
      Configure baseband constants. If secondaryInst = CONFIG_LO_LUT, the data field holds the
      LO LUT address to be set in the form {[0 for FSK / 1 for CT], 6-bit address}, else the data field is a Don't Care.
      [ Data = <LO LUT address / X> | secondaryInst = <target constant> | primaryInst = 0 ]
      [ additionalData = <value> ]
   */
  val CONFIG_CMD = 0.U

  // secondaryInst values for CONFIG_CMD
  val CONFIG_RADIO_MODE = 0.U
  val CONFIG_CRC_SEED = 1.U
  val CONFIG_ACCESS_ADDRESS = 2.U
  val CONFIG_SHR = 3.U
  val CONFIG_BLE_CHANNEL_INDEX = 4.U
  val CONFIG_LRWPAN_CHANNEL_INDEX = 5.U


  /* Send command:
      Transmit a specified number of PDU header and data bytes. Bytes are gathered by loading them from the
      specified address
      [ Data = <total bytes> | secondaryInst = X | primaryInst = 1 ]
      [ additionalData = <load address> ]
   */
  val SEND_CMD = 1.U

  /* Receive start command:
      Place the device into receive mode. If a message is picked up, it will be stored starting at
      the specified storage address.
      [ Data = X | secondaryInst = X | primaryInst = 2 ]
      [ additionalData = <storage address> ]
 */
  val RECEIVE_START_CMD = 2.U

  /* Receive exit command:
      Exit the device from receive mode.
      [ Data = X | secondaryInst = X | primaryInst = 3 ]
      [ additionalData = X ]
 */
  val RECEIVE_EXIT_CMD = 3.U

  /* Debug command:
      Turns on both the RX and TX paths according to the loopback mask and passes the specified number of PDU
      header and data bytes in a loop. For simplicity the return data is stored at <load address + total bytes>,
      rounded to the nearest byte aligned address.
      [ Data = <total bytes> | secondaryInst = <loopback mask> | primaryInst = 15 ]
      [ additionalData = <load address> ]
   */
  val DEBUG_CMD = 15.U

  // Interrupt reason codes
  val TX_INVALID_LENGTH = 0
  val TX_NOT_ENOUGH_DMA_DATA = 1

  val RX_INVALID_ADDR = 32
  val RX_FLAG_AA = 33
  val RX_FLAG_CRC = 34
  val RX_FLAG_AA_AND_CRC = 35

  def ERROR_MESSAGE(reason: Int, message: Int = 0): UInt = {
    Cat(message.U(26.W), reason.U(6.W))
  }

  def ERROR_MESSAGE(reason: Int, message: UInt): UInt = {
    Cat(message.pad(26), reason.U(6.W))
  }

  def RX_FINISH_MESSAGE(bytesReceived: UInt): UInt = {
    bytesReceived.pad(32)
  }
}