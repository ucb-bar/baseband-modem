package baseband

import chisel3._
import chisel3.util._

class SymbolToChipIO extends Bundle {
    val symbol = Input(UInt(4.W))
    val chip = Output(UInt(32.W))
}

class SymbolToChipSpreader extends Module {
    val io = IO(new Bundle{
        val in_data = Flipped(Decoupled(UInt(1.W)))
        val out_data = Decoupled(UInt(1.W))
        val state = Output(UInt(2.W))
    })
    val s_idle :: s_building :: s_holding :: Nil = Enum(3)
    val state = RegInit(s_idle)
    io.state := state



    val chipLUT = Module(new SymbolToChipLUT)
    val symbol = RegInit(0.U(4.W))
    val symbolCounter = RegInit(0.U(3.W))

    val chipCounter = RegInit(0.U(6.W))
    io.out_data.bits := chipLUT.io.chip(chipCounter)

    val data_in_ready = RegInit(true.B)
    io.in_data.ready := data_in_ready

    val data_out_valid = RegInit(false.B)
    io.out_data.valid := data_out_valid

    chipLUT.io.symbol := symbol.asUInt

    when(state === s_idle) {
        when(io.in_data.fire()) {
            state := s_building
            data_in_ready := true.B
            data_out_valid := false.B
            symbol := Cat(0.U(3.W), io.in_data.bits)
            symbolCounter := 1.U
            chipCounter := 0.U
        }
    }.elsewhen(state === s_building){
        when(io.in_data.fire()) {
            when(symbolCounter === 3.U) {
                symbol := Cat(io.in_data.bits, symbol(2,0))
                state := s_holding
                data_in_ready := false.B
                data_out_valid := true.B
                symbolCounter := 0.U
                chipCounter := 0.U
            }.elsewhen(symbolCounter === 2.U){
                symbol := Cat(0.U(1.W), io.in_data.bits, symbol(1,0))
            }.elsewhen(symbolCounter === 1.U){
                symbol := Cat(0.U(2.W), io.in_data.bits, symbol(0))
            }
            symbolCounter := symbolCounter + 1.U
        }
    }.otherwise {
        when(io.out_data.fire()) {
            when(chipCounter === 31.U) {
                state := s_idle
                data_in_ready := true.B
                data_out_valid := false.B
                symbolCounter := 0.U
                chipCounter := 0.U
            }.otherwise {
                chipCounter := chipCounter + 1.U
            }

        }
    }
}

class SymbolToChipLUT extends Module {
    // LUT defined according Table 73 of the IEEE 802.15.4-2011 standard
    // Symbol-to-chip mapping for the 2450 MHz band
    val io = IO(new SymbolToChipIO)
    when(io.symbol === 0.U) {
        io.chip := "b01110100010010101100001110011011".U
    }
    .elsewhen(io.symbol === 1.U) {
        io.chip := "b01000100101011000011100110110111".U
    }
    .elsewhen(io.symbol === 2.U) {
        io.chip := "b01001010110000111001101101110100".U
    }
    .elsewhen(io.symbol === 3.U) {
        io.chip := "b10101100001110011011011101000100".U
    }
    .elsewhen(io.symbol === 4.U) {
        io.chip := "b11000011100110110111010001001010".U
    }
    .elsewhen(io.symbol === 5.U) {
        io.chip := "b00111001101101110100010010101100".U
    }
    .elsewhen(io.symbol === 6.U) {
        io.chip := "b10011011011101000100101011000011".U
    }
    .elsewhen(io.symbol === 7.U) {
        io.chip := "b10110111010001001010110000111001".U
    }
    .elsewhen(io.symbol === 8.U) {
        io.chip := "b11011110111000000110100100110001".U
    }
    .elsewhen(io.symbol === 9.U) {
        io.chip := "b11101110000001101001001100011101".U
    }
    .elsewhen(io.symbol === 10.U) {
        io.chip := "b11100000011010010011000111011110".U
    }
    .elsewhen(io.symbol === 11.U) {
        io.chip := "b00000110100100110001110111101110".U
    }
    .elsewhen(io.symbol === 12.U) {
        io.chip := "b01101001001100011101111011100000".U
    }
    .elsewhen(io.symbol === 13.U) {
        io.chip := "b10010011000111011110111000000110".U
    }
    .elsewhen(io.symbol === 14.U) {
        io.chip := "b00110001110111101110000001101001".U
    }
    .elsewhen(io.symbol === 15.U) {
        io.chip := "b00011101111011100000011010010011".U
    }.otherwise{
        io.chip := DontCare
    }
}