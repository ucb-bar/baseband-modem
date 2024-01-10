package baseband

import chisel3._
import chisel3.util._
import chisel3.experimental._
import freechips.rocketchip.config.Parameters

import ee290cdma._
import modem._

class BLEBaseband(params: BasebandModemParams, beatBytes: Int) extends Module {
  val io = IO(new BLEBasebandIO(params.paddrBits, beatBytes))

  /* TX */
  val dmaPacketDisassembler = Module(new DMAPacketDisassembler(beatBytes))
  val bleAssembler = Module(new BLEPacketAssembler)

  dmaPacketDisassembler.io.consumer.done := bleAssembler.io.out.control.done
  dmaPacketDisassembler.io.dmaIn <> io.dma.readData

  bleAssembler.io.constants := io.constants
  bleAssembler.io.in.data <> dmaPacketDisassembler.io.consumer.data
  bleAssembler.io.in.control <> io.control.bleAssembler.in

  io.control.bleAssembler.out <> bleAssembler.io.out.control

  io.modem.digital.tx <> bleAssembler.io.out.data

  /* RX */
  val dmaPacketAssembler = Module(new DMAPacketAssembler(beatBytes))
  val bleDisassembler = Module(new BLEPacketDisassembler(params))
  dmaPacketAssembler.io.producer.done := bleDisassembler.io.out.control.done
  dmaPacketAssembler.io.producer.data <> bleDisassembler.io.out.data

  bleDisassembler.io.constants := io.constants
  bleDisassembler.io.in.control <> io.control.bleDisassembler.in
  bleDisassembler.io.in.data <> io.modem.digital.rx
  io.control.bleDisassembler.out <> bleDisassembler.io.out.control

  val dmaAddresser = Module(new BasebandDMAAddresser(params.paddrBits, beatBytes))
  dmaAddresser.io.in <> dmaPacketAssembler.io.dmaOut
  dmaAddresser.io.baseAddr <> io.control.baseAddr
  io.dma.writeReq <> dmaAddresser.io.out

  /* Debug */
  io.state.assemblerState := bleAssembler.io.state
  io.state.disassemblerState := bleDisassembler.io.state
}
