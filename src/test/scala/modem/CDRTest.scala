package modem

import chisel3._
import chisel3.util._
import chiseltest._

import org.scalatest.flatspec.AnyFlatSpec
import scala.collection.immutable.Seq
import scala.util.Random

class CDRTest extends AnyFlatSpec with ChiselScalatestTester {
  val N = 32

  it should "Test CDR accuracy" in {
    test(new CDR(N)) { c =>
      c.clock.setTimeout(0)

      val rand = new Random(69420)

      val AA = 0x8E89BED6
      val aa_bits = Seq.tabulate(32)(i => (AA & (1 << i)) != 0)

      val preamble = 0xAA
      val preamble_bits = Seq.tabulate(8)(i => (preamble & (1 << i)) != 0)

      // check all phase shifts with some random bit flips
      for (shift <- 0 until N) {
        val in_bits = (Seq.fill(shift)(true) ++
                       preamble_bits.flatMap {List.fill(N)(_)} ++
                       aa_bits.flatMap {List.fill(N)(_)} ++
                       Seq.fill(8)(true))
        val noise_bits = in_bits.map { b => if (rand.nextInt(12) != 0) b else !b }
        val out_bits = noise_bits.flatMap { b =>
          c.io.in.poke(b.B)
          c.clock.step()
          if (c.io.out.fire().peek().litValue > 0)
            Some(c.io.out.bits.peek().litValue > 0)
          else
            None
        }
        assert(out_bits.mkString("") contains aa_bits.mkString(""))
      }
    }
  }
}
