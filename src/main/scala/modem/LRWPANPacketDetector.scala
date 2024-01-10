package modem

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

class LRWPANPacketDetector extends Module {
  val io = IO(new Bundle {
    val shr = Input(UInt(16.W)) // "preamble", only matching 2 bytes
    
    val sop = Output(Bool()) // start-of-packet
    val eop = Input(Bool())  // end-of-packet
    val in  = Flipped(Valid(Bool())) // bitstream after CDR
    val out = Valid(UInt(8.W))
  })

  object State extends ChiselEnum {
    val sSearch, sData = Value
  }

  val rawWindow = Reg(UInt(128.W)) // 2-byte SHR = 4 symbols = 128 chips
  val symbols = Seq.fill(4)(Module(new ChipToSymbolLUT))
  symbols(0).io.chips := rawWindow(31,0)
  symbols(1).io.chips := rawWindow(63,32)
  symbols(2).io.chips := rawWindow(95,64)
  symbols(3).io.chips := rawWindow(127,96)

  val state = RegInit(State.sSearch)
  val counter = Reg(UInt(6.W)) // 64 chips per byte
  val window = Cat(
    symbols(3).io.symbol,
    symbols(2).io.symbol,
    symbols(1).io.symbol,
    symbols(0).io.symbol
  )

  // defaults
  io.sop := false.B
  io.out.valid := false.B

  when(io.eop) {
    state  := State.sSearch
    rawWindow := 0.U
  }.elsewhen(io.in.fire()) {
    rawWindow := Cat(io.in.bits, rawWindow >> 1)
    counter := counter + 1.U

    switch(state) {
      is(State.sSearch) {
        when(window === io.shr) {
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

  io.out.bits := window >> 8 // most recent byte
}
