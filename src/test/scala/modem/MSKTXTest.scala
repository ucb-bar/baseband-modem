package modem

import chisel3._
import chiseltest._
import chiseltest.{TreadleBackendAnnotation, WriteVcdAnnotation}

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.immutable.Seq

import breeze.plot.{Figure, plot}

import verif._
import modem.TestUtility._
import baseband.BasebandModemParams

import java.io.{BufferedWriter, FileWriter}

/* Note this is a purely visual test and should be manually inspected to check that
   the two plots line up exactly.
 */
class MSKTXTest extends AnyFlatSpec with ChiselScalatestTester {
  it should "Elaborate a MSKTX" in {
    test(new MSKTX(new BasebandModemParams)).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      val inDriver = new DecoupledDriverMaster(c.clock, c.io.digital.in)
      c.io.digital.in.initSource().setSourceClock(c.clock)
      c.clock.setTimeout(2000)

      val packet = Seq(0x03, 0x69, 0x42, 0x31) // PHR, 1-byte payload, 2-byte CRC
      val inBits = symbolsToChip(rawLRWPANPacket(packet)).map {b => if (b) 1 else 0}

      c.io.digital.in.enqueueSeq(inBits.map(b => b.U))
      c.io.control.valid.poke(true.B)
      c.io.control.bits.totalBytes.poke(1.U) // 1 byte payload (rest is calculated by the module)
      c.clock.step()
      c.io.control.valid.poke(false.B)

      // Build the output codes array from the values peeked at the circuit output
      var outCodes = Seq[Int]()
      while (c.io.out.state.peek().litValue.toInt != 0) {
        outCodes = outCodes ++ Seq(c.io.out.modIndex.peek().litValue.toInt)
        c.clock.step()
      }

      // Save the output codes to a file
      var file = "msktx_vco_out.csv"
      var writer = new BufferedWriter(new FileWriter(file))
      outCodes.map(s => s.toString + ", ").foreach(writer.write)
      writer.close()

      // Save the corresponding input bits to a file, so they can be correlated together
      file = "msktx_bits_in.csv"
      writer = new BufferedWriter(new FileWriter(file))
      inBits.map(s => s.toString + ", ").foreach(writer.write)
      writer.close()
      
      val delay = 16 // adjust as needed until first code comes out
      val cyclesPerChip = 16 // 32 MHz ADC clock / 2 Mchip/s = 16 cycles/chip
      val expectCodes = Seq.fill(delay)(31) ++ chipToMSK(symbolsToChip(rawLRWPANPacket(packet)))
                                                .flatMap {i => Seq.fill(cyclesPerChip)(if (i) 63 else 0)}

      // Assert that the output codes are equal to the expected codes
      outCodes.zip(expectCodes).foreach{case(o, e) => assert(o == e)}

      val f = Figure()
      val p = f.subplot(0)
      p += plot(Seq.tabulate(outCodes.length)(i => i), outCodes)
      p += plot(Seq.tabulate(expectCodes.length)(i => i), expectCodes)
      p.title = "Output Codes vs Expected Codes"
      p.xlabel = "Sample Index"
      p.ylabel = "VCO modulation code"
    }
  }
}
