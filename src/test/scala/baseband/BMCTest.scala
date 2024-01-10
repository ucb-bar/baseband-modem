package baseband

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.experimental.dataview._
import chisel3.util._
import chiseltest._
import chiseltest.{TreadleBackendAnnotation, VerilatorBackendAnnotation, WriteVcdAnnotation}

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.immutable.Seq
import scala.util.Random

import breeze.plot._
import breeze.stats.distributions.Gaussian

import verif._
import ee290cdma._
import modem.{ModemAnalogIO, ModemLUTConfigs, ModemLUTCommand}
import modem.TestUtility._

class BMCSingleClock(params: BasebandModemParams, beatBytes: Int) extends Module {
  val bmc = Module(new BMC(params, beatBytes))
  bmc.io.adc_clock := clock

  val io = IO(new BMCIO(params, beatBytes))
  io <> bmc.io.viewAsSupertype(new BMCIO(params, beatBytes))
}

class BMCTest extends AnyFlatSpec with ChiselScalatestTester {
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

  val tests = 2
  val params = BasebandModemParams()
  val beatBytes = 4

  it should "Pass a full BLE baseband loop" in {
    test(new BMCSingleClock(params, beatBytes)).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      // val cmdInDriver = new DecoupledDriverMaster(c.clock, c.io.cmd)
      // val dmaReadReqDriver = new DecoupledDriverSlave(c.clock, c.io.dma.readReq, 0)
      // val dmaReadReqMonitor = new DecoupledMonitor(c.clock, c.io.dma.readReq)
      // val dmaReadRespDriver = new DecoupledDriverMaster(c.clock, c.io.dma.readResp)
      // val dmaDataDriver = new DecoupledDriverMaster(c.clock, c.io.dma.readData)
      // val dmaDataMonitor = new DecoupledMonitor(c.clock, c.io.dma.readData)
      // val dmaWriteReqDriver = new DecoupledDriverSlave(c.clock, c.io.dma.writeReq, 0)
      // val dmaWriteReqMonitor = new DecoupledMonitor(c.clock, c.io.dma.writeReq)
      c.io.cmd.initSource().setSourceClock(c.clock)
      c.io.dma.readReq.initSink().setSinkClock(c.clock)
      c.io.dma.readResp.initSource().setSourceClock(c.clock)
      c.io.dma.readData.initSource().setSourceClock(c.clock)
      c.io.dma.writeReq.initSink().setSinkClock(c.clock)

      // messages are immediately "read"
      c.io.messages.rxErrorMessage.ready.poke(true.B)
      c.io.messages.txErrorMessage.ready.poke(true.B)
      c.io.messages.rxFinishMessage.ready.poke(true.B)

      for (i <- 0 until tests) {
        val bleChannelIndex = 37
        val accessAddress = 0x8E89BED6L
        val crcSeed = s"x555555"

        // config constants
        c.io.cmd.enqueueNow(
          new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
            _.inst.secondaryInst -> BasebandISA.CONFIG_BLE_CHANNEL_INDEX,
            _.inst.data -> 0.U,
            _.additionalData -> bleChannelIndex.U)
        )
        c.clock.step()

        c.io.cmd.enqueueNow(
          new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
            _.inst.secondaryInst -> BasebandISA.CONFIG_ACCESS_ADDRESS,
            _.inst.data -> 0.U,
            _.additionalData -> accessAddress.U(32.W))
        )
        c.clock.step()

        c.io.cmd.enqueueNow(
          new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
            _.inst.secondaryInst -> BasebandISA.CONFIG_CRC_SEED,
            _.inst.data -> 0.U,
            _.additionalData -> crcSeed.U)
        )
        c.clock.step()

        // set in debug mode with loopback (also starts the packet sending)
        val pduLengthIn = scala.util.Random.nextInt(4) + 2 // includes 2-byte header, keep it short this is very slow
        val addrInString = s"x${scala.util.Random.nextInt(1600)}0"

        println(s"Test ${i}:\t pduLengthIn ${pduLengthIn},\t addr 0${addrInString}")

        c.io.cmd.enqueueNow(
          new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.DEBUG_CMD,
            _.inst.secondaryInst -> 2.U, _.inst.data -> pduLengthIn.U, _.additionalData -> addrInString.U)
        )

        fork
          .withRegion(Monitor) {
            c.io.dma.readReq.waitForValid()
            c.io.dma.readReq.valid.expect(true.B)
          }
          .joinAndStep(c.clock)
        val pduLength = c.io.dma.readReq.bits.totalBytes.litValue.intValue
        val addr = c.io.dma.readReq.bits.addr.litValue.intValue
        assert(pduLengthIn == pduLength)
        assert(addrInString.U.litValue.intValue == addr)

        // generate and push packet
        val inBytes = Seq(scala.util.Random.nextInt(255), pduLength - 2) ++
          Seq.tabulate(pduLength - 2)(_ => scala.util.Random.nextInt(255))
        println(inBytes)

        val (inData, inSize) = seqToWidePackets(beatBytes, inBytes)
        val (inDataCRC, inSizeCRC) = seqToWidePackets(beatBytes, inBytes ++ bleCRCBytes(inBytes))

        fork {
          c.io.dma.readData.enqueueSeq(inData.map(d => d.U))
        }.fork {
          c.io.dma.readData.ready.poke(true.B)
          c.io.dma.readData.waitForValid()
          c.io.dma.readData.valid.expect(true.B)
        }.join()

        val expectedBaseAddr = (addrInString.U.litValue + pduLength + beatBytes) & ~(beatBytes - 1)
        val expectedOut = inDataCRC // PDA gives CRC too
          .map(d => d.U)
          .zip(inSizeCRC
            .map(s => s.U))
          .zip(inSizeCRC
            .scanLeft(0)(_ + _)
            .map(o => (o + expectedBaseAddr).U))
          .map {
            case ((d, s), a) => (new EE290CDMAWriterReq(params.paddrBits, beatBytes))
              .Lit(_.data -> d, _.totalBytes -> s, _.addr -> a)
          }

        c.io.dma.readResp.enqueueNow((new EE290CDMAReaderResp(params.maxReadSize))
          .Lit(_.bytesRead -> pduLengthIn.U))

        for (elt <- expectedOut) {
          c.io.dma.writeReq.ready.poke(true.B)
          fork
            .withRegion(Monitor) {
              c.io.dma.writeReq.waitForValid()
              c.io.dma.writeReq.valid.expect(true.B)
              assert(c.io.dma.writeReq.bits.data.peek().litValue.toInt == elt.data.litValue)
              assert(c.io.dma.writeReq.bits.totalBytes.peek().litValue.toInt == elt.totalBytes.litValue)
              assert(c.io.dma.writeReq.bits.addr.peek().litValue.toInt == elt.addr.litValue)
            }
            .joinAndStep(c.clock)
            println(elt)
        }
      }
    }
  }

  // it should "Properly receive BLE data with random channel index" in {
  //   test(new BMCSingleClock(params, beatBytes)).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
  //     val cmdInDriver = new DecoupledDriverMaster(c.clock, c.io.cmd)
  //     val dmaWriteReqDriver = new DecoupledDriverSlave(c.clock, c.io.dma.writeReq, 0)
  //     val dmaWriteReqMonitor = new DecoupledMonitor(c.clock, c.io.dma.writeReq)
  //     val errorOutDriver = new DecoupledDriverSlave(c.clock, c.io.messages.rxFinishMessage)
  //     val errorOutMonitor = new DecoupledMonitor(c.clock, c.io.messages.rxFinishMessage)

  //     // Set the appropriate tuning parameters
  //     c.io.firCmd.valid.poke(0.B)
  //     c.io.firCmd.bits.fir.poke(0.U)
  //     c.io.firCmd.bits.coeff.poke(0.U)
  //     c.io.firCmd.bits.value.poke(0.U)

  //     var expectedBytes = Seq[Int]()

  //     for (i <- 0 until tests) {
  //       val bleChannelIndex = scala.util.Random.nextInt(62) + 1
  //       val accessAddress = scala.util.Random.nextInt().abs
  //       val crcSeed = s"x555555"

  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
  //           _.inst.secondaryInst -> BasebandISA.CONFIG_BLE_CHANNEL_INDEX,
  //           _.additionalData -> bleChannelIndex.U)
  //       ))
  //       c.clock.step()

  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
  //           _.inst.secondaryInst -> BasebandISA.CONFIG_ACCESS_ADDRESS,
  //           _.additionalData -> accessAddress.U(32.W))
  //       ))
  //       c.clock.step()

  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
  //           _.inst.secondaryInst -> BasebandISA.CONFIG_CRC_SEED,
  //           _.additionalData -> crcSeed.U)
  //       ))
  //       c.clock.step()

  //       val addrInString = s"x${scala.util.Random.nextInt(1600)}0"

  //       println(s"Test: \t addr 0${addrInString}")

  //       // Push a start command (no loopback)
  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.RECEIVE_START_CMD,
  //           _.inst.secondaryInst -> 0.U, _.inst.data -> 0.U, _.additionalData -> addrInString.U)
  //       ))
  //       c.clock.step()

  //       val pduLength = 4 // very slow to simulate so keepin it short
  //       val inBytes = Seq(0, pduLength) ++ Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255))
  //       val inBytesCRC = inBytes ++ bleCRCBytes(inBytes)
  //       val packet = rawBLEPacket(whitenBytes(inBytesCRC, bleChannelIndex), aa = accessAddress)
  //       val (i, q) = if_mod(packet.map {b => if (b) 0.5 else -0.5}) // TODO use Gaussian
  //       val noise  = Gaussian(0,0.25 * 0.001).sample(256).toSeq // some padding

  //       val i_sig = ADC(noise ++ i ++ noise) // no AGC or DCO testing here
  //       val q_sig = ADC(noise ++ q ++ noise)

  //       (i_sig zip q_sig).map { case (x,y) =>
  //         c.io.analog.data.rx.i.data.poke(x.U)
  //         c.io.analog.data.rx.q.data.poke(y.U)
  //         c.clock.step()
  //       }
  //       c.clock.step(512) // make sure input is fully processed

  //       expectedBytes = expectedBytes ++ inBytesCRC

  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.RECEIVE_EXIT_CMD,
  //           _.inst.secondaryInst -> 0.U, _.inst.data -> 0.U, _.additionalData -> addrInString.U)
  //       ))
  //       c.clock.step(10)

  //       val expectedBaseAddr = (addrInString.U.litValue + pduLength + beatBytes) & ~(beatBytes - 1)
  //     }

  //     var receivedBytes = Seq[Int]()
  //     dmaWriteReqMonitor.monitoredTransactions
  //       .map(t => t.data)
  //       .foreach { o =>
  //         //assert(o.addr.litValue == expectedBaseAddr + outputLength) // TODO: MAKE SURE THE ADDRESSES MATCH
  //         receivedBytes = receivedBytes ++ Seq.tabulate(o.totalBytes.litValue.toInt) {i => ((o.data.litValue >> (8*i)) & 0xFF).toInt}
  //       }

  //     println(expectedBytes)
  //     println(receivedBytes)
  //     assert(expectedBytes == receivedBytes)
  //   }
  // }

  // it should "Exit RX Mode without complications" in {
  //   test(new BMCSingleClock(params, beatBytes)).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
  //     val cmdInDriver = new DecoupledDriverMaster(c.clock, c.io.cmd)
  //     val dmaWriteReqDriver = new DecoupledDriverSlave(c.clock, c.io.dma.writeReq, 0)
  //     val dmaWriteReqMonitor = new DecoupledMonitor(c.clock, c.io.dma.writeReq)
  //     val errorOutDriver = new DecoupledDriverSlave(c.clock, c.io.messages.rxFinishMessage)
  //     val errorOutMonitor = new DecoupledMonitor(c.clock, c.io.messages.rxFinishMessage)

  //     // Set the appropriate tuning parameters
  //     for (i <- 0 until tests) {
  //       val bleChannelIndex = 0
  //       val accessAddress = scala.util.Random.nextInt().abs
  //       val crcSeed = s"x555555"

  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
  //           _.inst.secondaryInst -> BasebandISA.CONFIG_BLE_CHANNEL_INDEX,
  //           _.additionalData -> bleChannelIndex.U)
  //       ))
  //       c.clock.step()

  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
  //           _.inst.secondaryInst -> BasebandISA.CONFIG_ACCESS_ADDRESS,
  //           _.additionalData -> accessAddress.U(32.W))
  //       ))
  //       c.clock.step()

  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
  //           _.inst.secondaryInst -> BasebandISA.CONFIG_CRC_SEED,
  //           _.additionalData -> crcSeed.U)
  //       ))
  //       c.clock.step()

  //       val addrInString = s"x${scala.util.Random.nextInt(1600)}0"

  //       println(s"Test: \t addr 0${addrInString}")

  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.RECEIVE_START_CMD,
  //           _.inst.secondaryInst -> 0.U, _.inst.data -> 0.U, _.additionalData -> addrInString.U)
  //       ))
  //       c.clock.step()

  //       val pduLength = 4 // very slow to simulate so keepin it short
  //       val inBytes = Seq(0, pduLength) ++ Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255))
  //       val inBytesCRC = inBytes ++ bleCRCBytes(inBytes)
  //       val packet = rawBLEPacket(whitenBytes(inBytesCRC, bleChannelIndex), aa = accessAddress)
  //       val (i, q) = if_mod(packet.map {b => if (b) 0.5 else -0.5}) // TODO use Gaussian
  //       val noise  = Gaussian(0,0.25 * 0.001).sample(256).toSeq // some padding

  //       val i_sig = ADC(noise ++ i ++ noise) // no AGC or DCO testing here
  //       val q_sig = ADC(noise ++ q ++ noise)

  //       val terminatePoint = scala.util.Random.nextInt(i_sig.length)
  //       println(s"Terminate point ${terminatePoint}")

  //       (i_sig zip q_sig).take(terminatePoint).map { case (x,y) =>
  //         c.io.analog.data.rx.i.data.poke(x.U)
  //         c.io.analog.data.rx.q.data.poke(y.U)
  //         c.clock.step()
  //       }

  //       println("Trying to push mid-run RX exit")

  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.RECEIVE_EXIT_CMD,
  //           _.inst.secondaryInst -> 0.U, _.inst.data -> 0.U, _.additionalData -> addrInString.U)
  //       ))
  //       c.clock.step(50)

  //       // TODO verify it actually exited by checking state or finishing the packet
  //     }
  //   }
  // }

  // it should "Pass a full LRWPAN baseband loop" in {
  //   test(new BMCSingleClock(params, beatBytes)).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
  //     val cmdInDriver = new DecoupledDriverMaster(c.clock, c.io.cmd)
  //     val dmaReadReqDriver = new DecoupledDriverSlave(c.clock, c.io.dma.readReq, 0)
  //     val dmaReadReqMonitor = new DecoupledMonitor(c.clock, c.io.dma.readReq)
  //     val dmaReadRespDriver = new DecoupledDriverMaster(c.clock, c.io.dma.readResp)
  //     val dmaDataDriver = new DecoupledDriverMaster(c.clock, c.io.dma.readData)
  //     val dmaDataMonitor = new DecoupledMonitor(c.clock, c.io.dma.readData)
  //     val dmaWriteReqDriver = new DecoupledDriverSlave(c.clock, c.io.dma.writeReq, 0)
  //     val dmaWriteReqMonitor = new DecoupledMonitor(c.clock, c.io.dma.writeReq)

  //     // messages are immediately "read"
  //     c.io.messages.rxErrorMessage.ready.poke(true.B)
  //     c.io.messages.txErrorMessage.ready.poke(true.B)
  //     c.io.messages.rxFinishMessage.ready.poke(true.B)

  //     for (i <- 0 until tests) {
  //       val lrwpanChannelIndex = 0
  //       val shr = 0xA700
  //       val crcSeed = 0x0000

  //       // config constants
  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
  //           _.inst.secondaryInst -> BasebandISA.CONFIG_LRWPAN_CHANNEL_INDEX,
  //           _.additionalData -> lrwpanChannelIndex.U)
  //       ))
  //       c.clock.step()

  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
  //           _.inst.secondaryInst -> BasebandISA.CONFIG_SHR,
  //           _.additionalData -> shr.U)
  //       ))
  //       c.clock.step()

  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
  //           _.inst.secondaryInst -> BasebandISA.CONFIG_CRC_SEED,
  //           _.additionalData -> crcSeed.U)
  //       ))
  //       c.clock.step()

  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
  //           _.inst.secondaryInst -> BasebandISA.CONFIG_RADIO_MODE,
  //           _.additionalData -> RadioMode.LRWPAN)
  //       ))
  //       c.clock.step()

  //       // set in debug mode with loopback (also starts the packet sending)
  //       val pduLengthIn = 2 // just need the payload length, packet length prepended by PA, short bc very slow
  //       val addrInString = s"x${scala.util.Random.nextInt(1600)}0"

  //       println(s"Test ${i}:\t pduLengthIn ${pduLengthIn},\t addr 0${addrInString}")

  //       cmdInDriver.push(new DecoupledTX(new BasebandModemCommand()).tx(
  //         new BasebandModemCommand().Lit(_.inst.primaryInst -> BasebandISA.DEBUG_CMD,
  //           _.inst.secondaryInst -> 2.U, _.inst.data -> pduLengthIn.U, _.additionalData -> addrInString.U)
  //       ))

  //       while (dmaReadReqMonitor.monitoredTransactions.isEmpty) {
  //         c.clock.step()
  //       }

  //       val pduLength = dmaReadReqMonitor.monitoredTransactions.head.data.totalBytes.litValue.intValue
  //       val addr = dmaReadReqMonitor.monitoredTransactions.head.data.addr.litValue.intValue

  //       assert(pduLength == pduLengthIn)
  //       assert(addr == addrInString.U.litValue.intValue)

  //       dmaReadReqMonitor.monitoredTransactions.clear()

  //       // generate and push packet
  //       val inBytes = Seq.tabulate(pduLength)(_ => scala.util.Random.nextInt(255))
  //       println(inBytes)

  //       val (inData, inSize) = seqToWidePackets(beatBytes, inBytes)
  //       val (inDataCRC, inSizeCRC) = seqToWidePackets(beatBytes, Seq(pduLength+2) ++ inBytes ++ lrwpanCRCBytes(inBytes))
  //         // received packet should have length and CRC attached

  //       dmaDataDriver.push(inData.map(d => new DecoupledTX(UInt((beatBytes * 8).W)).tx(d.U)))

  //       val expectedBaseAddr = (addrInString.U.litValue + pduLength + beatBytes) & ~(beatBytes - 1)

  //       val expectedOut = inDataCRC // PDA gives CRC too
  //         .map(d => d.U)
  //         .zip(inSizeCRC
  //           .map(s => s.U))
  //         .zip(inSizeCRC
  //           .scanLeft(0)(_ + _)
  //           .map(o => (o + expectedBaseAddr).U))
  //         .map {
  //           case ((d, s), a) => (new EE290CDMAWriterReq(params.paddrBits, beatBytes))
  //             .Lit(_.data -> d, _.totalBytes -> s, _.addr -> a)
  //         }

  //       while (dmaDataMonitor.monitoredTransactions.length != inData.length) {
  //         c.clock.step()
  //       }

  //       dmaDataMonitor.monitoredTransactions.clear()

  //       dmaReadRespDriver.push(new DecoupledTX(new EE290CDMAReaderResp(params.maxReadSize))
  //         .tx((new EE290CDMAReaderResp(params.maxReadSize))
  //           .Lit(_.bytesRead -> pduLengthIn.U)))

  //       while (dmaWriteReqMonitor.monitoredTransactions.length != expectedOut.length) {
  //         c.clock.step()
  //       }

  //       c.clock.step(100)

  //       assert(dmaWriteReqMonitor.monitoredTransactions.map(tx => tx.data.litValue).length == expectedOut.length)

  //       dmaWriteReqMonitor.monitoredTransactions
  //         .map(t => t.data)
  //         .zip(expectedOut)
  //         .foreach {
  //           case (o, e) =>
  //             assert(o.data.litValue == e.data.litValue)
  //             assert(o.totalBytes.litValue == e.totalBytes.litValue)
  //             assert(o.addr.litValue == e.addr.litValue)
  //         }

  //       dmaWriteReqMonitor.monitoredTransactions.clear()
  //     }
  //   }
  // }
}
