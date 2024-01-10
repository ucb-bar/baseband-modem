package modem

import chisel3._
import chisel3.util._
import chisel3.experimental.FixedPoint

class FIRCoefficientChangeCommand extends Bundle {
  /* LUT Command: used to write to a LUT within the modem.
    [31:10 - Value to be written to LUT | 9:4 - address in LUT | 3:0 - LUT index]
   */
  val fir   = UInt(4.W)
  val coeff = UInt(6.W)
  val value = UInt(22.W)
}

/* Generates a run-time configurable FIR filter that implements
    out[n] = b[0]*in[n] + b[1]*in[n-1] + b[2]*in[n-2] + ...
 */
class FIR(cfg: FIRConfig) extends Module {
  val io = IO(new Bundle {
    val in  = Flipped(Decoupled(cfg.dataType))
    val out = Decoupled(cfg.dataType)
    val firCmd = Flipped(Valid(new FIRCoefficientChangeCommand))
  })

  // coefficients and update logic
  val coeffs = RegInit(VecInit.tabulate(cfg.coefficients.length){i =>
    FixedPoint.fromDouble(cfg.coefficients(i), cfg.dataType.getWidth.W, cfg.dataType.binaryPoint)
  })

  when(io.firCmd.fire() && io.firCmd.bits.fir === cfg.name) {
    coeffs(io.firCmd.bits.coeff) := 
      io.firCmd.bits.value(cfg.dataType.getWidth-1,0).asFixedPoint(cfg.dataType.binaryPoint)
  }

  // the actual FIR
  io.in.ready  := io.out.ready
  io.out.valid := io.in.valid

  val zs = io.in.bits +: Seq.fill(coeffs.length - 1)(Reg(cfg.dataType)) // zs[i] = in[n-i]

  when (io.in.fire()) {
    for (i <- 1 until zs.length) {
      zs(i) := zs(i-1)
    }
  }

  io.out.bits := Seq.tabulate(coeffs.length){i => coeffs(i) * zs(i)}.reduce(_ + _)
}
