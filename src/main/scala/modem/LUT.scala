package modem

import chisel3._
import chisel3.util._

// TODO add initial values and LRWPAN LUTs
case class ModemLUTConfig(
  name: UInt,
  len: Int,
  typ: UInt
)

object ModemLUTConfigs {
  val VCO_MOD       = ModemLUTConfig(0.U, 64, UInt(8.W))
  val VCO_CT_BLE    = ModemLUTConfig(1.U, 64, UInt(16.W)) // only 40 entries used
  val VCO_CT_LRWPAN = ModemLUTConfig(2.U, 64, UInt(16.W)) // only 16 entries used

  // only 32 entries used
  val AGC_I = ModemLUTConfig(3.U, 64, UInt(10.W))
  val AGC_Q = ModemLUTConfig(4.U, 64, UInt(10.W))

  // only 32 entries used
  val DCO_I = ModemLUTConfig(5.U, 64, UInt(6.W))
  val DCO_Q = ModemLUTConfig(6.U, 64, UInt(6.W))
}

class ModemLUTCommand extends Bundle {
  /* LUT Command: used to write to a LUT within the modem.
    [31:10 - Value to be written to LUT | 9:4 - address in LUT | 3:0 - LUT index]
   */
  val lut = UInt(4.W)
  val address = UInt(6.W)
  val value = UInt(22.W)
}

class ModemLUT(cfg: ModemLUTConfig) extends Module {
  val io = IO(new Bundle {
    val out = Output(Vec(cfg.len, cfg.typ))
    val lutCmd = Flipped(Valid(new ModemLUTCommand))
  })

  val reg = RegInit(VecInit.tabulate(cfg.len){i=>0.U(cfg.typ.getWidth.W)})

  when(io.lutCmd.fire() && io.lutCmd.bits.lut === cfg.name) {
    reg(io.lutCmd.bits.address) := io.lutCmd.bits.value(cfg.typ.getWidth-1,0)
  }

  io.out := reg
}
