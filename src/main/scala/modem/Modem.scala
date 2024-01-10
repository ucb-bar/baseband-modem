package modem

import chisel3._
import chisel3.util._
import baseband.{BasebandConstants, BasebandModemParams, RadioMode, PDADataInputIO}
import freechips.rocketchip.util.AsyncQueue
import freechips.rocketchip.util.AsyncQueueParams

/* Data IO */
class ModemDigitalIO extends Bundle {
  val tx = Flipped(Decoupled(UInt(1.W)))
  val rx = Flipped(new PDADataInputIO)
}

class ModemAnalogIO(val params: BasebandModemParams) extends Bundle {
  val tx = new AnalogTXIO
  val rx = new AnalogRXIO(params)
  val tuning = new ModemTuningDataIO
}

class AnalogTXIO extends Bundle {
  val vco = new Bundle {
    val cap_coarse = Output(UInt(10.W))
    val cap_medium = Output(UInt(6.W))
    val cap_mod    = Output(UInt(8.W))
    val freq_reset = Output(Bool())
  }
}

class AnalogRXIO(val params: BasebandModemParams) extends Bundle {
  val i = new Bundle {
    val data = Input(UInt(params.adcBits.W))
    val valid = Input(Bool())
  }
  val q = new Bundle {
    val data = Input(UInt(params.adcBits.W))
    val valid = Input(Bool())
  }
}

class ModemTuningDataIO extends Bundle {
  val i = new Bundle {
    val vgaAtten = Output(UInt(10.W))
    val vga_s2   = Output(UInt(6.W))
  }
  val q = new Bundle {
    val vgaAtten = Output(UInt(10.W))
    val vga_s2   = Output(UInt(6.W))
  }
}

/* Control IO */
class ModemTuningControl(val params: BasebandModemParams) extends Bundle {
  val i = new Bundle {
    val AGC = new Bundle {
      val control = new AGCControlIO
      val useAGC  = Bool()
    }
    val DCO = new Bundle {
      val control = new DCOControlIO
      val useDCO  = Bool()
    }
  }
  val q = new Bundle {
    val AGC = new Bundle {
      val control = new AGCControlIO
      val useAGC  = Bool()
    }
    val DCO = new Bundle {
      val control = new DCOControlIO
      val useDCO  = Bool()
    }
  }
  val debug = new Bundle {
    val enabled = Bool()
  }
}

class ModemControlIO(val params: BasebandModemParams) extends Bundle {
  val tuning         = Input(new ModemTuningControl(params))
  val constants      = Input(new BasebandConstants)
  val lutCmd         = Flipped(Valid(new ModemLUTCommand))
  val firCmd         = Flipped(Valid(new FIRCoefficientChangeCommand))
  val gfskTX         = new FSKTXControlIO(params)
  val mskTX          = new FSKTXControlIO(params)
  val rx             = new FSKRXControlIO
  val bleLoopback    = Input(Bool())
  val lrwpanLoopback = Input(Bool())
}

/* Debug IO (expose over MMIO) */
class ModemStateIO extends Bundle {
  val txState = Output(UInt(log2Ceil(2+1).W))
  val modIndex = Output(UInt(6.W))
  val i = new Bundle {
    val agcIndex = Output(UInt(5.W))
    val dcoIndex = Output(UInt(5.W))
  }
  val q = new Bundle {
    val agcIndex = Output(UInt(5.W))
    val dcoIndex = Output(UInt(5.W))
  }
}

/* External IO (unused by Modem) */
class ModemTuningRXEnable extends Bundle {
  val mix = Bool() // Mixer
  val buf = Bool() // Buffer
  val tia = Bool() // TIA
  val vga = Bool() // VGA
  val bpf = Bool() // Bandpass filter
}

class ModemTuningIO extends Bundle {
  val trim = new Bundle {
    val g0 = Output(UInt(8.W))
    // Trim G1 is an input
    val g1 = Input(UInt(8.W))
    val g2 = Output(UInt(8.W))
    val g3 = Output(UInt(8.W))
    val g4 = Output(UInt(8.W))
    val g5 = Output(UInt(8.W))
    val g6 = Output(UInt(8.W))
    val g7 = Output(UInt(8.W))
  }
  val i = new Bundle {
    val vga_gain_ctrl = Output(UInt(10.W))
    val bpf = new Bundle {
      val chp_0 = Output(UInt(4.W))
      val chp_1 = Output(UInt(4.W))
      val chp_2 = Output(UInt(4.W))
      val chp_3 = Output(UInt(4.W))
      val chp_4 = Output(UInt(4.W))
      val chp_5 = Output(UInt(4.W))
      val clp_0 = Output(UInt(4.W))
      val clp_1 = Output(UInt(4.W))
      val clp_2 = Output(UInt(4.W))
    }
  }
  val q = new Bundle {
    val vga_gain_ctrl = Output(UInt(10.W))
    val bpf = new Bundle {
      val chp_0 = Output(UInt(4.W))
      val chp_1 = Output(UInt(4.W))
      val chp_2 = Output(UInt(4.W))
      val chp_3 = Output(UInt(4.W))
      val chp_4 = Output(UInt(4.W))
      val chp_5 = Output(UInt(4.W))
      val clp_0 = Output(UInt(4.W))
      val clp_1 = Output(UInt(4.W))
      val clp_2 = Output(UInt(4.W))
    }
  }
  val current_dac = new Bundle {
    val i = new Bundle {
      val vga_s2 = Output(UInt(6.W))
    }
    val q = new Bundle {
      val vga_s2 = Output(UInt(6.W))
    }
  }
  val enable = new Bundle {
    val i = Output(new ModemTuningRXEnable)
    val q = Output(new ModemTuningRXEnable)
    val vco_lo = Output(Bool())   // VCO local oscillator
  }
  val mux = new Bundle {
    val dbg = new Bundle {
      val in = Output(UInt(10.W))
      val out = Output(UInt(10.W))
    }
  }
}

class ModemIO(params: BasebandModemParams) extends Bundle {
  val analog = new ModemAnalogIO(params)
  val digital = new Bundle {
    val ble = new ModemDigitalIO
    val lrwpan = new ModemDigitalIO
  }
  val control = new ModemControlIO(params)
  val state = new ModemStateIO
  val ifCounter = new ModemIFCounterStateIO
}

/* Modem top level */
class Modem(params: BasebandModemParams) extends Module {
  val io = IO(new ModemIO(params) {
    val adc_clock = Input(Clock())
  })

  /* buffer the ADC (TODO needed?) */
  val i = withClock(io.adc_clock) {RegNext(io.analog.rx.i.data)}
  val q = withClock(io.adc_clock) {RegNext(io.analog.rx.q.data)}

  /* look at all those LUTs */
  val VCO_MOD_LUT       = Module(new ModemLUT(ModemLUTConfigs.VCO_MOD))
  val VCO_CT_BLE_LUT    = Module(new ModemLUT(ModemLUTConfigs.VCO_CT_BLE))
  val VCO_CT_LRWPAN_LUT = Module(new ModemLUT(ModemLUTConfigs.VCO_CT_LRWPAN))
  val AGC_I_LUT         = Module(new ModemLUT(ModemLUTConfigs.AGC_I))
  val AGC_Q_LUT         = Module(new ModemLUT(ModemLUTConfigs.AGC_Q))
  val DCO_I_LUT         = Module(new ModemLUT(ModemLUTConfigs.DCO_I))
  val DCO_Q_LUT         = Module(new ModemLUT(ModemLUTConfigs.DCO_Q))

  for (lut <- Seq(VCO_MOD_LUT, VCO_CT_BLE_LUT, VCO_CT_LRWPAN_LUT, 
                  AGC_I_LUT, AGC_Q_LUT, DCO_I_LUT, DCO_Q_LUT)) {
    lut.io.lutCmd := io.control.lutCmd
  }

  /* TX */
  val gfskTX = Module(new GFSKTXWrapper(params))
  gfskTX.io.control <> io.control.gfskTX
  gfskTX.io.digital.in <> io.digital.ble.tx
  gfskTX.io.firCmd := io.control.firCmd
  gfskTX.io.adc_clock := io.adc_clock

  val mskTX = Module(new MSKTXWrapper(params))
  mskTX.io.control <> io.control.mskTX
  mskTX.io.digital.in <> io.digital.lrwpan.tx
  mskTX.io.adc_clock := io.adc_clock

  io.analog.tx.vco.freq_reset := reset

  // channel selection
  val bleCT    = VCO_CT_BLE_LUT.io.out(io.control.constants.bleChannelIndex)
  val lrwpanCT = VCO_CT_LRWPAN_LUT.io.out(io.control.constants.lrwpanChannelIndex)
  io.analog.tx.vco.cap_medium := Mux(io.control.constants.radioMode === RadioMode.BLE,
                                      bleCT(15,10), lrwpanCT(15,10))
  io.analog.tx.vco.cap_coarse := Mux(io.control.constants.radioMode === RadioMode.BLE,
                                      bleCT(9, 0),  lrwpanCT(9,0))

  // modulation
  io.analog.tx.vco.cap_mod := Mux(io.control.constants.radioMode === RadioMode.BLE, 
                                    VCO_MOD_LUT.io.out(gfskTX.io.analog.modIndex),
                                    VCO_MOD_LUT.io.out(mskTX.io.analog.modIndex))

  io.state.txState  := Mux(io.control.constants.radioMode === RadioMode.BLE,
                            gfskTX.io.state, mskTX.io.state)
  io.state.modIndex := Mux(io.control.constants.radioMode === RadioMode.BLE,
                            gfskTX.io.analog.modIndex, mskTX.io.analog.modIndex)

  /* RX */
  val rx = Module(new FSKRX(params))
  rx.io.control <> io.control.rx
  rx.io.firCmd := io.control.firCmd
  rx.io.radioMode := io.control.constants.radioMode
  rx.io.accessAddress := io.control.constants.accessAddress
  rx.io.shr := io.control.constants.shr
  rx.io.adc_clock := io.adc_clock

  rx.io.analog.i := i
  rx.io.analog.q := q

  io.digital.ble.rx <> rx.io.ble
  io.digital.lrwpan.rx <> rx.io.lrwpan

  /* Loopback */
  rx.io.bleLoopback := io.control.bleLoopback
  rx.io.lrwpanLoopback := io.control.lrwpanLoopback
  rx.io.bleLoopBit := gfskTX.io.analog.modIndex > 31.U
  rx.io.lrwpanLoopBit := mskTX.io.analog.modIndex > 31.U

  /* IF Counter */
  io.ifCounter <> rx.io.ifCounter

  /* AGC */
  val iAGC = Module(new AGCWrapper(params))
  iAGC.io.control := io.control.tuning.i.AGC.control
  iAGC.io.adc_clock := io.adc_clock
  iAGC.io.adcIn := i

  io.analog.tuning.i.vgaAtten := AGC_I_LUT.io.out(iAGC.io.vgaLUTIndex)
  io.state.i.agcIndex := iAGC.io.vgaLUTIndex

  val qAGC = Module(new AGCWrapper(params))
  qAGC.io.control := io.control.tuning.q.AGC.control
  qAGC.io.adc_clock := io.adc_clock
  qAGC.io.adcIn := q

  io.analog.tuning.q.vgaAtten := AGC_Q_LUT.io.out(qAGC.io.vgaLUTIndex)
  io.state.q.agcIndex := qAGC.io.vgaLUTIndex

  /* DCO */
  val iDCO = Module(new DCOWrapper(params))
  iDCO.io.control := io.control.tuning.i.DCO.control
  iDCO.io.adc_clock := io.adc_clock
  iDCO.io.adcIn := i

  io.analog.tuning.i.vga_s2 := DCO_I_LUT.io.out(iDCO.io.dcoLUTIndex)
  io.state.i.dcoIndex := iDCO.io.dcoLUTIndex

  val qDCO = Module(new DCOWrapper(params))
  qDCO.io.control := io.control.tuning.q.DCO.control
  qDCO.io.adc_clock := io.adc_clock
  qDCO.io.adcIn := q

  io.analog.tuning.q.vga_s2 := DCO_Q_LUT.io.out(qDCO.io.dcoLUTIndex)
  io.state.q.dcoIndex := qDCO.io.dcoLUTIndex
}
