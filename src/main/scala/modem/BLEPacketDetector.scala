package modem

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

class BLEPacketDetector extends Module {
  val io = IO(new Bundle {
    val aa = Input(UInt(32.W)) // access address

    val sop = Output(Bool()) // start-of-packet
    val eop = Input(Bool())  // end-of-packet
    val in  = Flipped(Valid(Bool())) // bitstream after CDR
    val out = Valid(UInt(8.W))
  })

  object State extends ChiselEnum {
    val sSearch, sData = Value
  }

  val state   = RegInit(State.sSearch)
  val counter = Reg(UInt(3.W)) // 8 bits per byte
  val window  = Reg(UInt(32.W))

  // defaults
  io.sop := false.B
  io.out.valid := false.B

  when(io.eop) {
    state  := State.sSearch
    window := 0.U
  }.elsewhen(io.in.fire()) {
    window := Cat(io.in.bits, window >> 1)
    counter := counter + 1.U

    switch(state) {
      is(State.sSearch) {
        when(window === io.aa) {
          state := State.sData
          counter := 1.U
          io.sop := true.B // assert for 1 cycle
        }
      }

      is(State.sData) {
        when(counter === 0.U) {
          io.out.valid := true.B
        }
      }
    }
  }

  io.out.bits := window >> 24 // most recent byte
}
