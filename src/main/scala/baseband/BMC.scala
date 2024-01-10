package baseband

import chisel3._
import chisel3.util._
import modem.{FIRCoefficientChangeCommand, Modem, ModemAnalogIO, ModemIFCounterStateIO, ModemLUTCommand, ModemTuningControl, ModemTuningRXEnable}
import ee290cdma._

class BMCIO(params: BasebandModemParams, beatBytes: Int) extends Bundle {
  val analog = new Bundle {
    val data = new ModemAnalogIO(params)
    val enable = new Bundle {
      val rx = Output(new ModemTuningRXEnable)
      val lna = Output(Bool()) // LNA is separate from the receiver circuit chain (not separated I/Q)
    }
    val offChipMode = new Bundle {
      val rx = Output(Bool())
      val tx = Output(Bool())
    }
  }
  val cmd = Flipped(Decoupled(new BasebandModemCommand))
  val dma = new Bundle {
    val readReq = Decoupled(new EE290CDMAReaderReq(params.paddrBits, params.maxReadSize))
    val readResp = Flipped(Decoupled(new EE290CDMAReaderResp(params.maxReadSize)))
    val readData = Flipped(Decoupled(UInt((beatBytes * 8).W)))
    val writeReq = Decoupled(new EE290CDMAWriterReq(params.paddrBits, beatBytes))
  }
  val interrupt = Output(new BasebandModemInterrupts)
  val lutCmd = Flipped(Valid(new ModemLUTCommand))
  val firCmd = Flipped(Valid(new FIRCoefficientChangeCommand))
  val messages =  new BasebandModemMessagesIO
  val tuning = Input(new ModemTuningControl(params))
  val state = new Bundle {
    // State 0 components (MSB to LSB)
    val assemblerState      = Output(UInt(log2Ceil(6+1).W)) // 3
    val disassemblerState   = Output(UInt(log2Ceil(6+1).W)) // 3
    val txState             = Output(UInt(log2Ceil(2+1).W)) // 2
    val rxControllerState   = Output(UInt(log2Ceil(4+1).W)) // 3
    val txControllerState   = Output(UInt(log2Ceil(3+1).W)) // 2
    val mainControllerState = Output(UInt(log2Ceil(4+1).W)) // 3
    // ADC I 8
    // ADC Q 8
    // TOTAL: 32

    // State 1 components
    val modIndex = Output(UInt(6.W))   // 6
    val i = new Bundle {
      val agcIndex = Output(UInt(5.W)) // 5
      val dcoIndex = Output(UInt(5.W)) // 5
    }
    val q = new Bundle {
      val agcIndex = Output(UInt(5.W)) // 5
      val dcoIndex = Output(UInt(5.W)) // 5
    }
    // TOTAL: 26

    // State 2 components
    val bleBitCount = Output(UInt(32.W)) // 32
    // TOTAL: 32

    // State 3 Components
    val lrwpanBitCount = Output(UInt(32.W)) // 32
    // TOTAL: 32

    // State 4 Components
    // TOTAL: 0

    // TODO anything else we want to expose?
  }
  val ifCounter = new ModemIFCounterStateIO
}

// Baseband, Modem, and Controller Paired in a Unit
class BMC(params: BasebandModemParams, beatBytes: Int) extends Module {
  val io = IO(new BMCIO(params, beatBytes) {
    val adc_clock = Input(Clock())
  })

  // Controller
  val controller = Module(new Controller(params, beatBytes))
  controller.io.cmd <> io.cmd
  controller.io.dma.readReq <> io.dma.readReq
  controller.io.dma.readResp <> io.dma.readResp
  val constants = controller.io.constants

  // BLE Baseband
  val bleBaseband = Module(new BLEBaseband(params, beatBytes))
  bleBaseband.io.control <> controller.io.bleBasebandControl
  bleBaseband.io.constants := constants
  bleBaseband.io.dma.readData.bits := io.dma.readData.bits

  // LRWPAN Baseband
  val lrwpanBaseband = Module(new LRWPANBaseband(params, beatBytes))
  lrwpanBaseband.io.control <> controller.io.lrwpanBasebandControl
  lrwpanBaseband.io.constants := constants
  lrwpanBaseband.io.dma.readData.bits := io.dma.readData.bits

  // Allow only one baseband to make DMA write requests and respond/listen to DMA reads
  when(constants.radioMode === RadioMode.BLE) {
    io.dma.writeReq.valid := bleBaseband.io.dma.writeReq.valid
    io.dma.writeReq.bits := bleBaseband.io.dma.writeReq.bits
    bleBaseband.io.dma.writeReq.ready := io.dma.writeReq.ready
    lrwpanBaseband.io.dma.writeReq.ready := false.B
    io.dma.readData.ready := bleBaseband.io.dma.readData.ready
    bleBaseband.io.dma.readData.valid := io.dma.readData.valid
    lrwpanBaseband.io.dma.readData.valid := false.B
  }.otherwise {
    io.dma.writeReq.valid := lrwpanBaseband.io.dma.writeReq.valid
    io.dma.writeReq.bits := lrwpanBaseband.io.dma.writeReq.bits
    lrwpanBaseband.io.dma.writeReq.ready := io.dma.writeReq.ready
    bleBaseband.io.dma.writeReq.ready := false.B
    io.dma.readData.ready := lrwpanBaseband.io.dma.readData.ready
    bleBaseband.io.dma.readData.valid := false.B
    lrwpanBaseband.io.dma.readData.valid := io.dma.readData.valid
  }

  // Modem
  val modem = Module(new Modem(params))
  modem.io.adc_clock := io.adc_clock
  modem.io.analog <> io.analog.data
  modem.io.digital.ble <> bleBaseband.io.modem.digital
  modem.io.digital.lrwpan <> lrwpanBaseband.io.modem.digital
  modem.io.control.tuning := io.tuning
  modem.io.control.constants := constants
  modem.io.control.lutCmd := io.lutCmd
  modem.io.control.firCmd := io.firCmd
  modem.io.control.gfskTX <> controller.io.modemControl.gfskTX
  modem.io.control.mskTX <> controller.io.modemControl.mskTX
  modem.io.control.rx.enable := controller.io.analog.enable.rx.mix
  modem.io.control.bleLoopback := controller.io.bleBasebandControl.loopback(1)
  modem.io.control.lrwpanLoopback := controller.io.lrwpanBasebandControl.loopback(1)
  modem.io.ifCounter <> io.ifCounter

  // State 0
  io.state.assemblerState := Mux(constants.radioMode === RadioMode.BLE,
    bleBaseband.io.state.assemblerState,
    lrwpanBaseband.io.state.assemblerState
  )
  io.state.disassemblerState := Mux(constants.radioMode === RadioMode.BLE,
    bleBaseband.io.state.disassemblerState,
    lrwpanBaseband.io.state.disassemblerState
  )
  io.state.txState := modem.io.state.txState
  io.state.rxControllerState := controller.io.state.rxControllerState
  io.state.txControllerState := controller.io.state.txControllerState
  io.state.mainControllerState := controller.io.state.mainControllerState

  // State 1
  io.state.modIndex   := modem.io.state.modIndex
  io.state.i.agcIndex := modem.io.state.i.agcIndex
  io.state.i.dcoIndex := modem.io.state.i.dcoIndex
  io.state.q.agcIndex := modem.io.state.q.agcIndex
  io.state.q.dcoIndex := modem.io.state.q.dcoIndex

  // State 2
  io.state.bleBitCount := modem.io.digital.ble.rx.bitCount

  // State 3
  io.state.lrwpanBitCount := modem.io.digital.lrwpan.rx.bitCount

  // Interrupts
  io.messages <> controller.io.messages
  io.interrupt.rxError  := controller.io.interrupt.rxError
  io.interrupt.rxStart  := controller.io.interrupt.rxStart
  io.interrupt.rxFinish := controller.io.interrupt.rxFinish
  io.interrupt.txError  := controller.io.interrupt.txError
  io.interrupt.txFinish := controller.io.interrupt.txFinish

  // Enables
  io.analog.enable.rx <> controller.io.analog.enable.rx
  io.analog.enable.lna := controller.io.analog.enable.lna

  io.analog.offChipMode := controller.io.analog.offChipMode
}
