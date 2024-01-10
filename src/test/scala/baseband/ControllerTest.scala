package baseband

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import chiseltest._
import chiseltest.{TreadleBackendAnnotation, VerilatorBackendAnnotation, WriteVcdAnnotation}

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.immutable.Seq

import verif._
import ee290cdma._
import modem._
import modem.TestUtility._

class ControllerAndBasebandTester(params: BasebandModemParams =  BasebandModemParams(), beatBytes: Int) extends Module {
  val io = IO(new Bundle {
    val cmd = Flipped(Decoupled(new BasebandModemCommand))
    val constants = Output(new BasebandConstants)
    val  controllerDMA = new Bundle {
      val readReq = Decoupled(new EE290CDMAReaderReq(params.paddrBits, params.maxReadSize))
      val readResp = Flipped(Decoupled(new EE290CDMAReaderResp(params.maxReadSize)))
    }
    val basebandDMA = new BasebandDMAIO(params.paddrBits, beatBytes)
    val modem = new Bundle {
      val bleDigital = Flipped(new ModemDigitalIO)
      val lrwpanDigital = Flipped(new ModemDigitalIO)
    }
    val preambleDetected = Input(Bool())
    // monitors
    val bleAssemblerState = Output(UInt(3.W))
    val queueCount = Output(UInt(9.W))
  })

  val controller = Module(new Controller(params, beatBytes))
  io.cmd <> controller.io.cmd
  io.controllerDMA <> controller.io.dma
  // Assume that the modems are always ready
  controller.io.modemControl.mskTX.out.done := true.B
  controller.io.modemControl.mskTX.in.ready := true.B
  controller.io.modemControl.gfskTX.out.done := true.B
  controller.io.modemControl.gfskTX.in.ready := true.B
  // Messages are always ready?
  controller.io.messages.txErrorMessage.ready := true.B
  controller.io.messages.rxErrorMessage.ready := true.B
  controller.io.messages.rxFinishMessage.ready := true.B

  io.constants := controller.io.constants

  val bleBaseband = Module(new BLEBaseband(params, beatBytes))
  bleBaseband.io.control <> controller.io.bleBasebandControl
  bleBaseband.io.constants := controller.io.constants
  io.modem.bleDigital.tx <> bleBaseband.io.modem.digital.tx
  // State monitor
  io.bleAssemblerState := bleBaseband.io.state.assemblerState

  // recreate loopback by piping assembler output into packet detector
  // TODO make this much much better
  val blePacketDetector = Module(new BLEPacketDetector)
  io.modem.bleDigital.rx <> DontCare // overridden later
  bleBaseband.io.modem.digital.rx <> DontCare
  blePacketDetector.io.aa := io.constants.accessAddress
  blePacketDetector.io.eop := bleBaseband.io.modem.digital.rx.eop

  bleBaseband.io.modem.digital.rx.sop := blePacketDetector.io.sop
  bleBaseband.io.modem.digital.rx.data := blePacketDetector.io.out

  val queue = Module(new Queue(UInt(1.W), 256*8))
  val qFlush = RegInit(false.B)
  val ctr = RegInit(false.B)
  io.queueCount := queue.io.count
  queue.io.enq <> bleBaseband.io.modem.digital.tx
  blePacketDetector.io.in.valid := queue.io.deq.fire || qFlush === false.B
  blePacketDetector.io.in.bits := queue.io.deq.bits

  when(qFlush === false.B) {
    val packetBytes = (controller.io.bleBasebandControl.bleAssembler.in.bits.pduLength + 1.U + 4.U + 3.U)
    when(queue.io.count >= packetBytes * 8.U ){
      qFlush := true.B
      queue.io.deq.ready := true.B
      ctr := false.B
    }.otherwise{
      queue.io.deq.ready := false.B
    }
  }.otherwise{
    when(queue.io.count === 0.U){
      qFlush := false.B
      queue.io.deq.ready := true.B
    }.otherwise{
      queue.io.deq.ready := (true.B & ctr === true.B)
      ctr := !ctr
    }
  }

  bleBaseband.io.modem.digital.tx.ready := true.B

  // TODO lrwpan testing
  val lrwpanBaseband = Module(new LRWPANBaseband(params, beatBytes))
  lrwpanBaseband.io.control <> controller.io.lrwpanBasebandControl
  lrwpanBaseband.io.constants := controller.io.constants
  io.modem.lrwpanDigital <> lrwpanBaseband.io.modem.digital

  // Allow only one baseband to make DMA write requests and respond/listen to reads
  lrwpanBaseband.io.dma.readData <> io.basebandDMA.readData
  bleBaseband.io.dma.readData <> io.basebandDMA.readData

  // Allow only one baseband to make DMA write requests and respond to reads
  when(controller.io.constants.radioMode === RadioMode.BLE){
    io.basebandDMA.writeReq.valid := bleBaseband.io.dma.writeReq.valid
    io.basebandDMA.writeReq.bits := bleBaseband.io.dma.writeReq.bits
    bleBaseband.io.dma.writeReq.ready := io.basebandDMA.writeReq.ready
    lrwpanBaseband.io.dma.writeReq.ready := false.B
    bleBaseband.io.dma.readData <> io.basebandDMA.readData
    lrwpanBaseband.io.dma.readData <> DontCare
  }.otherwise{
    io.basebandDMA.writeReq.valid := lrwpanBaseband.io.dma.writeReq.valid
    io.basebandDMA.writeReq.bits := lrwpanBaseband.io.dma.writeReq.bits
    lrwpanBaseband.io.dma.writeReq.ready := io.basebandDMA.writeReq.ready
    bleBaseband.io.dma.writeReq.ready := false.B
    lrwpanBaseband.io.dma.readData <> io.basebandDMA.readData
    bleBaseband.io.dma.readData <> DontCare
  }
}

class ControllerTest extends AnyFlatSpec with ChiselScalatestTester {
  val tests = 16

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

  it should "Execute a BLE TX command" in {
    val beatBytes = 4
    val params = BasebandModemParams()
    test(new ControllerAndBasebandTester(params, beatBytes)).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      c.io.controllerDMA.readReq.initSink().setSinkClock(c.clock)
      c.io.controllerDMA.readResp.initSource().setSourceClock(c.clock)
      c.io.cmd.initSource().setSourceClock(c.clock)
      c.io.basebandDMA.readData.initSource().setSourceClock(c.clock)
      c.io.modem.bleDigital.tx.initSink().setSinkClock(c.clock)
      c.io.modem.lrwpanDigital.tx.initSink().setSinkClock(c.clock)

      val pduLengthIn = 18
      val addrIn = 4000 * 4
      // Access address of 0xFFFFFFF6 disables whitening
      val aa = BigInt("FFFFFFF6", 16)

      val preambleExpected = "01010101".map(c => c.toString.toInt)
      val aaExpected = aa.toInt.toBinaryString.reverse.map(c => c.toString.toInt)
      // Transmitting so no preamble
      c.io.preambleDetected.poke(false.B)

      // Set radio mode to BLE
      c.io.cmd.enqueue(
        (new BasebandModemCommand()).Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
          _.inst.secondaryInst -> BasebandISA.CONFIG_RADIO_MODE, _.inst.data -> 0.U(24.W), _.additionalData -> 0.U)
      )

      c.clock.step(2)

      assert(c.io.constants.radioMode.peek().litValue == 0.U.litValue)
      println("Radio mode successfully set to BLE")

      // Disable whitening
      c.io.cmd.enqueue(
        (new BasebandModemCommand()).Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
          _.inst.secondaryInst -> BasebandISA.CONFIG_ACCESS_ADDRESS, _.inst.data -> 0.U(24.W), _.additionalData -> aa.U(32.W))
      )

      c.clock.step(10)
      assert(c.io.constants.accessAddress.peek().litValue == aa.U.litValue)
      println("Access address successfully set to 0xFFFFFFFF to disable whitening")

      // Set channel index to 0
      c.io.cmd.enqueue(
        (new BasebandModemCommand()).Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
          _.inst.secondaryInst -> BasebandISA.CONFIG_BLE_CHANNEL_INDEX, _.inst.data -> 0.U(24.W), _.additionalData -> 0.U)
      )

      c.clock.step(10)

      // Push a send command
      c.io.cmd.enqueue(
        (new BasebandModemCommand()).Lit(_.inst.primaryInst -> BasebandISA.SEND_CMD,
          _.inst.secondaryInst -> 0.U(4.W),
          _.inst.data -> pduLengthIn.U, _.additionalData -> addrIn.U)
      )

      c.io.controllerDMA.readReq.ready.poke(true.B)
      c.io.controllerDMA.readReq.waitForValid()
      c.io.controllerDMA.readReq.valid.expect(true.B)

      val pduLength = c.io.controllerDMA.readReq.bits.totalBytes.peek().litValue.intValue
      val addr = c.io.controllerDMA.readReq.bits.addr.peek().litValue.intValue

      assert(pduLength == pduLengthIn)
      println("PDU packet length request to DMA is correct")
      assert(addr == addrIn)
      println("Packet address request to DMA is correct")

      val inBytes = Seq(scala.util.Random.nextInt(255), pduLength) ++ Seq.tabulate(pduLength - 2)(_ => scala.util.Random.nextInt(255))

      val (inData, inSize) = seqToWidePackets(beatBytes, inBytes)
      val expectedOut = preambleExpected ++ aaExpected ++ seqToBinary(inBytes)

      fork {
        c.io.basebandDMA.readData.enqueueSeq(inData.map(d => d.U))
      }.fork {
        c.io.modem.bleDigital.tx.expectDequeueSeq(expectedOut.map(b => b.U))
      }.join()
      println("Output packet bits match expected")
    }
  }

  it should "Execute a debug command" in {
    val beatBytes = 4
    val params = BasebandModemParams()
    test(new ControllerAndBasebandTester(params, beatBytes)).withAnnotations(Seq(TreadleBackendAnnotation, WriteVcdAnnotation)) { c =>
      c.io.controllerDMA.readReq.initSink().setSinkClock(c.clock)
      c.io.controllerDMA.readResp.initSource().setSourceClock(c.clock)
      c.io.cmd.initSource().setSourceClock(c.clock)
      c.io.basebandDMA.readData.initSource().setSourceClock(c.clock)
      c.io.basebandDMA.writeReq.initSink().setSinkClock(c.clock)

      for (i <- 0 until tests) {
        val pduLengthIn = scala.util.Random.nextInt(256) + 2
        val addrInString = s"x${scala.util.Random.nextInt(1600)}0"

        println(s"Test ${i}:\t pduLength ${pduLengthIn},\t addr 0${addrInString}")

        // Random whitening
        c.io.cmd.enqueue(
          (new BasebandModemCommand()).Lit(_.inst.primaryInst -> BasebandISA.CONFIG_CMD,
            _.inst.secondaryInst -> BasebandISA.CONFIG_BLE_CHANNEL_INDEX,
            _.inst.data -> 0.U(24.W),
            _.additionalData -> (scala.util.Random.nextInt(62) + 1).U)
        )

        // Push a debug command with post assembler loopback
        c.io.cmd.enqueue(
          (new BasebandModemCommand()).Lit(_.inst.primaryInst -> BasebandISA.DEBUG_CMD,
            _.inst.secondaryInst -> 2.U, _.inst.data -> pduLengthIn.U, _.additionalData -> addrInString.U)
        )

        println("Waiting readreq empty")
        c.io.controllerDMA.readReq.ready.poke(true.B)
        c.io.controllerDMA.readReq.waitForValid()
        c.io.controllerDMA.readReq.valid.expect(true.B)

        val pduLength = c.io.controllerDMA.readReq.bits.totalBytes.peek().litValue.intValue
        val addr = c.io.controllerDMA.readReq.bits.addr.peek().litValue.intValue

        assert(pduLength == pduLengthIn)
        assert(addr == addrInString.U.litValue.intValue)

        val inBytes = Seq(scala.util.Random.nextInt(255), pduLength - 2) ++
          Seq.tabulate(pduLength - 2)(_ => scala.util.Random.nextInt(255))

        val (inData, inSize) = seqToWidePackets(beatBytes, inBytes)
        val (inDataCRC, inSizeCRC) = seqToWidePackets(beatBytes, inBytes ++ bleCRCBytes(inBytes))

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

        c.io.basebandDMA.readData.enqueueSeq(inData.map(d => d.U))

        println("Waiting dmaDataMonitor")
        c.io.controllerDMA.readResp.enqueueNow((new EE290CDMAReaderResp(params.maxReadSize))
            .Lit(_.bytesRead -> pduLengthIn.U))

        println("Waiting writereq monitor")
        c.io.basebandDMA.writeReq.expectDequeueSeq(expectedOut)
      }
    }
  }
}
