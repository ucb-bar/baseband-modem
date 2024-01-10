package baseband

import chisel3._
import chisel3.util._
import chisel3.experimental._
import freechips.rocketchip.config.Parameters

import ee290cdma._
import modem._

class LRWPANBaseband(params: BasebandModemParams, beatBytes: Int) extends Module {
  val io = IO(new LRWPANBasebandIO(params.paddrBits, beatBytes))

  /* TX */
  val dmaPacketDisassembler = Module(new DMAPacketDisassembler(beatBytes))
  val assembler = Module(new LRWPANPacketAssembler)

  dmaPacketDisassembler.io.consumer.done := assembler.io.out.control.done
  dmaPacketDisassembler.io.dmaIn <> io.dma.readData

  assembler.io.constants := io.constants
  assembler.io.in.data <> dmaPacketDisassembler.io.consumer.data
  assembler.io.in.control <> io.control.lrwpanAssembler.in

  io.control.lrwpanAssembler.out <> assembler.io.out.control

  val spreader = Module(new SymbolToChipSpreader)
  spreader.io.in_data <> assembler.io.out.data
  io.modem.digital.tx <> spreader.io.out_data

  /* RX */
  val dmaPacketAssembler = Module(new DMAPacketAssembler(beatBytes))
  val disassembler = Module(new LRWPANPacketDisassembler(params))
  dmaPacketAssembler.io.producer.done := disassembler.io.out.control.done
  dmaPacketAssembler.io.producer.data <> disassembler.io.out.data

  disassembler.io.constants := io.constants
  disassembler.io.in.control <> io.control.lrwpanDisassembler.in
  disassembler.io.in.data <> io.modem.digital.rx
  io.control.lrwpanDisassembler.out <> disassembler.io.out.control

  val dmaAddresser = Module(new BasebandDMAAddresser(params.paddrBits, beatBytes))
  dmaAddresser.io.in <> dmaPacketAssembler.io.dmaOut
  dmaAddresser.io.baseAddr <> io.control.baseAddr
  io.dma.writeReq <> dmaAddresser.io.out

  /* Debug */
  io.state.assemblerState := assembler.io.state
  io.state.disassemblerState := disassembler.io.state
}