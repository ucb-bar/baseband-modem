package modem

import chisel3._
import chisel3.experimental.dataview._
import chisel3.util._
import chiseltest._

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.immutable.Seq
import scala.util.Random

import breeze.plot.{Figure, plot}
import breeze.linalg.linspace
import breeze.stats.distributions.Gaussian

import baseband.{BasebandModemParams, RadioMode}
import TestUtility._

class ModemSingleClock(params: BasebandModemParams) extends Module {
  val modem = Module(new Modem(params))
  modem.io.adc_clock := clock

  val io = IO(new ModemIO(params))
  io <> modem.io.viewAsSupertype(new ModemIO(params))
}

/* Combines AGC, DCO, and FSKRX tests to test entire RX chain
   Most values are taken from those tests

   Note there is an optional visual component to verify the DC offset and gain are good

   TODO Modem level TX chain tests? adding noise?
 */
class ModemTest extends AnyFlatSpec with ChiselScalatestTester {
  def vgaGain(index: Int): Double = {
    // 0dB to 40dB gain, 5 bit index, logarithmically spaced
    val lut = linspace(0,2,32).map {i => math.pow(10, i)}
    lut(index)
  }

  def dcoGain(index: Int): Double = {
    // index is actually a 5-bit signed int interpreted as unsigned
    if (index >= 16)
      (index - 32) / 16.0 * 0.25
    else
      index / 16.0 * 0.25
  }

  it should "Test BLE packet w/ gain and offset demodulation and capture" in {
    test(new ModemSingleClock(BasebandModemParams(loadBLECoefficients = true))) { c =>
      c.clock.setTimeout(0)

      // FSKRX setup
      c.io.digital.ble.rx.eop.poke(false.B)
      c.io.control.constants.radioMode.poke(RadioMode.BLE)
      c.io.control.constants.accessAddress.poke((0x8E89BED6L).U)
      c.io.control.lutCmd.valid.poke(false.B)
      c.io.control.firCmd.valid.poke(false.B)
      c.io.control.rx.enable.poke(true.B)
      c.io.control.bleLoopback.poke(false.B)

      // AGC setup
      c.io.control.tuning.i.AGC.control.sampleWindow.poke(19.U)
      c.io.control.tuning.i.AGC.control.idealPeakToPeak.poke(230.U)
      c.io.control.tuning.i.AGC.control.toleranceP2P.poke(15.U)
      c.io.control.tuning.i.AGC.control.gainInc.poke(1.U)
      c.io.control.tuning.i.AGC.control.gainDec.poke(4.U)
      c.io.control.tuning.i.AGC.control.reset.poke(false.B)
      c.io.control.tuning.i.AGC.useAGC.poke(true.B)

      c.io.control.tuning.q.AGC.control.sampleWindow.poke(19.U)
      c.io.control.tuning.q.AGC.control.idealPeakToPeak.poke(230.U)
      c.io.control.tuning.q.AGC.control.toleranceP2P.poke(15.U)
      c.io.control.tuning.q.AGC.control.gainInc.poke(1.U)
      c.io.control.tuning.q.AGC.control.gainDec.poke(4.U)
      c.io.control.tuning.q.AGC.control.reset.poke(false.B)
      c.io.control.tuning.q.AGC.useAGC.poke(true.B)

      // DCO setup
      c.io.control.tuning.i.DCO.control.gain.poke((2).F(9.W, 2.BP))
      c.io.control.tuning.i.DCO.useDCO.poke(true.B)

      c.io.control.tuning.q.DCO.control.gain.poke((2).F(9.W, 2.BP))
      c.io.control.tuning.q.DCO.useDCO.poke(true.B)

      val offset1 = -0.2
      val offset2 = 0.2
      val scale1  = 0.1
      val scale2  = 0.01

      val packet = rawBLEPacket(Seq(0x69))
      val (i, q) = if_mod(packet.map {b => if (b) 0.5 else -0.5}) // TODO use Gaussian
      val noise  = Gaussian(0,0.25 * 0.001).sample(256).toSeq

      val i_sig = noise ++ i.map {scale1 * _ + offset1} ++ noise ++ i.map {scale2 * _ + offset2} ++ noise
      val q_sig = noise ++ q.map {scale1 * _ + offset1} ++ noise ++ q.map {scale2 * _ + offset2} ++ noise

      var num_pkts = 0
      val samples = (i_sig zip q_sig).map { case (x,y) =>
        val i_vga   = vgaGain(c.io.state.i.agcIndex.peek().litValue.toInt)
        val q_vga   = vgaGain(c.io.state.q.agcIndex.peek().litValue.toInt)
        val i_off   = dcoGain(c.io.state.i.dcoIndex.peek().litValue.toInt)
        val q_off   = dcoGain(c.io.state.q.dcoIndex.peek().litValue.toInt)
        val i_mod   = (x - i_off) * i_vga
        val q_mod   = (y - q_off) * q_vga
        val i_quant = ADC(Seq(i_mod))(0)
        val q_quant = ADC(Seq(q_mod))(0)

        c.io.digital.ble.rx.eop.poke(false.B)
        c.io.analog.rx.i.data.poke(i_quant.U)
        c.io.analog.rx.q.data.poke(q_quant.U)

        if (c.io.digital.ble.rx.sop.peek().litToBoolean)
          num_pkts += 1
        if (c.io.digital.ble.rx.data.valid.peek().litToBoolean) {
          c.io.digital.ble.rx.eop.poke(true.B) // only 1 byte packet
          assert(c.io.digital.ble.rx.data.bits.peek().litValue == 0x69)
        }
        c.clock.step()
        (i_mod, q_mod) // optimal signal is centered around 0 +/- ~0.5
      }
      assert(num_pkts == 2)

      val f = Figure()
      val p = f.subplot(0)
      p.title = "BLE signal"
      p += plot(Seq.tabulate(samples.length)(i => i.toDouble), samples.map(s => s._1))
      p += plot(Seq.tabulate(samples.length)(i => i.toDouble), samples.map(s => s._2))
    }
  }

  it should "Test LRWPAN packet w/ gain and offset demodulation and capture" in {
    test(new ModemSingleClock(BasebandModemParams(loadBLECoefficients = false))) { c =>
      c.clock.setTimeout(0)

      // FSKRX setup
      c.io.digital.lrwpan.rx.eop.poke(false.B)
      c.io.control.constants.radioMode.poke(RadioMode.LRWPAN)
      c.io.control.constants.shr.poke((0xA700).U)
      c.io.control.lutCmd.valid.poke(false.B)
      c.io.control.firCmd.valid.poke(false.B)
      c.io.control.rx.enable.poke(true.B)
      c.io.control.lrwpanLoopback.poke(false.B)

      // AGC setup
      c.io.control.tuning.i.AGC.control.sampleWindow.poke(24.U)
      c.io.control.tuning.i.AGC.control.idealPeakToPeak.poke(230.U)
      c.io.control.tuning.i.AGC.control.toleranceP2P.poke(15.U)
      c.io.control.tuning.i.AGC.control.gainInc.poke(1.U)
      c.io.control.tuning.i.AGC.control.gainDec.poke(4.U)
      c.io.control.tuning.i.AGC.control.reset.poke(false.B)
      c.io.control.tuning.i.AGC.useAGC.poke(true.B)

      c.io.control.tuning.q.AGC.control.sampleWindow.poke(24.U)
      c.io.control.tuning.q.AGC.control.idealPeakToPeak.poke(230.U)
      c.io.control.tuning.q.AGC.control.toleranceP2P.poke(15.U)
      c.io.control.tuning.q.AGC.control.gainInc.poke(1.U)
      c.io.control.tuning.q.AGC.control.gainDec.poke(4.U)
      c.io.control.tuning.q.AGC.control.reset.poke(false.B)
      c.io.control.tuning.q.AGC.useAGC.poke(true.B)

      // DCO setup
      c.io.control.tuning.i.DCO.control.gain.poke((2).F(9.W, 2.BP))
      c.io.control.tuning.i.DCO.useDCO.poke(true.B)

      c.io.control.tuning.q.DCO.control.gain.poke((2).F(9.W, 2.BP))
      c.io.control.tuning.q.DCO.useDCO.poke(true.B)

      val offset1 = -0.2
      val offset2 = 0.2
      val scale1  = 0.1
      val scale2  = 0.01

      val packet = chipToMSK(symbolsToChip(rawLRWPANPacket(Seq(0x69))))
      val (i, q) = if_mod(packet.map {b => if (b) 1.0 else -1.0}, fs=F_LRWPAN)
      val noise  = Gaussian(0,0.25 * 0.001).sample(256).toSeq

      val i_sig = noise ++ i.map {scale1 * _ + offset1} ++ noise ++ i.map {scale2 * _ + offset2} ++ noise
      val q_sig = noise ++ q.map {scale1 * _ + offset1} ++ noise ++ q.map {scale2 * _ + offset2} ++ noise

      var num_pkts = 0
      val samples = (i_sig zip q_sig).map { case (x,y) =>
        val i_vga   = vgaGain(c.io.state.i.agcIndex.peek().litValue.toInt)
        val q_vga   = vgaGain(c.io.state.q.agcIndex.peek().litValue.toInt)
        val i_off   = dcoGain(c.io.state.i.dcoIndex.peek().litValue.toInt)
        val q_off   = dcoGain(c.io.state.q.dcoIndex.peek().litValue.toInt)
        val i_mod   = (x - i_off) * i_vga
        val q_mod   = (y - q_off) * q_vga
        val i_quant = ADC(Seq(i_mod))(0)
        val q_quant = ADC(Seq(q_mod))(0)

        c.io.digital.lrwpan.rx.eop.poke(false.B)
        c.io.analog.rx.i.data.poke(i_quant.U)
        c.io.analog.rx.q.data.poke(q_quant.U)

        if (c.io.digital.lrwpan.rx.sop.peek().litToBoolean)
          num_pkts += 1
        if (c.io.digital.lrwpan.rx.data.valid.peek().litToBoolean) {
          c.io.digital.lrwpan.rx.eop.poke(true.B) // only 1 byte packet
          assert(c.io.digital.lrwpan.rx.data.bits.peek().litValue == 0x69)
        }
        c.clock.step()
        (i_mod, q_mod) // optimal signal is centered around 0 +/- ~0.5
      }
      assert(num_pkts == 2)

      val f = Figure()
      val p = f.subplot(0)
      p.title = "LRWPAN signal"
      p += plot(Seq.tabulate(samples.length)(i => i.toDouble), samples.map(s => s._1))
      p += plot(Seq.tabulate(samples.length)(i => i.toDouble), samples.map(s => s._2))
    }
  }
}
