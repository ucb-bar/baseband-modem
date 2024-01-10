package baseband

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util.{Decoupled, Valid}
import chiseltest._
import chiseltest.{TreadleBackendAnnotation, VerilatorBackendAnnotation, WriteVcdAnnotation}
import modem.TestUtility
import org.scalatest.flatspec.AnyFlatSpec
import verif._

class LRWPANPacketAssemblerTestModule extends Module {
  val io = IO(new Bundle {
    val in_data  = Flipped(Decoupled(UInt(8.W)))
    val constants = Input(new BasebandConstants)
    val control = Flipped(Decoupled(new LRWPANPAControlInputBundle))
    val out_bits = Valid(UInt(1.W))
    val out_chips  = Decoupled(UInt(1.W))
  })

  val assembler = Module(new LRWPANPacketAssembler)
  assembler.io.constants := io.constants
  assembler.io.in.data <> io.in_data
  assembler.io.in.control <> io.control

  val spreader = Module(new SymbolToChipSpreader)
  spreader.io.in_data <> assembler.io.out.data

  //io.out_bits <> assembler.io.out.data
  io.out_bits.bits := assembler.io.out.data.bits
  io.out_bits.valid := assembler.io.out.data.fire()
  io.out_chips <> spreader.io.out_data

}

class LRWPANPacketAssemblerTest extends AnyFlatSpec with ChiselScalatestTester {
  def seqToBinary(in: Seq[Int]): Seq[Int] = {
    in.map(x => String.format("%8s", x.toBinaryString).replaceAll(" ", "0").reverse).mkString("").map(c => c.toString.toInt)
  }

  it should "Match binary output to input bytes without spreading" in {
    test(new LRWPANPacketAssembler).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      c.io.in.control.initSource().setSourceClock(c.clock)
      c.io.in.data.initSource().setSourceClock(c.clock)
      c.io.out.data.initSink().setSinkClock(c.clock)

      for (i <- 0 until 100) {
        val pduLength = scala.util.Random.nextInt(125)
        val inBytes = Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255)) // Need to add two for the header <IMPORTANT: In controller make sure to request bytesRead-2 as pduLength>
        val sfd = "b10100111".U(8.W) // Note: this is reversed from the 15.4 spec as it is MSB first here and LSB first there

        c.io.constants.lrwpanChannelIndex.poke("b000000".U)

        val preamble = "00000000".map(c => c.toString.toInt)
        val preambleExpected = preamble ++ preamble ++ preamble ++ preamble // 4 octets of 0
        val sfdExpected = sfd.litValue.toInt.toBinaryString.reverse.map(c => c.toString.toInt)
        val frameLengthExpected = ("0" ++ TestUtility.toBinary(pduLength + 2, 7)).reverse.map(x => x.asDigit)

        c.io.in.control.enqueueNow((new LRWPANPAControlInputBundle).Lit(_.sfd -> sfd, _.pduLength -> pduLength.U))
        val expectedOut = preambleExpected ++ sfdExpected ++ frameLengthExpected ++ seqToBinary(inBytes)

        println(s"LRWPAN Packet Assembler Test (no spreading) ${i}:\t packet size: ${expectedOut.length}")

        fork {
          c.io.in.data.enqueueSeq(inBytes.map(x => x.U))
        }.fork {
          c.io.out.data.expectDequeueSeq(expectedOut.map(x => x.U))
        }.join()

        // Trim the CRC off the end
        for (i <- 0 until 16) {
          c.io.out.data.ready.poke(true.B)
          fork
            .withRegion(Monitor) {
              c.io.out.data.waitForValid()
              c.io.out.data.valid.expect(true.B)
            }
            .joinAndStep(c.clock)
        }
      }
    }
  }

  it should "Match chip stream output to input bytes with spreading" in {
    test(new LRWPANPacketAssemblerTestModule).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      // val controlDriver = new DecoupledDriverMaster(c.clock, c.io.control)
      // val dmaDriver = new DecoupledDriverMaster(c.clock, c.io.in_data)
      // val outBitsMonitor = new ValidMonitor(c.clock, c.io.out_bits)
      // val outChipsDriver = new DecoupledDriverSlave(c.clock, c.io.out_chips, 0)
      // val outChipsMonitor = new DecoupledMonitor(c.clock, c.io.out_chips)
      c.io.control.initSource().setSourceClock(c.clock)
      c.io.in_data.initSource().setSourceClock(c.clock)
      c.io.out_bits.initSink().setSinkClock(c.clock)
      c.io.out_chips.initSink().setSinkClock(c.clock)


      val inputPayload = Seq(0,0,0,0,0,0,0,0,0,0,0,0,0,64,111,230)
      val pduLength = inputPayload.length
      val outputBits = Seq(0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,1,1,0,0,1,0,1,0,1,0,0,1,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,0,1,0,1,1,1,1,0,1,1,0,0,1,1,0,0,1,1,1)
      val outputChips = Seq(1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,0,1,1,1,1,0,1,1,1,0,0,0,1,1,0,0,1,0,0,1,0,1,1,0,0,0,0,0,0,1,1,1,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,1,1,0,0,1,0,0,1,0,1,1,0,0,0,0,0,0,1,1,1,0,1,1,1,1,0,1,1,1,0,0,0,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,1,0,0,0,0,1,1,0,1,0,1,0,0,1,0,0,0,1,0,1,1,1,0,1,1,0,1,1,0,0,1,1,0,0,1,0,1,1,0,0,0,0,0,0,1,1,1,0,1,1,1,1,0,1,1,1,0,0,0,1,1,0,0)
      val sfd = "b10100111".U(8.W) // Note: this is reversed from the 15.4 spec as it is MSB first here and LSB first there

      c.io.constants.lrwpanChannelIndex.poke("b000000".U)

      c.io.control.enqueueNow((new LRWPANPAControlInputBundle).Lit(_.sfd -> sfd, _.pduLength -> pduLength.U))

      fork {
        c.io.in_data.enqueueSeq(inputPayload.map(x => x.U))
      }.fork {
        c.io.out_bits.expectDequeueSeq(outputBits.map(x => x.U))
      }.fork {
        c.io.out_chips.expectDequeueSeq(outputChips.map(x => x.U))
      }.join()

      // Trim the CRC off the end
      fork
        .withRegion(Monitor) {
          for (i <- 0 until 16) {
            c.io.out_chips.ready.poke(true.B)
            c.io.out_bits.waitForValid()
            c.io.out_bits.valid.expect(true.B)
          }
        }.fork.withRegion(Monitor) {
          for (i <- 0 until 16 * 4) {
            c.io.out_chips.waitForValid()
            c.io.out_chips.valid.expect(true.B)
          }
        }
        .joinAndStep(c.clock)
      

      // c.clock.step(outputChips.length * 4)
      // // Trim the CRC off the end
      // val actualBitsOut = outBitsMonitor.monitoredTransactions.take(outBitsMonitor.monitoredTransactions.length - 16)
      // //println("Expected: " + outputBits)
      // //println("Result: " + actualBitsOut.map(x => x.data.litValue().toString()))
      // val actualChipsOut = outChipsMonitor.monitoredTransactions.take(outChipsMonitor.monitoredTransactions.length - 128)

      // assert(actualBitsOut.length == outputBits.length)
      // println("Correct bit length!")
      // assert(actualChipsOut.length == outputChips.length)
      // println("Correct chip length!")
      // actualBitsOut
      //   .map(x => x.data.litValue())
      //   .zip(outputBits)
      //   .foreach { case (o, e) => assert(o == e) }
      // outBitsMonitor.monitoredTransactions.clear()
      // println("LRWPANPA Test: Output bits match!")

      // actualChipsOut
      //   .map(x => x.data.litValue())
      //   .zip(outputChips)
      //   .foreach { case (o, e) => assert(o == e) }
      // outChipsMonitor.monitoredTransactions.clear()
      // println("LRWPANPA Test: Output chips match!")
    }
  }

}
