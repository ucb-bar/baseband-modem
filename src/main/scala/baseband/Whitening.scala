package baseband

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

/* Based on Figure 3.5 of Bluetooth Core Specification v5.2

   Input and output are valid in the same cycle
 */
class Whitening extends Module {
  val io = IO(new Bundle {
    val in   = Flipped(Valid(Bool()))
    val out  = Valid(Bool())
    val seed = Flipped(Valid(UInt(7.W)))
  })

  /* Whitening is slightly different from CRC */
  val lfsr = RegInit(0.U(7.W))
  val inv  = lfsr(6)

  when(io.seed.fire()) {
    lfsr := io.seed.bits
  }.elsewhen(io.in.fire()) {
    lfsr := Cat(lfsr(5), lfsr(4), lfsr(3)^inv, lfsr(2), lfsr(1), lfsr(0), inv)
  }

  io.out.valid := io.in.fire()
  io.out.bits  := io.in.bits ^ inv
}

/* Byte-oriented interface for packet disassemblers (PDA) */
class WhiteningBytes extends Module {
  val io = IO(new Bundle {
    val in   = Flipped(Valid(UInt(8.W)))
    val out  = Valid(UInt(8.W))
    val seed = Flipped(Valid(UInt(7.W)))
  })

  object State extends ChiselEnum {
    val sWait, sData, sOut = Value
  }

  val state   = RegInit(State.sWait)
  val counter = Reg(UInt(3.W))
  val inreg   = Reg(UInt(8.W))
  val outreg  = Reg(UInt(8.W))

  val whitener = Module(new Whitening)
  whitener.io.seed    := io.seed
  whitener.io.in.bits := inreg(0)

  io.out.bits  := outreg

  // defaults
  counter := counter + 1.U
  inreg   := inreg >> 1
  outreg  := Cat(whitener.io.out.bits, outreg >> 1) // in & out valid in same cycle
  whitener.io.in.valid := false.B
  io.out.valid := false.B

  switch(state) {
    is(State.sWait) {
      when(io.in.fire()) {
        state   := State.sData
        counter := 1.U
        inreg := io.in.bits
      }
    }

    is(State.sData) {
      whitener.io.in.valid := true.B

      when(counter === 0.U) {
        state := State.sOut
      }
    }

    is(State.sOut) {
      state := State.sWait
      io.out.valid := true.B
    }
  }
}
