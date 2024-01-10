/* Based on (with permission) the Fall 2018 290C BLE Baseband
   https://github.com/ucberkeley-ee290c/fa18-ble/
 */

package baseband

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy.LazyModule
import freechips.rocketchip.subsystem.BaseSubsystem

// We trigger the PA by sending it the Access Address we wish to write a packet for and the length of the PDU body,
// which is known from the command we are sent
class LRWPANPAControlInputBundle extends Bundle {
  val sfd = UInt(8.W)
  val pduLength = UInt(8.W)
}

class LRWPANPAInputIO extends Bundle {
  val control = Flipped(Decoupled(new LRWPANPAControlInputBundle))
  val data = Flipped(Decoupled(UInt(8.W)))
}

class LRWPANPAOutputIO extends Bundle {
  val control = Output(new PAControlOutputBundle)
  val data = Decoupled(UInt(1.W))
}

class LRWPANPacketAssemblerIO extends Bundle {
  val in = new LRWPANPAInputIO
  val out = new LRWPANPAOutputIO
  val constants = Input(new BasebandConstants)
  val state = Output(UInt(log2Ceil(6+1).W))
}

class LRWPANPacketAssembler extends Module {

  def stateUpdate(currentState: UInt, nextState: UInt, length: UInt, counter: UInt, counterByte: UInt, condition: Bool)= {
    val stateOut = Wire(UInt(3.W))
    val counterOut = Wire(UInt(8.W))
    val counterByteOut = Wire(UInt(4.W))
    counterOut := counter
    counterByteOut := counterByte

    when(counter === length - 1.U && counterByte === 7.U && condition) {
      stateOut := nextState
      counterOut := 0.U
      counterByteOut := 0.U
    }.otherwise {
      stateOut := currentState
      when(condition) {
        when(counterByte === 7.U) {
          counterOut := counter + 1.U
          counterByteOut := 0.U
        }.otherwise {
          counterByteOut := counterByte + 1.U
        }
      }
    }
    (stateOut, counterOut, counterByteOut)
  }

  val io = IO(new LRWPANPacketAssemblerIO)

  // State
  // preamble - 4 bytes
  // sfd - 1 byte (11100101)
  // phr - 1 byte (frame length field)
  // psdu payload
  // crc
  val s_idle :: s_preamble :: s_sfd :: s_phr :: s_psdu_payload :: s_crc :: Nil = Enum(6)
  val state = RegInit(s_idle)

  io.state := state

  // Internal Counters
  val counter = RegInit(0.U(8.W)) // Counts bytes in a given message component
  val counter_byte = RegInit(0.U(3.W)) // Counts bit place within a given byte

  // Data registers
  val data = RegInit(0.U(8.W))
  val sfd = RegInit(0.U(8.W))
  val pdu_length = RegInit(0.U(8.W))
  val frame_length = RegInit(0.U(7.W))

  // Preamble is 4 octets of all zeroes
  val preamble = "b00000000".U

  // CRC
  val crc_reset =  io.in.control.fire()
  val crc_data = Wire(UInt(1.W))
  val crc_valid = Wire(Bool())
  val crc_result = Wire(UInt(24.W))
  val crc_seed = io.constants.crcSeed


  // Handshake parameters
  val data_in_ready = Reg(Bool())
  val data_out_valid = Reg(Bool())
  val data_out_fire = io.out.data.fire()
  val data_in_fire = io.in.data.fire()

  io.in.data.ready := data_in_ready
  io.out.data.valid := data_out_valid

  // Input control bits
  io.in.control.ready := state === s_idle

  // Output data
  val done = Reg(Bool())
  io.out.control.done := done
  io.out.data.bits := data(counter_byte)

  // Output control
  io.out.control.busy := state =/= s_idle

  // State Transition with counter updates
  when(state === s_idle) {
    data_in_ready := false.B
    data_out_valid := false.B
    done := false.B

    when(io.in.control.fire) {
      state := s_preamble
      counter := 0.U
      counter_byte := 0.U
      sfd := io.in.control.bits.sfd
      pdu_length := io.in.control.bits.pduLength
      val flen = io.in.control.bits.pduLength + 2.U
      frame_length := flen(6,0) // Truncate to 127, 15.4 MaxPHYPacketSize
    }
  }.elsewhen(state === s_preamble) {
    val (stateOut, counterOut, counterByteOut) = stateUpdate(s_preamble, s_sfd, 4.U, counter, counter_byte, data_out_fire)
    state := stateOut
    counter := counterOut
    counter_byte := counterByteOut
    data_out_valid := true.B
    // IMPORTANT: Do NOT do when() logic on stateOut, it will not propagate correctly on the next clock!
    // Do as shown below:
    when (counter === 3.U && counter_byte === 7.U && data_out_fire) {
      data := sfd                 // Place the SFD into data as the next byte
      data_in_ready := false.B    // We are not ready for the real data just yet
      data_out_valid := true.B   // Start PDU header with a out invalid (need to get input data first)
    }.otherwise {
      data := preamble
    }
  }.elsewhen(state === s_sfd) {
    val (stateOut, counterOut, counterByteOut) = stateUpdate(s_sfd, s_phr, 1.U, counter, counter_byte, data_out_fire)
    state := stateOut
    counter := counterOut
    counter_byte := counterByteOut
    data_out_valid := true.B
    when(counter_byte === 7.U && data_out_fire) {
      data := Cat(0.U(1.W), frame_length)
      data_in_ready := false.B  // We are not ready for the data just yet, one more byte (the frame length)
    }.otherwise {
      data := sfd
    }
  }.elsewhen(state === s_phr) {
    val next_state = Mux(pdu_length =/= 0.U, s_psdu_payload, s_crc) // If we have a zero length PDU, skip PDU payload
    val (stateOut, counterOut, counterByteOut) = stateUpdate(s_phr, next_state, 1.U, counter, counter_byte, data_out_fire)
    state := stateOut
    counter := counterOut
    counter_byte := counterByteOut

    when (counter_byte === 7.U && data_out_fire && next_state === s_crc) {
      data := crc_result(7,0)
      data_in_ready := false.B // Our next state is CRC, we do not need more data
      data_out_valid := true.B // The output is fully present in CRC
    }.elsewhen(counter_byte === 7.U && data_out_fire) { // We have cleared the last output bit from this byte
      data_in_ready := true.B // Now we're ready to receive the packet
      data_out_valid := false.B // But we need to wait for the byte to be ready
    }.otherwise {
      data := Cat(0.U(1.W), frame_length)
      data_in_ready := false.B
      data_out_valid := true.B
    }
  }.elsewhen(state === s_psdu_payload) {
    val (stateOut, counterOut, counterByteOut) = stateUpdate(s_psdu_payload, s_crc, pdu_length, counter, counter_byte, data_out_fire)
    state := stateOut
    counter := counterOut
    counter_byte := counterByteOut

    when (stateOut === s_crc) {
      data := crc_result(7,0)
      data_in_ready := false.B // Our next state is CRC, we do not need more data
      data_out_valid := true.B // The output is fully present in CRC
    }.elsewhen (counter_byte === 7.U && data_out_fire) { // We have cleared the last output bit from this byte
      data_in_ready := true.B
      data_out_valid := false.B
    }.elsewhen (data_in_fire) { // We have received a new input byte
      data_in_ready := false.B
      data_out_valid := true.B
    }
  }.elsewhen(state === s_crc) {
    val (stateOut, counterOut, counterByteOut) = stateUpdate(s_crc, s_idle, 2.U, counter, counter_byte, data_out_fire)
    state := stateOut
    counter := counterOut
    counter_byte := counterByteOut

    when(counterOut === 1.U) {
      data := crc_result(15,8)
    }

    when (stateOut === s_idle) {
      done := true.B // We are done on our transition to idle
      data_out_valid := false.B
      data := 0.U
      pdu_length := 0.U
      frame_length := 0.U
      sfd := 0.U
    }
  }.otherwise {
    state := s_idle
  }

  // Grab data when available
  when(state === s_psdu_payload) {
    when(data_in_fire) {
      data := io.in.data.bits
    }
  }

  //Set CRC Parameters
  when(state === s_psdu_payload) {
    crc_data := data(counter_byte)
    crc_valid := data_out_fire
  }.otherwise {
    crc_data := 0.U
    crc_valid := false.B
  }


  //Instantiate CRC Module
  val crc = Module(new CRC(CRCPolynomials.LRWPAN))

  crc.io.seed.bits  := crc_seed
  crc.io.seed.valid := crc_reset
  crc.io.in.bits    := crc_data
  crc.io.in.valid   := crc_valid
  crc_result        := crc.io.out
}
