package modem

import chisel3._
import chisel3.util._
import chiseltest._

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.immutable.Seq

import breeze.plot.{Figure, plot}
import breeze.linalg.linspace
import breeze.stats.distributions.Gaussian

import TestUtility._

/* Note this is a purely visual test and should be manually inspected to check that the gain
   decreases when the packet arrives and then increases at the end.
 */
class AGCTest extends AnyFlatSpec with ChiselScalatestTester {
  def vgaGain(index: Int): Double = {
    // 0dB to 40dB gain, 5 bit index, logarithmically spaced
    val lut = linspace(0,2,32).map {i => math.pow(10, i)}
    lut(index)
  }

  it should "Test AGC Convergence" in {
    test(new AGC(params)) { c =>
      c.clock.setTimeout(0)

      // set some defaults, can be used to simulate good starting values
      c.io.control.sampleWindow.poke(22.U) // 22 for LRWPAN
      c.io.control.idealPeakToPeak.poke(230.U) // ~90% of max peak to peak
      c.io.control.toleranceP2P.poke(15.U)
      c.io.control.gainInc.poke(1.U)
      c.io.control.gainDec.poke(4.U)
      c.io.control.reset.poke(false.B)

      // generate signal
      val bits = Seq.fill(16)(-0.5) // should get close within BLE preamble (8*32=256 cycles)
      val noise = Gaussian(0,0.25 * 0.001).sample(256).toSeq
      val (i, q) = if_mod(bits)

      // try out different amplitudes
      for (scale <- Seq(0.01, 0.1, 1.0)) {
        val signal = noise ++ (i.map{scale*_}) ++ noise

        val samples = signal.map { s =>
          val lutIndex = c.io.vgaLUTIndex.peek().litValue().toInt
          val vgaAmplitude = vgaGain(lutIndex)
          val scaledSample = vgaAmplitude * s
          val quantizedSample = ADC(Seq(scaledSample))(0)
          c.io.adcIn.poke(quantizedSample.U)
          c.clock.step()
          (quantizedSample, vgaAmplitude)
        }

        val f = Figure()
        val p = f.subplot(0)
        p.title = "Amplitude " + scale
        p += plot(Seq.tabulate(samples.length)(i => i), samples.map(s => s._1))
        p += plot(Seq.tabulate(samples.length)(i => i.toDouble), samples.map(s => s._2))
      }
    }
  }
}
