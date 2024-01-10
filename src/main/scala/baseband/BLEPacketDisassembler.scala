package baseband

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

/* IO definitions shared between BLE and LRWPAN */
object PDAControlInputCommands {
  val START_CMD = 0.U
  val DEBUG_CMD = 1.U
  val EXIT_CMD  = 2.U
}

class PDAControlInputBundle extends Bundle {
  val command = UInt(2.W)
}

class PDADataInputIO extends Bundle {
  val sop      = Input(Bool())  // start-of-packet
  val eop      = Output(Bool()) // end-of-packet
  val data     = Flipped(Valid(UInt(8.W)))
  val bitCount = Input(UInt(32.W))
}

class PDAInputIO extends Bundle {
  val control = Flipped(Valid(new PDAControlInputBundle))
  val data    = new PDADataInputIO 
}

class PDAControlOutputBundle extends Bundle {
  val length   = UInt(8.W) // packet length
  val flag_sop = Bool()    // AA or SHR not matched (TODO needed?)
  val flag_crc = Bool()    // CRC fail
  val done     = Bool()    // done ("valid" for all other signals)
  val busy     = Bool()    // processing packet
}

class PDAOutputIO extends Bundle {
  val control = Output(new PDAControlOutputBundle)
  val data    = Decoupled(UInt(8.W))
}

class BLEPacketDisassembler(params: BasebandModemParams) extends Module {
  val io = IO(new Bundle {
    val in        = new PDAInputIO
    val out       = new PDAOutputIO
    val constants = Input(new BasebandConstants)
    val state     = Output(UInt(log2Ceil(6+1).W)) // for debug
  })

  object State extends ChiselEnum {
    val s_disabled, s_idle, s_pdu_hdr, s_pdu_data, s_crc, s_wait = Value
  }

  val state   = RegInit(State.s_disabled)
  val counter = RegInit(0.U(8.W)) // general-purpose

  io.state := state.asUInt

  /* Modem feedback */
  val eop = RegInit(false.B)
  io.in.data.eop := eop

  /* Dewhitener (PDU + CRC) 
     Seed from "3.2 DATA WHITENING" of Bluetooth Core Specification v5.2
   */
  val white = Module(new WhiteningBytes)
  white.io.in         := io.in.data.data
  white.io.seed.bits  := Cat(Reverse(io.constants.bleChannelIndex), 1.U(1.W))
  white.io.seed.valid := io.in.data.sop

  /* CRC */
  val crc = Module(new CRCBytes(CRCPolynomials.BLE))
  crc.io.in.bits    := white.io.out.bits
  crc.io.seed.bits  := io.constants.crcSeed
  crc.io.seed.valid := io.in.data.sop
  // ignoring crc.io.in.ready since bytes arrive slower than it processes

  /* Output queue */
  val queue = withReset(reset.asBool | io.in.data.sop) { // TODO new Queue has flush signal, use it
    Module(new Queue(UInt(8.W), params.modemQueueDepth))
  }
  queue.io.enq.bits  := white.io.out.bits
  queue.io.enq.valid := white.io.out.valid
  io.out.data <> queue.io.deq

  /* Packet status */
  val length   = RegInit(0.U(8.W))
  val flag_crc = RegInit(false.B)
  val done     = RegInit(false.B)
  val busy     = RegInit(false.B)

  io.out.control.length   := length
  io.out.control.flag_sop := false.B // always matched
  io.out.control.flag_crc := flag_crc
  io.out.control.done     := done
  io.out.control.busy     := busy

  // some defaults
  eop  := false.B
  done := false.B
  crc.io.in.valid := false.B

  // FSM
  when(state === State.s_disabled) {
    eop := true.B // not listening to packets, just in case keep packet detectors in "reset"

    when(io.in.control.fire() && (io.in.control.bits.command === PDAControlInputCommands.START_CMD ||
                                  io.in.control.bits.command === PDAControlInputCommands.DEBUG_CMD)) {
      state := State.s_idle
    }
  }.elsewhen(io.in.control.fire() && (io.in.control.bits.command === PDAControlInputCommands.EXIT_CMD)) {
    state    := State.s_disabled
    length   := 0.U
    flag_crc := false.B
    done     := true.B
    busy     := false.B
  }.elsewhen(state === State.s_idle) {
    when(io.in.data.sop === true.B) { // found a packet!
      state    := State.s_pdu_hdr
      counter  := 1.U
      flag_crc := false.B
      busy     := true.B
    }
  }.elsewhen(white.io.out.fire()) { // process the packet!
    counter := counter - 1.U

    switch(state) {
      is(State.s_pdu_hdr) {
        crc.io.in.valid := true.B
        when(counter === 0.U) {
          length  := white.io.out.bits

          when(white.io.out.bits === 0.U) {
            state   := State.s_crc
            counter := 2.U
          }.otherwise {
            state   := State.s_pdu_data
            counter := white.io.out.bits - 1.U
          }
        }
      }

      is(State.s_pdu_data) {
        crc.io.in.valid := true.B
        when(counter === 0.U) {
          state   := State.s_crc
          counter := 2.U
        }
      }

      is(State.s_crc) {
        crc.io.in.valid := true.B
        when(counter === 0.U) {
          state   := State.s_wait
          eop     := true.B
        }
      }
    }
  }.elsewhen(state === State.s_wait) {
    when(io.in.data.sop === true.B) { // next packet arrived, kill current one
      state    := State.s_pdu_hdr
      counter  := 1.U
      length   := 0.U
      flag_crc := false.B
      done     := true.B
      busy     := true.B
    }.elsewhen(crc.io.out.valid === true.B && queue.io.count === 0.U) { // CRC done and queue emptied
      state    := State.s_idle
      flag_crc := crc.io.out.bits =/= 0.U
      done     := true.B
      busy     := false.B
    }
  }
}
