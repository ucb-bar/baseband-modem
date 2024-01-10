package modem

import chisel3._
import chisel3.util._
import chisel3.experimental.FixedPoint
import chiseltest._

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.immutable.Seq

import breeze.plot.{Figure, plot}

import baseband.BasebandModemParams
import TestUtility._

/* Note this is a purely visual test and should be manually inspected to check that the
   image frequency is mostly removed but normal signal passes
 */
class HilbertFilter(params: BasebandModemParams) extends Module {
  val io = IO(new Bundle {
    val i = Input(UInt(params.adcBits.W))
    val q = Input(UInt(params.adcBits.W))
    val out = Output(FIRConfigs.RX_Hilbert_I.dataType)
    val firCmd = Flipped(Valid(new FIRCoefficientChangeCommand))
  })

  /* copied from FSKDemodulation.scala */

  val delay_i = RegNext(io.i)
  val delay_q = RegNext(io.q)

  // reinterpret as FixedPoint between -1 and ~1 (assumes middle is 0V)
  val sint_i = Cat(~delay_i(params.adcBits-1), delay_i(params.adcBits-2,0)).asSInt
  val sint_q = Cat(~delay_q(params.adcBits-1), delay_q(params.adcBits-2,0)).asSInt

  val fp_i = sint_i.asFixedPoint((params.adcBits-1).BP)
  val fp_q = sint_q.asFixedPoint((params.adcBits-1).BP)

  // Hilbert filter for that image rejection
  val hilbert_i = Module(new FIR(FIRConfigs.RX_Hilbert_I))
  val hilbert_q = Module(new FIR(FIRConfigs.RX_Hilbert_Q))
  hilbert_i.io.in.bits   := fp_i
  hilbert_i.io.in.valid  := true.B
  hilbert_i.io.out.ready := true.B
  hilbert_i.io.firCmd    := io.firCmd
  hilbert_q.io.in.bits   := fp_q
  hilbert_q.io.in.valid  := true.B
  hilbert_q.io.out.ready := true.B
  hilbert_q.io.firCmd    := io.firCmd

  io.out := RegNext(hilbert_i.io.out.bits + hilbert_q.io.out.bits)
}

class FIRTest extends AnyFlatSpec with ChiselScalatestTester {
  it should "Test image rejection and signal pass" in {
    test(new HilbertFilter(params)) { c =>
      c.clock.setTimeout(0)

      c.io.firCmd.valid.poke(false.B)

      val bits = Seq.fill(32)(1.0).flatMap {i => Seq(i, -i)}

      val (im_i, im_q) = if_mod(bits, image = true)
      val (rf_i, rf_q) = if_mod(bits, image = false)
      val (i, q) = (im_i ++ rf_i, im_q ++ rf_q)

      val samples = (i zip q).map { case (x,y) =>
        val i_quant = ADC(Seq(x * 0.9))(0)
        val q_quant = ADC(Seq(y * 0.9))(0)
        c.io.i.poke(i_quant.U)
        c.io.q.poke(q_quant.U)
        c.clock.step()
        (c.io.out.peek().litToDouble)
      }

      val f = Figure()
      val p = f.subplot(0)
      p += plot(Seq.tabulate(samples.length)(i => i.toDouble), samples.map(s => s))
    }
  }
}
