package baseband

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import chiseltest._
import chiseltest.{TreadleBackendAnnotation, VerilatorBackendAnnotation, WriteVcdAnnotation}

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.immutable.Seq

import modem.Modem
import modem.TestUtility._

import verif._

class BLEPacketLoop extends Module {
  val io = IO(new Bundle {
    val assembler = new BLEPAInputIO
    val disassembler = new Bundle {
      val out = new PDAOutputIO
    }
    val constants = Input(new BasebandConstants)
  })

  /* Assembler */
  val assembler = Module(new BLEPacketAssembler)
  assembler.io.constants := io.constants
  assembler.io.in <> io.assembler

  /* Disassembler */
  val disassembler = Module(new BLEPacketDisassembler(BasebandModemParams()))
  disassembler.io.constants := io.constants
  disassembler.io.in.control.valid := true.B // not great, but it should work
  disassembler.io.in.control.bits.command := PDAControlInputCommands.START_CMD
  io.disassembler.out <> disassembler.io.out

  /* Modem */
  val modem = Module(new Modem(BasebandModemParams()))
  modem.io <> DontCare
  modem.io.digital.ble.tx <> assembler.io.out.data
  modem.io.digital.ble.rx <> disassembler.io.in.data
  modem.io.control.constants := io.constants
  modem.io.control.firCmd.valid := false.B
  modem.io.control.bleLoopback := true.B
  modem.io.control.rx.enable := true.B

  modem.io.control.gfskTX.in.bits.totalBytes := assembler.io.in.control.bits.pduLength + 2.U
  modem.io.control.gfskTX.in.valid := assembler.io.in.control.valid
  // gfskTX is always ready hopefully
}

class BLEPacketLoopTest extends AnyFlatSpec with ChiselScalatestTester {
  def seqToBinary(in: Seq[Int]): Seq[Int] = {
    in.map(x => String.format("%8s", x.toBinaryString).replaceAll(" ", "0").reverse).mkString("").map(c => c.toString.toInt)
  }

  /*
  // TODO: This does not check without whitening because the packet disassembler does not support disabling whitening
  it should "Pass a circular test without whitening" in {
    test(new BLEPacketLoop).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      val paControlDriver = new DecoupledDriverMaster(c.clock, c.io.assembler.control)
      val paDMADriver = new DecoupledDriverMaster(c.clock, c.io.assembler.data)
      val pdaDMADriver = new DecoupledDriverSlave(c.clock, c.io.disassembler.out.data, 0) // TODO: randomize?
      val pdaDMAMonitor = new DecoupledMonitor(c.clock, c.io.disassembler.out.data)

      val pduLength = scala.util.Random.nextInt(255)
      val inBytes = Seq(0, pduLength) ++ Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255))
      //val pduLength = 4
      //val inBytes = Seq(0, pduLength) ++ Seq.tabulate(pduLength)(i => i)
      val aa = BigInt("8E89BED6", 16)

      c.io.constants.radioMode.poke(RadioMode.BLE)
      c.io.constants.crcSeed.poke("x555555".U)
      c.io.constants.accessAddress.poke(aa.U)
      c.io.constants.bleChannelIndex.poke("b000000".U)

      paDMADriver.push(inBytes.map(x => (new DecoupledTX(UInt(8.W))).tx(x.U)))
      paControlDriver.push(new DecoupledTX(new BLEPAControlInputBundle).tx((new BLEPAControlInputBundle).Lit(_.aa -> aa.U, _.pduLength -> pduLength.U)))

      val expectedOut = inBytes ++ bleCRCBytes(inBytes)
      println(expectedOut)

      while(pdaDMAMonitor.monitoredTransactions.map(x => x.data.litValue).length != expectedOut.length) {
        c.clock.step()
      }

      c.clock.step(256)

      assert(pdaDMAMonitor.monitoredTransactions.map(x => x.data.litValue).length == expectedOut.length)

      pdaDMAMonitor.monitoredTransactions
        .map(x => x.data.litValue)
        .zip(expectedOut)
        .foreach {case (o, e) => assert(o == e)}
    }
  }
*/
  it should "Pass a circular test with whitening" in {
    test(new BLEPacketLoop).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      val paControlDriver = new DecoupledDriverMaster(c.clock, c.io.assembler.control)
      val paDMADriver = new DecoupledDriverMaster(c.clock, c.io.assembler.data)
      val pdaDMADriver = new DecoupledDriverSlave(c.clock, c.io.disassembler.out.data, 0) // TODO: randomize?
      val pdaDMAMonitor = new DecoupledMonitor(c.clock, c.io.disassembler.out.data)

      val pduLength = scala.util.Random.nextInt(255)
      val inBytes = Seq(0, pduLength) ++ Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255))
      val aa = BigInt("8E89BED6", 16)

      c.io.constants.radioMode.poke(RadioMode.BLE)
      c.io.constants.crcSeed.poke("x555555".U)
      c.io.constants.accessAddress.poke(aa.U)
      c.io.constants.bleChannelIndex.poke((scala.util.Random.nextInt(62) + 1).U) // Poke random 6 bit value (not 0)

      paDMADriver.push(inBytes.map(x => (new DecoupledTX(UInt(8.W))).tx(x.U)))
      paControlDriver.push(new DecoupledTX(new BLEPAControlInputBundle).tx((new BLEPAControlInputBundle).Lit(_.aa -> aa.U, _.pduLength -> pduLength.U)))

      val expectedOut = inBytes ++ bleCRCBytes(inBytes)
      println(expectedOut)

      while(pdaDMAMonitor.monitoredTransactions.map(x => x.data.litValue).length != expectedOut.length) {
        c.clock.step()
      }

      c.clock.step(256)

      assert(pdaDMAMonitor.monitoredTransactions.map(x => x.data.litValue).length == expectedOut.length)

      pdaDMAMonitor.monitoredTransactions
        .map(x => x.data.litValue)
        .zip(expectedOut)
        .foreach {case (o, e) => assert(o == e)}
    }
  }
/*
  // TODO: This does not check without whitening because the packet disassembler does not support disabling whitening
  it should "Pass repeated circular tests without whitening" in {
    test(new BLEPacketLoop).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      val paControlDriver = new DecoupledDriverMaster(c.clock, c.io.assembler.control)
      val paDMADriver = new DecoupledDriverMaster(c.clock, c.io.assembler.data)
      val pdaDMADriver = new DecoupledDriverSlave(c.clock, c.io.disassembler.out.data, 0) // TODO: randomize?
      val pdaDMAMonitor = new DecoupledMonitor(c.clock, c.io.disassembler.out.data)

      for (_ <- 0 until 8) {
        val pduLength = scala.util.Random.nextInt(255)
        val inBytes = Seq(0, pduLength) ++ Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255))
        val aa = BigInt("8E89BED6", 16)

        c.io.constants.radioMode.poke(RadioMode.BLE)
        c.io.constants.crcSeed.poke("x555555".U)
        c.io.constants.accessAddress.poke(aa.U)
        c.io.constants.bleChannelIndex.poke("b000000".U)

        paDMADriver.push(inBytes.map(x => (new DecoupledTX(UInt(8.W))).tx(x.U)))
        paControlDriver.push(new DecoupledTX(new BLEPAControlInputBundle).tx((new BLEPAControlInputBundle).Lit(_.aa -> aa.U, _.pduLength -> pduLength.U)))

        val expectedOut = inBytes ++ bleCRCBytes(inBytes)
        println(expectedOut)

        while(pdaDMAMonitor.monitoredTransactions.map(x => x.data.litValue).length != expectedOut.length) {
          c.clock.step()
        }

        c.clock.step(256)

        assert(pdaDMAMonitor.monitoredTransactions.map(x => x.data.litValue).length == expectedOut.length)

        pdaDMAMonitor.monitoredTransactions
          .map(x => x.data.litValue)
          .zip(expectedOut)
          .foreach { case (o, e) => assert(o == e) }

        pdaDMAMonitor.monitoredTransactions.clear()
      }
    }
  }
 */

  it should "Pass repeated circular tests with whitening" in {
    test(new BLEPacketLoop).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      val paControlDriver = new DecoupledDriverMaster(c.clock, c.io.assembler.control)
      val paDMADriver = new DecoupledDriverMaster(c.clock, c.io.assembler.data)
      val pdaDMADriver = new DecoupledDriverSlave(c.clock, c.io.disassembler.out.data, 0) // TODO: randomize?
      val pdaDMAMonitor = new DecoupledMonitor(c.clock, c.io.disassembler.out.data)

      for (_ <- 0 until 8) {
        val pduLength = scala.util.Random.nextInt(255)
        val inBytes = Seq(0, pduLength) ++ Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255))
        val aa = BigInt("8E89BED6", 16)

        c.io.constants.radioMode.poke(RadioMode.BLE)
        c.io.constants.crcSeed.poke("x555555".U)
        c.io.constants.accessAddress.poke(aa.U)
        c.io.constants.bleChannelIndex.poke((scala.util.Random.nextInt(62) + 1).U) // Poke random 6 bit value (not 0)

        paDMADriver.push(inBytes.map(x => (new DecoupledTX(UInt(8.W))).tx(x.U)))
        paControlDriver.push(new DecoupledTX(new BLEPAControlInputBundle).tx((new BLEPAControlInputBundle).Lit(_.aa -> aa.U, _.pduLength -> pduLength.U)))

        val expectedOut = inBytes ++ bleCRCBytes(inBytes)
        println(expectedOut)

        while(pdaDMAMonitor.monitoredTransactions.map(x => x.data.litValue).length != expectedOut.length) {
          c.clock.step()
        }

        c.clock.step(256)

        assert(pdaDMAMonitor.monitoredTransactions.map(x => x.data.litValue).length == expectedOut.length)

        pdaDMAMonitor.monitoredTransactions
          .map(x => x.data.litValue)
          .zip(expectedOut)
          .foreach { case (o, e) => assert(o == e) }

        pdaDMAMonitor.monitoredTransactions.clear()
      }
    }
  }
}
