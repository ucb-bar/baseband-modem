package baseband

import chisel3._
import chisel3.util._
import chisel3.experimental._
import freechips.rocketchip.config.Parameters

import ee290cdma._
import modem._

object RadioMode {
  val BLE = 0.U
  val LRWPAN = 1.U
}

class BasebandConstants extends Bundle {
  val radioMode = UInt(1.W)
  val bleChannelIndex = UInt(6.W)
  val lrwpanChannelIndex = UInt(6.W)
  val crcSeed = UInt(24.W)
  val accessAddress = UInt(32.W)
  val shr = UInt(16.W)
}

class BasebandDMAIO(addrBits: Int, beatBytes: Int) extends Bundle {
  val readData = Flipped(Decoupled(UInt((beatBytes * 8).W)))
  val writeReq = Decoupled(new EE290CDMAWriterReq(addrBits, beatBytes))
}

class LRWPANAssemblerControlIO extends Bundle {
  val in = Flipped(Decoupled(new LRWPANPAControlInputBundle))
  val out = Output(new PAControlOutputBundle)
}

class BLEAssemblerControlIO extends Bundle {
  val in = Flipped(Decoupled(new BLEPAControlInputBundle))
  val out = Output(new PAControlOutputBundle)
}

class LRWPANDisassemblerControlIO extends Bundle {
  val in = Flipped(Valid(new PDAControlInputBundle))
  val out = Output(new PDAControlOutputBundle)
}

class BLEDisassemblerControlIO extends Bundle {
  val in = Flipped(Valid(new PDAControlInputBundle))
  val out = Output(new PDAControlOutputBundle)
}

class BLEBasebandControlIO(val addrBits: Int) extends Bundle {
  val bleAssembler = new BLEAssemblerControlIO
  val bleDisassembler = new BLEDisassemblerControlIO
  val baseAddr = Flipped(Valid(UInt(addrBits.W)))
  val loopback = Input(Vec(2, Bool()))
}

class LRWPANBasebandControlIO(val addrBits: Int) extends Bundle {
  val lrwpanAssembler = new LRWPANAssemblerControlIO
  val lrwpanDisassembler = new LRWPANDisassemblerControlIO
  val baseAddr = Flipped(Valid(UInt(addrBits.W)))
  val loopback = Input(Vec(2, Bool()))
}

class BasebandDMAAddresser(addrBits: Int, beatBytes: Int) extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new DMAPacketAssemblerDMAOUTIO(beatBytes)))
    val out = Decoupled(new EE290CDMAWriterReq(addrBits, beatBytes))
    val baseAddr = Flipped(Valid(UInt(addrBits.W)))
  })

  val out_valid = RegInit(false.B)
  val out_ready = RegInit(false.B)
  val out_totalBytes = Reg(UInt(log2Ceil(beatBytes + 1).W))
  val out_data = Reg(UInt((8 * beatBytes).W))
  val out_addr = Reg(UInt(addrBits.W))

  val offset = RegInit(0.U(addrBits.W))

  val baseAddr = Reg(UInt(addrBits.W))

  io.out.valid := out_valid
  io.out.bits.totalBytes := out_totalBytes
  io.out.bits.data := out_data
  io.out.bits.addr := out_addr
  out_ready := io.out.ready

  io.in.ready := out_ready

  when (io.baseAddr.fire()) {
    baseAddr := io.baseAddr.bits
    offset := 0.U
  } .elsewhen(io.in.fire()) {
    offset := offset + io.in.bits.size

    out_totalBytes := io.in.bits.size
    out_data := io.in.bits.data
    out_addr := baseAddr + offset
  }

  out_valid := io.in.valid

}

class BLEBasebandIO(val addrBits: Int, val beatBytes: Int) extends Bundle {
  val constants = Input(new BasebandConstants)
  val control = new BLEBasebandControlIO(addrBits)
  val dma = new BasebandDMAIO(addrBits, beatBytes)
  val modem = new Bundle {
    val digital = Flipped(new ModemDigitalIO)
  }
  val state = new Bundle {
    val assemblerState = Output(UInt(log2Ceil(6+1).W))
    val disassemblerState = Output(UInt(log2Ceil(6+1).W))
  }
}

class LRWPANBasebandIO(val addrBits: Int, val beatBytes: Int) extends Bundle {
  val constants = Input(new BasebandConstants)
  val control = new LRWPANBasebandControlIO(addrBits)
  val dma = new BasebandDMAIO(addrBits, beatBytes)
  val modem = new Bundle {
    val digital = Flipped(new ModemDigitalIO)
  }
  val state = new Bundle {
    val assemblerState = Output(UInt(log2Ceil(6+1).W))
    val disassemblerState = Output(UInt(log2Ceil(6+1).W))
  }
}