package modem

import chisel3._
import chisel3.util._
import chisel3.experimental.FixedPoint
import baseband.BasebandModemParams

class FSKDemodulation(params: BasebandModemParams) extends Module {
  val io = IO(new Bundle {
    val i = Input(UInt(params.adcBits.W))
    val q = Input(UInt(params.adcBits.W))
    val out = Output(Bool())
    val firCmd = Flipped(Valid(new FIRCoefficientChangeCommand))
  })

  val cfg = if (params.loadBLECoefficients) FIRConfigs.BLE else FIRConfigs.LRWPAN
  
  // buffer the ADC input (assumes new sample every cycle)
  val delay_i = RegNext(io.i)
  val delay_q = RegNext(io.q)

  // reinterpret as FixedPoint between -1 and ~1 (assumes middle is 0V)
  val sint_i = Cat(~delay_i(params.adcBits-1), delay_i(params.adcBits-2,0)).asSInt
  val sint_q = Cat(~delay_q(params.adcBits-1), delay_q(params.adcBits-2,0)).asSInt

  val fp_i = sint_i.asFixedPoint((params.adcBits-1).BP)
  val fp_q = sint_q.asFixedPoint((params.adcBits-1).BP)

  // Hilbert filter for that image rejection
  val hilbert_i = Module(new FIR(FIRConfigs.RX_Hilbert_I))
  val hilbert_q = Module(new FIR(FIRConfigs.RX_Hilbert_Q))

  hilbert_i.io.in.bits := fp_i
  hilbert_q.io.in.bits := fp_q

  val hilbert_out = RegNext(hilbert_i.io.out.bits + hilbert_q.io.out.bits)

  // Matched filters for that frequency detection
  val match_sin_f0 = Module(new FIR(cfg.RX_match_sin_f0))
  val match_cos_f0 = Module(new FIR(cfg.RX_match_cos_f0))
  val match_sin_f1 = Module(new FIR(cfg.RX_match_sin_f1))
  val match_cos_f1 = Module(new FIR(cfg.RX_match_cos_f1))

  match_sin_f0.io.in.bits := hilbert_out
  match_cos_f0.io.in.bits := hilbert_out
  match_sin_f1.io.in.bits := hilbert_out
  match_cos_f1.io.in.bits := hilbert_out

  val match_f0_out = RegNext(match_sin_f0.io.out.bits * match_sin_f0.io.out.bits
                              + match_cos_f0.io.out.bits * match_cos_f0.io.out.bits)
  val match_f1_out = RegNext(match_sin_f1.io.out.bits * match_sin_f1.io.out.bits
                              + match_cos_f1.io.out.bits * match_cos_f1.io.out.bits)

  // LPF to the bitrate to remove that high frequency noise
  val lpf_f0 = Module(new FIR(cfg.RX_lpf))
  val lpf_f1 = Module(new FIR(cfg.RX_lpf))

  lpf_f0.io.in.bits := match_f0_out
  lpf_f1.io.in.bits := match_f1_out

  // Make the guess!
  val guess = RegNext(lpf_f1.io.out.bits >= lpf_f0.io.out.bits)
  io.out := guess

  // bypass Decoupled and connect firCmd for all filters
  for (filter <- Seq(hilbert_i, hilbert_q, match_sin_f0, match_cos_f0,
                      match_sin_f1, match_cos_f1, lpf_f0, lpf_f1)) {
    filter.io.in.valid  := true.B
    filter.io.out.ready := true.B
    filter.io.firCmd := io.firCmd
  }
}
