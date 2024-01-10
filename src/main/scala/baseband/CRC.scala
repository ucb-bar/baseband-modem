package baseband

import chisel3._
import chisel3.util._
import chisel3.experimental.ChiselEnum

object CRCPolynomials {
  /* Based on Figure 3.4 of Bluetooth Core Specification v5.2
     Note CRC is transmitted MSB first (rest of packet is LSB first)

     x^24 + x^10 + x^9 + x^6 + x^4 + x^3 + x^1 + 1
   */
  val BLE = "b000000000000011001011011".U(24.W) // x^24 implied

  /* Based on Figure 7-4 of IEEE Std 802.15.4-2020
     From figure, CRC is actually transmitted "MSB" first (same as BLE)

     x^16 + x^12 + x^5 + 1
   */
  val LRWPAN = "b0001000000100001".U(16.W) // x^16 implied
}

/* Output is valid in same cycle as input valid */
class CRC(polynomial: UInt) extends Module {
  val N = polynomial.getWidth

  val io = IO(new Bundle {
    val in   = Flipped(Valid(Bool()))
    val out  = Output(UInt(N.W))
    val seed = Flipped(Valid(UInt(N.W)))
  })

  val lfsr = RegInit(0.U(N.W))
  val inv  = lfsr(N-1) ^ io.in.bits

  val new_lfsr = Cat(Mux(inv, polynomial >> 1, 0.U((N-1).W)) ^ lfsr(N-2,0), inv)

  when(io.seed.fire()) {
    lfsr := io.seed.bits
  }.elsewhen(io.in.fire()) {
    lfsr := new_lfsr
  }

  /* Since BLE and LRWPAN transmit MSB of CRC first but rest of packet LSB first,
     we set out(0) to be the MSB of the CRC so out can be transmitted "LSB" first
   */
  io.out := Reverse(Mux(io.in.fire(), new_lfsr, lfsr)) // valid within same cycle
}

/* Byte-oriented interface for packet disassemblers (PDA)
   Output valid interface is used to denote when CRC is done, ready for next byte
   9 cycle output delay
 */
class CRCBytes(polynomial: UInt) extends Module {
  val N = polynomial.getWidth

  val io = IO(new Bundle {
    val in   = Flipped(Decoupled(UInt(8.W)))
    val out  = Valid(UInt(N.W))
    val seed = Flipped(Valid(UInt(N.W)))
  })

  object State extends ChiselEnum {
    val sWait, sData = Value
  }

  val state   = RegInit(State.sWait)
  val counter = Reg(UInt(3.W))
  val inreg   = Reg(UInt(8.W))

  val crc = Module(new CRC(polynomial))
  crc.io.seed    := io.seed
  crc.io.in.bits := inreg(0)

  io.out.bits := crc.io.out

  // defaults
  counter := counter + 1.U
  inreg   := inreg >> 1
  crc.io.in.valid := false.B
  io.in.ready  := false.B
  io.out.valid := false.B

  switch(state) {
    is(State.sWait) {
      io.in.ready := true.B
      io.out.valid := true.B

      when(io.in.fire()) {
        state   := State.sData
        counter := 1.U
        inreg := io.in.bits
      }
    }

    is(State.sData) {
      crc.io.in.valid := true.B

      when(counter === 0.U) {
        state := State.sWait
      }
    }
  }
}
