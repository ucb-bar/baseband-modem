package baseband

import chisel3._
import chisel3.util._

/*
Connects io.left.in to io.left.out (loopback) when io.select is high. O
Otherwise io.left.in feeds io.right.out and io.right.in feeds io.left.out
 */
class Loopback[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val left = new Bundle {
      val in = Input(gen)
      val out = Output(gen)
    }
    val right = new Bundle {
      val in = Input(gen)
      val out = Output(gen)
    }
    val select = Input(Bool())
  })

  io.left.out := Mux(io.select, io.left.in, io.right.in)
  io.right.out := io.left.in
}

class ValidLoopback[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val left = new Bundle {
      val in = Flipped(Valid(gen))
      val out = Valid(gen)
    }
    val right = new Bundle {
      val in = Flipped(Valid(gen))
      val out = Valid(gen)
    }
    val select = Input(Bool())
  })

  io.left.out := Mux(io.select, io.left.in, io.right.in)
  io.right.out := io.left.in
}

class DecoupledLoopback[T <: Data](gen: T) extends Module {
  val io = IO(new Bundle {
    val left = new Bundle {
      val in = Flipped(Decoupled(gen))
      val out = Decoupled(gen)
    }
    val right = new Bundle {
      val in = Flipped(Decoupled(gen))
      val out = Decoupled(gen)
    }
    val select = Input(Bool())
  })

  when (io.select) {
    io.left.out <> io.left.in
    io.right.out.valid := false.B
    io.right.in.ready := false.B
    io.right.out.bits := DontCare
  }.otherwise {
    io.left.out <> io.right.in
    io.right.out <> io.left.in
  }
}