package baseband

import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.{TreadleBackendAnnotation, WriteVcdAnnotation}
import modem.TestUtility
import org.scalatest.flatspec.AnyFlatSpec
import verif.{DecoupledDriverMaster, DecoupledDriverSlave, DecoupledMonitor, DecoupledTX}

import scala.collection.immutable.Seq
import scala.util.Random

class SymbolToChipTestModule extends Module {
  val io = IO(new Bundle {
    val in_data  = Flipped(Decoupled(UInt(1.W)))
    val out_data  = Decoupled(UInt(1.W))
    val state = Output(UInt(2.W))
  })

  val spreader = Module(new SymbolToChipSpreader)
  spreader.io.in_data <> io.in_data
  spreader.io.out_data <> io.out_data
  io.state := spreader.io.state
}


class SymbolToChipTest extends AnyFlatSpec with ChiselScalatestTester {
  val params = BasebandModemParams()


  it should "Translate 2 symbols to 64 chips" in {
    test(new SymbolToChipTestModule()).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      // val inDriver = new DecoupledDriverMaster(c.clock, c.io.in_data)
      // val outDriver = new DecoupledDriverSlave(c.clock, c.io.out_data, 0)
      // val outMonitor = new DecoupledMonitor(c.clock, c.io.out_data)

      c.io.in_data.initSource().setSourceClock(c.clock)
      c.io.out_data.initSink().setSinkClock(c.clock)

      // 0x0 = 1 1 0 1 1 0 0 1 1 1 0 0 0 0 1 1 0 1 0 1 0 0 1 0 0 0 1 0 1 1 1 0
      val input = TestUtility.toBinary(3, 8).reverse.map(x => x.asDigit)
      val chip1 = Seq(1, 1, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 1, 0, 1, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1, 0)
      val chip0 = Seq(0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 1, 0, 1, 0, 1)

      val expectedResult = chip0 ++ chip1

      fork {
        c.io.in_data.enqueueSeq(input.map(b => b.U(1.W)))
      }.fork {
        c.io.out_data.expectDequeueSeq(expectedResult.map(b => b.U(1.W)))
      }.join()
      // inDriver.push(input.map(b => new DecoupledTX(UInt(1.W)).tx(b.U)))
      // c.clock.step(200)

      // assert(outMonitor.monitoredTransactions.length == 64)

      // println("Result: " + outMonitor.monitoredTransactions.map(x => x.data.litValue.toString()))

      // outMonitor.monitoredTransactions
      //   .map(x => x.data.litValue)
      //   .zip(expectedResult)
      //   .foreach {case (o, e) => assert(o == e)}
    }
  }
  it should "Translate symbols to chips" in {
    test(new SymbolToChipTestModule()).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      // val inDriver = new DecoupledDriverMaster(c.clock, c.io.in_data)
      // val outDriver = new DecoupledDriverSlave(c.clock, c.io.out_data, 0)
      // val outMonitor = new DecoupledMonitor(c.clock, c.io.out_data)
      
      c.io.in_data.initSource().setSourceClock(c.clock)
      c.io.out_data.initSink().setSinkClock(c.clock)

      for (i <- 0 until 40) {
        val numBytes = scala.util.Random.nextInt(127)
        val inBytes = Seq.tabulate(numBytes)(_ => scala.util.Random.nextInt(255))
        val inBits = inBytes.flatMap(i => TestUtility.toBinary(i, 8).reverse).map(x => x.asDigit)
        assert(inBits.length == inBytes.length * 8)
        val expectedResult = inBytes.flatMap(i => TestUtility.byteToChip(i.U(8.W).litValue.toInt))

        println(s"LRWPAN Spread Test ${i}:\t num bytes: ${numBytes}")

        fork {
          c.io.in_data.enqueueSeq(inBits.map(b => b.U(1.W)))
        }.fork {
          c.io.out_data.expectDequeueSeq(expectedResult.map(b => b.U(1.W)))
        }.join()

        // assert(expectedResult.length == numBytes * 64)

        // inDriver.push(inBits.map(b => new DecoupledTX(UInt(1.W)).tx(b.U(1.W))))
        // c.clock.step((inBits.length * 10))

        // assert(outMonitor.monitoredTransactions.length == numBytes * 64)

        // outMonitor.monitoredTransactions
        //   .map(x => x.data.litValue)
        //   .zip(expectedResult)
        //   .foreach {case (o, e) => assert(o == e)}
        // outMonitor.monitoredTransactions.clear()
      }

    }
  }





}
