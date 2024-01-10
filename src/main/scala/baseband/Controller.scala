package baseband

import chisel3._
import chisel3.experimental.BundleLiterals.AddBundleLiteralConstructor
import chisel3.util._
import BasebandISA._
import ee290cdma._
import modem._

object TXChainControllerInputCommands {
  val START = 0.U
  val DEBUG = 1.U
}
class TXChainControllerCommand(val addrBits: Int, val maxReadSize: Int) extends Bundle {
  val command = UInt(1.W)
  val addr = UInt(addrBits.W)
  val totalBytes = UInt(log2Ceil(maxReadSize+1).W)
}

class TXChainControllerControlIO(addrBits: Int, maxReadSize: Int) extends Bundle {
  val cmd = Flipped(Decoupled(new TXChainControllerCommand(addrBits, maxReadSize)))
  val done = Output(Bool())
}

class TXChainController(params: BasebandModemParams) extends Module {
  val io = IO(new Bundle {
    val interrupt = new Bundle {
      val error = Output(Bool())
      val txFinish = Output(Bool())
    }
    val bleAssemblerControl = Flipped(new BLEAssemblerControlIO)
    val lrwpanAssemblerControl = Flipped(new LRWPANAssemblerControlIO)
    val modemTXControl = Flipped(new FSKTXControlIO(params))
    val  dma = new Bundle {
      val readReq = Decoupled(new EE290CDMAReaderReq(params.paddrBits, params.maxReadSize))
      val readResp = Flipped(Decoupled(new EE290CDMAReaderResp(params.maxReadSize)))
    }
    val constants = Input(new BasebandConstants)
    val control = new TXChainControllerControlIO(params.paddrBits, params.maxReadSize)
    val messages = new BasebandModemMessagesIO
    val state = Output(UInt(log2Ceil(3+1).W))
  })

  val s_idle :: s_working :: s_error :: Nil = Enum(3)
  val state = RegInit(s_idle)

  val done = RegInit(false.B)
  val cmd = Reg(new TXChainControllerCommand(params.paddrBits, params.maxReadSize))

  val assemblerReqValid = RegInit(false.B)
  val assemblerDone = RegInit(false.B)

  val modemTXReqValid = RegInit(false.B)
  val modemTXDone = RegInit(false.B)

  val dmaReqValid = RegInit(false.B)
  val dmaRespReady = RegInit(false.B)
  val dmaReadResp = Reg(new EE290CDMAReaderResp(params.maxReadSize))

  val error = RegInit(false.B)
  val txFinish = RegInit(false.B)

  val errorMessageValid = RegInit(false.B)
  val errorMessageBits = RegInit(0.U(32.W))

  // State IO
  io.state := state

  // Control IO
  io.control.cmd.ready := io.bleAssemblerControl.in.ready & io.dma.readReq.ready & state === s_idle
  io.control.done := done

  // BLE Assembler IO
  io.bleAssemblerControl.in.valid := Mux(io.constants.radioMode === RadioMode.BLE, assemblerReqValid, false.B)
  io.bleAssemblerControl.in.bits.pduLength := cmd.totalBytes - 2.U // minus header
  io.bleAssemblerControl.in.bits.aa := io.constants.accessAddress

  // LRWPAN Assembler IO
  io.lrwpanAssemblerControl.in.valid := Mux(io.constants.radioMode === RadioMode.LRWPAN, assemblerReqValid, false.B)
  io.lrwpanAssemblerControl.in.bits.pduLength := cmd.totalBytes // only reads data payload from DMA, no length/header
  io.lrwpanAssemblerControl.in.bits.sfd := "b10100111".U(8.W)

  // TX IO
  io.modemTXControl.in.valid := modemTXReqValid
  io.modemTXControl.in.bits.totalBytes := cmd.totalBytes

  // DMA IO
  io.dma.readReq.valid := dmaReqValid
  io.dma.readReq.bits.addr := cmd.addr
  io.dma.readReq.bits.totalBytes := cmd.totalBytes

  io.dma.readResp.ready := dmaRespReady

  // Interrupt
  io.interrupt.error := error
  io.interrupt.txFinish := txFinish

  // Messages
  io.messages.rxErrorMessage.bits := DontCare
  io.messages.rxErrorMessage.valid := false.B
  io.messages.rxFinishMessage.bits := DontCare
  io.messages.rxFinishMessage.valid := false.B

  io.messages.txErrorMessage.bits := errorMessageBits
  io.messages.txErrorMessage.valid := errorMessageValid

  // Set signals that are a 1-cycle pulse
  when(error) {
    error := false.B
  }

  when(txFinish) {
    txFinish := false.B
  }

  when(done) {
    done := false.B
  }

  // dual-mode helpers
  val ioAssemblerReadyValid = Mux(io.constants.radioMode === RadioMode.BLE, io.bleAssemblerControl.in.fire(), io.lrwpanAssemblerControl.in.fire())
  val ioAssemblerDone = Mux(io.constants.radioMode === RadioMode.BLE, io.bleAssemblerControl.out.done, io.lrwpanAssemblerControl.out.done)

  // Main FSM
  switch(state) {
    is(s_idle) {
      when(io.control.cmd.fire()) {
        when(io.control.cmd.bits.totalBytes > 1.U & io.control.cmd.bits.totalBytes < 258.U) {
          cmd := io.control.cmd.bits

          dmaReqValid := true.B
          assemblerReqValid := true.B

          when (io.control.cmd.bits.command === TXChainControllerInputCommands.START ||
                io.control.cmd.bits.command === TXChainControllerInputCommands.DEBUG) {
            modemTXReqValid := true.B
          }

          state := s_working
        }.otherwise {
          // Confirm that all regs get reset to false
          assemblerReqValid := false.B
          assemblerDone := false.B
          modemTXReqValid := false.B
          modemTXDone := false.B
          dmaReqValid := false.B
          dmaRespReady := false.B

          errorMessageValid := true.B
          errorMessageBits := ERROR_MESSAGE(TX_INVALID_LENGTH, io.control.cmd.bits.totalBytes)

          state := s_error
        }
      }
    }
    is(s_working) {
      when(io.dma.readReq.fire()) {
        dmaReqValid := false.B
        dmaRespReady := true.B
      }

      // assemblerReqValid is muxed to the assembler
      when(ioAssemblerReadyValid) {
        assemblerReqValid := false.B
      }

      when(io.modemTXControl.in.fire()) {
        modemTXReqValid := false.B
      }

      when(io.dma.readResp.fire()) {
        dmaReadResp := io.dma.readResp.bits
        dmaRespReady := false.B
      }

      // Set assemblerDone to true only when the active assembler is done
      when(ioAssemblerDone) {
        assemblerDone := true.B
      }

      when(io.modemTXControl.out.done) {
        modemTXDone := true.B
      }

      when(assemblerDone && (modemTXDone | cmd.command === TXChainControllerInputCommands.DEBUG)) {
        // Confirm that all regs get reset to false
        assemblerReqValid := false.B
        assemblerDone := false.B
        modemTXReqValid := false.B
        modemTXDone := false.B
        dmaReqValid := false.B
        dmaRespReady := false.B

        // DMA should complete before our packet is done sending, so the resp should match our cmd
        when(dmaReadResp.bytesRead === cmd.totalBytes) {
          // Interrupt to say that TX is finished
          txFinish := true.B

          // Fire done
          done := true.B

          state := s_idle
        }.otherwise {
          errorMessageValid := true.B
          errorMessageBits := ERROR_MESSAGE(TX_NOT_ENOUGH_DMA_DATA, io.control.cmd.bits.totalBytes)
          state := s_error
        }
      }
    }
    is(s_error) {
      when (io.messages.txErrorMessage.fire()) {
        // Signal error only after the message is taken up
        error := true.B
        errorMessageValid := false.B

        // Fire Done
        done := true.B

        state := s_idle
      }
    }
  }
}

class RXChainControllerCommand(val addrBits: Int) extends Bundle {
  val command = UInt(2.W)
  val addr = UInt(addrBits.W)
}

class RXChainControllerControlIO(addrBits: Int) extends Bundle {
  val cmd = Flipped(Decoupled(new RXChainControllerCommand(addrBits)))
  val baseAddr = Valid(UInt(addrBits.W))
  val done = Output(Bool())
}

class RXChainController(params: BasebandModemParams) extends Module {
  val io = IO(new Bundle {
    val interrupt = new Bundle {
      val error = Output(Bool())
      val rxStart = Output(Bool())
      val rxFinish = Output(Bool())
    }
    val bleDisassemblerControl = Flipped(new BLEDisassemblerControlIO)
    val lrwpanDisassemblerControl = Flipped(new LRWPANDisassemblerControlIO)
    val modemRXControl = new Bundle {
      val enable = Output(Bool())
    }
    val constants = Input(new BasebandConstants)
    val control = new RXChainControllerControlIO(params.paddrBits)
    val messages = new BasebandModemMessagesIO
    val state = Output(UInt(log2Ceil(4+1).W))
  })

  val s_idle :: s_working :: s_error :: s_rxFinish :: Nil = Enum(4)
  val state = RegInit(s_idle)

  val done = RegInit(false.B)
  val cmd = Reg(new RXChainControllerCommand(params.paddrBits))

  val baseAddrValid = RegInit(false.B)

  val disassemblerReqValid = RegInit(false.B)
  val disassemblerBusy = RegInit(false.B)

  val modemRXEnable = RegInit(false.B)

  val error = RegInit(false.B)
  val rxStart = RegInit(false.B)
  val rxFinish = RegInit(false.B)

  val errorMessageValid = RegInit(false.B)
  val errorMessageBits = RegInit(0.U(32.W))

  val rxFinishMessageValid = RegInit(false.B)
  val rxFinishMessageBits = RegInit(0.U(32.W))

  def gotoIdle(): Unit = {
    // Signal done
    done := true.B

    // Confirm that all regs get reset to false
    modemRXEnable := false.B
    disassemblerBusy := false.B
    disassemblerReqValid := false.B

    state := s_idle
  }

  // State IO
  io.state := state

  // Control IO
  io.control.cmd.ready := state === s_idle | state === s_working
  io.control.done := done
  io.control.baseAddr.valid := baseAddrValid
  io.control.baseAddr.bits := cmd.addr

  // BLE Disassembler IO
  io.bleDisassemblerControl.in.valid := Mux(io.constants.radioMode === RadioMode.BLE, disassemblerReqValid, false.B)
  io.bleDisassemblerControl.in.bits.command := cmd.command

  // LRWPAN Disassembler IO
  io.lrwpanDisassemblerControl.in.valid := Mux(io.constants.radioMode === RadioMode.LRWPAN, disassemblerReqValid, false.B)
  io.lrwpanDisassemblerControl.in.bits.command := cmd.command

  // Modem IO
  io.modemRXControl.enable := modemRXEnable

  // Interrupt IO
  io.interrupt.error := error
  io.interrupt.rxStart := rxStart
  io.interrupt.rxFinish := rxFinish

  // Messages
  io.messages.txErrorMessage.bits := DontCare
  io.messages.txErrorMessage.valid := DontCare

  io.messages.rxErrorMessage.bits := errorMessageBits
  io.messages.rxErrorMessage.valid := errorMessageValid
  io.messages.rxFinishMessage.bits := rxFinishMessageBits
  io.messages.rxFinishMessage.valid := rxFinishMessageValid

  // Set up 1-cycle pulses
  when(error) {
    error := false.B
  }

  when(rxStart) {
    rxStart := false.B
  }

  when(rxFinish) {
    rxFinish := false.B
  }

  when(done) {
    done := false.B
  }

  // Dual-mode helper wires
  val flag_aa_sfd = Mux(io.constants.radioMode === RadioMode.BLE, io.bleDisassemblerControl.out.flag_sop, io.lrwpanDisassemblerControl.out.flag_sop)
  val flag_crc = Mux(io.constants.radioMode === RadioMode.BLE, io.bleDisassemblerControl.out.flag_crc, io.lrwpanDisassemblerControl.out.flag_crc)
  val ioDisassemblerReadyValid = Mux(io.constants.radioMode === RadioMode.BLE, io.bleDisassemblerControl.in.fire(), io.lrwpanDisassemblerControl.in.fire())
  val ioDisassemblerDone = Mux(io.constants.radioMode === RadioMode.BLE, io.bleDisassemblerControl.out.done, io.lrwpanDisassemblerControl.out.done)
  val ioDisassemblerLength = Mux(io.constants.radioMode === RadioMode.BLE, io.bleDisassemblerControl.out.length, io.lrwpanDisassemblerControl.out.length)

  // Main FSM
  switch(state) {
    is(s_idle) {
      disassemblerReqValid := false.B
      modemRXEnable := false.B

      when(io.control.cmd.fire() & (io.control.cmd.bits.command === PDAControlInputCommands.START_CMD |
                                    io.control.cmd.bits.command === PDAControlInputCommands.DEBUG_CMD)) {
        when(io.control.cmd.bits.addr(1,0) === 0.U) {
          cmd := io.control.cmd.bits
          baseAddrValid := true.B

          state := s_working
        }.otherwise {
          errorMessageValid := true.B
          errorMessageBits := ERROR_MESSAGE(RX_INVALID_ADDR)
          state := s_error
        }
      }
    }
    is(s_working) {
      when(io.control.cmd.fire() & (io.control.cmd.bits.command === PDAControlInputCommands.EXIT_CMD)) {
        cmd := io.control.cmd.bits
        disassemblerReqValid := true.B
      }.otherwise {
        when(io.control.baseAddr.fire()) {
          baseAddrValid := false.B

          disassemblerReqValid := true.B
          when(cmd.command === PDAControlInputCommands.START_CMD) { // Don't enable modem in debug mode
            modemRXEnable := true.B
          }
        }

        when(ioDisassemblerReadyValid) {
          disassemblerReqValid := false.B
        }

        when(!disassemblerBusy) {
          when((io.bleDisassemblerControl.out.busy & io.constants.radioMode === RadioMode.BLE)
            | (io.lrwpanDisassemblerControl.out.busy & io.constants.radioMode === RadioMode.LRWPAN)) {

            rxStart := true.B
            disassemblerBusy := true.B
          }
        }
      }

      when(ioDisassemblerDone){
        modemRXEnable := false.B
        disassemblerBusy := false.B

        when (flag_aa_sfd | flag_crc) {
          errorMessageValid := true.B
          when(flag_aa_sfd && flag_crc) {
            errorMessageBits := ERROR_MESSAGE(RX_FLAG_AA_AND_CRC)
          }.elsewhen(flag_aa_sfd) {
            errorMessageBits := ERROR_MESSAGE(RX_FLAG_AA)
          }.otherwise {
            errorMessageBits := ERROR_MESSAGE(RX_FLAG_CRC)
          }
          state := s_error
        }.otherwise {
          rxFinishMessageValid := true.B
          rxFinishMessageBits := RX_FINISH_MESSAGE(ioDisassemblerLength)
          state := s_rxFinish
        }
      }
    }
    is(s_rxFinish) {
      when(io.messages.rxFinishMessage.fire()) {
        // Send finish after message taken up
        rxFinish := true.B

        rxFinishMessageValid := false.B
        gotoIdle()
      }
    }
    is(s_error) {
      when(io.messages.rxErrorMessage.fire()) {
        // Send error after message taken up
        error := true.B

        errorMessageValid := false.B
        gotoIdle()
      }
    }
  }
}

class Controller(params: BasebandModemParams, beatBytes: Int) extends Module {
  val io = IO(new Bundle {
    val interrupt = Output(new BasebandModemInterrupts)
    val analog = new Bundle {
      val offChipMode = new Bundle {
        val rx = Output(Bool())
        val tx = Output(Bool())
      }
      val enable = new Bundle {
        val rx = Output(new ModemTuningRXEnable)
        val lna = Output(Bool()) // LNA is separate from the receiver circuit chain (not separated I/Q)
      }
    }
    val bleBasebandControl = Flipped(new BLEBasebandControlIO(params.paddrBits))
    val lrwpanBasebandControl = Flipped(new LRWPANBasebandControlIO(params.paddrBits))
    val cmd = Flipped(Decoupled(new BasebandModemCommand))
    val constants = Output(new BasebandConstants)
    val  dma = new Bundle {
      val readReq = Decoupled(new EE290CDMAReaderReq(params.paddrBits, params.maxReadSize))
      val readResp = Flipped(Decoupled(new EE290CDMAReaderResp(params.maxReadSize)))
    }
    val modemControl = Flipped(new ModemControlIO(params))
    val messages = new BasebandModemMessagesIO
    val state = new Bundle {
      val rxControllerState = Output(UInt(log2Ceil(4+1).W))
      val txControllerState = Output(UInt(log2Ceil(3+1).W))
      val mainControllerState = Output(UInt(log2Ceil(4+1).W))
    }
  })

  val constants = RegInit(new BasebandConstants, WireInit(new BasebandConstants().Lit(
    _.radioMode -> RadioMode.BLE,
    _.crcSeed -> "x555555".U,
    _.bleChannelIndex -> "b010011".U,
    _.lrwpanChannelIndex -> "b00000".U,
    _.accessAddress -> "x8E89BED6".U,
    _.shr -> "xA700".U
  )))

  io.constants := constants
  io.modemControl <> DontCare // default, necessary things overridden later

  val s_idle :: s_tx :: s_rx :: s_debug :: Nil = Enum(4)

  val state = RegInit(s_idle)

  // TX Controller
  val txController = Module(new TXChainController(params))
  io.bleBasebandControl.bleAssembler <> txController.io.bleAssemblerControl
  io.lrwpanBasebandControl.lrwpanAssembler <> txController.io.lrwpanAssemblerControl
  io.dma <> txController.io.dma
  io.modemControl.gfskTX <> txController.io.modemTXControl
  io.modemControl.mskTX <> txController.io.modemTXControl

  txController.io.constants := constants

  val txControllerCmdValid = RegInit(false.B)
  val txControllerCmd = Reg(new TXChainControllerCommand(params.paddrBits, params.maxReadSize))
  txController.io.control.cmd.valid := txControllerCmdValid
  txController.io.control.cmd.bits := txControllerCmd
  txController.io.messages.rxFinishMessage.ready := false.B
  txController.io.messages.rxErrorMessage.ready := false.B

  val txControllerDone = RegInit(false.B)

  // RX Controller
  val rxController = Module(new RXChainController(params))
  io.bleBasebandControl.bleDisassembler <> rxController.io.bleDisassemblerControl
  io.lrwpanBasebandControl.lrwpanDisassembler <> rxController.io.lrwpanDisassemblerControl
  io.bleBasebandControl.baseAddr <> rxController.io.control.baseAddr
  io.lrwpanBasebandControl.baseAddr <> rxController.io.control.baseAddr
  rxController.io.messages.txErrorMessage.ready := false.B

  rxController.io.constants := constants

  val rxControllerCmdValid = RegInit(false.B)
  val rxControllerCmd = Reg(new RXChainControllerCommand(params.paddrBits))
  rxController.io.control.cmd.valid := rxControllerCmdValid
  rxController.io.control.cmd.bits := rxControllerCmd

  val rxControllerDone = RegInit(false.B)

  // Loopback Mask
  val loopbackMask = RegInit(0.U(4.W))
  io.bleBasebandControl.loopback := loopbackMask(1,0).asBools()
  io.lrwpanBasebandControl.loopback := loopbackMask(1,0).asBools()

  // Analog IO
  io.analog.enable.rx.mix := Mux(state === s_rx | state === s_debug, true.B, false.B)
  io.analog.enable.rx.buf := Mux(state === s_rx | state === s_debug, true.B, false.B)
  io.analog.enable.rx.tia := Mux(state === s_rx | state === s_debug, true.B, false.B)
  io.analog.enable.rx.vga := Mux(state === s_rx | state === s_debug, true.B, false.B)
  io.analog.enable.rx.bpf := Mux(state === s_rx | state === s_debug, true.B, false.B)
  io.analog.enable.lna := Mux(state === s_rx | state === s_debug, true.B, false.B)

  io.analog.offChipMode.rx := state === s_rx
  io.analog.offChipMode.tx := state === s_tx

  // State IO
  io.state.rxControllerState := rxController.io.state
  io.state.txControllerState := txController.io.state
  io.state.mainControllerState := state
  // Interrupts
  io.interrupt.txError := txController.io.interrupt.error
  io.interrupt.txFinish := txController.io.interrupt.txFinish
  io.interrupt.rxError :=  rxController.io.interrupt.error
  io.interrupt.rxStart := rxController.io.interrupt.rxStart
  io.interrupt.rxFinish := rxController.io.interrupt.rxFinish

  // Messages
  io.messages.txErrorMessage <> txController.io.messages.txErrorMessage
  io.messages.rxErrorMessage <> rxController.io.messages.rxErrorMessage
  io.messages.rxFinishMessage <> rxController.io.messages.rxFinishMessage

  // Command wires
  io.cmd.ready := state === s_idle | state === s_rx

  switch(state) {
    is (s_idle) {
      when (io.cmd.fire) {
        switch (io.cmd.bits.inst.primaryInst) {
          is (BasebandISA.CONFIG_CMD) { // Don't need to waste a cycle to setup config
            switch (io.cmd.bits.inst.secondaryInst) {
              is (BasebandISA.CONFIG_RADIO_MODE) {
                constants.radioMode := io.cmd.bits.additionalData(0)
              }
              is (BasebandISA.CONFIG_CRC_SEED) {
                constants.crcSeed := io.cmd.bits.additionalData(23, 0)
              }
              is (BasebandISA.CONFIG_ACCESS_ADDRESS) {
                constants.accessAddress := io.cmd.bits.additionalData
              }
              is (BasebandISA.CONFIG_SHR) {
                constants.shr := io.cmd.bits.additionalData(15,0)
              }
              is (BasebandISA.CONFIG_BLE_CHANNEL_INDEX) {
                constants.bleChannelIndex := io.cmd.bits.additionalData(5, 0)
              }
              is (BasebandISA.CONFIG_LRWPAN_CHANNEL_INDEX) {
                constants.lrwpanChannelIndex := io.cmd.bits.additionalData(5, 0)
              }
            }
          }
          is (BasebandISA.SEND_CMD) {
            txControllerCmdValid := true.B
            txControllerCmd.totalBytes := io.cmd.bits.inst.data
            txControllerCmd.addr := io.cmd.bits.additionalData
            txControllerCmd.command := TXChainControllerInputCommands.START

            state := s_tx
          }
          is (BasebandISA.RECEIVE_START_CMD) {
            rxControllerCmdValid := true.B
            rxControllerCmd.command := PDAControlInputCommands.START_CMD
            rxControllerCmd.addr := io.cmd.bits.additionalData
            state := s_rx
          }
          is (BasebandISA.DEBUG_CMD) {
            loopbackMask := io.cmd.bits.inst.secondaryInst

            txControllerCmdValid := true.B
            txControllerCmd.totalBytes := io.cmd.bits.inst.data
            txControllerCmd.addr := io.cmd.bits.additionalData
            txControllerCmd.command := TXChainControllerInputCommands.DEBUG

            rxControllerCmdValid := true.B
            rxControllerCmd.command := PDAControlInputCommands.DEBUG_CMD
            rxControllerCmd.addr := (io.cmd.bits.additionalData + io.cmd.bits.inst.data + beatBytes.U) & (~((beatBytes-1).U(params.paddrBits.W))).asUInt
            state := s_debug
          }
        }
      }
    }
    is (s_tx) {
      when (txController.io.control.cmd.fire()) {
        txControllerCmdValid := false.B
      }

      when (txController.io.control.done) {
        state := s_idle
      }
    }
    is (s_rx) {
      when (io.cmd.fire & io.cmd.bits.inst.primaryInst === BasebandISA.RECEIVE_EXIT_CMD) {
        rxControllerCmd.command := PDAControlInputCommands.EXIT_CMD
        rxControllerCmdValid := true.B
      }.otherwise {
        when(rxController.io.control.cmd.fire()) {
          rxControllerCmdValid := false.B
        }

        when(rxController.io.control.done) {
          state := s_idle
        }
      }
    }
    is (s_debug) {
      when (rxController.io.control.cmd.fire()) {
        rxControllerCmdValid := false.B
      }

      when (txController.io.control.cmd.fire()) {
        txControllerCmdValid := false.B
      }

      when (txController.io.control.done) {
        txControllerDone := true.B
      }

      when (rxController.io.control.done) {
        rxControllerDone := true.B
      }

      when (rxControllerDone & txControllerDone) {
        loopbackMask := 0.U

        txControllerDone := false.B
        rxControllerDone := false.B

        state := s_idle
      }
    }
  }
}