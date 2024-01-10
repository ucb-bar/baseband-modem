package baseband

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

class LRWPANPacketDisassembler(params: BasebandModemParams) extends Module {
  val io = IO(new Bundle {
    val in        = new PDAInputIO
    val out       = new PDAOutputIO
    val constants = Input(new BasebandConstants)
    val state     = Output(UInt(log2Ceil(6+1).W)) // for debug
  })

  object State extends ChiselEnum {
    val s_disabled, s_idle, s_phr, s_psdu_data, s_psdu_crc, s_wait = Value
  }

  val state   = RegInit(State.s_disabled)
  val counter = RegInit(0.U(8.W)) // general-purpose

  io.state := state.asUInt

  /* Modem feedback */
  val eop = RegInit(false.B)
  io.in.data.eop := eop

  /* CRC */
  val crc = Module(new CRCBytes(CRCPolynomials.LRWPAN))
  crc.io.in.bits    := io.in.data.data.bits
  crc.io.seed.bits  := io.constants.crcSeed
  crc.io.seed.valid := io.in.data.sop
  // ignoring crc.io.in.ready since bytes arrive slower than it processes

  /* Output queue */
  val queue = withReset(reset.asBool | io.in.data.sop) { // TODO new Queue has flush signal, use it
    Module(new Queue(UInt(8.W), params.modemQueueDepth))
  }
  queue.io.enq.bits  := io.in.data.data.bits
  queue.io.enq.valid := io.in.data.data.valid
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
      state    := State.s_phr
      flag_crc := false.B
      busy     := true.B
    }
  }.elsewhen(io.in.data.data.fire()) { // process the packet!
    counter := counter - 1.U

    switch(state) {
      is(State.s_phr) {
        crc.io.in.valid := false.B
        // PSDU >= 5 bytes in length
        state   := State.s_psdu_data
        counter := io.in.data.data.bits(6,0) - 1.U - 2.U // PHR includes FCS (CRC)
        length  := io.in.data.data.bits(6,0)
      }

      is(State.s_psdu_data) {
        crc.io.in.valid := true.B // CRC over PSDU (MPDU) only
        when(counter === 0.U) {
          state   := State.s_psdu_crc
          counter := 1.U
        }
      }

      is(State.s_psdu_crc) {
        crc.io.in.valid := true.B
        when(counter === 0.U) {
          state   := State.s_wait
          eop     := true.B
        }
      }
    }
  }.elsewhen(state === State.s_wait) {
    when(io.in.data.sop === true.B) { // next packet arrived, kill current one
      state    := State.s_phr
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
