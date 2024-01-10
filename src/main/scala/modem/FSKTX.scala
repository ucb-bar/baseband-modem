package modem

import chisel3._
import chisel3.util._
import chisel3.experimental.FixedPoint
import chisel3.experimental.ChiselEnum
import baseband.BasebandModemParams
import freechips.rocketchip.util.AsyncQueue
import freechips.rocketchip.util.AsyncQueueParams

class FSKTXControlInputBundle(val params: BasebandModemParams) extends Bundle {
  private val maxPacketSize = 1 + 4 + 2 + params.maxReadSize + 3 // BLE packets are longest

  val totalBytes = UInt(log2Ceil(maxPacketSize+1).W)
}

class FSKTXControlOutputBundle extends Bundle {
  val done = Bool()
}

class FSKTXControlIO(val params: BasebandModemParams) extends Bundle {
  val in = Flipped(Decoupled(new FSKTXControlInputBundle(params)))
  val out = Output(new FSKTXControlOutputBundle)
}

class FSKTXOutputIO() extends Bundle {
  val modIndex = Output(UInt(6.W))
  val state = Output(UInt(log2Ceil(2+1).W))
  val done = Output(Bool())
}

class GFSKTX(params: BasebandModemParams) extends Module {
  val io = IO(new Bundle{
    val digital = new Bundle {
      val in = Flipped(Decoupled(UInt(1.W)))
    }
    val control = Flipped(Decoupled(new FSKTXControlInputBundle(params)))
    val firCmd = Flipped(Valid(new FIRCoefficientChangeCommand))
    val out = new FSKTXOutputIO()
  })

  private val maxPacketSize = 1 + 4 + params.maxReadSize + 3

  val s_idle :: s_working :: nil = Enum(2)

  val state = RegInit(s_idle)
  io.out.state := state

  val cyclesPerSymbol = 32
  val cyclesPerSample = cyclesPerSymbol / 16 // 2x oversample to shorten filter

  val counter = RegInit(0.U(8.W))
  val counterBytes = RegInit(0.U(3.W)) // Counts bits within a byte

  val sentBytes = RegInit(0.U(log2Ceil(maxPacketSize+1).W))
  val totalBytes = RegInit(0.U(log2Ceil(maxPacketSize+1).W))

  val done = RegInit(false.B)

  val firWidth = FIRConfigs.TX_Gaussian.dataType.getWidth.W
  val firBP = FIRConfigs.TX_Gaussian.dataType.binaryPoint
  val firInValid = RegInit(false.B)
  val firInData = RegInit(0.F(firWidth, firBP))

  val fir = Module(new FIR(FIRConfigs.TX_Gaussian))
  fir.io.in.valid := firInValid
  fir.io.in.bits := firInData
  fir.io.out.ready := state === s_working
  fir.io.firCmd := io.firCmd

  io.digital.in.ready := state === s_working && counter === 0.U && sentBytes < totalBytes

  io.control.ready := state === s_idle
  io.out.done := done

  when(state === s_idle) {
    done := false.B
    when (io.control.fire()) {
      state := s_working
      counter := 0.U
      totalBytes := io.control.bits.totalBytes + (1 + 4 + 3).U // Preamble, AA, CRC
      sentBytes := 0.U
    }
  }.elsewhen(state === s_working) {
    when(counter === 0.U) {
      when(io.digital.in.fire()) {
        firInValid := true.B
        firInData := Mux(io.digital.in.bits === 0.U, (-1).F(firWidth, firBP), 1.F(firWidth, firBP))
        counter := counter + 1.U
      }.elsewhen(sentBytes >= totalBytes){
        firInValid := true.B
        firInData := 0.F(firWidth, firBP)
        counter := counter + 1.U
      }.otherwise {
        firInValid := false.B
      }
    }.elsewhen(counter =/= 0.U) {
      counter := Mux(counter === (cyclesPerSymbol - 1).U, 0.U, counter + 1.U)

      when(counter === (cyclesPerSymbol - 1).U) {
        counterBytes := Mux(counterBytes === 7.U, 0.U, counterBytes + 1.U)

        when(counterBytes === 7.U) {
          when(sentBytes === totalBytes) {
            sentBytes := 0.U
            done := true.B
            state := s_idle
          }.otherwise {
            sentBytes := sentBytes + 1.U
          }
        }
      }

      when(fir.io.in.fire()) {
        firInValid := false.B
      }.elsewhen(counter % cyclesPerSample.U === 0.U) {
        firInValid := true.B
      }
    }
  }

  val firOut = fir.io.out.bits
  val signedModIdx = firOut(firOut.getWidth - 1, firOut.getWidth - 6)
  val modIdx = signedModIdx ^ 0x20.U // convert signed to unsigned between 0 and 63
  io.out.modIndex := Mux(state === s_working, modIdx, 31.U)
}

class MSKTX(params: BasebandModemParams) extends Module {
  val io = IO(new Bundle{
    val digital = new Bundle {
      val in = Flipped(Decoupled(UInt(1.W)))
    }
    val control = Flipped(Decoupled(new FSKTXControlInputBundle(params)))
    val out = new FSKTXOutputIO()
  })

  private val maxPacketSize = 1 + 4 + 1 + params.maxReadSize + 2 // Preamble(4) + SFD(1) + PHR(1) + CRC(2)
  private val pFd = 63.U(6.W)  // Fc + delta-F
  private val zFd = 31.U(6.W)  // Fc + 0
  private val nFd = 0.U(6.W)   // Fc - delta-F

  private val cyclesPerChip = 16 // 32 MHz ADC clock / 2 Mchip/s = 16 cycles/chip
  private val chipsPerByte = 64 // counts chips within byte (1byte * 32chips/4bits -> 64 chips)

  object State extends ChiselEnum {
    val s_idle, s_working = Value
  }

  val state = RegInit(State.s_idle)
  io.out.state := state.asUInt

  val outModIndex = RegInit(zFd)
  val prevChip = RegInit(false.B)
  val evenChip = RegInit(false.B)

  val chipHoldCounter = RegInit(0.U(8.W))
  val counterByte = RegInit(0.U(8.W))

  val bytesLeft = RegInit(0.U(log2Ceil(maxPacketSize+1).W))

  val done = RegInit(false.B)
  io.out.done := done

  // some defaults
  io.digital.in.ready := false.B
  io.control.ready := false.B
  chipHoldCounter := chipHoldCounter - 1.U
  done := false.B

  switch(state) {
    is(State.s_idle) {
      io.control.ready := true.B
      outModIndex := zFd

      when (io.control.fire()) {
        state := State.s_working
        evenChip := true.B
        prevChip := false.B // doesn't really matter
        chipHoldCounter := (cyclesPerChip - 1).U // wait one chip period
        counterByte := chipsPerByte.U // one extra bit bc extra chip period of waiting
        bytesLeft := io.control.bits.totalBytes + (4 + 1 + 1 + 2).U // Preamble(4) + SFD(1) + PHR(1) + CRC(2)
        // note due to implementation we exit one byte early so must wait an extra one
      }
    }

    is(State.s_working) {
      when (chipHoldCounter === 0.U) {
        io.digital.in.ready := true.B
        chipHoldCounter := (cyclesPerChip - 1).U
        counterByte := counterByte - 1.U

        when (io.digital.in.fire()) {
          when(evenChip) {
            outModIndex := Mux((prevChip ^ io.digital.in.bits) === 0.U, pFd, nFd)
          }.otherwise {
            outModIndex := Mux((prevChip ^ io.digital.in.bits) === 0.U, nFd, pFd)
          }
          prevChip := io.digital.in.bits
          evenChip := ~evenChip
          counterByte := counterByte - 1.U
        }.otherwise { // shouldn't happen unless PA is slow
          outModIndex := zFd
        }

        when (counterByte === 0.U) {
          bytesLeft := bytesLeft - 1.U
          counterByte := (chipsPerByte - 1).U
        }

        when (bytesLeft === 0.U) {
          state := State.s_idle
          outModIndex := zFd
          done := true.B
        }
      }
    }
  }

  io.out.modIndex := Mux(state === State.s_working, outModIndex, zFd)
}

/* Wrappers to declutter CDC queues */

class GFSKTXWrapper(params: BasebandModemParams) extends Module {
    val io = IO(new Bundle {
    val analog = new Bundle {
      val modIndex = Output(UInt(6.W))
    }
    val digital = new Bundle {
      val in = Flipped(Decoupled(UInt(1.W)))
    }
    val control = new FSKTXControlIO(params)
    val state = Output(UInt(log2Ceil(2+1).W))
    val firCmd = Flipped(Valid(new FIRCoefficientChangeCommand))
    val adc_clock = Input(Clock())
  })

  val gfskTX = withClock(io.adc_clock) {Module(new GFSKTX(params))}

  val digInQueue = Module(new AsyncQueue(UInt(1.W), AsyncQueueParams(depth = params.modemQueueDepth)))
  digInQueue.io.enq_clock := clock
  digInQueue.io.enq_reset := reset
  digInQueue.io.enq <> io.digital.in

  digInQueue.io.deq_clock := io.adc_clock
  digInQueue.io.deq_reset := reset
  gfskTX.io.digital.in <> digInQueue.io.deq

  val firCmdQueue = Module(new AsyncQueue(new FIRCoefficientChangeCommand, AsyncQueueParams(depth = params.asyncQueueDepth)))
  firCmdQueue.io.enq_clock := clock
  firCmdQueue.io.enq_reset := reset
  firCmdQueue.io.enq.bits := io.firCmd.bits
  firCmdQueue.io.enq.valid := io.firCmd.valid

  firCmdQueue.io.deq_clock := io.adc_clock
  firCmdQueue.io.deq_reset := reset
  firCmdQueue.io.deq.ready := true.B
  gfskTX.io.firCmd.bits := firCmdQueue.io.deq.bits
  gfskTX.io.firCmd.valid := firCmdQueue.io.deq.valid

  val controlInputQueue = Module(new AsyncQueue(new FSKTXControlInputBundle(params), AsyncQueueParams(depth = params.asyncQueueDepth)))
  controlInputQueue.io.enq_clock := clock
  controlInputQueue.io.enq_reset := reset
  controlInputQueue.io.enq <> io.control.in

  controlInputQueue.io.deq_clock := io.adc_clock
  controlInputQueue.io.deq_reset := reset
  gfskTX.io.control <> controlInputQueue.io.deq

  val outputQueue = Module(new AsyncQueue(new FSKTXOutputIO(), AsyncQueueParams(depth = params.asyncQueueDepth)))
  outputQueue.io.enq_clock := io.adc_clock
  outputQueue.io.enq_reset := reset
  outputQueue.io.enq.valid := true.B
  outputQueue.io.enq.bits := gfskTX.io.out

  outputQueue.io.deq_clock := clock
  outputQueue.io.deq_reset := reset
  val outputIO = RegEnable(outputQueue.io.deq.bits, outputQueue.io.deq.valid)
  outputQueue.io.deq.ready := true.B

  io.control.out.done := outputIO.done
  io.state := outputIO.state
  io.analog.modIndex := outputIO.modIndex
}

// TODO: Combine both and parametrize
class MSKTXWrapper(params: BasebandModemParams) extends Module {
    val io = IO(new Bundle {
    val analog = new Bundle {
      val modIndex = Output(UInt(6.W))
    }
    val digital = new Bundle {
      val in = Flipped(Decoupled(UInt(1.W)))
    }
    val control = new FSKTXControlIO(params)
    val state = Output(UInt(log2Ceil(2+1).W))
    val adc_clock = Input(Clock())
  })

  val mskTX = withClock(io.adc_clock) {Module(new MSKTX(params))}

  val digInQueue = Module(new AsyncQueue(UInt(1.W), AsyncQueueParams(depth = params.modemQueueDepth)))
  digInQueue.io.enq_clock := clock
  digInQueue.io.enq_reset := reset
  digInQueue.io.enq <> io.digital.in

  digInQueue.io.deq_clock := io.adc_clock
  digInQueue.io.deq_reset := reset
  mskTX.io.digital.in <> digInQueue.io.deq

  val controlInputQueue = Module(new AsyncQueue(new FSKTXControlInputBundle(params), AsyncQueueParams(depth = params.asyncQueueDepth)))
  controlInputQueue.io.enq_clock := clock
  controlInputQueue.io.enq_reset := reset
  controlInputQueue.io.enq <> io.control.in

  controlInputQueue.io.deq_clock := io.adc_clock
  controlInputQueue.io.deq_reset := reset
  mskTX.io.control <> controlInputQueue.io.deq

  val outputQueue = Module(new AsyncQueue(new FSKTXOutputIO(), AsyncQueueParams(depth = params.asyncQueueDepth)))
  outputQueue.io.enq_clock := io.adc_clock
  outputQueue.io.enq_reset := reset
  outputQueue.io.enq.valid := true.B
  outputQueue.io.enq.bits := mskTX.io.out

  outputQueue.io.deq_clock := clock
  outputQueue.io.deq_reset := reset
  val outputIO = RegEnable(outputQueue.io.deq.bits, outputQueue.io.deq.valid)
  outputQueue.io.deq.ready := true.B

  io.control.out.done := outputIO.done
  io.state := outputIO.state
  io.analog.modIndex := outputIO.modIndex
}