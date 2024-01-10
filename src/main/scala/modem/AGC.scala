package modem

import chisel3._
import chisel3.util._
import baseband.BasebandModemParams
import freechips.rocketchip.util.AsyncQueue
import freechips.rocketchip.util.AsyncQueueParams

class AGCControlIO extends Bundle {
  val sampleWindow    = UInt(8.W)
  val idealPeakToPeak = UInt(8.W)
  val toleranceP2P    = UInt(8.W)
  val gainInc         = UInt(8.W)
  val gainDec         = UInt(8.W)
  val reset           = Bool()
}

/*  AGC (Automatic Gain Control) for one ADC

    Has a current gain and updates it every N cycles (the AGC update period)
      Every period, keeps track of the max and min to compute peak to peak
      If peak to peak below target, increase gain. If above, decrease gain
      Essentially, it's a bang-bang controller w/ hysteresis

    For 32MHz ADC w/ min 1.5MHz signal, should have window >= 22 to see peaks
    Peak to peak around 90% of max should be good for DSP
    Want fast decrease and slow increase bc packets start saturated
      May need to be faster for BLE bc short preamble. LRWPAN be chill
    To stabilize, tolerance should be larger than gain resolution
 */
class AGC(params: BasebandModemParams) extends Module {
  val io = IO(new Bundle {
    val adcIn = Input(UInt(params.adcBits.W))
    val vgaLUTIndex = Output(UInt(5.W))
    val control = Input(new AGCControlIO)
  })

  withReset(reset.asBool | io.control.reset) {
    val counter = RegInit(0.U(8.W))
    val counter_ovf = counter >= io.control.sampleWindow // N = window + 1
    counter := Mux(counter_ovf, 0.U, counter + 1.U)

    val midpt  = scala.math.pow(2,params.adcBits-1).toInt.U(8.W)
    val minreg = RegInit(midpt)
    val maxreg = RegInit(midpt)
    val minval = Mux(minreg < io.adcIn, minreg, io.adcIn)
    val maxval = Mux(maxreg > io.adcIn, maxreg, io.adcIn)
    minreg := Mux(counter_ovf, midpt, minval)
    maxreg := Mux(counter_ovf, midpt, maxval)
    val p2p = maxval - minval

    val init_gain = 31.U(5.W) // start at the top
    val current_gain = RegInit(init_gain)

    val inc_gain = current_gain +& io.control.gainInc
    val dec_gain = Mux(io.control.gainDec > current_gain, 0.U, current_gain - io.control.gainDec)

    when(counter_ovf) {
      when(p2p > io.control.idealPeakToPeak + io.control.toleranceP2P) {
        current_gain := dec_gain
      }.elsewhen(p2p < io.control.idealPeakToPeak - io.control.toleranceP2P) {
        current_gain := Mux(inc_gain > 31.U, 31.U, inc_gain)
      }
    }

    io.vgaLUTIndex := current_gain
  }
}

class AGCWrapper(params: BasebandModemParams) extends Module {
  val io = IO(new Bundle {
    val adcIn = Input(UInt(params.adcBits.W))
    val vgaLUTIndex = Output(UInt(5.W))
    val control = Input(new AGCControlIO)
    val adc_clock = Input(Clock())
  })

  val agc = withClock(io.adc_clock) {Module(new AGC(params))}
  agc.io.adcIn := io.adcIn

  val controlQueue = Module(new AsyncQueue(new AGCControlIO, AsyncQueueParams(depth = params.modemQueueDepth)))
  controlQueue.io.enq_clock := clock
  controlQueue.io.enq_reset := reset
  controlQueue.io.enq.bits := io.control

  val controlPrev = RegNext(io.control)
  controlQueue.io.enq.valid := controlPrev.asUInt =/= io.control.asUInt

  controlQueue.io.deq_clock := io.adc_clock
  controlQueue.io.deq_reset := reset
  controlQueue.io.deq.ready := true.B
  agc.io.control := RegEnable(controlQueue.io.deq.bits, controlQueue.io.deq.valid)  


  val idxQueue = Module(new AsyncQueue(UInt(params.adcBits.W), AsyncQueueParams(depth = params.modemQueueDepth)))
  idxQueue.io.enq_clock := io.adc_clock
  idxQueue.io.enq_reset := reset
  idxQueue.io.enq.valid := true.B
  idxQueue.io.enq.bits := agc.io.vgaLUTIndex

  idxQueue.io.deq_clock := clock
  idxQueue.io.deq_reset := reset
  idxQueue.io.deq.ready := true.B
  io.vgaLUTIndex := RegEnable(idxQueue.io.deq.bits, idxQueue.io.deq.valid)
}
