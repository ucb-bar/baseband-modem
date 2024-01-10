package modem

import chisel3._
import chisel3.experimental.dataview._
import chisel3.util._
import chiseltest._

import org.scalatest.flatspec.AnyFlatSpec
import collection.immutable.Seq

import baseband.{BasebandModemParams, RadioMode}
import TestUtility._

class FSKRXSingleClock(params: BasebandModemParams) extends Module {
  val fskrx = Module(new FSKRX(params))
  fskrx.io.adc_clock := clock

  val io = IO(new FSKRXIO(params))
  io <> fskrx.io.viewAsSupertype(new FSKRXIO(params))
}

class FSKRXTest extends AnyFlatSpec with ChiselScalatestTester {
  it should "Test BLE packet demodulation and capture" in {
    test(new FSKRXSingleClock(BasebandModemParams(loadBLECoefficients = true))) { c =>
      c.clock.setTimeout(0)

      c.io.ble.eop.poke(false.B)
      c.io.control.enable.poke(true.B)
      c.io.firCmd.valid.poke(false.B)
      c.io.radioMode.poke(RadioMode.BLE)
      c.io.accessAddress.poke((0x8E89BED6L).U)
      c.io.bleLoopback.poke(false.B)

      val space = Seq.fill(69)(true)
      val packet = rawBLEPacket(Seq(0x69))
      val bits = space ++ packet ++ space ++ packet ++ space
      val (i, q) = if_mod(bits.map {b => if (b) 0.5 else -0.5}) // no Gaussian but that's ok

      var num_pkts = 0
      (i zip q).map { case (x,y) =>
        val i_quant = ADC(Seq(x * 0.9))(0)
        val q_quant = ADC(Seq(y * 0.9))(0)
        c.io.ble.eop.poke(false.B)
        c.io.analog.i.poke(i_quant.U)
        c.io.analog.q.poke(q_quant.U)
        if (c.io.ble.sop.peek().litToBoolean)
          num_pkts += 1
        if (c.io.ble.data.valid.peek().litToBoolean) {
          c.io.ble.eop.poke(true.B) // only 1 byte packet
          assert(c.io.ble.data.bits.peek().litValue == 0x69)
        }
        c.clock.step()
      }
      assert(num_pkts == 2)
    }
  }

  it should "Test LRWPAN packet demodulation and capture" in {
    test(new FSKRXSingleClock(BasebandModemParams(loadBLECoefficients = false))) { c =>
      c.clock.setTimeout(0)

      c.io.lrwpan.eop.poke(false.B)
      c.io.control.enable.poke(true.B)
      c.io.firCmd.valid.poke(false.B)
      c.io.radioMode.poke(RadioMode.LRWPAN)
      c.io.shr.poke((0xA700).U)
      c.io.lrwpanLoopback.poke(false.B)

      val space = Seq.fill(69)(true)
      val chips = chipToMSK(symbolsToChip(rawLRWPANPacket(Seq(0x69))))
      val bits = space ++ chips ++ space ++ chips ++ space
      val (i, q) = if_mod(bits.map {b => if (b) 1.0 else -1.0}, fs=F_LRWPAN) // no Gaussian but that's ok

      var num_pkts = 0
      (i zip q).map { case (x,y) =>
        val i_quant = ADC(Seq(x * 0.9))(0)
        val q_quant = ADC(Seq(y * 0.9))(0)
        c.io.lrwpan.eop.poke(false.B)
        c.io.analog.i.poke(i_quant.U)
        c.io.analog.q.poke(q_quant.U)
        if (c.io.lrwpan.sop.peek().litToBoolean)
          num_pkts += 1
        if (c.io.lrwpan.data.valid.peek().litToBoolean) {
          c.io.lrwpan.eop.poke(true.B) // only 1 byte packet
          assert(c.io.lrwpan.data.bits.peek().litValue == 0x69)
        }
        c.clock.step()
      }
      assert(num_pkts == 2)
    }
  }
}
