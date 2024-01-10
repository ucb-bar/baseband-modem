package modem

import chisel3._
import chisel3.util._
import baseband.BasebandModemParams

class ModemIFCounterStateIO extends Bundle {
  val input = Input(new ModemIFCounterInputIO)
  val output = Output(new ModemIFCounterOutputIO)
}

class ModemIFCounterControlIO extends Bundle {
  val restartCounter = Bool()
}

class ModemIFCounterInputIO extends Bundle {
  val ifTickThreshold = UInt(32.W)
  val control = new ModemIFCounterControlIO
}

class ModemIFCounterOutputIO extends Bundle {
  // Number of ADC clock ticks since the beginning
  // Equivalent to a 32 MHz RTC
  val adcTicks = UInt(32.W)

  // Number of IF ticks since SOP, up to ifTickThreshold
  val ifTicksPacket = UInt(32.W)
  // Number of ADC ticks for ifTickThreshold IF ticks since SOP
  val adcTicksPacket = UInt(32.W)
  // Cached value of ifTicksPacket, delayed by 1 packet
  val ifTicksPrevPacket = UInt(32.W)
  // Cached value of adcTicksPacket, delayed by 1 packet
  val adcTicksPrevPacket = UInt(32.W)

  val thresholdInterrupt = Bool()
}

class IFCounter(params: BasebandModemParams) extends Module {
  val io = IO(new Bundle {
    val data = Input(UInt(params.adcBits.W))
    val sop = Input(Bool())
    val state = new ModemIFCounterStateIO
  })

  val if_tick_threshold = RegInit(200.U(32.W)) // default to 200
  val adc_ticks = RegInit(0.U(32.W))
  val if_ticks_packet = RegInit(0.U(32.W))
  val adc_ticks_packet = RegInit(0.U(32.W))
  val if_ticks_prev_packet = RegInit(0.U(32.W))
  val adc_ticks_prev_packet = RegInit(0.U(32.W))

  val threshold_interrupt = RegInit(false.B)
  val threshold_interrupted = RegInit(false.B)

  val prev_data = RegNext(io.data)
  val cutoff = 1.U << (params.adcBits - 1)
  val is_if_tick = (prev_data < cutoff) & (io.data >= cutoff)

  // Assign output wires
  io.state.output.adcTicks := adc_ticks
  io.state.output.ifTicksPacket := if_ticks_packet
  io.state.output.adcTicksPacket := adc_ticks_packet
  io.state.output.ifTicksPrevPacket := if_ticks_prev_packet
  io.state.output.adcTicksPrevPacket := adc_ticks_prev_packet
  io.state.output.thresholdInterrupt := threshold_interrupt

  // Reset interrupt
  threshold_interrupt := false.B

  // Logic for 32 MHz counter
  // This one wraps around
  adc_ticks := adc_ticks + 1.U
  
  // Logic for IF counter
  when(io.sop === true.B || io.state.input.control.restartCounter === true.B) {
    // Save prev packet values
    if_ticks_prev_packet := if_ticks_packet
    adc_ticks_prev_packet := adc_ticks_packet
    // Reset if_ticks_packet and adc_ticks_packet
    if_ticks_packet := 0.U
    adc_ticks_packet := 0.U
    // Fetch the setting for if_tick_threshold
    if_tick_threshold := io.state.input.ifTickThreshold
    threshold_interrupted := false.B
  }.otherwise {
    when(if_ticks_packet =/= if_tick_threshold) {
      // If we haven't reached if_tick_threshold
      // Increment if_ticks if it is an if_tick (e.g. neg->pos)
      if_ticks_packet := Mux(is_if_tick, if_ticks_packet + 1.U, if_ticks_packet)
      // Increment adc_ticks_packet (since this is running on adc clock)
      adc_ticks_packet := adc_ticks_packet + 1.U
    }.otherwise {
      // Trigger interrupt if this packet has not interrupted yet
      when(threshold_interrupted === false.B) {
        threshold_interrupt := true.B
        threshold_interrupted := true.B
      }
    }
  }
}
