package baseband

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import chiseltest._
import chiseltest.{TreadleBackendAnnotation, VerilatorBackendAnnotation, WriteVcdAnnotation}
import org.scalatest.flatspec.AnyFlatSpec

import  ee290cdma._
/*
class BasebandLoopback(params: BasebandModemParams = BasebandModemParams(), beatBytes: Int) extends Module {
  val io = IO(new BasebandIO(params.paddrBits, beatBytes))

  val baseband = Module(new Baseband(params, beatBytes))

  io.control <> baseband.io.control
  io.dma <> baseband.io.dma

  baseband.io.constants := io.constants

  baseband.io.control.loopback := Seq(false.B, true.B)

  baseband.io.modem.rx <> DontCare
  baseband.io.modem.tx <> DontCare

  io.modem.rx <> DontCare
  io.modem.tx <> DontCare
  io.control.loopback := DontCare
}

class BasebandTest extends AnyFlatSpec with ChiselScalatestTester {
  val tests = 40

  def seqToBinary(in: Seq[Int]): Seq[Int] = {
    in.map(x => String.format("%8s", x.toBinaryString).replaceAll(" ", "0").reverse).mkString("").map(c => c.toString.toInt)
  }

  def seqToWidePackets(beatBytes: Int, seq: Seq[Int]): (Seq[BigInt], Seq[Int]) = {
    var in = seq
    var out = Seq[BigInt]()
    var lengths = Seq[Int]()

    while (in.nonEmpty) {
      val (group, rest) = in.splitAt(beatBytes)
      val bytes = group.padTo(beatBytes, 0)

      var sum = BigInt(0)
      for (i <- 0 until beatBytes) {
        sum = sum + (BigInt(bytes(i)) << (8*i))
      }
      lengths = lengths :+ group.length
      out = out :+ sum
      in = rest
    }
    (out, lengths)
  }

  it should "Form proper DMA requests" in {
    val addrBits = 32
    val beatBytes = 4
    test(new BasebandDMAAddresser(addrBits, beatBytes)).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      val inDriver = new DecoupledDriverMaster(c.clock, c.io.in)
      val outDriver = new DecoupledDriverSlave(c.clock, c.io.out, 0)
      val addrDriver = new ValidDriverMaster(c.clock, c.io.baseAddr)
      val outMonitor = new DecoupledMonitor(c.clock, c.io.out)

      for (_ <- 0 until 20) {

        val baseAddr = (scala.util.Random.nextInt(10000)).toString
        addrDriver.push(new ValidTX(s"x${baseAddr}".U))

        c.clock.step(1)

        val n = scala.util.Random.nextInt(3) + 20
        val (inData, inSize) = seqToWidePackets(beatBytes, Seq.tabulate(n)(_ => scala.util.Random.nextInt(255)))

        inDriver.push(inData
          .zip(inSize)
          .map {
            case (d, s) => new DecoupledTX(new DMAPacketAssemblerDMAOUTIO(beatBytes))
              .tx((new DMAPacketAssemblerDMAOUTIO(beatBytes))
                .Lit(_.data -> d.U, _.size -> s.U))
          })

        c.clock.step(100)

        val expectedOut = inData
          .map(d => d.U)
          .zip(inSize
            .map(s => s.U))
          .zip(inSize
            .scanLeft(0)(_ + _)
            .map(o => (o + BigInt(baseAddr, 16)).U))
          .map {
            case ((d, s), a) => (new EE290CDMAWriterReq(addrBits, beatBytes))
              .Lit(_.data -> d, _.totalBytes -> s, _.addr -> a)
          }

        outMonitor.monitoredTransactions
          .map(t => t.data)
          .zip(expectedOut)
          .foreach {
            case (o, e) =>
              assert(o.data.litValue == e.data.litValue)
              assert(o.totalBytes.litValue == e.totalBytes.litValue)
              assert(o.addr.litValue == e.addr.litValue)
          }

        outMonitor.monitoredTransactions.clear
      }
    }
  }

  it should "Pass a full baseband loop without whitening" in {
    val params = BasebandModemParams()
    val beatBytes = 4
    test(new BasebandLoopback(params, beatBytes)).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      val inDMADriver = new DecoupledDriverMaster(c.clock, c.io.dma.readData)
      val paInControlDriver = new DecoupledDriverMaster(c.clock, c.io.control.assembler.in)
      val pdaInControlDriver = new DecoupledDriverMaster(c.clock, c.io.control.disassembler.in)
      val outDMADriver = new DecoupledDriverSlave(c.clock, c.io.dma.writeReq, 0)
      val outDMAMonitor = new DecoupledMonitor(c.clock, c.io.dma.writeReq)
      val addrDriver = new ValidDriverMaster(c.clock, c.io.control.baseAddr)

      for (i <- 0 until tests) {
        c.io.constants.bleChannelIndex.poke("b000000".U)

        val baseAddr = (scala.util.Random.nextInt(10000)).toString
        addrDriver.push(new ValidTX(s"x${baseAddr}".U))

        c.clock.step(1)

        val pduLength = scala.util.Random.nextInt(255)
        println(s"Test $i, pduLength = $pduLength")

        val aa = BigInt("8E89BED6", 16)

        val (inData, inSize) = seqToWidePackets(beatBytes, Seq(0, pduLength) ++ Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255)))

        paInControlDriver.push(new DecoupledTX(new BLEPAControlInputBundle).tx((new BLEPAControlInputBundle)
          .Lit(_.aa -> aa.U, _.pduLength -> pduLength.U)))
        pdaInControlDriver.push(new DecoupledTX(new BLEPDAControlInputBundle).tx((new BLEPDAControlInputBundle)
          .Lit(_.aa -> aa.U, _.command -> PDAControlInputCommands.START_CMD)))
        inDMADriver.push(inData.map(d => new DecoupledTX(UInt((beatBytes * 8).W)).tx(d.U)))

        val expectedOut = inData
          .map(d => d.U)
          .zip(inSize
            .map(s => s.U))
          .zip(inSize
            .scanLeft(0)(_ + _)
            .map(o => (o + BigInt(baseAddr, 16)).U))
          .map {
            case ((d, s), a) => (new EE290CDMAWriterReq(params.paddrBits, beatBytes))
              .Lit(_.data -> d, _.totalBytes -> s, _.addr -> a)
          }

        while (outDMAMonitor.monitoredTransactions.length != expectedOut.length) {
          c.clock.step()
        }

        c.clock.step(100)

        assert(outDMAMonitor.monitoredTransactions.map(t => t.data).length == expectedOut.length)

        outDMAMonitor.monitoredTransactions
          .map(t => t.data)
          .zip(expectedOut)
          .foreach {
            case (o, e) =>
              assert(o.data.litValue == e.data.litValue)
              assert(o.totalBytes.litValue == e.totalBytes.litValue)
              assert(o.addr.litValue == e.addr.litValue)
          }

        outDMAMonitor.monitoredTransactions.clear
      }
    }
  }

  it should "Pass a full baseband loop with whitening" in {
    val params = BasebandModemParams()
    val beatBytes = 4
    test(new BasebandLoopback(params, beatBytes)).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      val inDMADriver = new DecoupledDriverMaster(c.clock, c.io.dma.readData)
      val paInControlDriver = new DecoupledDriverMaster(c.clock, c.io.control.assembler.in)
      val pdaInControlDriver = new DecoupledDriverMaster(c.clock, c.io.control.disassembler.in)
      val outDMADriver = new DecoupledDriverSlave(c.clock, c.io.dma.writeReq, 0)
      val outDMAMonitor = new DecoupledMonitor(c.clock, c.io.dma.writeReq)
      val addrDriver = new ValidDriverMaster(c.clock, c.io.control.baseAddr)

      for (i <- 0 until tests) {
        c.io.constants.bleChannelIndex.poke((scala.util.Random.nextInt(62) + 1).U) // Poke random 6 bit value (not 0)

        val baseAddr = (scala.util.Random.nextInt(10000)).toString
        addrDriver.push(new ValidTX(s"x${baseAddr}".U))

        c.clock.step(1)


        val pduLength = scala.util.Random.nextInt(255)
        println(s"Test $i, pduLength = $pduLength")

        val aa = BigInt("8E89BED6", 16)

        val (inData, inSize) = seqToWidePackets(beatBytes, Seq(0, pduLength) ++ Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255)))

        paInControlDriver.push(new DecoupledTX(new BLEPAControlInputBundle).tx((new BLEPAControlInputBundle)
          .Lit(_.aa -> aa.U, _.pduLength -> pduLength.U)))
        pdaInControlDriver.push(new DecoupledTX(new BLEPDAControlInputBundle).tx((new BLEPDAControlInputBundle)
          .Lit(_.aa -> aa.U, _.command -> PDAControlInputCommands.START_CMD)))
        inDMADriver.push(inData.map(d => new DecoupledTX(UInt((beatBytes * 8).W)).tx(d.U)))

        val expectedOut = inData
          .map(d => d.U)
          .zip(inSize
            .map(s => s.U))
          .zip(inSize
            .scanLeft(0)(_ + _)
            .map(o => (o + BigInt(baseAddr, 16)).U))
          .map {
            case ((d, s), a) => (new EE290CDMAWriterReq(params.paddrBits, beatBytes))
              .Lit(_.data -> d, _.totalBytes -> s, _.addr -> a)
          }

        while (outDMAMonitor.monitoredTransactions.length != expectedOut.length) {
          c.clock.step()
        }

        c.clock.step(100)

        assert(outDMAMonitor.monitoredTransactions.map(t => t.data).length == expectedOut.length)

        outDMAMonitor.monitoredTransactions
          .map(t => t.data)
          .zip(expectedOut)
          .foreach {
            case (o, e) =>
              assert(o.data.litValue == e.data.litValue)
              assert(o.totalBytes.litValue == e.totalBytes.litValue)
              assert(o.addr.litValue == e.addr.litValue)
          }

        outDMAMonitor.monitoredTransactions.clear
      }
    }
  }

  it should "Pass a short length PDU baseband loop without whitening" in {
    val params = BasebandModemParams()
    val beatBytes = 4
    test(new BasebandLoopback(params, beatBytes)).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      val inDMADriver = new DecoupledDriverMaster(c.clock, c.io.dma.readData)
      val paInControlDriver = new DecoupledDriverMaster(c.clock, c.io.control.assembler.in)
      val pdaInControlDriver = new DecoupledDriverMaster(c.clock, c.io.control.disassembler.in)
      val outDMADriver = new DecoupledDriverSlave(c.clock, c.io.dma.writeReq, 0)
      val outDMAMonitor = new DecoupledMonitor(c.clock, c.io.dma.writeReq)
      val addrDriver = new ValidDriverMaster(c.clock, c.io.control.baseAddr)

      for (i <- 0 until tests) {
        c.io.constants.bleChannelIndex.poke("b000000".U)

        val baseAddr = (scala.util.Random.nextInt(10000)).toString
        addrDriver.push(new ValidTX(s"x${baseAddr}".U))

        c.clock.step(1)

        val pduLength = scala.util.Random.nextInt(6)

        println(s"Test $i, pduLength = $pduLength")
        val aa = BigInt("8E89BED6", 16)

        val (inData, inSize) = seqToWidePackets(beatBytes, Seq(0, pduLength) ++ Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255)))

        paInControlDriver.push(new DecoupledTX(new BLEPAControlInputBundle).tx((new BLEPAControlInputBundle)
          .Lit(_.aa -> aa.U, _.pduLength -> pduLength.U)))
        pdaInControlDriver.push(new DecoupledTX(new BLEPDAControlInputBundle).tx((new BLEPDAControlInputBundle)
          .Lit(_.aa -> aa.U, _.command -> PDAControlInputCommands.START_CMD)))
        inDMADriver.push(inData.map(d => new DecoupledTX(UInt((beatBytes * 8).W)).tx(d.U)))

        val expectedOut = inData
          .map(d => d.U)
          .zip(inSize
            .map(s => s.U))
          .zip(inSize
            .scanLeft(0)(_ + _)
            .map(o => (o + BigInt(baseAddr, 16)).U))
          .map {
            case ((d, s), a) => (new EE290CDMAWriterReq(params.paddrBits, beatBytes))
              .Lit(_.data -> d, _.totalBytes -> s, _.addr -> a)
          }

        while (outDMAMonitor.monitoredTransactions.length != expectedOut.length) {
          c.clock.step()
        }

        c.clock.step(100)

        assert(outDMAMonitor.monitoredTransactions.map(t => t.data).length == expectedOut.length)

        outDMAMonitor.monitoredTransactions
          .map(t => t.data)
          .zip(expectedOut)
          .foreach {
            case (o, e) =>
              assert(o.data.litValue == e.data.litValue)
              assert(o.totalBytes.litValue == e.totalBytes.litValue)
              assert(o.addr.litValue == e.addr.litValue)
          }

        outDMAMonitor.monitoredTransactions.clear
      }
    }
  }

  it should "Pass a zero length PDU baseband loop without whitening" in {
    val params = BasebandModemParams()
    val beatBytes = 4
    test(new BasebandLoopback(params, beatBytes)).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      val inDMADriver = new DecoupledDriverMaster(c.clock, c.io.dma.readData)
      val paInControlDriver = new DecoupledDriverMaster(c.clock, c.io.control.assembler.in)
      val pdaInControlDriver = new DecoupledDriverMaster(c.clock, c.io.control.disassembler.in)
      val outDMADriver = new DecoupledDriverSlave(c.clock, c.io.dma.writeReq, 0)
      val outDMAMonitor = new DecoupledMonitor(c.clock, c.io.dma.writeReq)
      val addrDriver = new ValidDriverMaster(c.clock, c.io.control.baseAddr)

      for (_ <- 0 until 30) {
        c.io.constants.bleChannelIndex.poke("b000000".U)

        val baseAddr = (scala.util.Random.nextInt(10000)).toString
        addrDriver.push(new ValidTX(s"x${baseAddr}".U))

        c.clock.step(1)

        val pduLength = 0

        val aa = BigInt("8E89BED6", 16)

        val (inData, inSize) = seqToWidePackets(beatBytes, Seq(scala.util.Random.nextInt(255), pduLength) ++ Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255)))

        paInControlDriver.push(new DecoupledTX(new BLEPAControlInputBundle).tx((new BLEPAControlInputBundle)
          .Lit(_.aa -> aa.U, _.pduLength -> pduLength.U)))
        pdaInControlDriver.push(new DecoupledTX(new BLEPDAControlInputBundle).tx((new BLEPDAControlInputBundle)
          .Lit(_.aa -> aa.U, _.command -> PDAControlInputCommands.START_CMD)))
        inDMADriver.push(inData.map(d => new DecoupledTX(UInt((beatBytes * 8).W)).tx(d.U)))


        val expectedOut = inData
          .map(d => d.U)
          .zip(inSize
            .map(s => s.U))
          .zip(inSize
            .scanLeft(0)(_ + _)
            .map(o => (o + BigInt(baseAddr, 16)).U))
          .map {
            case ((d, s), a) => (new EE290CDMAWriterReq(params.paddrBits, beatBytes))
              .Lit(_.data -> d, _.totalBytes -> s, _.addr -> a)
          }

        while (outDMAMonitor.monitoredTransactions.length != expectedOut.length) {
          c.clock.step()
        }

        c.clock.step(100)


        assert(outDMAMonitor.monitoredTransactions.length == expectedOut.length)

        outDMAMonitor.monitoredTransactions
          .map(t => t.data)
          .zip(expectedOut)
          .foreach {
            case (o, e) =>
              assert(o.data.litValue == e.data.litValue)
              assert(o.totalBytes.litValue == e.totalBytes.litValue)
              assert(o.addr.litValue == e.addr.litValue)
          }

        outDMAMonitor.monitoredTransactions.clear
      }
    }
  }
}
 */
