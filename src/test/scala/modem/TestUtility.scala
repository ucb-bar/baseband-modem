package modem

import baseband.BasebandModemParams
import breeze.plot.{Figure, plot}
import breeze.stats.distributions.Gaussian

import scala.collection.immutable.Seq
import scala.collection.mutable.ListBuffer
import scala.util.Random
import scala.math

object TestUtility {
  val params = BasebandModemParams()

  val F_RF = 2.4e9
  val F_DF = 500e3 // deviation if given -1 or 1
  val F_IF = 2e6

  val F_ADC = 32e6
  val F_DAC = 32e6

  val F_BLE = 1e6
  val F_LRWPAN = 2e6

  val F_LO = F_RF - F_IF
  val F_IM = F_LO - F_IF

  /* Modulates data sampled at fs onto IF with CPFSK, -1 is F_IF-F_DF and +1 is F_IF+F_DF
     Returns "analog" I/Q signals sampled at F_ADC with amplitude 0.5 (1 peak to peak)
   */
  def if_mod(data: Seq[Double], fs: Double = F_BLE, image: Boolean = false): (Seq[Double], Seq[Double]) = {
    val samples_per_bit = (F_ADC / fs).toInt
    val data_upsampled = data.flatMap {List.fill(samples_per_bit)(_)}
    val dt = 1.0 / F_ADC
    val phases = data_upsampled.map {var p = 0.0; d => {p += 2*math.Pi*(F_IF+F_DF*d)*dt; p}}
    val I = phases.map {p => 0.5 * math.cos(p)}
    val Q = phases.map {p => if (!image) 0.5 * math.sin(p) else -0.5 * math.sin(p)}
    (I, Q)
  }

  /* Converts sequence of "analog" (+/-0.5) samples to "digital" samples
   */
  def ADC(raw: Seq[Double]): Seq[Int] = {
    val range = math.pow(2, params.adcBits)
    raw.map {s => math.max(math.min(math.round(range * (s + 0.5)), range-1), 0).toInt}
  }

  /* Converts 4-bit symbols to 32-chip sequences */
  def symbolsToChip(symbols: Seq[Int]): Seq[Boolean] = {
    val sequences = Seq( // c0 is LSB
      0x744ac39b, 0x44ac39b7, 0x4ac39b74, 0xac39b744,
      0xc39b744a, 0x39b744ac, 0x9b744ac3, 0xb744ac39,
      0xdee06931, 0xee06931d, 0xe06931de, 0x6931dee,
      0x6931dee0, 0x931dee06, 0x31dee069, 0x1dee0693,
    ).map { seq =>
      Seq.tabulate(32)(i => (seq & (1 << i)) != 0)
    }
    symbols.flatMap {i => sequences(i)}
  }

  /* Converts chip sequences to a bit sequence suitable for MSK, assumes first chip is c0
     Simplification of software/models/notebooks/lrwpan.ipynb
   */
  def chipToMSK(chips: Seq[Boolean]): Seq[Boolean] = {
    var pc = false
    var even = true
    chips.map { c =>
      var k = false
      if (even) {
        k = !(pc^c)
      } else {
        k = pc^c
      }
      pc = c
      even = !even
      k
    }
  }

  /* Generates bitstream for a raw (no whitening) BLE packet */
  def rawBLEPacket(bytes: Seq[Int], aa: Int = 0x8E89BED6): Seq[Boolean] = {
    val aaLSB = aa & 0x1
    val preamble = Seq.tabulate(8){i => i % 2 != aaLSB}
    val accessAddress = Seq.tabulate(32){i => ((aa >> i) & 0x1) != 0}
    val payload = bytes.flatMap {b => Seq.tabulate(8){i => ((b >> i) & 0x1) != 0}}
    preamble ++ accessAddress ++ payload
  }

  /* Generates symbol stream for a raw LRWPAN packet */
  def rawLRWPANPacket(bytes: Seq[Int], shr: Long = 0xA700000000L): Seq[Int] = {
    val preamble = Seq.tabulate(10){i => ((shr >> (i*4)) & 0x0F).toInt}
    val payload = bytes.flatMap {b => Seq.tabulate(2){i => ((b >> (i*4)) & 0x0F).toInt}}
    preamble ++ payload
  }

  /* Generates whitened (or dewhitened) bytes from a byte sequence */
  def whitenBytes(bytes: Seq[Int], bleChannelIndex: Int): List[Int] = {
    val bits = bytes.flatMap {b => Seq.tabulate(8){i => ((b >> i) & 0x1)}}
    val white_bits = whiten(bits, bleChannelIndex)
    var cnt = 0
    var bite = 0
    white_bits.flatMap { b =>
      val nb = (bite >> 1) | (b << 7)
      if (cnt == 7) {
        bite = 0
        cnt = 0
        Some(nb)
      } else {
        bite = nb
        cnt += 1
        None
      }
    }
  }

  /* bit oriented whitening helpers */
  def whiten(bits: Iterable[Int], bleChannelIndex: Int): List[Int] = {
    getWhiteningBits(bits.size, bleChannelIndex).zip(bits).map { p => p._1 ^ p._2}
  }

  def getWhiteningBits(n: Int, bleChannelIndex: Int): List[Int] = {
    var lfsr = (Seq.tabulate(6){i => (bleChannelIndex >> i) & 0x1} ++ Seq(1)).reverse
    List.tabulate(n){_ =>
      val res = whitener(lfsr)
      lfsr = res._1
      res._2
    }
  }

  def whitener(lfsr: Seq[Int]): (Seq[Int],Int) = {
    val last = lfsr.last
    (lfsr.indices.map { i =>
      if (i == 0) {
        lfsr.last
      } else if (i == 4) {
        lfsr.last ^ lfsr(3)
      } else {
        lfsr(i - 1)
      }
    }, last)
  }

  /* Generates BLE CRC bytes (in order suitable for LSB transmission) for byte sequence */
  def bleCRCBytes(bytes: Seq[Int]): Seq[Int] = {
    val bits = bytes.flatMap {b => Seq.tabulate(8){i => ((b >> i) & 0x1)}}
    val crc_bits = bleCRC(bits)
    var cnt = 0
    var bite = 0
    crc_bits.flatMap { b =>
      val nb = (bite >> 1) | (b << 7)
      if (cnt == 7) {
        bite = 0
        cnt = 0
        Some(nb)
      } else {
        bite = nb
        cnt += 1
        None
      }
    }
  }

  def bleCRC(bits: Seq[Int]): Seq[Int] = {
    var lfsr = Seq.tabulate(6){i => Seq(0, 1, 0, 1)}.flatten.reverse // seed 0x555555
    def genCRC(b: Int) = {
      val last = lfsr.last
      lfsr = lfsr.indices.map { i =>
        val xor = lfsr.last ^ b
        if (i == 0) {
          xor
        } else if (i == 1) {
          xor ^ lfsr(0)
        } else if (i == 3) {
          xor ^ lfsr(2)
        } else if (i == 4) {
          xor ^ lfsr(3)
        } else if (i == 6) {
          xor ^ lfsr(5)
        } else if (i == 9) {
          xor ^ lfsr(8)
        } else if (i == 10) {
          xor ^ lfsr(9)
        } else {
          lfsr(i - 1)
        }
      }
    }
    bits.toList.foreach {genCRC(_)}
    lfsr.reverse
  }

  /* Generates LRWPAN CRC bytes (in order suitable for LSB transmission) for byte sequence */
  def lrwpanCRCBytes(bytes: Seq[Int]): Seq[Int] = {
    val bits = bytes.flatMap {b => Seq.tabulate(8){i => ((b >> i) & 0x1)}}
    val crc_bits = lrwpanCRC(bits)
    var cnt = 0
    var bite = 0
    crc_bits.flatMap { b =>
      val nb = (bite >> 1) | (b << 7)
      if (cnt == 7) {
        bite = 0
        cnt = 0
        Some(nb)
      } else {
        bite = nb
        cnt += 1
        None
      }
    }
  }

  def lrwpanCRC(bits: Seq[Int]): Seq[Int] = {
    var lfsr = Seq.tabulate(4){i => Seq(0, 0, 0, 0)}.flatten.reverse // seed 0x0000
    def genCRC(b: Int) = {
      val last = lfsr.last
      lfsr = lfsr.indices.map { i =>
        val xor = lfsr.last ^ b
        if (i == 0) {
          xor
        } else if (i == 5) {
          xor ^ lfsr(4)
        } else if (i == 12) {
          xor ^ lfsr(11)
        } else {
          lfsr(i - 1)
        }
      }
    }
    bits.toList.foreach {genCRC(_)}
    lfsr.reverse
  }

  /* TX test helpers */
  def toBinary(i: Int, digits: Int = 8) =
    String.format("%" + digits + "s", i.toBinaryString).replace(' ', '0')

  def byteToChip(code: Int): Seq[Int] = {
    val lss = code.toChar & (0xF)
    val mss = (code.toChar >> 4)
    symbolToChip(lss) ++ symbolToChip(mss)
  }

  def symbolToChip(code: Int): Seq[Int] = {
    code match {
      case 0 =>   Seq(1, 1, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 1, 0, 1, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1, 0)
      case 1 =>   Seq(1, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 1, 0, 1, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0)
      case 2 =>   Seq(0, 0, 1, 0, 1, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 1, 0, 1, 0, 1, 0, 0, 1, 0)
      case 3 =>   Seq(0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 1, 0, 1, 0, 1)
      case 4 =>   Seq(0, 1, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 1)
      case 5 =>   Seq(0, 0, 1, 1, 0, 1, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0, 1, 1, 1, 0, 0)
      case 6 =>   Seq(1, 1, 0, 0, 0, 0, 1, 1, 0, 1, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1, 1, 0, 1, 1, 0, 0, 1)
      case 7 =>   Seq(1, 0, 0, 1, 1, 1, 0, 0, 0, 0, 1, 1, 0, 1, 0, 1, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1, 1, 0, 1)
      case 8 =>   Seq(1, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1)
      case 9 =>   Seq(1, 0, 1, 1, 1, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1)
      case 10 =>  Seq(0, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1)
      case 11 =>  Seq(0, 1, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 0, 0, 0, 0)
      case 12 =>  Seq(0, 0, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0)
      case 13 =>  Seq(0, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0, 0, 1, 1, 0, 0, 1, 0, 0, 1)
      case 14 =>  Seq(1, 0, 0, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0, 0, 1, 1, 0, 0)
      case 15 =>  Seq(1, 1, 0, 0, 1, 0, 0, 1, 0, 1, 1, 0, 0, 0, 0, 0, 0, 1, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 1, 0, 0, 0)
      case _ =>   throw new Exception("Unexpected symbol: " + code)
    }
  }
}
