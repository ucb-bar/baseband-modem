package modem

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import chiseltest._
import chiseltest.{TreadleBackendAnnotation, VcsBackendAnnotation, VerilatorBackendAnnotation, WriteVcdAnnotation}

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.immutable.Seq

import breeze.plot.{Figure, plot}

import verif._
import baseband.BasebandModemParams

/* Note this is a purely visual test and should be manually inspected to check that
   output does look Gaussian filtered.
 */
class GFSKTXTest extends AnyFlatSpec with ChiselScalatestTester {
  it should "Elaborate a GFSKTX" in {
    test(new GFSKTX(new BasebandModemParams)).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      c.io.digital.in.initSource().setSourceClock(c.clock)

      val pduLength = 5
      val inBits = Seq.tabulate(8*(pduLength+1+4+3))(_ => scala.util.Random.nextInt(2))
      println(inBits)

      c.io.digital.in.enqueueSeq(inBits.map(b => b.U))
      c.io.control.valid.poke(true.B)
      c.io.control.bits.totalBytes.poke(pduLength.U)

      val outCodes = scala.collection.mutable.Queue[Int]()

      c.clock.step()

      while (!c.io.out.done.peek().litToBoolean) {
        outCodes += c.io.out.modIndex.peek().litValue.toInt
        c.clock.step()
      }

      val waveform = outCodes.map(c => c)

      val p1 = Figure().subplot(0)
      val wrange = waveform.length
      p1 += plot(Seq.tabulate(wrange)(i => i), waveform, name = "FIR output waveform")
      p1.title = "GFSK Filtered Waveform"
      p1.xlabel = "Normalized time"
      p1.ylabel = "Modulation Index"
      p1.legend = true
      val extensionFactor = 32 // 32MHz clock, 1Mbps data rate
      val remainder = outCodes.length - (extensionFactor*inBits.length) + 10
      val yv = (inBits.flatMap(x => Seq.tabulate(extensionFactor)(_ => 63 * x.toDouble)) ++ // scale up to max index
        Seq.tabulate(remainder)(_ => 0.toDouble)).take(wrange)
      p1 += plot(Seq.tabulate(wrange)(i => i.toDouble), yv, name = "Input bits (extended domain)")
    }
  }
}
