package baseband

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chiseltest._
import chiseltest.{TreadleBackendAnnotation, VerilatorBackendAnnotation, WriteVcdAnnotation}
import org.scalatest.flatspec.AnyFlatSpec

class BLEPacketAssemblerTest extends AnyFlatSpec with ChiselScalatestTester {
  def seqToBinary(in: Seq[Int]): Seq[Int] = {
    in.map(x => String.format("%8s", x.toBinaryString).replaceAll(" ", "0").reverse).mkString("").map(c => c.toString.toInt)
  }

  it should "Match binary output to input bytes with no whitening" in {
    test(new BLEPacketAssembler).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      c.io.in.control.initSource().setSourceClock(c.clock)
      c.io.in.data.initSource().setSourceClock(c.clock)
      c.io.out.data.initSink().setSinkClock(c.clock)

      val pduLength = scala.util.Random.nextInt(255)
      val inBytes = Seq(0, pduLength) ++ Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255)) // Need to add two for the header <IMPORTANT: In controller make sure to request bytesRead-2 as pduLength>
      println("Access address of 0xFFFFFFF6 disables whitening")
      val aa = BigInt("FFFFFFF6", 16)

      c.io.constants.bleChannelIndex.poke("b000000".U)

      val preambleExpected = "01010101".map(c => c.toString.toInt)
      val aaExpected = aa.toInt.toBinaryString.reverse.map(c => c.toString.toInt)

      c.io.in.control.enqueueNow((new BLEPAControlInputBundle).Lit(_.aa -> aa.U, _.pduLength -> pduLength.U))
      val expectedOut = preambleExpected ++ aaExpected ++ seqToBinary(inBytes)

      fork {
        c.io.in.data.enqueueSeq(inBytes.map(x => x.U))
      }.fork {
        c.io.out.data.expectDequeueSeq(expectedOut.map(x => x.U))
      }.join()
    }
  }

  it should "Not match binary output to input bytes with whitening" in {
    test(new BLEPacketAssembler).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      // val controlDriver = new DecoupledDriverMaster(c.clock, c.io.in.control)
      // val dmaDriver = new DecoupledDriverMaster(c.clock, c.io.in.data)
      // val outDriver = new DecoupledDriverSlave(c.clock, c.io.out.data, 0)
      // val outMonitor = new DecoupledMonitor(c.clock, c.io.out.data)
      c.io.in.control.initSource().setSourceClock(c.clock)
      c.io.in.data.initSource().setSourceClock(c.clock)
      c.io.out.data.initSink().setSinkClock(c.clock)

      val pduLength = scala.util.Random.nextInt(255)
      val inBytes = Seq(0, pduLength) ++ Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255)) // Need to add two for the header <IMPORTANT: In controller make sure to request bytesRead-2 as pduLength>
      val aa = BigInt("8E89BED6", 16)

      c.io.constants.bleChannelIndex.poke((scala.util.Random.nextInt(62) + 1).U) // Poke random 6 bit value (not 0)

      val preambleExpected = "01010101".map(c => c.toString.toInt)
      val aaExpected = aa.toInt.toBinaryString.reverse.map(c => c.toString.toInt)

      c.io.in.control.enqueueNow((new BLEPAControlInputBundle).Lit(_.aa -> aa.U, _.pduLength -> pduLength.U))
      val expectedOut = preambleExpected ++ aaExpected ++ seqToBinary(inBytes)

      fork {
        c.io.in.data.enqueueSeq(inBytes.map(x => x.U))
      }.fork {
        // Check Preamble and AA match
        for (elt <- preambleExpected ++ aaExpected) {
          c.io.out.data.ready.poke(true.B)
          fork
            .withRegion(Monitor) {
              c.io.out.data.waitForValid()
              c.io.out.data.valid.expect(true.B)
              assert(c.io.out.data.bits.peek().litValue.toInt == elt)
            }
            .joinAndStep(c.clock)
        }
        // Check there is some mismatch in PDU
        for (elt <- seqToBinary(inBytes)) {
          c.io.out.data.ready.poke(true.B)
          var matches = false
          fork
            .withRegion(Monitor) {
              c.io.out.data.waitForValid()
              c.io.out.data.valid.expect(true.B)
              matches = matches && c.io.out.data.bits.peek().litValue.toInt != elt
            }
            .joinAndStep(c.clock)
          assert(matches == false)
        }
      }.join()
    }
  }
}
