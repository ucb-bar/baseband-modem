package modem

import chisel3._
import chisel3.util._

class CDR(samplesPerBit: Int, shift: Int = 1) extends Module {
  val io = IO(new Bundle {
    val in = Input(Bool())
    val out = Valid(Bool())
  })

  val window = Reg(UInt((samplesPerBit + 2 * shift).W))
  window := (window << 1) | io.in

  val early   = Module(new Integrator(samplesPerBit))
  val present = Module(new Integrator(samplesPerBit))
  val late    = Module(new Integrator(samplesPerBit))

  early.io.window   := window(samplesPerBit-1, 0)
  present.io.window := window(samplesPerBit+shift-1, shift)
  late.io.window    := window(samplesPerBit+2*shift-1, 2*shift)

  val counter_max = samplesPerBit + shift - 1
  val counter = RegInit((samplesPerBit-1).U((log2Floor(counter_max)+1).W))

  when(counter === 0.U) {
    io.out.valid := true.B

    when((present.io.sum.abs >= early.io.sum.abs) 
          && (present.io.sum.abs >= late.io.sum.abs)) {
      io.out.bits := present.io.sum >= 0.S
      counter := (samplesPerBit - 1).U
    }.elsewhen(early.io.sum.abs > late.io.sum.abs) {
      io.out.bits := early.io.sum >= 0.S
      counter := (samplesPerBit + shift - 1).U
    }.otherwise { // late >= early
      io.out.bits := late.io.sum >= 0.S
      counter := (samplesPerBit - shift - 1).U
    }
  }.otherwise {
    io.out.valid := false.B
    io.out.bits  := DontCare
    counter := counter - 1.U
  }
}

class Integrator(samplesPerBit: Int) extends Module {
  val io = IO(new Bundle {
    val window = Input(UInt(samplesPerBit.W))
    val sum = Output(SInt(signedBitLength(samplesPerBit).W))
  })

  val nrzs = Seq.tabulate(samplesPerBit){i => Mux(io.window(i), 1.S, (-1).S)}
  io.sum := nrzs.reduce(_ +& _)
}
