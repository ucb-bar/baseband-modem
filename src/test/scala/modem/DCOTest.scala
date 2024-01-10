package modem

import chisel3._
import chisel3.util._
import chisel3.experimental.FixedPoint
import chiseltest._

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.immutable.Seq

import breeze.plot.{Figure, plot}

import TestUtility._

/* Note this is a purely visual test and should be manually inspected to check that the
   introduced DC offset is corrected
 */
class DCOTest extends AnyFlatSpec with ChiselScalatestTester {
  def dcoGain(index: Int): Double = {
    // index is actually a 5-bit signed int interpreted as unsigned
    if (index >= 16)
      (index - 32) / 16.0 * 0.25
    else
      index / 16.0 * 0.25
  }

  it should "Test DCO convergence" in {
    test(new DCO(params)) { c =>
      c.clock.setTimeout(0)

      // set some defaults, can be used to simulate good starting values
      c.io.control.gain.poke((2).F(9.W, 2.BP))

      // generate signal
      val bits = Seq.fill(32)(0.0)
      val (i, q) = if_mod(bits)

      // try out different offsets
      for (offset <- Seq(-0.2, 0.0, 0.2)) {
        val signal = i.map{offset + 0.25 * _} // scale down a bit bc to see effect easier

        val samples = signal.map { s =>
          val lutIndex = c.io.dcoLUTIndex.peek().litValue().toInt
          val dcoOffset = dcoGain(lutIndex)
          val shiftSample = s - dcoOffset // opposite direction!
          val quantizedSample = ADC(Seq(shiftSample))(0)
          c.io.adcIn.poke(quantizedSample.U)
          c.clock.step()
          (shiftSample, dcoOffset)
        }

        val f = Figure()
        val p = f.subplot(0)
        p.title = "Offset " + offset
        p += plot(Seq.tabulate(samples.length)(i => i.toDouble), samples.map(s => s._1))
        p += plot(Seq.tabulate(samples.length)(i => i.toDouble), samples.map(s => s._2))
      }
    }
  }
}